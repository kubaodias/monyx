package com.monyx

import com.monyx.data.AccountEntity
import com.monyx.data.defaultAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which account is "the default one".
 *
 * Worth its own tests because it was wrong twice in two different directions,
 * and because nothing on screen announces the answer — it shows up only as a
 * pill on a ledger row or as the account the keypad happens to have opened on.
 */
class DefaultAccountTest {

    private fun account(
        name: String,
        sortOrder: Int = 0,
        archived: Int = 0,
        outside: Int = 0,
    ) = AccountEntity(
        id = "acc-$name",
        name = name,
        sortOrder = sortOrder,
        archived = archived,
        excludedFromSummary = outside,
    )

    /** The DAO's order: `sortOrder, name`. Every case starts from it. */
    private fun ordered(vararg accounts: AccountEntity) =
        accounts.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    @Test
    fun `takes the first account in the household's order`() {
        val accounts = ordered(
            account("Portfel", sortOrder = 0),
            account("Poduszka", sortOrder = 1),
            account("PZU", sortOrder = 2),
        )
        assertEquals("acc-Portfel", defaultAccount(accounts)?.id)
    }

    /**
     * The bug this was written for. Nothing had ever been dragged, so several
     * accounts sat at sortOrder 0 and the tie fell to the name — putting a
     * savings account ahead of the current account it alphabetically precedes.
     */
    @Test
    fun `an outside-summary account never wins a tie on sortOrder`() {
        val accounts = ordered(
            account("Oszczędności", sortOrder = 0, outside = 1),
            account("Portfel", sortOrder = 0),
        )
        assertEquals("acc-Oszczędności", accounts.first().id)
        assertEquals("acc-Portfel", defaultAccount(accounts)?.id)
    }

    @Test
    fun `an outside-summary account never wins on sortOrder either`() {
        val accounts = ordered(
            account("IKE", sortOrder = 0, outside = 1),
            account("Portfel", sortOrder = 5),
        )
        assertEquals("acc-Portfel", defaultAccount(accounts)?.id)
    }

    @Test
    fun `a finished account is never the default`() {
        val accounts = ordered(
            account("Wakacje 2026", sortOrder = 0, archived = 1),
            account("Portfel", sortOrder = 1),
        )
        assertEquals("acc-Portfel", defaultAccount(accounts)?.id)
    }

    @Test
    fun `archived and outside together are still skipped`() {
        val accounts = ordered(
            account("Tatry 2026", sortOrder = 0, archived = 1, outside = 1),
            account("Oszczędności", sortOrder = 0, outside = 1),
            account("Portfel", sortOrder = 1),
        )
        assertEquals("acc-Portfel", defaultAccount(accounts)?.id)
    }

    /**
     * A household that keeps every open account out of the summary still has to
     * be able to add a transaction, so the rule gives ground rather than giving
     * up. Archived is the one exclusion that never yields: those are closed.
     */
    @Test
    fun `falls back to the first open account when all of them are outside`() {
        val accounts = ordered(
            account("IKE", sortOrder = 0, outside = 1),
            account("Obligacje", sortOrder = 1, outside = 1),
        )
        assertEquals("acc-IKE", defaultAccount(accounts)?.id)
    }

    @Test
    fun `nothing open means no default`() {
        val accounts = ordered(
            account("Wakacje 2025", sortOrder = 0, archived = 1),
            account("Tatry 2026", sortOrder = 1, archived = 1),
        )
        assertNull(defaultAccount(accounts))
    }

    @Test
    fun `no accounts at all means no default`() {
        assertNull(defaultAccount(emptyList()))
    }

    /**
     * Once the accounts have been dragged, sortOrder is position and there is
     * nothing left to tie-break — dragging Portfel to the top has to be enough
     * to make it the default, whatever it is called.
     */
    @Test
    fun `dragging an account to the top makes it the default`() {
        val accounts = ordered(
            account("Portfel", sortOrder = 0),
            account("Alfa", sortOrder = 1),
        )
        assertEquals("acc-Portfel", defaultAccount(accounts)?.id)
    }
}
