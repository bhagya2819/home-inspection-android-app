package com.homeinspection.app.core.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.homeinspection.app.HomeInspectionApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Decides *when* the outbox is drained; [SyncEngine] and the outbox table decide *what*.
 *
 * Uses the platform JobScheduler directly instead of WorkManager: the same OS service underneath,
 * without WorkManager's extra library and its own database (keeps the APK small). Jobs are
 * persisted across reboots and only run with a network connection (PRD §5.6, §8.3).
 */
class SyncScheduler(private val context: Context, private val engine: SyncEngine, private val scope: CoroutineScope) {
    private val jobs get() = context.getSystemService(JobScheduler::class.java)

    /** Sync now if possible (app in use, after edits); otherwise leaves a retry job behind. */
    fun kick() {
        scope.launch {
            engine.sync()
            scheduleRetry() // also covers an offline sign-in waiting for the network
        }
    }

    /** One-off job for queued work: runs once the network is back and the backoff has passed. */
    suspend fun scheduleRetry() {
        val due = engine.nextDueAt() ?: return
        if (jobs.getPendingJob(RETRY_JOB) != null) return // already waiting; don't stop a running one
        val delay = (due - System.currentTimeMillis()).coerceIn(5_000L, 6 * 60 * 60 * 1000L)
        jobs.schedule(
            JobInfo.Builder(RETRY_JOB, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
        )
    }

    /** Hourly pull of other inspectors' changes, and a safety net for anything left queued. */
    fun schedulePeriodic() {
        if (jobs.getPendingJob(PERIODIC_JOB) != null) return
        jobs.schedule(
            JobInfo.Builder(PERIODIC_JOB, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60 * 60 * 1000L, 15 * 60 * 1000L)
                .setPersisted(true)
                .build()
        )
    }

    fun cancelAll() {
        jobs.cancel(RETRY_JOB)
        jobs.cancel(PERIODIC_JOB)
    }

    companion object {
        const val RETRY_JOB = 1001
        const val PERIODIC_JOB = 1002
    }
}

class SyncJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as HomeInspectionApp
        running = app.appScope.launch {
            val outcome = app.container.syncEngine.sync()
            // Offline/failed: let JobScheduler back off and retry this job (only for the one-off job).
            val retry = params.jobId == SyncScheduler.RETRY_JOB && (outcome == SyncOutcome.OFFLINE || outcome == SyncOutcome.FAILED)
            jobFinished(params, retry)
            if (!retry && outcome == SyncOutcome.DONE) app.container.syncScheduler.scheduleRetry()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // The OS needs the resources back. Stopping mid-upload is safe: the outbox row stays.
        running?.cancel()
        return true
    }
}
