package com.monyx.ui.overview

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chartStore by preferencesDataStore(name = "monyx_chart")

/**
 * Which categories this phone keeps out of the twelve-month chart.
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
class HiddenCategories(private val context: Context) {

    private val key = stringSetPreferencesKey("history_hidden_categories")

    val flow: Flow<Set<String>> = context.chartStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(id: String) {
        context.chartStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (id in current) current - id else current + id
        }
    }

    suspend fun set(ids: Set<String>) {
        context.chartStore.edit { it[key] = ids }
    }
}
