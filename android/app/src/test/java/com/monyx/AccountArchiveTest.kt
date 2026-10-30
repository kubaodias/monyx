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
 * What archiving an account does to the other flag: nothing.
 *
 * The two are orthogonal — `archived` is lifecycle, `excludedFromSummary` is
 * ownership — and archiving used to flip the second one as well, to keep a
 * finished holiday account out of the budget's carry-over. The carry-over is
 * narrowed to the accounts the budget counts now, so that patch outlived its
 * bug, and what it left behind was a switch nobody touched moving the
 * household's total: this household's per-trip accounts are expense buckets
 * nobody funded, so archiving one RAISED Bilans by what the trip had cost.
 */
class AccountArchiveTest {

    private fun account(outside: Int = 0, archived: Int = 0) = AccountEntity(
        id = "acc-1",
        name = "Wakacje 2026",
        archived = archived,
        excludedFromSummary = outside,
    )

    @Test
    fun `archiving hides the account and leaves the summary alone`() {
        // The trip is over; what it cost is still the household's money. The
        // account loses its chip, its place in the pickers and its rows in the
        // ledger's month list — and keeps its balance inside Bilans.
        val archived = MonyxRepository.applyArchive(account(), archived = true)
        assertEquals(1, archived.archived)
        assertEquals(0, archived.excludedFromSummary)
    }

    /** An account already held outside the summary is simply left there. */
    @Test
    fun `archiving an already excluded account changes nothing else`() {
        val archived = MonyxRepository.applyArchive(account(outside = 1), archived = true)
        assertEquals(1, archived.archived)
        assertEquals(1, archived.excludedFromSummary)
    }

    /**
     * The flag is the household's to set, in either direction, and archiving is
     * not a vote on it. Nothing to restore on the way back, because nothing was
     * taken away — which is also the old asymmetry gone: archiving used to
     * exclude and un-archiving deliberately did not re-include.
     */
    @Test
    fun `un-archiving leaves the flag exactly as it was`() {
        val wasExcluded = MonyxRepository.applyArchive(
            account(outside = 1, archived = 1),
            archived = false,
        )
        assertEquals(0, wasExcluded.archived)
        assertEquals(1, wasExcluded.excludedFromSummary)

        val wasCounted = MonyxRepository.applyArchive(account(archived = 1), archived = false)
        assertEquals(0, wasCounted.archived)
        assertEquals(0, wasCounted.excludedFromSummary)
    }

    @Test
    fun `archiving and restoring is a round trip`() {
        // The pair that used to lose something on the way: archive, restore,
        // and the account came back counted differently from how it left.
        val before = account()
        val after = MonyxRepository.applyArchive(
            MonyxRepository.applyArchive(before, archived = true),
            archived = false,
        )
        assertEquals(before.archived, after.archived)
        assertEquals(before.excludedFromSummary, after.excludedFromSummary)
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
