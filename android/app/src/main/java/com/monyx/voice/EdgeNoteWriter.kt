package com.monyx.voice

import com.monyx.sync.Api
import com.monyx.sync.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The note, second-guessed by a model on the other end of the sync route.
 *
 * On-device was built first and set aside: it works, but it needs a
 * multi-gigabyte model download the owner did not want, on a device list most
 * phones are not on. This is the trade taken instead, and it is worth writing
 * down what it costs rather than only what it buys. **A sentence the household
 * said out loud about its own money now leaves the phone**, it costs money per
 * utterance, and it does nothing at all offline. See ADR 0019.
 *
 * What makes that acceptable — and it is the whole argument — is that the call
 * is not in the add path. The row is written from the keypad's own rules, with
 * a note, and the summary is on screen before this is called at all. The
 * five-second rule is not bent here; it is not involved. Offline, in a basement
 * supermarket, this returns null on the first connect attempt and the household
 * gets exactly the app it had before any of this existed.
 *
 * It authenticates with the device session the phone already holds. There is no
 * new secret on this side: the assistant's URL-token pattern exists for a
 * caller with no session, and this caller has one.
 */
class EdgeNoteWriter(private val session: Session) : NoteWriter {

    override suspend fun improve(request: NoteRequest): String? = withContext(Dispatchers.IO) {
        val token = session.token() ?: return@withContext null
        val answer = Api.suggestNote(
            token = token,
            transcript = request.transcript,
            amountMinor = request.amountMinor,
            category = request.categoryName,
            note = request.note,
        )
        // The server bounds the answer; this decides whether it is BETTER. The
        // grounding rule needs the transcript to check against, and the phone is
        // the side that still has it.
        NotePrompt.accept(answer, request)
    }
}
