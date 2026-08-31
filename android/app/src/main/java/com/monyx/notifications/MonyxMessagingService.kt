package com.monyx.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.monyx.MainActivity
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.sync.Session
import com.monyx.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MonyxMessagingService : FirebaseMessagingService() {

    /**
     * The FCM token rides along as an optional field on /sync/push. A
     * dedicated endpoint would be a fire-and-forget call at app start that fails
     * whenever the phone happens to be offline at launch, with no retry and no
     * state to retry from. Attaching it to a request that already retries
     * deletes an endpoint, its error handling, and a failure mode.
     */
    override fun onNewToken(token: String) {
        val session = Session(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            session.saveFcmToken(token)
            SyncWorker.enqueue(applicationContext)
        }
    }

    /**
     * There are two render paths, not one.
     *
     * Backgrounded or killed, the system tray resolves the loc keys itself. In
     * the FOREGROUND this fires and the keys are NOT auto-resolved — so the
     * handler reads them and posts the notification itself, or the family sees
     * nothing.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        val bodyKey = notification?.bodyLocalizationKey
        val bodyArgs = notification?.bodyLocalizationArgs

        val bodyRes = LocKeys.body(bodyKey)
        val body = when {
            bodyRes != null && bodyArgs != null -> getString(bodyRes, *bodyArgs)
            bodyRes != null -> getString(bodyRes)
            !notification?.body.isNullOrEmpty() -> notification.body!!
            else -> return
        }
        val titleRes = LocKeys.title(notification?.titleLocalizationKey)
        val title = titleRes?.let { getString(it) }
            ?: notification?.title
            ?: getString(R.string.app_name)

        postNotification(title, body, message.data)
    }

    private fun postNotification(title: String, body: String, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Without the runtime permission every push is dropped silently
            // anyway; posting would throw. Nothing to do but return.
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        // FLAG_IMMUTABLE is required on API 31+; omitting it throws.
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(this, MonyxApp.BUDGET_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val id = (data["category_id"].orEmpty() + data["period"].orEmpty()).hashCode()
        getSystemService(NotificationManager::class.java).notify(id, built)
    }
}
