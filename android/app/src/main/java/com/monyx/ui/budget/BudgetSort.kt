package com.monyx.ui.budget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monyx.R
import com.monyx.data.BudgetUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.budgetStore by preferencesDataStore(name = "monyx_budget")

/**
 * How the budget list is ordered.
 *
 * [Own] is the household's own order — the one the categories are dragged into
 * in Settings, which is what every other list in the app follows and is
 * therefore the only possible default. The other three are questions you ask the
 * budget rather than ways to arrange it, and each one puts its answer in the
 * first row:
 *
 *  - [Limit]: where the money was promised.
 *  - [Spent]: where it actually went.
 *  - [Progress]: what is about to run out, which is the one a household with
 *    fourteen limits opens this screen for.
 *
 * The labels are resources rather than words, because the menu names the
 * question, not the field.
 */
enum class BudgetSort(val stored: String, val labelRes: Int) {
    Own("own", R.string.budget_sort_own),
    Progress("progress", R.string.budget_sort_progress),
    Spent("spent", R.string.budget_sort_spent),
    Limit("limit", R.string.budget_sort_limit),
    ;

    companion object {
        /** Anything unrecognised reads as [Own] — including a value a newer
         *  build wrote and this one has never heard of. */
        fun of(stored: String?): BudgetSort =
            entries.firstOrNull { it.stored == stored } ?: Own
    }
}

/**
 * The rows in the chosen order.
 *
 * Every sort but [BudgetSort.Own] is stable on top of the order the query
 * returned, which IS the household's own — so two categories with the same
 * spend stay in the order they always sit in rather than swapping places
 * between frames.
 *
 * [BudgetSort.Progress] sorts on the fraction of the limit that has gone, not on
 * what is left: 180 of 200 and 1 800 of 2 000 are the same answer to "what is
 * about to run out", and ordering by the money left over would put the second
 * one ten rows down. A limit of nothing is a real limit — "do not spend here" —
 * so spending against it is infinitely over and belongs at the very top; an
 * untouched one has nothing to report and sorts as zero, which is where a
 * category nobody has spent in belongs.
 */
fun sortedBudgets(rows: List<BudgetUsage>, sort: BudgetSort): List<BudgetUsage> = when (sort) {
    BudgetSort.Own -> rows
    BudgetSort.Limit -> rows.sortedByDescending { it.limitMinor }
    BudgetSort.Spent -> rows.sortedByDescending { it.spentMinor }
    BudgetSort.Progress -> rows.sortedByDescending(::progressOf)
}

private fun progressOf(usage: BudgetUsage): Double = when {
    usage.limitMinor > 0L -> usage.spentMinor.toDouble() / usage.limitMinor
    usage.spentMinor > 0L -> Double.MAX_VALUE
    else -> 0.0
}

/**
 * How this phone looks at the budget list.
 *
 * Local and unsynced, for the reason [com.monyx.ui.overview.ChartPreferences]
 * spells out: an ordering is a lens, not a fact about the household's money, and
 * pushing it would rearrange a screen somebody else is reading without them
 * touching anything. In DataStore rather than rememberSaveable because somebody
 * who reads this list by what is running out reads it that way every time.
 */
class BudgetPreferences(private val context: Context) {

    private val key = stringPreferencesKey("budget_sort")

    val sort: Flow<BudgetSort> = context.budgetStore.data.map { BudgetSort.of(it[key]) }

    suspend fun setSort(sort: BudgetSort) {
        context.budgetStore.edit { it[key] = sort.stored }
    }
}
