package com.monyx.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.TransactionEntity
import com.monyx.ui.add.EntryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * What was written, in the fields the summary shows.
 *
 * Ids rather than names and colours. Palette.colorForChild needs four arguments
 * to work out a subcategory's colour and the sheet already has the category
 * list to hand — and carrying a snapshotted label would show a stale one if
 * somebody renamed the category between the save and the dismissal.
 */
data class VoiceSummary(
    val transactionId: String,
    val kind: EntryKind,
    val amountMinor: Long,
    val categoryId: String?,
    val accountId: String,
    val date: LocalDate,
    val transcript: String,
)

/** A one-shot message over the summary, cleared by the next interaction. */
enum class SavedNote { Updated }

/** What the microphone on the summary sheet is doing. */
sealed interface CorrectionState {
    data object Idle : CorrectionState
    data class Listening(val partial: String) : CorrectionState

    /** Heard, and nothing in it was a command. The row is untouched, and
     *  saying so is the whole point of a closed grammar. */
    data object NotUnderstood : CorrectionState

    /** Two category names scored the same. Refusing was right in the first
     *  pass, where the alternative was a modal nobody asked for; here the sheet
     *  is already on screen, so it asks. */
    data class Ambiguous(val candidates: List<VoiceCategory>) : CorrectionState
}

/**
 * One state machine for the whole feature, and the only thing the UI watches.
 *
 * A listen is embedded wherever one is happening rather than kept in a second
 * flow beside this one: the first listen IS [Listening], a correction listen is
 * [Saved] with its [CorrectionState], and two machines would have to agree
 * about which of them the finger on the microphone belongs to.
 */
sealed interface VoiceEntryState {
    data object Hidden : VoiceEntryState
    data object NeedsPermission : VoiceEntryState
    data class Listening(val partial: String) : VoiceEntryState

    /** Heard, and nothing in it was a transaction. */
    data class NotUnderstood(val transcript: String) : VoiceEntryState

    /** Part of it was a transaction. The keypad is already prefilled behind
     *  this; the sheet only says why it is. */
    data class Incomplete(val transcript: String) : VoiceEntryState

    /** The recogniser itself could not do it: no pack for the language, no
     *  connection and no pack, a microphone another app is holding. */
    data class Failed(val reason: ListenFailure) : VoiceEntryState

    /**
     * The write threw. A row that silently failed to appear is the same class
     * of bug as a wrong row that silently did, and the keypad is a swipe away.
     */
    data class SaveFailed(val transcript: String) : VoiceEntryState

    data class Saved(
        val summary: VoiceSummary,
        val note: SavedNote? = null,
        val correction: CorrectionState = CorrectionState.Idle,
    ) : VoiceEntryState

    /** The summary is kept, because Undo needs something to put back. */
    data class Reverted(val summary: VoiceSummary) : VoiceEntryState
}

/**
 * Holds the microphone, decides what the utterance was, and writes the row.
 *
 * It commits and then shows a summary, rather than asking first. What that
 * costs, stated plainly: a mis-heard row exists in the shared ledger for the
 * seconds before the summary is read, and if a push goes out in that window the
 * other phone shows a row that then disappears. What makes it survivable is
 * that a revert is a tombstone and SyncEngine builds its push from the row's
 * CURRENT state, so an add-then-revert before the push transmits exactly once,
 * as a delete.
 *
 * Sync is enqueued on save, on revert and on edit, exactly where the keypad and
 * the transactions list enqueue it. Holding it back until the sheet was
 * dismissed was considered and dropped: it creates no window worth having (the
 * enqueue is unique-KEEP and app open already queued one) and it loses the row
 * entirely when the sheet is never dismissed — screen off, pocket, or a swipe
 * from recents, which is a force stop on several OEMs.
 *
 * No Context reaches this class. It cannot enqueue sync and must not learn how;
 * [onSyncRequested] arrives from the scaffold, the same lambda TransactionsScreen
 * and AddScreen are handed.
 */
