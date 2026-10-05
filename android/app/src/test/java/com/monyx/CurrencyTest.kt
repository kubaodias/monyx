package com.monyx

import com.monyx.data.AccountBalance
import com.monyx.data.Currency
import com.monyx.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an account's currency is allowed to change, and what it must not.
 *
 * The criterion: złoty is the reporting currency, so a foreign account may show
 * its own balance in its own unit and may never contribute to a total. See
 * ADR 0022.
 */
class CurrencyTest {

    @Test
    fun `an unset currency reads as zloty`() {
        // Every account that existed before the column did. Silence means
        // złoty, on the phone and on the server.
        assertEquals(Currency.PLN, Currency.of(null))
        assertEquals(Currency.PLN, Currency.of(""))
    }

    @Test
    fun `an unrecognised currency reads as zloty rather than crashing`() {
        // A row from a newer client offering a currency this build does not
        // know. A figure in the wrong unit on one row beats a crash on open,
        // and the server refuses codes IT does not know, so the set can only
        // run ahead of a phone and never be invented.
        assertEquals(Currency.PLN, Currency.of("JPY"))
        assertEquals(Currency.PLN, Currency.of("HRK"))
    }

    @Test
    fun `only zloty is the reporting currency`() {
        assertTrue(Currency.PLN.isReporting)
        for (other in Currency.entries.filter { it != Currency.PLN }) {
            assertFalse("${other.code} must not read as the reporting currency", other.isReporting)
        }
    }

    @Test
    fun `the nine offered currencies match the server's list`() {
        // rates.ts carries the same nine. They have to agree or the picker
        // offers a currency a push is then refused for.
        assertEquals(
            listOf("PLN", "EUR", "USD", "GBP", "CHF", "CZK", "SEK", "NOK", "DKK"),
            Currency.entries.map { it.code },
        )
    }

    @Test
    fun `the Scandinavian three are told apart`() {
        // All three are "kr". Three rows reading "1 240,00 kr" with no way to
        // tell which is which is the reason these print their code.
        val suffixes = listOf(Currency.SEK, Currency.NOK, Currency.DKK).map { it.suffix }
        assertEquals(suffixes.distinct().size, suffixes.size)
    }

    @Test
    fun `a balance prints in its own currency`() {
        // The GROUPING follows the interface language and the suffix does not,
        // so this asserts the suffix and delegates the digits to Money.format.
        // Asserting "1 240,00" here would only be testing the JVM's locale,
        // which is English under the unit tests and Polish on the phone.
        val digits = Money.format(124_000)
        assertEquals("$digits \u20ac", Money.formatIn(124_000, Currency.EUR))
        assertEquals("$digits z\u0142", Money.formatIn(124_000, Currency.PLN))
        // The code, not a symbol, for the ones that would collide.
        assertEquals("${Money.format(50_000)} SEK", Money.formatIn(50_000, Currency.SEK))
    }

    @Test
    fun `a total still prints zloty regardless of any account`() {
        // formatWithCurrency takes no currency on purpose: a total is always in
        // the reporting currency and must not be reachable with another one.
        assertEquals("${Money.format(124_000)} z\u0142", Money.formatWithCurrency(124_000))
        assertEquals(Money.formatIn(124_000, Currency.PLN), Money.formatWithCurrency(124_000))
    }

    // ------------------------------------------- what the summary counts

    private fun balance(id: String, currency: String, excluded: Int = 0) =
        AccountBalance(
            id = id,
            name = id,
            icon = null,
            color = null,
            balanceMinor = 100_00,
            excludedFromSummary = excluded,
            currency = currency,
        )

    /**
     * The predicate OverviewViewModel applies before summing balances. Kept
     * here in the same shape so the rule is asserted rather than only read.
     */
    private fun counted(accountIds: Set<String>): (AccountBalance) -> Boolean = {
        Currency.of(it.currency).isReporting &&
            if (accountIds.isEmpty()) it.excludedFromSummary == 0 else it.id in accountIds
    }

    @Test
    fun `a foreign account is left out of the balance total`() {
        val accounts = listOf(balance("portfel", "PLN"), balance("revolut", "EUR"))
        val summed = accounts.filter(counted(emptySet())).sumOf { it.balanceMinor }
        // 100,00 zł, not 200,00 of two different things.
        assertEquals(100_00L, summed)
    }

    @Test
    fun `selecting a foreign account explicitly still does not add it to a total`() {
        // The guard is unconditional, in the DAO and here. Tapping the account
        // on the Overview — if a chip for it existed — must not mix units; the
        // chip is hidden too, which is belt and braces on the same rule.
        val accounts = listOf(balance("portfel", "PLN"), balance("revolut", "EUR"))
        val summed = accounts.filter(counted(setOf("portfel", "revolut"))).sumOf { it.balanceMinor }
        assertEquals(100_00L, summed)
    }

    @Test
    fun `a foreign account excluded from the summary is out for both reasons`() {
        val accounts = listOf(balance("revolut", "EUR", excluded = 1))
        assertEquals(0L, accounts.filter(counted(emptySet())).sumOf { it.balanceMinor })
    }

    @Test
    fun `a zloty account kept out of the summary is still out`() {
        // The currency guard must not have replaced the older rule.
        val accounts = listOf(balance("pzu", "PLN", excluded = 1))
        assertEquals(0L, accounts.filter(counted(emptySet())).sumOf { it.balanceMinor })
    }
}
