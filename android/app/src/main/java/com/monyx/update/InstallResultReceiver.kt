package com.monyx.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.monyx.MonyxApp

/**
 * Where a PackageInstaller session reports. Not exported: only the system,
 * through the PendingIntent the session was committed with, ever sends it.
 *
 * PENDING_USER_ACTION is the normal first answer for an app that is not the
 * installer of record — it carries the system's confirmation screen, which has
 * to be started from here. The app is in the foreground at that moment (the
 * dialog is what started the install), which is what lets a receiver start an
 * activity at all on Android 10 and later.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) {
                runCatching { context.startActivity(confirm) }
                    .onFailure { (context.applicationContext as MonyxApp).updater.onInstallResult(PackageInstaller.STATUS_FAILURE) }
            }
            return
        }
        (context.applicationContext as MonyxApp).updater.onInstallResult(status)
    }
}
