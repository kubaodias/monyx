package com.monyx

import com.monyx.data.AccountEntity
import com.monyx.data.MonyxRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What archiving an account does to the other flag.
 *
 * The two are orthogonal — `archived` is lifecycle, `excludedFromSummary` is
 * ownership — and a finished account that was still counted turned out to be
 * unreachable: the summary strip only offers open accounts, so its balance sat
 * inside every total with no chip anywhere to switch it off.
 */
class AccountArchiveTest {

    private fun account(outside: Int = 0, archived: Int = 0) = AccountEntity(
        id = "acc-1",
        name = "Wakacje 2026",
        archived = archived,
        excludedFromSummary = outside,
    )

    @Test
    fun `archiving takes the account out of the summary`() {
        val archived = MonyxRepository.applyArchive(account(), archived = true)
        assertEquals(1, archived.archived)
        assertEquals(1, archived.excludedFromSummary)
    }

    /** An account already held outside the summary is simply left there. */
    @Test
    fun `archiving an already excluded account changes nothing else`() {
        val archived = MonyxRepository.applyArchive(account(outside = 1), archived = true)
        assertEquals(1, archived.excludedFromSummary)
    }

    /**
     * Un-archiving does NOT put it back in. Restoring the old value would mean
     * storing it, and a schema change wipes every phone — see MonyxDatabase. The
     * toggle is on the same row, one tap away.
     */
    @Test
    fun `un-archiving leaves it outside the summary`() {
        val reopened = MonyxRepository.applyArchive(
            account(outside = 1, archived = 1),
            archived = false,
        )
        assertEquals(0, reopened.archived)
        assertEquals(1, reopened.excludedFromSummary)
    }

    /** One that was never excluded stays counted when it comes back. */
    @Test
    fun `un-archiving a counted account keeps it counted`() {
        val reopened = MonyxRepository.applyArchive(account(archived = 1), archived = false)
        assertEquals(0, reopened.archived)
        assertEquals(0, reopened.excludedFromSummary)
    }

    /** Either way it is a local edit the sync has not carried yet. */
    @Test
    fun `marks the row pending and clears any rejection`() {
        val archived = MonyxRepository.applyArchive(
            account().copy(rejected = 1),
            archived = true,
        )
        assertEquals(1, archived.pending)
        assertEquals(0, archived.rejected)
    }
}
