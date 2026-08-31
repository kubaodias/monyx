package com.monyx.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.monyx.data.MonyxDatabase
import java.util.concurrent.TimeUnit

/**
 * Nothing here is user-waited; the UI already returned.
 *
 * Expedited work is the wrong tool: below API 31 it runs as a foreground
 * service, which crashes unless getForegroundInfo() is overridden and otherwise
 * shows the family a notification every time someone adds an expense — hostile
 * to everything the design is for.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = MonyxDatabase.get(applicationContext)
        val session = Session(applicationContext)
        if (!session.isEnrolled()) return Result.success()

        // WorkManager handles retry with backoff; we do not write our own.
        return SyncEngine(db, session).sync().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val UNIQUE_ONE_SHOT = "sync"
        private const val UNIQUE_PERIODIC = "sync-periodic"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * After a local write and on app open. The ~15 s delay is a debounce —
         * five expenses entered in a row coalesce into one push.
         *
         * A force-stopped app runs no WorkManager jobs at all until someone
         * launches it, and on several OEMs swiping from recents IS a force stop,
         * which makes sync-on-open the load-bearing trigger.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONE_SHOT, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * What "Sync now" and a pull-to-refresh mean: no debounce, and REPLACE
         * rather than KEEP.
         *
         * KEEP is right for enqueue(), where the point is to coalesce. It is
         * wrong here: a debounced push already sitting in the queue would make
         * an explicit tap do nothing at all for fifteen seconds, which reads as
         * a broken button.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Called the moment enrolment succeeds, not only on the next cold start.
         *
         * Without this a newly joined phone schedules no work at all until the
         * app is killed and reopened: the first thing a new family member sees
         * is an empty app, and nothing on screen suggests restarting.
         */
        fun onEnrolled(context: Context) {
            syncNow(context)
            schedulePeriodic(context)
        }

        /**
         * Hourly, as a bonus on top of sync-on-open.
         *
         * UPDATE, not KEEP: KEEP on periodic work means a later change to the
         * interval or constraints never reaches an installed app.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
