package com.monyx

import com.monyx.data.AccountBalance
import com.monyx.data.Currencies
import com.monyx.data.Currency
import com.monyx.data.Money
import com.monyx.data.TransactionListItem
import com.monyx.ui.add.AddUiState
import com.monyx.ui.add.AddViewModel
import com.monyx.ui.add.EntryKind
import com.monyx.ui.overview.OverviewViewModel
import com.monyx.ui.transactions.TransactionsViewModel
import com.monyx.ui.transactions.dayTotalMinor
import com.monyx.ui.settings.currencyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a currency this build has no symbol for reads as itself, not as zloty`() {
        // It used to read as złoty, which was defensible while the nine were
        // the only possibilities and is the opposite of safe now that a
        // household can add its own: a baht row resolving to PLN would print
        // "zł" after a baht figure AND claim to be the reporting currency, so
        // the summary would count it as złoty. Itself, with no rate, is the
        // truth about a code nobody on this phone has named.
        assertEquals("THB", Currency.of("THB").code)
        assertEquals("THB", Currency.of("THB").suffix)
        assertFalse(Currency.of("THB").isReporting)
    }

    @Test
    fun `two of the same code are the same currency whatever symbol each carries`() {
        // The app compares these to answer "is this the account's own unit",
        // and one side may have been resolved before the symbols had loaded.
        assertEquals(Currency.of("THB"), Currency.custom("THB", "฿"))
        assertEquals(Currency.of("EUR"), Currency.EUR)
        assertFalse(Currency.of("THB") == Currency.of("SEK"))
    }

    @Test
    fun `a code has to be three capitals`() {
        // The same rule the server applies, so nothing offered here can be
        // refused at sync time. See isCurrencyCode in server/src/rates.ts.
        assertTrue(Currency.isValidCode("THB"))
        assertFalse(Currency.isValidCode("thb"))
        assertFalse(Currency.isValidCode("EU"))
        assertFalse(Currency.isValidCode("EURO"))
        assertFalse(Currency.isValidCode(""))
    }

    @Test
    fun `only zloty is the reporting currency`() {
        assertTrue(Currency.PLN.isReporting)
        for (other in Currency.KNOWN.filter { it != Currency.PLN }) {
            assertFalse("${other.code} must not read as the reporting currency", other.isReporting)
        }
        assertFalse(Currency.custom("PLX", "zl").isReporting)
    }

    @Test
    fun `the nine rated currencies match the server's list`() {
        // rates.ts carries the same nine. They have to agree or a currency the
        // picker calls convertible has no rate to convert by — which is no
        // longer a rejected push, and is therefore worth asserting.
        assertEquals(
            listOf("PLN", "EUR", "USD", "GBP", "CHF", "CZK", "SEK", "NOK", "DKK"),
            Currency.KNOWN.map { it.code },
        )
        assertEquals(Currency.KNOWN.map { it.code }.toSet(), Currency.RATED)
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

    // ------------------------------------------ which ones a picker offers

    @Test
    fun `a phone nobody has configured offers zloty and the euro`() {
        // The nine are what the app can CONVERT; this is what a Polish
        // household meets. Seven more entries in a picker reached from a chip
        // beside the amount is seven things between a thumb and the euro, and
        // the dollar was the eighth until somebody counted them.
        assertEquals(
            listOf("PLN", "EUR"),
            Currencies.offered(null).map { it.code },
        )
    }

    @Test
    fun `one typed in by hand is offered with the symbol it was given`() {
        // A code and the text to print after a figure is all a currency is
        // here. No rate, which is a separate fact — see the ledger rows below.
        val offered = Currencies.offered(setOf("EUR", "THB=฿"))
        assertEquals(listOf("PLN", "EUR", "THB"), offered.map { it.code })
        assertEquals("฿", offered.last().suffix)
    }

    @Test
    fun `an added currency with no symbol prints its code`() {
        // Which is what CHF and the krona do anyway. A figure with no unit
        // after it on a ledger of mixed currencies is the worse outcome.
        assertEquals("THB", Currencies.offered(setOf("THB")).last().suffix)
        assertEquals("THB", Currencies.offered(setOf("THB=")).last().suffix)
    }

    @Test
    fun `a known currency keeps its own symbol whatever was stored beside it`() {
        // "EUR=E" would be one phone printing a glyph for the euro that no
        // other screen in the app agrees with.
        assertEquals("€", Currencies.offered(setOf("EUR=E")).last().suffix)
        assertEquals("EUR", Currencies.entry("eur", "E"))
        assertEquals("THB=฿", Currencies.entry("thb", " ฿ "))
        assertEquals("THB", Currencies.entry("THB", ""))
    }

    @Test
    fun `an entry that is not a code is dropped rather than shown`() {
        assertEquals(listOf("PLN"), Currencies.offered(setOf("zloty", "EU", "")).map { it.code })
    }

    @Test
    fun `the symbols handed to the resolver are the added ones only`() {
        // What MonyxApp publishes to Currency.of, which every ledger row goes
        // through. The nine resolve themselves and do not belong in it.
        assertEquals(
            mapOf("THB" to "฿"),
            Currencies.symbols(setOf("EUR", "USD", "THB=฿")),
        )
    }

    @Test
    fun `zloty cannot be hidden, whatever has been chosen`() {
        // Every total is in it and every account defaults to it: a picker
        // without złoty is one you cannot get back out of.
        assertTrue(Currency.PLN in Currencies.offered(emptySet()))
        assertTrue(Currencies.locked(Currency.PLN, emptySet()))
    }

    @Test
    fun `a currency an account holds is offered even when it was removed`() {
        // Removing is about not being asked. An account denominated in krona is
        // a fact already written down, and a form that would not offer SEK
        // could not edit that account without silently changing what its money
        // is. Including a custom one, which is the case that matters most: it
        // is the only record of what that symbol means.
        val offered = Currencies.offered(setOf("EUR"), inUse = setOf("SEK", "THB"))
        assertEquals(listOf("PLN", "EUR", "SEK", "THB"), offered.map { it.code })
        assertTrue("Settings must refuse it", Currencies.locked(Currency.SEK, setOf("SEK")))
    }

    @Test
    fun `removing one leaves the others alone`() {
        val offered = Currencies.offered(setOf("USD")).map { it.code }
        assertEquals(listOf("PLN", "USD"), offered)
        assertFalse(Currencies.locked(Currency.EUR, emptySet()))
    }

    @Test
    fun `removing the last one is not the same as never choosing`() {
        // Empty is a decision and has to stick; absent is "nobody has said",
        // which is the default. Collapsing the two would make the last removal
        // silently bring the euro back.
        assertEquals(listOf("PLN"), Currencies.offered(emptySet()).map { it.code })
        assertEquals(listOf("PLN", "EUR"), Currencies.offered(null).map { it.code })
    }

    @Test
    fun `the offer lists the rated ones in their own order, then the rest`() {
        // The picker is a reference list, not a ranking: PLN, EUR, USD, GBP …
        // is the order it has always been in, and a set has no order to carry.
        // Anything added by hand comes after all of those, alphabetically.
        assertEquals(
            listOf("PLN", "EUR", "GBP", "DKK", "BHD", "THB"),
            Currencies.offered(setOf("THB", "DKK", "GBP", "EUR", "BHD")).map { it.code },
        )
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
    fun `every ledger row carries its unit and only a foreign one converts`() {
        // Money.ledgerRowFigure, not a copy of the rule. The previous version
        // of this test reimplemented the suffix inline and asserted its own
        // arithmetic — so it passed while the production change it was written
        // for never landed in TransactionsScreen at all, and every euro row
        // shipped for two releases looking exactly like a złoty one.
        val euro = Money.ledgerRowFigure(15_00, "expense", Currency.EUR, 65_78)
        assertEquals("${Money.formatSigned(15_00, "expense")} €", euro.main)
        assertEquals("${Money.formatSigned(65_78, "expense")} zł", euro.aside)

        // "zł" on the ordinary row too, which it did not use to carry: only the
        // exceptions were marked, and a column holding one marked figure and
        // four unmarked ones is four figures the reader has to attribute from
        // memory. The second line stays an exception — there is nothing to
        // convert a złoty row to.
        val zloty = Money.ledgerRowFigure(89_99, "expense", Currency.PLN, 89_99)
        assertEquals("${Money.formatSigned(89_99, "expense")} zł", zloty.main)
        assertNull("a złoty row has nothing to convert to", zloty.aside)
    }

    @Test
    fun `a day heading converts instead of adding two currencies together`() {
        // The same bug as the total under the search box, one release later and
        // one heading further down: a day holding three euro purchases printed
        // a euro-sized figure with "zł" beside it, while every other day in the
        // list was honest. 15,00 € is 65,78 zł, so this day cost 155,77 zł.
        val rows = listOf(
            row("expense", 15_00, "EUR", 65_78),
            row("expense", 89_99, "PLN", 89_99),
        )
        assertEquals(-155_77L, dayTotalMinor(rows))
        assertEquals("the old answer added cents to grosze", false, dayTotalMinor(rows) == -104_99L)
    }

    @Test
    fun `a day heading nets income against spending and leaves transfers out`() {
        val rows = listOf(
            row("income", 5_000_00, "PLN", 5_000_00),
            row("expense", 89_99, "PLN", 89_99),
            // A transfer is one row with one amount and it is not spending.
            row("transfer", 1_000_00, "PLN", 1_000_00),
        )
        assertEquals(4_910_01L, dayTotalMinor(rows))
    }

    @Test
    fun `a day with a row the rate is missing for counts the rest of it`() {
        // Null plnMinor contributes nothing rather than its face value in the
        // wrong unit — the degradation every total in the app shares.
        val rows = listOf(
            row("expense", 15_00, "EUR", null),
            row("expense", 89_99, "PLN", 89_99),
        )
        assertEquals(-89_99L, dayTotalMinor(rows))
    }

    @Test
    fun `a row with no rate still shows what was entered`() {
        // The amount that happened is known; only its złoty value is not. So
        // the row keeps its figure and simply says nothing it cannot say.
        val row = Money.ledgerRowFigure(15_00, "expense", Currency.EUR, null)
        assertEquals("${Money.formatSigned(15_00, "expense")} €", row.main)
        assertNull(row.aside)
    }

    @Test
    fun `an income row keeps its sign in both units`() {
        // Both halves go through formatSigned, so a credit cannot print as
        // "+15,00 €" over "-65,78 zł".
        val row = Money.ledgerRowFigure(15_00, "income", Currency.EUR, 65_78)
        assertTrue(row.main.startsWith("+"))
        assertTrue(row.aside!!.startsWith("+"))
    }

    @Test
    fun `an account tile leads with its own money and keeps zloty beside it`() {
        // One line, the account's own currency first: a tile is that one
        // account's position, and "how many euro have I got" is what the strip
        // is opened to answer. The brackets are what let the złoty figure share
        // the line instead of making exactly one tile taller than its
        // neighbours. 0.23.1 had these the other way round.
        val euro = Money.accountStripFigure(15_00, Currency.EUR, 65_78)
        assertEquals(Money.formatIn(15_00, Currency.EUR), euro.main)
        assertEquals("(${Money.formatWithCurrency(65_78)})", euro.aside)

        val zloty = Money.accountStripFigure(100_00, Currency.PLN, 100_00)
        assertEquals(Money.formatWithCurrency(100_00), zloty.main)
        assertNull("złoty twice would be noise on every other tile", zloty.aside)
    }

    @Test
    fun `an account with no rate still shows its own balance`() {
        // The money is not hidden — it is the headline figure now. What is
        // missing is the conversion, and that is said with an em dash inside the
        // brackets rather than by dropping them, or the tile would look like a
        // złoty account.
        val tile = Money.accountStripFigure(15_00, Currency.EUR, null)
        assertEquals(Money.formatIn(15_00, Currency.EUR), tile.main)
        assertEquals("(—)", tile.aside)
    }

    @Test
    fun `a zloty row on a foreign account shows both, zloty leading`() {
        // The report this came from: 100 zł paid from a euro card sat in a list
        // of euro rows as a bare "100,00", which reads as euro. Złoty leads
        // because złoty is what happened; the euro figure is what the account's
        // other rows are in.
        val row = Money.ledgerRowFigure(
            amountMinor = 100_00,
            kind = "expense",
            currency = Currency.PLN,
            plnMinor = 100_00,
            accountCurrency = Currency.EUR,
            accountMinor = 22_80,
        )
        assertEquals("${Money.formatSigned(100_00, "expense")} zł", row.main)
        assertEquals("${Money.formatSigned(22_80, "expense")} €", row.aside)
    }

    @Test
    fun `a zloty row on a foreign account with no rate still says zloty`() {
        // The unit of the figure is known even when the conversion is not, and
        // it is the half that matters: without it the row reads as euro.
        val row = Money.ledgerRowFigure(
            amountMinor = 100_00,
            kind = "expense",
            currency = Currency.PLN,
            plnMinor = 100_00,
            accountCurrency = Currency.EUR,
            accountMinor = null,
        )
        assertEquals("${Money.formatSigned(100_00, "expense")} zł", row.main)
        assertNull(row.aside)
    }

    @Test
    fun `the row's own currency wins over its account's`() {
        // A euro row on a euro account converts to złoty, not to itself. The
        // account's currency decides the SECOND figure only, and only for a row
        // that is already in złoty.
        val row = Money.ledgerRowFigure(
            amountMinor = 15_00,
            kind = "expense",
            currency = Currency.EUR,
            plnMinor = 65_78,
            accountCurrency = Currency.EUR,
            accountMinor = 15_00,
        )
        assertEquals("${Money.formatSigned(15_00, "expense")} €", row.main)
        assertEquals("${Money.formatSigned(65_78, "expense")} zł", row.aside)
    }

    // ------------------------------------------- one entry's own currency

    @Test
    fun `an entry follows its account until it is told otherwise`() {
        // null is "whatever the account says", which is the state a fresh entry
        // starts in. Storing a copy of the account's currency instead would go
        // stale the moment another account was picked.
        val fresh = AddUiState()
        assertEquals(Currency.EUR, fresh.currencyOr(Currency.EUR))
        assertEquals(Currency.PLN, fresh.currencyOr(Currency.PLN))
    }

    @Test
    fun `an explicit choice wins over the account`() {
        // The case this feature exists for: 15 EUR paid with a złoty card.
        val overridden = AddUiState(currency = Currency.EUR)
        assertEquals(Currency.EUR, overridden.currencyOr(Currency.PLN))
    }

    @Test
    fun `the keypad's next entry carries no override`() {
        // nextEntry is what the keypad resets to after a save. An override left
        // behind would silently apply to the following purchase, which is the
        // same mistake carrying the ACCOUNT over would have been — see the note
        // on nextEntry.
        assertEquals(null, AddViewModel.nextEntry(EntryKind.Expense, emptyList()).currency)
    }
}
