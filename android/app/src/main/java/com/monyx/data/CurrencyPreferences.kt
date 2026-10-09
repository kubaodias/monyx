package com.monyx.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.currencyStore by preferencesDataStore(name = "monyx_currencies")

/**
 * Which currencies this phone deals in.
 *
 * [Currency] is the list the app can CONVERT; this is the list a household
 * actually meets. They are not the same question. A Polish family with a euro
 * card met a picker with nine entries in it every time they changed what an
 * amount was in — six of them Scandinavian — and the one they wanted was never
 * the one under the thumb. It is now złoty, the euro, and whatever they have
 * added since.
 *
 * Anything can be added, not only the nine with rates: a code and the text to
 * print after a figure, typed in Settings. A currency with no NBP rate is a
 * real currency with a real balance that simply does not convert, which is a
 * state every total in the app already knows how to show — see [Currency].
 *
 * Local and unsynced, like [com.monyx.ui.overview.ChartPreferences] and for the
 * same reason: it is not a fact about the household's money, it is how one phone
 * is set up. Nothing here can be pushed, so nothing here can be pushed by
 * accident — and a phone that is shown fewer currencies is not a phone that
 * cannot READ a row in one of them. This changes what is OFFERED, never what is
 * stored or shown. The cost is that a custom currency added on one phone prints
 * its code on the other until it is added there too, which is the honest
 * consequence of the setting being local.
 */
class CurrencyPreferences(private val context: Context) {

    /**
     * Still the key the switch list wrote in 0.26.0, and still readable.
     *
     * An entry is a code — "EUR" — or a code and a symbol — "THB=฿". The old
     * format is the first of those two, so a phone that had already hidden or
     * shown something keeps its answer; see [Currencies.parse].
     */
    private val key = stringSetPreferencesKey("shown_currencies")

    /**
     * What the household has chosen, or null when it has never chosen anything
     * — which reads as [Currencies.DEFAULT] rather than as "none".
     *
     * Null and empty are genuinely different here: empty is somebody removing
     * the last one, and that has to stick rather than falling back to the
     * default the way an absent key does. Złoty is in neither — it is the
     * reporting currency and is never a choice, so storing it would make it look
     * like one that could be withdrawn.
     */
    val chosen: Flow<Set<String>?> = context.currencyStore.data.map { it[key] }

    /** Adds one, replacing any entry with the same code. */
    suspend fun add(code: String, suffix: String) {
        val entry = Currencies.entry(code, suffix)
        context.currencyStore.edit { prefs ->
            val current = prefs[key] ?: Currencies.DEFAULT
            prefs[key] = current.filterNot { Currencies.codeOf(it) == code }.toSet() + entry
        }
    }

    suspend fun remove(code: String) {
        context.currencyStore.edit { prefs ->
            val current = prefs[key] ?: Currencies.DEFAULT
            prefs[key] = current.filterNot { Currencies.codeOf(it) == code }.toSet()
        }
    }
}

/** The rules behind [CurrencyPreferences], kept pure so they can be tested. */
object Currencies {

    /**
     * What a phone offers before anybody has chosen: the euro.
     *
     * Plus złoty, which is not in here because it is never a choice. Two
     * currencies is what a Polish household meets without thinking about it,
     * and everything else — the dollar, the krona, the baht — is one trip to
     * Settings away on the day it stops being hypothetical. The dollar used to
     * be in this set and was removed for the same reason the other six were: a
     * currency nobody has spent is one more row between a thumb and the one they
     * came for.
     */
    val DEFAULT: Set<String> = setOf(Currency.EUR.code)

    /** The code half of a stored entry: "THB=฿" → "THB". */
    fun codeOf(entry: String): String = entry.substringBefore('=').trim().uppercase()

    /**
     * How one is written down. A known currency never carries a symbol: its own
     * is canonical, and storing "EUR=E" would mean one phone printing a glyph
     * for the euro that no other screen in the app agrees with.
     */
    fun entry(code: String, suffix: String): String {
        val clean = code.trim().uppercase()
        val symbol = suffix.trim()
        return if (clean in Currency.RATED || symbol.isEmpty()) clean else "$clean=$symbol"
    }

    /**
     * The household's own list, in the order a picker should show it: the ones
     * with rates first, in [Currency.KNOWN]'s order, then everything added by
     * hand, alphabetically.
     *
     * Entries that are not three capitals are dropped rather than shown. The
     * only way one gets in is a build that wrote a different format, and a
     * picker is not the place to find out about it.
     */
    fun parse(entries: Set<String>?): List<Currency> =
        ordered((entries ?: DEFAULT).mapNotNull(::oneOf))

    private fun oneOf(entry: String): Currency? {
        val code = codeOf(entry)
        if (!Currency.isValidCode(code)) return null
        if (code in Currency.RATED) return Currency.of(code)
        return Currency.custom(code, entry.substringAfter('=', "").trim())
    }

    private fun ordered(all: Collection<Currency>): List<Currency> =
        all.distinctBy { it.code }.sortedWith(
            compareBy(
                { currency -> Currency.KNOWN.indexOfFirst { it.code == currency.code }.takeIf { i -> i >= 0 } ?: Int.MAX_VALUE },
                { it.code },
            ),
        )

    /**
     * The currencies a picker should list.
     *
     * Three things are always in it, whatever the household chose:
     *
     *  - **złoty**, because every total is in it and every account defaults to
     *    it. A picker without it would be a picker you cannot get back out of.
     *  - **anything an account already holds.** Removing a currency is about not
     *    being asked about it; an account denominated in it is a fact that is
     *    already true, and a form that would not offer it could not edit that
     *    account without silently changing what its money is.
     *  - **whatever is currently chosen**, which the caller passes in [inUse]
     *    for the same reason: a row entered in a currency that was later removed
     *    still opens for editing, and the dialog has to be able to show its own
     *    answer.
     */
    fun offered(entries: Set<String>?, inUse: Set<String> = emptySet()): List<Currency> {
        val byCode = LinkedHashMap<String, Currency>()
        byCode[Currency.PLN.code] = Currency.PLN
        parse(entries).forEach { byCode.getOrPut(it.code) { it } }
        inUse.filter { Currency.isValidCode(it) }.forEach { code ->
            byCode.getOrPut(code) { Currency.of(code) }
        }
        return ordered(byCode.values)
    }

    /**
     * Whether Settings must refuse to remove one: złoty, and anything an account
     * holds. The row simply has no remove button — it used to carry a line of
     * explanation instead, which was two of the three texts on the screen and
     * said what a household already knows about its own accounts.
     */
    fun locked(currency: Currency, inUse: Set<String>): Boolean =
        currency.isReporting || currency.code in inUse

    /** Code → symbol for the added ones, which is all [Currency.of] needs. */
    fun symbols(entries: Set<String>?): Map<String, String> =
        parse(entries).filterNot { it.code in Currency.RATED }.associate { it.code to it.suffix }

    /**
     * Hands the symbols to [Currency], which every ledger row resolves through.
     *
     * Called from MonyxApp, once per change, for the life of the process. A
     * composable reading the preference is not enough: the symbol is needed by
     * rows that are on screen before any picker has been opened.
     */
    fun publishSymbols(entries: Set<String>?) {
        Currency.publishSymbols(symbols(entries))
    }
}
