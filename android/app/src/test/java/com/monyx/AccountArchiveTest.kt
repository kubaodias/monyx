package com.monyx

import com.monyx.data.AccountEntity
import com.monyx.data.Currency
import com.monyx.data.MonyxRepository
import com.monyx.ui.settings.AccountRow
import com.monyx.ui.settings.AccountSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ------------------------------------------- what the editor opens on

    /**
     * The editor asks for the balance the account has NOW and works the opening
     * one backwards from it, so the seed has to carry the difference between
     * the two. Get this wrong and saving an account without touching the
     * balance field moves it by every transaction it has ever held.
     *
     * Worth a test even though it is subtraction: it is the one piece of
     * arithmetic in the editor, it is invisible on screen, and the editor is
     * Compose and so out of this suite's reach.
     */
    @Test
    fun `the seed carries what the transactions have done since the account opened`() {
        val seed = AccountSeed.of(
            AccountRow(
                entity = account().copy(initialBalanceMinor = 50_000, currency = "EUR"),
                balanceMinor = 123_45,
            ),
        )

        assertEquals(12345L, seed.balanceMinor)
        assertEquals(12345L - 50_000L, seed.movementsMinor)
        // opening = typed - movements, so re-saving the same balance is a no-op
        assertEquals(50_000L, seed.balanceMinor - seed.movementsMinor)
        assertEquals("acc-1", seed.accountId)
        assertEquals(Currency.EUR, seed.currency)
        assertEquals(true, seed.inSummary)
    }

    /** An account held outside the summary opens with the switch off. */
    @Test
    fun `an account outside the summary seeds the switch off`() {
        assertEquals(
            false,
            AccountSeed.of(AccountRow(entity = account(outside = 1), balanceMinor = 0)).inSummary,
        )
    }

    /** No row behind it, which is what tells the editor to insert. */
    @Test
    fun `a new account has no id and no movements`() {
        val seed = AccountSeed()
        assertNull(seed.accountId)
        assertEquals(0L, seed.movementsMinor)
        assertEquals(Currency.PLN, seed.currency)
    }
}
