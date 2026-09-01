package com.monyx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.monyx.data.MonyxDatabase
import com.monyx.data.MonyxRepository
import com.monyx.sync.Session
import com.monyx.ui.SelectedMonth

class MonyxApp : Application() {

    val database by lazy { MonyxDatabase.get(this) }
    val repository by lazy { MonyxRepository(database.dao()) }
    val session by lazy { Session(this) }

    /**
     * Held here because it has to outlive every ViewModel that reads it. Three
     * tabs show one month between them, and the tab you left is destroyed while
     * you are on the one you went to.
     */
    val selectedMonth by lazy { SelectedMonth() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // NOTE: sync is deliberately NOT enqueued here. Application.onCreate and
        // everything before it is what actually threatens the five-second target
        // — WorkManager initialises through an androidx.startup
        // ContentProvider that runs first and opens its own Room database.
        // Enqueue from a LaunchedEffect after the first frame instead.
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
