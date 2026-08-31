package com.monio

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.monio.sync.SyncWorker
import com.monio.ui.MonioAppRoot
import com.monio.ui.theme.MonioTheme

/**
 * One Activity. A home screen shortcut points here with an extra and branches
 * the start destination; launchMode is singleTop with onNewIntent. Do not add a
 * second Activity.
 *
 * AppCompatActivity rather than ComponentActivity for one reason: below API 33
 * there is no framework LocaleManager, and AppCompat's back-port applies the
 * chosen language by wrapping the Activity's base context. A plain
 * ComponentActivity would store the preference and then ignore it.
 */
class MainActivity : AppCompatActivity() {

    private var pendingDeepLink by mutableStateOf<DeepLink?>(null)
    private var startDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash on Android 12+ is not optional and cannot be
        // removed. What must be avoided is a CUSTOM splash Activity and any
        // setKeepOnScreenCondition.
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)

        val app = application as MonioApp

        setContent {
            MonioTheme {
                val enrolled by produceState<Boolean?>(initialValue = null) {
                    value = app.session.isEnrolled()
                }
                val memberId by produceState<String?>(initialValue = null) {
                    value = app.session.memberId()
                }

                // Enqueue sync from a LaunchedEffect AFTER the first frame, never
                // from Application.onCreate — everything before the first frame is
                // what actually threatens the five-second target.
                LaunchedEffect(enrolled) {
                    if (enrolled == true) {
                        SyncWorker.enqueue(this@MainActivity)
                        SyncWorker.schedulePeriodic(this@MainActivity)
                    }
                }

                MonioAppRoot(
                    app = app,
                    enrolled = enrolled,
                    memberId = memberId,
                    startDestination = startDestination,
                    deepLink = pendingDeepLink,
                    onDeepLinkHandled = { pendingDeepLink = null },
                )
            }
        }
    }

    /**
     * With a notification block present, onMessageReceived is NOT called while
     * the app is backgrounded — the data arrives as extras on the launching
     * Activity's intent. So the deep link is read here, never in the messaging
     * service.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        when (extras.getString("type")) {
            "budget_alert" -> {
                val categoryId = extras.getString("category_id")
                val period = extras.getString("period")
                if (categoryId != null && period != null) {
                    pendingDeepLink = DeepLink.Budget(categoryId, period)
                }
            }
        }
        when (extras.getString("start")) {
            "add" -> startDestination = Destinations.ADD
        }
    }
}

sealed interface DeepLink {
    data class Budget(val categoryId: String, val period: String) : DeepLink
}

object Destinations {
    const val OVERVIEW = "overview"
    const val ADD = "add"
    const val TRANSACTIONS = "transactions"
    const val BUDGET = "budget"
    const val SETTINGS = "settings"
}
