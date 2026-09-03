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
import com.monyx.voice.SavedNote
import com.monyx.voice.VoiceEntryState
import com.monyx.voice.VoiceEntryViewModel
import com.monyx.voice.VoiceLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        override fun start(languageTag: String) {
            heard.value = ListenState.Listening
        }
        override fun stop() = Unit
        override fun cancel() = Unit
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

    private fun viewModel(
        ledger: FakeLedger,
        recogniser: FakeRecogniser,
        onSync: () -> Unit = {},
    ) = VoiceEntryViewModel(
        ledger = ledger,
        recogniser = recogniser,
        onSyncRequested = onSync,
        today = { today },
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
}
