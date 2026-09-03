package com.monyx

import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.TransactionEntity
import com.monyx.ui.add.EntryKind
import com.monyx.voice.ListenState
import com.monyx.voice.Recogniser
import com.monyx.voice.VoiceAccount
import com.monyx.voice.VoiceCategory
import com.monyx.voice.CorrectionState
import com.monyx.voice.ListenFailure
import com.monyx.voice.SavedNote
import com.monyx.voice.VoiceEntryState
import com.monyx.voice.VoiceEntryViewModel
import com.monyx.voice.SpokenTransaction
import com.monyx.voice.VoiceLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What the ViewModel DECIDES, with Room and the microphone stood in for.
 *
 * The two failures worth pinning are the ones that are silent: a write that
 * throws and leaves no row and no message, and a revert that reports success
 * without tombstoning anything. Both are the same class of bug as a wrong row —
 * the household believes something is in the ledger that is not.
 *
 * The scope is injected rather than viewModelScope, because there is no
 * coroutine-test dispatcher in this project and adding one for this would be a
 * dependency. Unconfined runs each launch to its first real suspension inline,
 * which is all these need.
 */
class VoiceEntryTest {

    private val today = LocalDate.of(2026, 3, 14)
    private val transport = VoiceCategory("c-transport", "Transport", EntryKind.Expense)
    private val cash = VoiceAccount("a-cash", "Gotówka")

    private class FakeRecogniser : Recogniser {
        val heard = MutableStateFlow<ListenState>(ListenState.Idle)
        override val state: StateFlow<ListenState> = heard
        var released = false
        var stops = 0
        var cancels = 0
        override fun start(languageTag: String) {
            heard.value = ListenState.Listening
        }
        override fun stop() {
            stops++
        }
        override fun cancel() {
            cancels++
        }
        override fun release() {
            released = true
        }
    }

    /**
     * Room, in a map. It holds a real [TransactionEntity] and mutates it, which
     * is the only way the correction path can be tested at all: `apply` and
     * `applyEdit` both read the row before they write it, so a fake that
     * returns null makes every one of those tests pass by doing nothing.
     *
     * [failWrites] fails everything that writes, which is the point of B4 — a
     * missing row and a wrong row are the same class of bug, and three of the
     * four write paths used to report success regardless.
     */
    private class FakeLedger(
        override val categories: Flow<List<VoiceCategory>>,
        override val accounts: Flow<List<VoiceAccount>>,
        /** Flipped mid-test to break everything that writes, so a failure can
         *  be aimed at one path with a row already in the table. */
        var failWrites: Boolean = false,
    ) : VoiceLedger {
        override val editableCategories: Flow<List<CategoryEntity>> = flowOf(emptyList())
        override val editableAccounts: Flow<List<AccountEntity>> = flowOf(emptyList())

        val rows = mutableMapOf<String, TransactionEntity>()
        val written = mutableListOf<String>()

        override suspend fun add(
            kind: EntryKind,
            amountMinor: Long,
            accountId: String,
            categoryId: String?,
            occurredAtMs: Long,
            createdBy: String,
        ): String {
            if (failWrites) throw IllegalStateException("disk full")
            written += "$kind:$amountMinor:$categoryId"
            val id = "t-1"
            rows[id] = TransactionEntity(
                id = id,
                kind = kind.wire,
                amountMinor = amountMinor,
                accountId = accountId,
                categoryId = categoryId,
                occurredAt = occurredAtMs,
                occurredOn = "2026-03-14",
                createdBy = createdBy,
                source = "voice",
                createdAt = occurredAtMs,
                pending = 1,
            )
            return id
        }

        override suspend fun read(id: String): TransactionEntity? = rows[id]

        override suspend fun update(entity: TransactionEntity) {
            if (failWrites) throw IllegalStateException("disk full")
            rows[entity.id] = entity
        }

        override suspend fun remove(id: String) {
            if (failWrites) throw IllegalStateException("disk full")
            rows[id] = rows.getValue(id).copy(deleted = 1, pending = 1)
        }

        override suspend fun restore(id: String) {
            if (failWrites) throw IllegalStateException("disk full")
            rows[id] = rows.getValue(id).copy(deleted = 0, pending = 1)
        }
    }

    private fun ledger(failWrites: Boolean = false) = FakeLedger(
        categories = flowOf(listOf(transport)),
        accounts = flowOf(listOf(cash)),
        failWrites = failWrites,
    )

