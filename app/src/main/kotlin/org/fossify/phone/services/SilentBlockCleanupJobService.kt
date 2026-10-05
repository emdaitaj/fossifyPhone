package org.fossify.phone.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CallLog
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.helpers.SilentBlockCallLogCleaner

/**
 * Removes silently blocked calls from the system call log whenever the call log changes. Telecom writes those rows
 * asynchronously and the app process might be gone by then, so a content triggered job is used.
 */
class SilentBlockCleanupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        ensureBackgroundThread {
            val isCleanupPending = try {
                SilentBlockCallLogCleaner(appContext).cleanCallLog()
            } catch (_: Exception) {
                true
            }

            jobFinished(params, false)
            if (isCleanupPending) {
                schedule(appContext)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 807926910
        private const val TRIGGER_UPDATE_DELAY_MS = 500L
        private const val TRIGGER_MAX_DELAY_MS = 5000L

        fun schedule(context: Context) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val trigger = JobInfo.TriggerContentUri(
                CallLog.Calls.CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            )

            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, SilentBlockCleanupJobService::class.java))
                .addTriggerContentUri(trigger)
                .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_MS)
                .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_MS)
                .build()

            try {
                jobScheduler.schedule(jobInfo)
            } catch (_: Exception) {
                // the in-process sweeps and the cleanup on opening the call history still apply
            }
        }
    }
}
