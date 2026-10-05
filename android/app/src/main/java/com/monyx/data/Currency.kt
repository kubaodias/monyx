package com.monyx.data

/**
 * What an account's money is denominated in.
 *
 * The REPORTING currency is złoty and is not configurable — every total the app
 * prints is in złoty. This says what the rows of one account are counted in.
 * See docs/decisions/0022, and rates.ts on the server, which carries the same
 * list: the two have to agree or a push is refused for a currency the picker
 * offered.
 *
 * Every one of these is a two-decimal currency in ISO 4217, which is what lets
 * [AccountEntity.initialBalanceMinor] and every amount stay "hundredths of the
 * unit" with no per-currency minor-unit handling. **A currency does not go on
 * this list without checking that**, or every amount in it is off by a factor
 * of a hundred. JPY and HUF-as-used are the obvious traps.
 *
 * No kuna: Croatia adopted the euro on 1 January 2023, so a Croatian balance is
 * a euro balance.
 */
enum class Currency(val code: String, val suffix: String) {
    /**
     * The reporting currency, and the default for every account.
     *
     * [Money.CURRENCY] is the same string. It stays a separate constant because
     * most of the app is only ever about złoty and should not have to name a
     * currency to print a total.
     */
    PLN("PLN", "zł"),
    EUR("EUR", "€"),
    USD("USD", "$"),
    GBP("GBP", "£"),

    /**
     * The rest print their ISO code rather than a symbol, deliberately.
     *
     * The Scandinavian three all use "kr" — three accounts reading "1 240,00
     * kr" with no way to tell which is which is worse than three reading "SEK",
     * "NOK" and "DKK". CHF and CZK follow the same rule for consistency rather
     * than necessity: once some rows show a code, a row showing a symbol looks
     * like a different kind of thing.
     */
    CHF("CHF", "CHF"),
    CZK("CZK", "CZK"),
    SEK("SEK", "SEK"),
    NOK("NOK", "NOK"),
    DKK("DKK", "DKK"),
    ;

    /** True for the one currency that needs no conversion to be a total. */
    val isReporting: Boolean get() = this == PLN

    companion object {
        /**
         * The currency stored on an account, or [PLN] when it is unset or
         * unrecognised.
         *
         * Unrecognised reads as złoty rather than throwing: a row could arrive
         * from a newer client that offers a currency this build does not, and a
         * crash on open is a worse answer than a figure in the wrong unit on
         * one row. The server refuses currencies IT does not know, so the set
         * can only ever be ahead on a phone, never invented.
         */
        fun of(code: String?): Currency =
            entries.firstOrNull { it.code == code } ?: PLN
    }
}
