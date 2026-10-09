package com.monyx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.monyx.data.Currencies
import com.monyx.data.CurrencyPreferences
import com.monyx.data.MonyxDatabase
import com.monyx.data.MonyxRepository
import com.monyx.sync.Session
import com.monyx.ui.SelectedMonth
import com.monyx.ui.overview.ChartPreferences
import com.monyx.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MonyxApp : Application() {

    val database by lazy { MonyxDatabase.get(this) }
    val repository by lazy { MonyxRepository(database.dao()) }
    val session by lazy { Session(this) }
    val updater by lazy { Updater(this, session) }

    /** This phone's own view of the twelve-month chart. Never synced. */
    val chartPreferences by lazy { ChartPreferences(this) }

    /** Which currencies this phone offers to pick from. Never synced. */
    val currencyPreferences by lazy { CurrencyPreferences(this) }

    /**
     * Held here because it has to outlive every ViewModel that reads it. Three
     * tabs show one month between them, and the tab you left is destroyed while
     * you are on the one you went to.
     */
    val selectedMonth by lazy { SelectedMonth() }

    /**
     * For the one thing that has to be collected for as long as the process
     * lives, and belongs to no screen: see [publishCurrencySymbols]. Anything
     * with a ViewModel to live in belongs in that ViewModel's scope instead.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        publishCurrencySymbols()
        // NOTE: sync is deliberately NOT enqueued here. Application.onCreate and
        // everything before it is what actually threatens the five-second target
        // — WorkManager initialises through an androidx.startup
        // ContentProvider that runs first and opens its own Room database.
        // Enqueue from a LaunchedEffect after the first frame instead.
    }

    /**
     * Keeps [com.monyx.data.Currency] able to print a currency this household
     * typed in itself.
     *
     * A ledger row resolves its unit synchronously, from a list item, long
     * before anybody opens a picker — so the symbols for the added currencies
     * have to be in memory rather than behind a Flow a composable happens to be
     * collecting. One collector for the life of the process, started here.
     *
     * Cheap in the way the note below cares about: launching a coroutine costs
     * nothing on the main thread and DataStore reads the file on its own
     * dispatcher. Until it answers, an added currency prints its code — the same
     * thing it prints on a phone that has never been told about it.
     */
    private fun publishCurrencySymbols() {
        applicationScope.launch {
            currencyPreferences.chosen.collect(Currencies::publishSymbols)
        }
    }

    /**
     * A notification channel's importance is immutable after creation. It can
     * only be lowered, by the user, in system settings. Set it correctly on the
     * first install or every family member fixes it by hand.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            BUDGET_CHANNEL_ID,
            getString(R.string.channel_budget_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_budget_alerts_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val BUDGET_CHANNEL_ID = "budget_alerts"
    }
}
