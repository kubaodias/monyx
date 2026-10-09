package com.monyx.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.currencyStore by preferencesDataStore(name = "monyx_currencies")

/**
 * Which of the nine currencies this phone bothers to offer.
 *
 * [Currency] is the list the app CAN handle and the server agrees with; this is
 * the list a household wants to be asked about. They are not the same question.
 * A Polish family with a euro card and a dollar subscription met a picker with
 * nine entries in it every time they changed what an amount was in — six of them
 * Scandinavian — and the one they wanted was never the one under the thumb.
 *
 * Local and unsynced, like [com.monyx.ui.overview.ChartPreferences] and for the
 * same reason: it is not a fact about the household's money, it is how one phone
 * is set up. Nothing here can be pushed, so nothing here can be pushed by
 * accident — and a phone that is shown fewer currencies is not a phone that
 * cannot READ a row in one of them. Hiding changes what is offered, never what
 * is stored or shown.
 */
class CurrencyPreferences(private val context: Context) {

    private val key = stringSetPreferencesKey("shown_currencies")

    /**
     * The codes the household has said yes to, or null when it has never said
     * anything — which reads as [Currencies.DEFAULT_SHOWN] rather than as "none".
     *
     * Null and empty are genuinely different here: empty is somebody switching
     * the last one off, and that has to stick rather than falling back to the
     * default the way an absent key does.
     */
    val shown: Flow<Set<String>?> = context.currencyStore.data.map { it[key] }

    suspend fun setShown(code: String, shown: Boolean) {
        context.currencyStore.edit { prefs ->
            val current = prefs[key] ?: Currencies.DEFAULT_SHOWN
            prefs[key] = if (shown) current + code else current - code
        }
    }
}

/** The rule behind [CurrencyPreferences], kept pure so it can be tested. */
object Currencies {

    /**
     * What a phone offers before anybody has chosen: the euro and the dollar.
     *
     * Not every currency, and not none. These two are what a Polish household
     * actually meets — a holiday, a subscription, a transfer from abroad — and
     * the other six are each one tap away in Settings on the day they stop being
     * hypothetical. Złoty is deliberately absent from this set: it is the
     * reporting currency and is never a choice, so putting it here would make it
     * look like one that could be withdrawn.
     */
    val DEFAULT_SHOWN: Set<String> = setOf(Currency.EUR.code, Currency.USD.code)

    /**
     * The currencies a picker should list, in [Currency]'s own order.
     *
     * Three things are always in it, whatever [shown] says:
     *
     *  - **złoty**, because every total is in it and every account defaults to
     *    it. A picker without it would be a picker you cannot get back out of.
     *  - **anything an account already holds.** Hiding a currency is about not
     *    being asked about it; an account denominated in it is a fact that is
     *    already true, and a form that would not offer it could not edit that
     *    account without silently changing what its money is.
     *  - **whatever is currently chosen**, which the caller passes in [inUse]
     *    for the same reason: a row entered in a currency that was later hidden
     *    still opens for editing, and the dialog has to be able to show its own
     *    answer.
     */
    fun offered(shown: Set<String>?, inUse: Set<String> = emptySet()): List<Currency> {
        val keep = shown ?: DEFAULT_SHOWN
        return Currency.entries.filter { it.isReporting || it.code in keep || it.code in inUse }
    }

    /**
     * Whether Settings must refuse to hide one: złoty, and anything an account
     * holds. The switch is disabled rather than missing, with the reason beside
     * it — a row that quietly cannot be changed reads as a broken switch.
     */
    fun locked(currency: Currency, inUse: Set<String>): Boolean =
        currency.isReporting || currency.code in inUse
}