    /**
     * The three delays are injected, and a zero one is not a short wait: delay()
     * returns without suspending at or below zero, so under Dispatchers.Unconfined
     * the timer fires inline and the assertion follows it. A LONG one is
     * equally useful — the coroutine parks, the test never joins it, and what is
     * on screen at that moment is exactly what a person would be looking at.
     */
    private fun viewModel(
        ledger: FakeLedger,
        recogniser: FakeRecogniser,
        onSync: () -> Unit = {},
        silenceMillis: Long = FOREVER,
        speechMillis: Long = FOREVER,
        noticeMillis: Long = FOREVER,
    ) = VoiceEntryViewModel(
        ledger = ledger,
        recogniser = recogniser,
        onSyncRequested = onSync,
        today = { today },
        silenceMillis = silenceMillis,
        speechMillis = speechMillis,
        noticeMillis = noticeMillis,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun say(
        ledger: FakeLedger,
        recogniser: FakeRecogniser,
        sentence: String,
        onSync: () -> Unit = {},
    ): VoiceEntryViewModel = viewModel(ledger, recogniser, onSync).also {
        it.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Done(sentence, emptyList())
    }

    /** Saves a row, then holds the microphone again over the summary. */
    private fun correct(
        ledger: FakeLedger,
        recogniser: FakeRecogniser,
        sentence: String,
        onSync: () -> Unit = {},
    ): VoiceEntryViewModel {
        val viewModel = say(ledger, recogniser, "dodaj 200 na transport", onSync)
        viewModel.startCorrecting("pl-PL")
        recogniser.heard.value = ListenState.Done(sentence, emptyList())
        return viewModel
    }

    @Test
    fun `a complete sentence becomes a row and a summary`() {
        val ledger = ledger()
        var synced = 0
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport") { synced++ }

        val saved = viewModel.state.value as VoiceEntryState.Saved
        assertEquals(20000L, saved.summary.amountMinor)
        assertEquals(transport.id, saved.summary.categoryId)
        assertEquals(cash.id, saved.summary.accountId)
        assertEquals(listOf("Expense:20000:c-transport"), ledger.written)
        assertEquals("voice", ledger.rows.getValue("t-1").source)
        // The same point the keypad enqueues at, for the same reason: a sheet
        // that is never dismissed must not be able to strand the row.
        assertEquals(1, synced)
    }

    @Test
    fun `a write that throws is reported rather than swallowed`() {
        val viewModel = say(ledger(failWrites = true), FakeRecogniser(), "dodaj 200 na transport")
        val failed = viewModel.state.value as VoiceEntryState.SaveFailed
        assertEquals("dodaj 200 na transport", failed.transcript)
    }

    @Test
    fun `revert tombstones the row and keeps the summary for an undo`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport")
        viewModel.revert()

        val reverted = viewModel.state.value as VoiceEntryState.Reverted
        assertEquals(1, ledger.rows.getValue("t-1").deleted)
        assertEquals(20000L, reverted.summary.amountMinor)

        viewModel.undoRevert()
        assertEquals(0, ledger.rows.getValue("t-1").deleted)
        assertTrue(viewModel.state.value is VoiceEntryState.Saved)
    }

