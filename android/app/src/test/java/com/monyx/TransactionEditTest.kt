package com.monyx

import com.monyx.data.Dates
import com.monyx.data.MonyxRepository
import com.monyx.data.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The one fold three screens share.
 *
 * History, the Overview's recent list and the voice summary all edit the same
 * transaction through [MonyxRepository.applyEdit], and it is on the companion
 * for the same reason `allAccounts` is: the thing worth pinning has no database
 * in it. What it is protecting is `occurredOn` — a derived column every month
 * query buckets on, which an editor that only wrote `occurredAt` would leave
 * pointing at the old month while the row showed its new date.
 */
class TransactionEditTest {

    private val original = TransactionEntity(
        id = "t-1",
        kind = "expense",
        amountMinor = 20000,
        accountId = "a-cash",
        categoryId = "c-transport",
        note = "bilet",
        occurredAt = Dates.startOfDayMillis(LocalDate.of(2026, 3, 14)) + 12 * 60 * 60 * 1000,
        occurredOn = "2026-03-14",
        createdBy = "m-1",
        createdAt = 0,
        seq = 7,
        pending = 0,
        rejected = 1,
    )

    private fun edit(
        amountMinor: Long = original.amountMinor,
        categoryId: String? = original.categoryId,
        accountId: String = original.accountId,
        note: String = original.note.orEmpty(),
        occurredAtMs: Long = original.occurredAt,
    ) = MonyxRepository.applyEdit(original, amountMinor, categoryId, accountId, note, occurredAtMs)

    @Test
    fun `the five fields an editor owns are the five it changes`() {
        val edited = edit(amountMinor = 4999, categoryId = "c-home", accountId = "a-card", note = "  paragon ")
        assertEquals(4999L, edited.amountMinor)
        assertEquals("c-home", edited.categoryId)
        assertEquals("a-card", edited.accountId)
        assertEquals("  paragon ", edited.note)

        // Everything else is carried, seq and the sync flags included — the
        // fold does not touch them, so nothing here can lose a row's place in
        // the epoch or quietly clear a rejection.
        assertEquals(original.id, edited.id)
        assertEquals(original.kind, edited.kind)
        assertEquals(original.createdBy, edited.createdBy)
        assertEquals(original.seq, edited.seq)
        assertEquals(original.rejected, edited.rejected)
        assertEquals(original.deleted, edited.deleted)
    }

    /** A blank note is the absence of one. An empty string in the column reads
     *  as a note nobody wrote and shows up in a LIKE search for "". */
    @Test
    fun `a blank note is cleared, not stored as an empty string`() {
        assertNull(edit(note = "").note)
        assertNull(edit(note = "   ").note)
    }

    /**
     * The one that would be silent. Moving a transaction to another month has
     * to move it for every total in the app, and those all group on occurredOn.
     */
    @Test
    fun `moving the date moves the month the row is counted in`() {
        val moved = edit(occurredAtMs = Dates.startOfDayMillis(LocalDate.of(2026, 4, 2)) + 12 * 60 * 60 * 1000)
        assertEquals("2026-04-02", moved.occurredOn)
        assertEquals("2026-04", Dates.periodOfDateString(moved.occurredOn))
    }

    @Test
    fun `an edit that changes nothing changes nothing`() {
        assertEquals(original, edit())
    }
}
