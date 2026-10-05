package com.monyx

import com.monyx.data.AccountBalance
import com.monyx.data.Currency
import com.monyx.data.Money
import com.monyx.data.TransactionListItem
import com.monyx.ui.overview.OverviewViewModel
import com.monyx.ui.transactions.TransactionsViewModel
import com.monyx.ui.settings.currencyLabel
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

    @Test
    fun `the dropdown label does not repeat a code that is its own suffix`() {
        // "EUR \u2014 \u20ac" tells you which symbol will appear on the account row.
        assertEquals("EUR \u2014 \u20ac", currencyLabel(Currency.EUR))
        assertEquals("PLN \u2014 z\u0142", currencyLabel(Currency.PLN))
        // "CHF \u2014 CHF" would be the same word twice.
        assertEquals("CHF", currencyLabel(Currency.CHF))
        assertEquals("SEK", currencyLabel(Currency.SEK))
    }

    // ------------------------------------------- what the summary counts

    private fun balance(
        id: String,
        currency: String,
        excluded: Int = 0,
        nativeMinor: Long = 100_00,
        plnMinor: Long? = 100_00,
    ) = AccountBalance(
        id = id,
        name = id,
        icon = null,
        color = null,
        balanceMinor = nativeMinor,
        excludedFromSummary = excluded,
        currency = currency,
        plnMinor = plnMinor,
    )

    /**
     * These call OverviewViewModel.summedBalance directly.
     *
     * An earlier version of this file kept its own copy of the predicate, which
     * meant the tests went on passing after the production rule changed under
     * them — they were asserting the copy. Calling the real function is the
     * whole point.
     */

    @Test
    fun `a converted account is counted in zloty, not at face value`() {
        // 1 240,00 € converted to 5 424,38 zł, plus 100,00 zł.
        val accounts = listOf(
            balance("portfel", "PLN", nativeMinor = 100_00, plnMinor = 100_00),
            balance("revolut", "EUR", nativeMinor = 124_000, plnMinor = 542_438),
        )
        assertEquals(552_438L, OverviewViewModel.summedBalance(accounts, emptySet()))
    }

    @Test
    fun `an account with no rate yet contributes nothing rather than its face value`() {
        // The degradation that matters. Adding 124_000 euro cents to grosze
        // would overstate the total by thousands of złoty, silently.
        val accounts = listOf(
            balance("portfel", "PLN", nativeMinor = 100_00, plnMinor = 100_00),
            balance("revolut", "EUR", nativeMinor = 124_000, plnMinor = null),
        )
        assertEquals(100_00L, OverviewViewModel.summedBalance(accounts, emptySet()))
    }

    @Test
    fun `an account kept out of the summary is still out once convertible`() {
        // Conversion must not have quietly replaced the older rule.
        val accounts = listOf(balance("pzu", "PLN", excluded = 1))
        assertEquals(0L, OverviewViewModel.summedBalance(accounts, emptySet()))
    }

    @Test
    fun `an explicit selection counts the converted figure`() {
        val accounts = listOf(
            balance("portfel", "PLN", nativeMinor = 100_00, plnMinor = 100_00),
            balance("revolut", "EUR", nativeMinor = 124_000, plnMinor = 542_438),
        )
        // Selecting only the euro account gives its złoty value, not its cents.
        assertEquals(542_438L, OverviewViewModel.summedBalance(accounts, setOf("revolut")))
        // And an excluded account selected ON DEMAND does count — that is what
        // the strip's buttons are for.
        val withExcluded = listOf(balance("pzu", "PLN", excluded = 1))
        assertEquals(100_00L, OverviewViewModel.summedBalance(withExcluded, setOf("pzu")))
    }

    // ------------------------------------- the ledger's rows and its total

    private fun row(kind: String, amountMinor: Long, currency: String, plnMinor: Long?) =
        TransactionListItem(
            id = "t-$currency-$amountMinor",
            kind = kind,
            amountMinor = amountMinor,
            note = null,
            occurredAt = 0,
            occurredOn = "2026-10-05",
            categoryId = null,
            accountId = "a",
            categoryName = null,
            categoryIcon = null,
            categoryColor = null,
            categoryColorKey = null,
            accountName = null,
            transferAccountName = null,
            currency = currency,
            plnMinor = plnMinor,
            recurringRuleId = null,
            pending = 0,
            rejected = 0,
        )

    /**
     * The real sum, not a copy of it.
     *
     * The first draft of these tests reimplemented the filter here, which is
     * the same mistake the balance tests above were written to undo: a test
     * asserting its own copy passes forever, whatever the app does.
     */
    private fun expenseTotal(rows: List<TransactionListItem>): Long =
        TransactionsViewModel.totalsOf(rows).expenseMinor

    @Test
    fun `the ledger total converts instead of adding two currencies together`() {
        // The bug: 15,00 € and 89,99 zł summed to 104,99 and were printed with
        // "zł" beside them. 15,00 € is 65,78 zł, so the real total is 155,77.
        val rows = listOf(
            row("expense", 15_00, "EUR", 65_78),
            row("expense", 89_99, "PLN", 89_99),
        )
        assertEquals(155_77L, expenseTotal(rows))
        // And emphatically not the old answer.
        assertEquals(false, expenseTotal(rows) == 104_99L)
    }

    @Test
    fun `a row with no rate contributes nothing to the ledger total`() {
        val rows = listOf(
            row("expense", 15_00, "EUR", null),
            row("expense", 89_99, "PLN", 89_99),
        )
        assertEquals(89_99L, expenseTotal(rows))
    }

    @Test
    fun `income rows stay out of the expense total`() {
        // Guarding the existing split while changing the column it sums.
        val rows = listOf(
            row("income", 5_000_00, "PLN", 5_000_00),
            row("expense", 89_99, "PLN", 89_99),
        )
        assertEquals(89_99L, expenseTotal(rows))
    }

    @Test
    fun `a foreign row carries its unit and a zloty row does not`() {
        // The rule the ledger row applies: mark the exception, leave the rest
        // bare. Two identical-looking "-15,00" rows for different money was
        // the complaint.
        fun suffixFor(code: String) =
            Currency.of(code).let { if (it.isReporting) "" else " ${it.suffix}" }
        assertEquals(" €", suffixFor("EUR"))
        assertEquals(" SEK", suffixFor("SEK"))
        assertEquals("", suffixFor("PLN"))
    }
}