    @Test
    fun `a sentence the parser cannot finish writes nothing and hands over`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200")
        assertTrue(viewModel.state.value is VoiceEntryState.Incomplete)
        assertTrue(ledger.written.isEmpty())
    }

    @Test
    fun `a sentence with nothing in it writes nothing and says so`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "asdf qwerty")
        assertTrue(viewModel.state.value is VoiceEntryState.NotUnderstood)
        assertTrue(ledger.written.isEmpty())
    }

    @Test
    fun `a recogniser failure is a message, never a crash and never a row`() {
        val recogniser = FakeRecogniser()
        val ledger = ledger()
        val viewModel = viewModel(ledger, recogniser)
        viewModel.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Failed(com.monyx.voice.ListenFailure.Network)

        assertTrue(viewModel.state.value is VoiceEntryState.Failed)
        assertTrue(ledger.written.isEmpty())
    }

    /**
     * The locale reaches the grammar from the language the recogniser was asked
     * for, and from nowhere else — no part of this path calls
     * Locale.getDefault(). Pinned with a spelled-out English numeral, which
     * only the English table can read.
     */
    @Test
    fun `the language the recogniser was asked for is the language that is parsed`() {
        val ledger = FakeLedger(
            categories = flowOf(listOf(VoiceCategory("c-t", "Transport", EntryKind.Expense))),
            accounts = flowOf(listOf(cash)),
        )
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger, recogniser)
        viewModel.startListening("en-GB", "m-1")
        recogniser.heard.value = ListenState.Done("add two hundred to transport", emptyList())

        val saved = viewModel.state.value as VoiceEntryState.Saved
        assertEquals(20000L, saved.summary.amountMinor)
        assertEquals("c-t", saved.summary.categoryId)
    }

    // ------------------------------------------------------- corrections

    @Test
    fun `a spoken correction rewrites the row and the summary together`() {
        val ledger = ledger()
        var synced = 0
        val viewModel = correct(ledger, FakeRecogniser(), "ma być 250") { synced++ }

        val saved = viewModel.state.value as VoiceEntryState.Saved
        assertEquals(25000L, saved.summary.amountMinor)
        assertEquals(SavedNote.Updated, saved.note)
        assertEquals(25000L, ledger.rows.getValue("t-1").amountMinor)
        // Once for the save, once for the correction.
        assertEquals(2, synced)
    }

    @Test
    fun `a correction that reaches nothing leaves the row exactly as it was`() {
        val ledger = ledger()
        val viewModel = correct(ledger, FakeRecogniser(), "asdf qwerty")

        val saved = viewModel.state.value as VoiceEntryState.Saved
        assertEquals(CorrectionState.NotUnderstood, saved.correction)
        assertEquals(20000L, ledger.rows.getValue("t-1").amountMinor)
        assertEquals(transport.id, ledger.rows.getValue("t-1").categoryId)
    }

    /** Refusing was right in the first pass. Here the sheet is on screen, so it
     *  asks — and the chip that is tapped is what gets written. */
    @Test
    fun `tied category names become chips, and tapping one writes it`() {
        val tied = listOf(
            transport,
            VoiceCategory("c-a", "Zakupy spożywcze", EntryKind.Expense),
            VoiceCategory("c-b", "Zakupy domowe", EntryKind.Expense),
        )
        val ledger = FakeLedger(flowOf(tied), flowOf(listOf(cash)))
        val viewModel = correct(ledger, FakeRecogniser(), "zakupy")

        val asking = viewModel.state.value as VoiceEntryState.Saved
        val ambiguous = asking.correction as CorrectionState.Ambiguous
        assertEquals(setOf("c-a", "c-b"), ambiguous.candidates.map { it.id }.toSet())
        assertEquals(transport.id, ledger.rows.getValue("t-1").categoryId)

        viewModel.chooseCategory(ambiguous.candidates.first { it.id == "c-b" })
        assertEquals("c-b", ledger.rows.getValue("t-1").categoryId)
        assertEquals(SavedNote.Updated, (viewModel.state.value as VoiceEntryState.Saved).note)
    }

    @Test
    fun `an undo verb said with a correction changes nothing at all`() {
        val ledger = ledger()
        val viewModel = correct(ledger, FakeRecogniser(), "nie anuluj, zmień na transport")

        val saved = viewModel.state.value as VoiceEntryState.Saved
        assertEquals(CorrectionState.NotUnderstood, saved.correction)
        assertEquals(0, ledger.rows.getValue("t-1").deleted)
    }

    @Test
    fun `Change loads the whole row, and the edit writes it back`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport")

        viewModel.beginEdit()
        assertEquals("t-1", viewModel.editing.value?.id)

        viewModel.applyEdit(
            amountMinor = 4999,
            categoryId = transport.id,
            accountId = cash.id,
            note = "  ",
            occurredAtMs = 1_773_000_000_000L,
        )
        val row = ledger.rows.getValue("t-1")
        assertEquals(4999L, row.amountMinor)
        // A blank note is null, not an empty string — the same rule the
        // transactions list writes by.
        assertNull(row.note)
        assertNull(viewModel.editing.value)
        assertEquals(SavedNote.Updated, (viewModel.state.value as VoiceEntryState.Saved).note)
    }

    // --------------------------------------------- every write can fail

    /**
     * B4 applies to all four paths, not only the add. A revert that threw and
     * still reported "Cofnięto" leaves the household believing a row is gone
     * while it sits in the shared ledger — the same class of failure as a wrong
     * row written silently, and the worse half of it, because there is nothing
     * on screen to notice.
     */
    @Test
    fun `a revert whose write throws does not report success`() {
        val ledger = ledger()
        var synced = 0
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport") { synced++ }
        ledger.failWrites = true

        viewModel.revert()
        assertTrue(viewModel.state.value is VoiceEntryState.SaveFailed)
        assertEquals(0, ledger.rows.getValue("t-1").deleted)
        // Nothing changed, so nothing is asked to push.
        assertEquals(1, synced)
    }

    @Test
    fun `a spoken correction whose write throws does not report Updated`() {
        val ledger = ledger()
        val recogniser = FakeRecogniser()
        val viewModel = say(ledger, recogniser, "dodaj 200 na transport")
        ledger.failWrites = true

        viewModel.startCorrecting("pl-PL")
        recogniser.heard.value = ListenState.Done("ma być 250", emptyList())

        assertTrue(viewModel.state.value is VoiceEntryState.SaveFailed)
        assertEquals(20000L, ledger.rows.getValue("t-1").amountMinor)
    }

    @Test
    fun `a tapped edit whose write throws does not report Updated`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport")
        ledger.failWrites = true

        viewModel.applyEdit(4999, transport.id, cash.id, "", 1_773_000_000_000L)
        assertTrue(viewModel.state.value is VoiceEntryState.SaveFailed)
        assertEquals(20000L, ledger.rows.getValue("t-1").amountMinor)
    }

    @Test
    fun `an undo whose write throws does not report success`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport")
        viewModel.revert()
        ledger.failWrites = true

        viewModel.undoRevert()
        assertTrue(viewModel.state.value is VoiceEntryState.SaveFailed)
        assertEquals(1, ledger.rows.getValue("t-1").deleted)
    }

    /**
     * `copy(...)` does not touch `deleted`, so writing a row back over a
     * tombstone resurrects a transaction somebody had taken back. Reached here
     * by tombstoning the row underneath a sheet that still believes it is
     * live — which is what another device's pull, or a revert this ViewModel
     * did not do, looks like from in here.
     */
    @Test
    fun `a write over a tombstoned row is refused rather than resurrecting it`() {
        val ledger = ledger()
        val viewModel = say(ledger, FakeRecogniser(), "dodaj 200 na transport")
        ledger.rows["t-1"] = ledger.rows.getValue("t-1").copy(deleted = 1)

        viewModel.applyEdit(9999, transport.id, cash.id, "", 1_773_000_000_000L)
        assertTrue(viewModel.state.value is VoiceEntryState.SaveFailed)
        assertEquals(1, ledger.rows.getValue("t-1").deleted)
        assertEquals(20000L, ledger.rows.getValue("t-1").amountMinor)
    }

    // ------------------------------------------------- the listen lifecycle

    /**
     * The lift is not an end any more, and this is the whole of that change.
     *
     * A phone half out of a pocket is not held still for the length of a
     * sentence, so the release was never a reliable "I have finished speaking"
     * — it was a thumb shifting. Nothing but the recogniser, the two clocks and
     * the Stop button ends a listen now.
     */
    @Test
    fun `nothing but the recogniser, a clock or Stop ends a listen`() {
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger(), recogniser)
        viewModel.startListening("pl-PL", "m-1")

        // Speech began and is still going: the microphone stays open with
        // nobody's finger anywhere near it.
        recogniser.heard.value = ListenState.Speaking
        recogniser.heard.value = ListenState.Hearing("dodaj dwieście")
        assertTrue(viewModel.state.value is VoiceEntryState.Listening)
        assertEquals(0, recogniser.stops)
        assertEquals(0, recogniser.cancels)

        // Stop is still there, and still the only thing that can end a listen
        // TalkBack started.
        viewModel.stopListening()
        assertEquals(1, recogniser.stops)
    }

    /** Nobody spoke. Give up, say so, and take the message away — no button. */
    @Test
    fun `the silence cap gives up when no voice ever starts`() {
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger(), recogniser, silenceMillis = 0)
        viewModel.startListening("pl-PL", "m-1")

        val failed = viewModel.state.value as VoiceEntryState.Failed
        assertEquals(ListenFailure.NoSpeech, failed.reason)
        // cancel, not stop: no voice was ever reported, so there is nothing to
        // ask the recogniser for and nothing that can arrive late.
        assertEquals(1, recogniser.cancels)
        assertEquals(0, recogniser.stops)
    }

    /** ...and it must not fire once somebody has begun. */
    @Test
    fun `the silence cap is called off the moment a voice starts`() {
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger(), recogniser, silenceMillis = FOREVER)
        viewModel.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Speaking

        assertTrue(viewModel.state.value is VoiceEntryState.Listening)
        assertEquals(0, recogniser.cancels)
    }

    /**
     * The hard cap is a safety net against a recogniser that never endpoints,
     * not a limit on the sentence — so it stops rather than cancels, and
     * whatever was said still gets parsed.
     */
    @Test
    fun `the hard cap stops a listen that never ends itself`() {
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger(), recogniser, speechMillis = 0)
        viewModel.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Speaking

        assertEquals(1, recogniser.stops)
        assertEquals(0, recogniser.cancels)
    }

    @Test
    fun `a recogniser that finishes by itself is never hurried`() {
        val ledger = ledger()
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger, recogniser)
        viewModel.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Speaking
        recogniser.heard.value = ListenState.Done("dodaj 200 na transport", emptyList())

        assertTrue(viewModel.state.value is VoiceEntryState.Saved)
        assertEquals(0, recogniser.stops)
        assertEquals(0, recogniser.cancels)
    }

    // ------------------------------------------------------ auto-dismissal

    /**
     * Every dead end takes itself away. None of them asks anything of anybody,
     * and "Nothing was heard" with a button under it turns the phone's failure
     * into the household's chore.
     */
    @Test
    fun `a message that asks nothing closes itself`() {
        val recogniser = FakeRecogniser()

        val nothingHeard = viewModel(ledger(), recogniser, silenceMillis = 0, noticeMillis = 0)
        nothingHeard.startListening("pl-PL", "m-1")
        assertEquals(VoiceEntryState.Hidden, nothingHeard.state.value)

        // Every recogniser failure except the one that is really a request:
        // ERROR_INSUFFICIENT_PERMISSIONS has a button worth tapping.
        for (reason in ListenFailure.entries - ListenFailure.NoPermission) {
            val recognisers = FakeRecogniser()
            val failing = viewModel(ledger(), recognisers, noticeMillis = 0)
            failing.startListening("pl-PL", "m-1")
            recognisers.heard.value = ListenState.Failed(reason)
            assertEquals(reason.name, VoiceEntryState.Hidden, failing.state.value)
        }

        val refused = FakeRecogniser()
        val needsMic = viewModel(ledger(), refused, noticeMillis = 0)
        needsMic.startListening("pl-PL", "m-1")
        refused.heard.value = ListenState.Failed(ListenFailure.NoPermission)
        assertEquals(VoiceEntryState.NeedsPermission, needsMic.state.value)

        val nonsense = FakeRecogniser()
        val unparsed = viewModel(ledger(), nonsense, noticeMillis = 0)
        unparsed.startListening("pl-PL", "m-1")
        nonsense.heard.value = ListenState.Done("asdf qwerty", emptyList())
        assertEquals(VoiceEntryState.Hidden, unparsed.state.value)

        val broken = FakeRecogniser()
        val unwritable = viewModel(ledger(failWrites = true), broken, noticeMillis = 0)
        unwritable.startListening("pl-PL", "m-1")
        broken.heard.value = ListenState.Done("dodaj 200 na transport", emptyList())
        assertEquals(VoiceEntryState.Hidden, unwritable.state.value)
    }

    /**
     * The prefill is the entire point of Incomplete, so the message closing
     * itself must not be able to take the draft with it. It cannot: the handoff
     * has already gone by then, and the dismissal is a state change here and
     * nothing more.
     */
    @Test
    fun `an incomplete sentence hands over first and closes second`() {
        val recogniser = FakeRecogniser()
        val viewModel = viewModel(ledger(), recogniser, noticeMillis = 0)
        val handed = mutableListOf<SpokenTransaction>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { viewModel.handoff.collect { handed += it } }

        viewModel.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Done("dodaj 200", emptyList())

        assertEquals(20000L, handed.single().amountMinor)
        assertEquals(VoiceEntryState.Hidden, viewModel.state.value)
        scope.cancel()
    }

    /** What asks something stays. Three real choices, and a tap that is the
     *  whole point of the state it is in. */
    @Test
    fun `a summary and a permission request stay until they are answered`() {
        val recogniser = FakeRecogniser()
        val saved = viewModel(ledger(), recogniser, noticeMillis = 0)
        saved.startListening("pl-PL", "m-1")
        recogniser.heard.value = ListenState.Done("dodaj 200 na transport", emptyList())
        assertTrue(saved.state.value is VoiceEntryState.Saved)

        saved.revert()
        assertTrue(saved.state.value is VoiceEntryState.Reverted)

        val asking = viewModel(ledger(), FakeRecogniser(), noticeMillis = 0)
        asking.permissionRequired()
        assertEquals(VoiceEntryState.NeedsPermission, asking.state.value)
    }

    private companion object {
        /** Longer than any test will wait for. The coroutine parks and is never
         *  joined, so the state under test is the one left on screen. */
        const val FOREVER = 60_000L
    }
}
