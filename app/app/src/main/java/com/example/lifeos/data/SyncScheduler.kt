package com.example.lifeos.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

private const val IMMEDIATE_WORK_NAME = "lifeos-sync"
private const val PERIODIC_WORK_NAME = "lifeos-sync-periodic"

/**
 * Schedules SyncWorker runs. Never a resident/foreground service (some OEM battery
 * managers kill those) -- WorkManager bursts only: app-foreground, after each local edit,
 * and periodically.
 */
object SyncScheduler {
    private fun connectedConstraints() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Foreground launch + after-every-local-edit trigger. KEEP dedups bursts that fire
     * faster than one sync cycle completes (e.g. several quick edits in a row). */
    fun scheduleImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Idempotent -- safe to call on every app launch; KEEP means it's a no-op once
     * already scheduled. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
