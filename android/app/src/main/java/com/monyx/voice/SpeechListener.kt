package com.monyx.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a listen got to. Only [Done] and [Failed] are ends. */
sealed interface ListenState {
    data object Idle : ListenState

    /** The microphone is open and nobody has said anything yet. */
    data object Listening : ListenState

    /**
     * A voice started. Reported separately from [Hearing] because it arrives
     * well before the first partial transcript does, and the difference matters
     * to whoever is holding the clock: waiting five seconds for somebody to
     * begin is a dead microphone, and waiting fifteen for them to finish is a
     * long sentence. See VoiceEntryViewModel.
     */
    data object Speaking : ListenState

    data class Hearing(val partial: String) : ListenState
    data class Done(val best: String, val alternatives: List<String>) : ListenState
    data class Failed(val reason: ListenFailure) : ListenState
}

/**
 * Every way this can end badly, collapsed to the ones a person can act on.
 * Thirteen framework error codes say more about the recogniser's internals
 * than about what the household should do next.
 */
enum class ListenFailure {
    NoService,
    NoPermission,
    NoSpeech,
    Network,
    Busy,
    Audio,
    LanguageUnavailable,
    Other,
}

/**
 * The thing that turns held air into text. An interface because the ViewModel's
 * decisions are worth testing on a JVM, and [SpeechRecognizer] cannot be
 * constructed off a device.
 */
interface Recogniser {
    val state: StateFlow<ListenState>

    /** Begins a fresh listen, discarding any in progress. Main thread. */
    fun start(languageTag: String)

    /** Stop taking audio and deliver what was heard. */
    fun stop()

    /** Stop taking audio and throw it away. */
    fun cancel()

    fun release()
}

/**
 * Android's own recogniser, on-device where the language pack exists.
 *
 * The alternative — record with AudioRecord, encode, POST it somewhere to be
 * transcribed — costs a second or two of shop 4G in the middle of the five
 * seconds this app budgets for adding an expense, costs money per utterance
 * forever, cannot work in a basement supermarket at all, and sends a
 * household's audio to a third party. This costs nothing, ships with the
 * phone, and returns partial results while the sentence is still being said.
 *
 * `EXTRA_PREFER_OFFLINE` rather than createOnDeviceSpeechRecognizer(): the
 * on-device constructor is API 33 and would not compile away cleanly below it.
 *
 * The flag does NOT fall back on its own, whatever its name suggests — a phone
 * without the language downloaded fails with a language error and stops there.
 * The fallback is [retriesOnline]: one more attempt without the flag, which is
 * the behaviour this was always assumed to have.
 *
 * The recogniser must be created and driven on the main thread and delivers its
 * callbacks there, which is why every caller is a UI event and nothing here
 * touches a background dispatcher.
 */
class SpeechListener(private val context: Context) : Recogniser {

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** The language of the listen in progress, for the one retry below. */
    private var listeningTo: String? = null

    /** Whether that listen asked for the on-device engine. */
    private var preferredOffline = false

    /**
     * Whether the finger has come off the button since this listen began.
     *
     * The retry below reopens the microphone, and reopening it after the hold
     * has ended would be a recorder nobody is talking into. The failure it
     * retries arrives within a frame or two of starting, so in practice the
     * hold is still down — and when it is not, the honest answer is the error.
     */
    private var released = false

    override fun start(languageTag: String) {
        start(languageTag, offline = true)
    }

    private fun start(languageTag: String, offline: Boolean) {
        if (!isAvailable(context)) {
            _state.value = ListenState.Failed(ListenFailure.NoService)
            return
        }
        // Always cancel first. Two holds that overlap by a frame otherwise land
        // on ERROR_RECOGNIZER_BUSY, which is a failure message for something
        // the user did not do wrong.
        recognizer?.cancel()
        val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        listeningTo = languageTag
        preferredOffline = offline
        released = false
        _state.value = ListenState.Listening
        active.startListening(intentFor(languageTag, offline))
    }

    override fun stop() {
        released = true
        recognizer?.stopListening()
    }

    override fun cancel() {
        released = true
        listeningTo = null
        recognizer?.cancel()
        _state.value = ListenState.Idle
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
        _state.value = ListenState.Idle
    }

    private fun intentFor(languageTag: String, offline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // The n-best list is not decoration: "na tran sport" comes back on
            // top and "na transport" second often enough that walking the
            // alternatives is the cheapest accuracy the parser will ever get.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = ListenState.Listening
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults.firstResult() ?: return
            if (partial.isNotBlank()) _state.value = ListenState.Hearing(partial)
        }

        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val best = heard.firstOrNull()?.takeIf { it.isNotBlank() }
            _state.value = if (best == null) {
                ListenState.Failed(ListenFailure.NoSpeech)
            } else {
                ListenState.Done(best, heard.drop(1).filter { it.isNotBlank() })
            }
        }

        override fun onError(error: Int) {
            // One more go, over the network, before giving up.
            //
            // EXTRA_PREFER_OFFLINE does not mean "prefer". On Google's
            // recogniser it means only, and a phone without the Polish pack
            // downloaded answers ERROR_LANGUAGE_UNAVAILABLE instantly — so the
            // feature was dead on exactly the phones it had never been tested
            // on, under a message ("Speech is not available in this language")
            // that blamed the language rather than a missing download. Asking
            // again without the flag is what the comment above always claimed
            // this did.
            val retry = listeningTo
            if (retry != null && preferredOffline && !released && retriesOnline(error)) {
                start(retry, offline = false)
                return
            }
            listeningTo = null
            _state.value = ListenState.Failed(failureOf(error))
        }

        override fun onBeginningOfSpeech() {
            _state.value = ListenState.Speaking
        }

        // Deliberately not an end. The recogniser reports the pause it thinks
        // it heard, and then goes on to decide whether it was really the end of
        // the sentence; onResults is the one that means it.
        override fun onEndOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        private const val MAX_RESULTS = 5

        /**
         * The two language error codes arrived in API 33 and the constants with
         * them, so they are compared as the raw ints they have always been on
         * the wire rather than referenced by a name that does not compile
         * against an older platform.
         */
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /**
         * False on a phone with no recognition service at all, and then the
         * gesture simply does not exist — the same way a checkout with no
         * voice.properties has no call button. Nothing to crash over and
         * nothing to explain unprompted.
         *
         * This needs the <queries> entry in the manifest to answer honestly on
         * API 30 and above; package visibility hides the service otherwise and
         * this returns false on a phone that does have one.
         */
        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        /**
         * Whether a failed on-device listen is worth one more try over the
         * network.
         *
         * Only the two language codes. They are the ones that mean "this engine
         * has not got that language", which the network engine usually has —
         * everything else means the attempt itself went wrong, and asking a
         * second time would only fail a second time more slowly. Silence in
         * particular is NOT retried: nothing was said, and reopening the
         * microphone on it would be a recorder that would not take no for an
         * answer.
         */
        internal fun retriesOnline(error: Int): Boolean =
            error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE

        private fun failureOf(error: Int): ListenFailure = when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> ListenFailure.Network
            SpeechRecognizer.ERROR_AUDIO -> ListenFailure.Audio
            // A hold released a few milliseconds after it started produces one
            // of these two, and so does a genuinely silent room. Neither is
            // worth two different messages.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> ListenFailure.NoSpeech
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenFailure.Busy
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenFailure.NoPermission
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> ListenFailure.LanguageUnavailable
            else -> ListenFailure.Other
        }
    }
}
