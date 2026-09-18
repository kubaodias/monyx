package com.monyx.ui.overview

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chartStore by preferencesDataStore(name = "monyx_chart")

/**
 * How this phone looks at the twelve-month chart: which categories it keeps off
 * it, and whether it draws the budget line.
 *
 * Local and deliberately unsynced, which is the whole point: it is not a fact
 * about the household's money, it is how one person looks at it. Hiding the
 * rent to see what the rest of the year did is a lens, and pushing that lens
 * onto the other phone would change a chart somebody else was reading without
 * them touching anything.
 *
 * In DataStore rather than in Room for the same reason: nothing here belongs in
 * the sync protocol, and a preference that cannot be pushed cannot be pushed by
 * accident. It survives the app being killed, which is what makes it a setting
 * rather than a gesture — the alternative was rememberSaveable, which lasts
 * until the tab is left.
 *
 * Ids are kept even when the category they name is gone from the window, or
 * from the household. They cost nothing, and a category that is out of sight
 * for three months and comes back is one the phone should still be hiding.
 */
class ChartPreferences(private val context: Context) {

    private val key = stringSetPreferencesKey("history_hidden_categories")
    private val budgetKey = booleanPreferencesKey("history_budget_hidden")
    private val showsMonthKey = booleanPreferencesKey("breakdown_shows_month")

    val hidden: Flow<Set<String>> = context.chartStore.data.map { it[key] ?: emptySet() }

    /** The budget line is drawn unless it has been turned off here. */
    val budgetHidden: Flow<Boolean> = context.chartStore.data.map { it[budgetKey] ?: false }

    /**
     * Whether the breakdown card counts the selected month or a normal one.
     *
     * ONE answer for both faces. The pie and the twelve-month legend ask the
     * same question of the same categories, and a card whose two sides were set
     * differently would make turning it over change the subject silently.
     *
     * Remembered, for the same reason hiding is: somebody who reads this card
     * one way reads it that way every time, and being asked again on every
     * launch is the app forgetting what it was told.
     *
     * Defaults to the month, which is what the pie has always shown and what
     * the month switcher directly above the card is pointing at.
     */
    val showsMonth: Flow<Boolean> = context.chartStore.data.map { it[showsMonthKey] ?: true }

    /**
     * Set outright rather than flipped: the control is two segments, and
     * tapping the one already lit has to be a no-op. A toggle behind that would
     * turn "I want the average" into "give me the other one".
     */
    suspend fun setShowsMonth(value: Boolean) {
        context.chartStore.edit { it[showsMonthKey] = value }
    }

    suspend fun toggle(id: String) {
        context.chartStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (id in current) current - id else current + id
        }
    }

    suspend fun set(ids: Set<String>) {
        context.chartStore.edit { it[key] = ids }
    }

    suspend fun toggleBudget() {
        context.chartStore.edit { it[budgetKey] = !(it[budgetKey] ?: false) }
    }


}
