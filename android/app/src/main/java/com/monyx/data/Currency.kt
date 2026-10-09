package com.monyx.data

/**
 * What an account's money is denominated in: a code, and the text to print
 * after a figure in it.
 *
 * The REPORTING currency is złoty and is not configurable — every total the app
 * prints is in złoty. This says what the rows of one account are counted in.
 * See docs/decisions/0022.
 *
 * [KNOWN] is the list that has RATES: those nine are in NBP table A, the server
 * fetches them daily, and a row in one of them converts into złoty and counts
 * towards every total. A currency outside that list is still allowed — the
 * household types the code and the symbol in Settings — and is still stored,
 * synced and shown; it simply has no rate, so its rows show their own figure and
 * contribute nothing to a total. That is the same state a known currency is in
 * before its first rate has synced, which the whole app already handles: see
 * [Money.ledgerRowFigure] and `ledger_pln`.
 *
 * Not an enum any more, for exactly that reason. It was one while the nine were
 * the only possibilities; now the set is open, and the thing that must stay
 * closed is the arithmetic. Every currency here is assumed to be a two-decimal
 * one, which is what lets [AccountEntity.initialBalanceMinor] and every amount
 * stay "hundredths of the unit" with no per-currency minor-unit handling. A
 * household adding JPY or HUF-as-used would be off by a factor of a hundred,
 * which is why Settings says what the field is for rather than offering a list
 * of every ISO code.
 *
 * No kuna: Croatia adopted the euro on 1 January 2023, so a Croatian balance is
 * a euro balance.
 */
class Currency private constructor(val code: String, val suffix: String) {

    /** True for the one currency that needs no conversion to be a total. */
    val isReporting: Boolean get() = code == PLN_CODE

    /**
     * The code, and only the code.
     *
     * Two currencies with the same code ARE the same currency whatever symbol
     * each was built with — the one the household typed in Settings, the one a
     * ledger row resolved before that preference had loaded — and the whole app
     * compares them (`option == accountCurrency`, "is this the account's own
     * unit") expecting the answer to be about the money, not about the glyph.
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Currency && other.code == code)

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        private const val PLN_CODE = "PLN"

        /**
         * The reporting currency, and the default for every account.
         *
         * [Money.CURRENCY] is the same string. It stays a separate constant
         * because most of the app is only ever about złoty and should not have
         * to name a currency to print a total.
         */
        val PLN = Currency(PLN_CODE, "zł")
        val EUR = Currency("EUR", "€")
        val USD = Currency("USD", "$")
        val GBP = Currency("GBP", "£")

        /**
         * The rest print their ISO code rather than a symbol, deliberately.
         *
         * The Scandinavian three all use "kr" — three accounts reading "1 240,00
         * kr" with no way to tell which is which is worse than three reading
         * "SEK", "NOK" and "DKK". CHF and CZK follow the same rule for
         * consistency rather than necessity: once some rows show a code, a row
         * showing a symbol looks like a different kind of thing.
         */
        val CHF = Currency("CHF", "CHF")
        val CZK = Currency("CZK", "CZK")
        val SEK = Currency("SEK", "SEK")
        val NOK = Currency("NOK", "NOK")
        val DKK = Currency("DKK", "DKK")

        /**
         * The currencies a rate is published for, in the order a picker lists
         * them.
         *
         * The same list as CURRENCIES in server/src/rates.ts, and the two have
         * to agree: a code in here that the server does not fetch is a currency
         * whose rows would silently never convert. The server no longer refuses
         * codes outside it — see isCurrencyCode in server/src/schema.ts — so
         * disagreement is no longer a rejected push, which is why it is worth
         * saying out loud here.
         */
        val KNOWN: List<Currency> = listOf(PLN, EUR, USD, GBP, CHF, CZK, SEK, NOK, DKK)

        /**
         * Codes a rate exists for, for the places that need to say so.
         */
        val RATED: Set<String> = KNOWN.map { it.code }.toSet()

        private val CODE = Regex("^[A-Z]{3}$")

        /**
         * The symbols the household typed in for currencies outside [KNOWN].
         *
         * A process-wide snapshot, written once at startup and on every change
         * — see [Currencies.publishSymbols] and MonyxApp. It exists because
         * [of] is called from every list row in the app, synchronously, and a
         * ledger row cannot wait on DataStore to find out what to print after
         * a figure. Nothing reads it to DECIDE anything: it only supplies a
         * glyph, and the fallback is the code itself, which is wrong only in
         * being terse.
         */
        @Volatile
        private var symbols: Map<String, String> = emptyMap()

        internal fun publishSymbols(defined: Map<String, String>) {
            symbols = defined
        }

        /**
         * The currency stored on an account or a row.
         *
         * Unset reads as złoty, which is what an account created before
         * currencies existed means. An unrecognised code reads as ITSELF — a
         * three-letter unit this build has no symbol for — and NOT as złoty,
         * which is the change that made custom currencies safe: a baht row
         * resolving to PLN would print "zł" after a baht figure and claim to be
         * the reporting currency, so the summary would count it as złoty. A code
         * nobody on this phone has named is still honestly a foreign unit with
         * no rate.
         */
        fun of(code: String?): Currency {
            if (code.isNullOrBlank()) return PLN
            KNOWN.firstOrNull { it.code == code }?.let { return it }
            return Currency(code, symbols[code] ?: code)
        }

        /**
         * One the household typed in: a code, and whatever they want printed
         * after a figure in it.
         *
         * A blank symbol falls back to the code rather than to nothing. A figure
         * with no unit after it on a ledger of mixed currencies is the one
         * outcome worse than a terse one.
         */
        fun custom(code: String, suffix: String): Currency =
            Currency(code, suffix.trim().ifBlank { code })

        /**
         * Whether [code] is the shape of a currency code: three capitals.
         *
         * The same rule the server applies, so a code accepted here cannot be
         * refused at sync time. It is a shape check and nothing more — ISO 4217
         * is not enumerated anywhere in this app, and "QQQ" is a currency as far
         * as both ends are concerned.
         */
        fun isValidCode(code: String): Boolean = CODE.matches(code)
    }
}