class VoiceEntryViewModel(
    private val ledger: VoiceLedger,
    private val recogniser: Recogniser,
    private val onSyncRequested: () -> Unit,
    private val today: () -> LocalDate = Dates::today,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val work: CoroutineScope = scope ?: viewModelScope

    /**
     * Eagerly, not WhileSubscribed. The parse runs the instant the finger comes
     * off the bar, and nothing has subscribed to these at that point — the
     * sheet is showing a waveform, not a category list. A lazily-shared flow
     * would hand the parser an empty household.
     */
    val categories: StateFlow<List<VoiceCategory>> =
        ledger.categories.stateIn(work, SharingStarted.Eagerly, emptyList())

    val accounts: StateFlow<List<VoiceAccount>> =
        ledger.accounts.stateIn(work, SharingStarted.Eagerly, emptyList())

    /** The rows EditTransactionDialog wants, in the shape it wants them. It is
     *  not this package's dialog and is not going to be, so it gets entities. */
    val editableCategories: StateFlow<List<CategoryEntity>> =
        ledger.editableCategories.stateIn(work, SharingStarted.WhileSubscribed(5_000), emptyList())

    val editableAccounts: StateFlow<List<AccountEntity>> =
        ledger.editableAccounts.stateIn(work, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<VoiceEntryState>(VoiceEntryState.Hidden)
    val state: StateFlow<VoiceEntryState> = _state.asStateFlow()

    /** The row the tap-to-edit dialog is open on, or null for closed. */
    private val _editing = MutableStateFlow<TransactionEntity?>(null)
    val editing: StateFlow<TransactionEntity?> = _editing.asStateFlow()

    /** Emitted when the parse fell short: the caller prefills the keypad. */
    private val _handoff = MutableSharedFlow<SpokenTransaction>(extraBufferCapacity = 1)
    val handoff: SharedFlow<SpokenTransaction> = _handoff.asSharedFlow()

    private var memberId: String? = null

    /** The language the recogniser was asked for, which is also the language the
     *  grammar reads. Kept from the caller so no part of this reaches for
     *  Locale.getDefault(). */
    private var locale: Locale = Locale.ROOT

    /** Whether the listen in flight belongs to the summary rather than to a new
     *  transaction. The alternative is asking the state, which is a lie for the
     *  frame between the release and the recogniser's first callback. */
    private var correcting = false

    /** Whether a listen is actually in flight. [stopListening] is reached by a
     *  plain tab tap as well as by the end of a hold, and forwarding one that
     *  ends nothing would be a stop on an idle recogniser. */
    private var listening = false

    /**
     * A write is in flight. "Ignore a hold while listening" does not cover the
     * gap between the transcript arriving and Room returning, which is exactly
     * where a second hold would produce two rows for one sentence.
     */
    private var writing = false

    init {
        work.launch {
            recogniser.state.collect(::onListen)
        }
    }

    // -------------------------------------------------------------- the mic

    fun startListening(languageTag: String, memberId: String?) {
        if (writing) return
        this.memberId = memberId
        locale = Locale.forLanguageTag(languageTag)
        correcting = false
        listening = true
        _editing.value = null
        _state.value = VoiceEntryState.Listening("")
        recogniser.start(languageTag)
    }

    /** A hold that began over the summary. It corrects the row; it does not
     *  start a new one. Holding the Add tab instead is what starts a new one,
     *  and it dismisses this summary on the way. */
    fun startCorrecting(languageTag: String) {
        val saved = _state.value as? VoiceEntryState.Saved ?: return
        locale = Locale.forLanguageTag(languageTag)
        correcting = true
        listening = true
        _state.value = saved.copy(note = null, correction = CorrectionState.Listening(""))
        recogniser.start(languageTag)
    }

    /** The finger came up, or the sheet's Stop was tapped — which is the same
     *  thing, and has to be, because TalkBack's long-press action fires a hold
     *  that no finger will ever end. */
    fun stopListening() {
        // Reached by every pointer-up on the tab, a plain tap included: the
        // gesture modifier reports the lift, not "the lift that ended a hold".
        if (!listening) return
        recogniser.stop()
    }

    fun permissionRequired() {
        _state.value = VoiceEntryState.NeedsPermission
    }

    // ------------------------------------------------------- what came back

    private fun onListen(listen: ListenState) {
        when (listen) {
            ListenState.Idle -> Unit
            ListenState.Listening -> showListening("")
            is ListenState.Hearing -> showListening(listen.partial)
            is ListenState.Done -> onTranscript(listen.best, listen.alternatives)
            is ListenState.Failed -> onFailure(listen.reason)
        }
    }

    private fun showListening(partial: String) {
        val current = _state.value
        _state.value = when {
            correcting && current is VoiceEntryState.Saved ->
                current.copy(correction = CorrectionState.Listening(partial))
            correcting -> current
            else -> VoiceEntryState.Listening(partial)
        }
    }

    private fun onFailure(reason: ListenFailure) {
        listening = false
        val current = _state.value
        // A correction that could not be heard and a correction that made no
        // sense say the same thing to the person holding the phone: nothing
        // changed. The row is still on screen behind the message.
        _state.value = when {
            correcting && current is VoiceEntryState.Saved ->
                current.copy(correction = CorrectionState.NotUnderstood)
            else -> VoiceEntryState.Failed(reason)
        }
        correcting = false
    }

    private fun onTranscript(best: String, alternatives: List<String>) {
        listening = false
        if (correcting) {
            correcting = false
            correct(best, alternatives)
            return
        }
        val parsed = VoiceParser.parse(
            transcript = best,
            alternatives = alternatives,
            categories = categories.value,
            accounts = accounts.value,
            locale = locale,
            today = today(),
        )
        when (parsed) {
            is VoiceParse.Complete -> save(parsed.transaction)
            is VoiceParse.Partial -> {
                // Not an error and not a save. The keypad is the second engine.
                _state.value = VoiceEntryState.Incomplete(parsed.transaction.transcript)
                _handoff.tryEmit(parsed.transaction)
            }
            is VoiceParse.Unrecognised -> _state.value = VoiceEntryState.NotUnderstood(parsed.transcript)
        }
    }

    // ------------------------------------------------------------- writing

    /**
     * Every write goes through here, and there are two reasons for that rather
     * than one.
     *
     * A failure is a STATE. A write that threw and left the household believing
     * a row exists — or believing a row is gone — is the same class of bug as a
     * wrong row written silently, and it is the worse half of it, because there
     * is nothing on screen to notice. So the block either returns the state
     * that describes what it did or it throws, and a throw is reported.
     *
     * And one flag covers all of them, not just the add. Revert re-reads and
     * rewrites the row whole; if an edit started while a revert was still in
     * flight it would read the row from before the tombstone and write it back
     * with `deleted = 0`, resurrecting a transaction the user had just taken
     * back. Serialising the four paths is what makes that unrepresentable.
     */
    private fun write(transcript: String, block: suspend () -> VoiceEntryState) {
        if (writing) return
        writing = true
        work.launch {
            val outcome = runCatching { block() }
            writing = false
            _state.value = outcome.getOrElse { VoiceEntryState.SaveFailed(transcript) }
            if (outcome.isSuccess) onSyncRequested()
        }
    }

    private fun save(spoken: SpokenTransaction) {
        val createdBy = memberId
        val accountId = spoken.accountId
        if (createdBy == null || accountId == null) {
            _state.value = VoiceEntryState.SaveFailed(spoken.transcript)
            return
        }
        write(spoken.transcript) {
            val id = ledger.add(
                kind = spoken.kind,
                amountMinor = spoken.amountMinor,
                accountId = accountId,
                categoryId = spoken.categoryId,
                occurredAtMs = occurredAt(spoken.date),
                createdBy = createdBy,
            )
            VoiceEntryState.Saved(
                VoiceSummary(
                    transactionId = id,
                    kind = spoken.kind,
                    amountMinor = spoken.amountMinor,
                    categoryId = spoken.categoryId,
                    accountId = accountId,
                    date = spoken.date,
                    transcript = spoken.transcript,
                ),
            )
        }
    }

    /**
     * A soft delete, through the same path the transactions list uses. Never a
     * hard one: the never-physically-delete rule is not server-only, and if the
     * create has already been pushed the tombstone follows it and every read
     * path already honours tombstones.
     */
    fun revert() {
        val saved = _state.value as? VoiceEntryState.Saved ?: return
        write(saved.summary.transcript) {
            ledger.remove(saved.summary.transactionId)
            VoiceEntryState.Reverted(saved.summary)
        }
    }

    fun undoRevert() {
        val reverted = _state.value as? VoiceEntryState.Reverted ?: return
        write(reverted.summary.transcript) {
            ledger.restore(reverted.summary.transactionId)
            VoiceEntryState.Saved(reverted.summary)
        }
    }

    /**
     * The tap route, straight from EditTransactionDialog. Five loose fields
     * rather than the dialog's own record, mirroring TransactionsViewModel.saveEdit
     * — an edit is an upsert of the whole row, because the sync protocol carries
     * whole rows and there is no partial-update path to get wrong.
     */
    fun applyEdit(
        amountMinor: Long,
        categoryId: String?,
        accountId: String,
        note: String,
        occurredAtMs: Long,
    ) {
        val saved = _state.value as? VoiceEntryState.Saved ?: return
        _editing.value = null
        write(saved.summary.transcript) {
            val original = readLive(saved.summary.transactionId)
            ledger.update(
                original.copy(
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    note = note.ifBlank { null },
                    occurredAt = occurredAtMs,
                    occurredOn = Dates.localDate(occurredAtMs),
                ),
            )
            VoiceEntryState.Saved(
                summary = saved.summary.copy(
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    date = LocalDate.parse(Dates.localDate(occurredAtMs)),
                ),
                note = SavedNote.Updated,
            )
        }
    }

    /** One of the tied names, tapped. */
    fun chooseCategory(category: VoiceCategory) {
        val saved = _state.value as? VoiceEntryState.Saved ?: return
        apply(saved, VoiceCorrection(categoryId = category.id, kind = category.kind))
    }

    private fun correct(best: String, alternatives: List<String>) {
        val saved = _state.value as? VoiceEntryState.Saved ?: return
        val correction = VoiceCommands.parse(
            transcript = best,
            alternatives = alternatives,
            categories = categories.value,
            accounts = accounts.value,
            locale = locale,
            today = today(),
        )
        when {
            correction.revert -> revert()
            correction.confirm -> dismiss()
            correction.ambiguous.isNotEmpty() ->
                _state.value = saved.copy(correction = CorrectionState.Ambiguous(correction.ambiguous))
            correction.isEmpty ->
                _state.value = saved.copy(correction = CorrectionState.NotUnderstood)
            else -> apply(saved, correction)
        }
    }

    private fun apply(saved: VoiceEntryState.Saved, correction: VoiceCorrection) {
        write(saved.summary.transcript) {
            val original = readLive(saved.summary.transactionId)
            val kind = correction.kind ?: saved.summary.kind
            val amountMinor = correction.amountMinor ?: saved.summary.amountMinor
            val categoryId = correction.categoryId ?: saved.summary.categoryId
            val accountId = correction.accountId ?: saved.summary.accountId
            val date = correction.date ?: saved.summary.date
            val occurredAtMs = occurredAt(date)
            ledger.update(
                original.copy(
                    kind = kind.wire,
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    occurredAt = occurredAtMs,
                    occurredOn = Dates.localDate(occurredAtMs),
                ),
            )
            VoiceEntryState.Saved(
                summary = saved.summary.copy(
                    kind = kind,
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    date = date,
                ),
                note = SavedNote.Updated,
            )
        }
    }

    /**
     * The row as it stands, refusing to carry on without one.
     *
     * A row that has gone, or has already been tombstoned, is not something to
     * write back over: `copy(...)` does not touch `deleted`, so rewriting a
     * tombstone would resurrect a transaction somebody had just taken back.
     * Both cases throw, and [write] turns the throw into a message.
     */
    private suspend fun readLive(id: String): TransactionEntity {
        val row = ledger.read(id) ?: error("transaction $id is gone")
        check(row.deleted == 0) { "transaction $id is already reverted" }
        return row
    }

    // ---------------------------------------------------- change, by tap

    /**
     * Loads the whole row for EditTransactionDialog.
     *
     * Here rather than in the sheet: the sheet is Compose and nothing else, and
     * [VoiceLedger] exists so that exactly one class in this package knows the
     * database is there.
     */
    fun beginEdit() {
        val saved = _state.value as? VoiceEntryState.Saved ?: return
        work.launch { _editing.value = ledger.read(saved.summary.transactionId) }
    }

    fun cancelEdit() {
        _editing.value = null
    }

    /** Closes the sheet. The row stays exactly where it is — top of the history
     *  list, editable and deletable there. The summary is a fast path over
     *  TransactionDetailSheet, not a replacement for it. */
    fun dismiss() {
        correcting = false
        listening = false
        _editing.value = null
        recogniser.cancel()
        _state.value = VoiceEntryState.Hidden
    }

    /** Midday on the chosen day when it is not today, so a date change cannot
     *  land the row in a neighbouring day — the same convention the keypad uses. */
    private fun occurredAt(date: LocalDate): Long =
        if (date == today()) {
            System.currentTimeMillis()
        } else {
            Dates.startOfDayMillis(date) + 12 * 60 * 60 * 1000
        }

    override fun onCleared() {
        recogniser.release()
        super.onCleared()
    }
}
