package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.CallLog.Calls
import org.fossify.commons.extensions.getIntValueOrNull
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValueOrNull
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.commons.helpers.getQuestionMarks
import org.fossify.phone.models.SilentBlockedCall
import org.fossify.phone.services.SilentBlockCleanupJobService

/**
 * Telecom always writes blocked calls into the system call log, even if the screening service asks it not to.
 * This class removes those rows, so silently blocked calls only show up in the secret history.
 */
class SilentBlockCallLogCleaner(context: Context) {
    private val appContext = context.applicationContext
    private val historyRepository = SilentBlockHistoryRepository(appContext)
    private val matcher = SilentBlockNumberMatcher(appContext)

    /**
     * Deletes the system call log rows of recently silently blocked calls. Call it from a background thread.
     *
     * @return true if some calls might still get logged later, so another cleanup should be scheduled
     */
    fun cleanCallLog(): Boolean {
        val now = System.currentTimeMillis()
        historyRepository.purgeRemovedCalls(
            oldestTimestamp = now - MAX_PENDING_AGE_MS,
            sweepFromTimestamp = now - SWEEP_WINDOW_MS
        )

        val pendingCalls = historyRepository.getCallsPendingCleanup(
            oldestTimestamp = now - MAX_PENDING_AGE_MS,
            sweepFromTimestamp = now - SWEEP_WINDOW_MS
        )

        if (pendingCalls.isEmpty()) {
            return false
        }

        val canAccessCallLog = appContext.hasPermission(PERMISSION_READ_CALL_LOG)
            && appContext.hasPermission(PERMISSION_WRITE_CALL_LOG)
        if (!canAccessCallLog) {
            return true
        }

        val cleanedIds = pendingCalls.filter { deleteCallLogRows(it) > 0 }.map { it.id }.toSet()
        historyRepository.markCallLogCleaned(cleanedIds)

        // a call is considered cleaned once a row was removed, but recent calls keep being swept for a while,
        // because some systems write a second row when the caller finally gives up
        return pendingCalls.any { call ->
            val isCleaned = call.isCallLogCleaned || call.id in cleanedIds
            !isCleaned || call.timestamp >= now - SWEEP_WINDOW_MS
        }
    }

    /**
     * Returns a filter telling whether a system call log row belongs to a silently blocked call. Used for keeping such
     * rows out of the app's call history, in case the cleanup couldn't remove them yet.
     */
    fun createCallLogFilter(): (number: String, isHiddenNumber: Boolean, type: Int, timestamp: Long) -> Boolean {
        val calls = try {
            // calls removed from the history still count, until their call log rows are gone
            historyRepository.getCalls(includeRemoved = true).sortedBy { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }

        if (calls.isEmpty()) {
            return { _, _, _, _ -> false }
        }

        val timestamps = calls.map { it.timestamp }.toLongArray()
        return { number, isHiddenNumber, type, timestamp ->
            var index = findFirstIndexAtOrAfter(timestamps, timestamp - CALL_LOG_TIME_TOLERANCE_MS)
            var isBlocked = false
            while (type in BLOCKED_CALL_TYPES && !isBlocked && index < calls.size) {
                if (timestamps[index] > timestamp + CALL_LOG_TIME_TOLERANCE_MS) {
                    break
                }

                val call = calls[index]
                isBlocked = matcher.isSameCaller(call.number, call.isHiddenNumber, number, isHiddenNumber)
                index++
            }
            isBlocked
        }
    }

    // unlike binarySearch, this always finds the first of several equal timestamps
    private fun findFirstIndexAtOrAfter(sortedTimestamps: LongArray, timestamp: Long): Int {
        var low = 0
        var high = sortedTimestamps.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sortedTimestamps[middle] < timestamp) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    // the caller checks both call log permissions, they are granted to the default dialer
    @SuppressLint("MissingPermission")
    private fun deleteCallLogRows(call: SilentBlockedCall): Int {
        return try {
            val ids = findCallLogRows(call)
            if (ids.isEmpty()) {
                0
            } else {
                val selection = "${Calls._ID} IN (${getQuestionMarks(ids.size)})"
                appContext.contentResolver.delete(Calls.CONTENT_URI, selection, ids.toTypedArray())
            }
        } catch (_: Exception) {
            0
        }
    }

    @SuppressLint("MissingPermission")
    private fun findCallLogRows(call: SilentBlockedCall): List<String> {
        // only the row types Telecom writes for a call that never got answered, answered and outgoing calls stay
        val typePlaceholders = getQuestionMarks(BLOCKED_CALL_TYPES.size)
        val selection = "${Calls.DATE} BETWEEN ? AND ? AND ${Calls.TYPE} IN ($typePlaceholders)"
        val timeRange = listOf(call.timestamp - CALL_LOG_TIME_TOLERANCE_MS, call.timestamp + CALL_LOG_TIME_TOLERANCE_MS)
        val selectionArgs = (timeRange + BLOCKED_CALL_TYPES.map { it.toLong() }).map { it.toString() }.toTypedArray()
        val projection = arrayOf(Calls._ID, Calls.NUMBER, Calls.NUMBER_PRESENTATION)

        val ids = ArrayList<String>()
        appContext.contentResolver.query(Calls.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getStringValueOrNull(Calls.NUMBER).orEmpty()
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val isHiddenNumber = SilentBlockNumberMatcher.isHiddenPresentation(presentation)
                    || SilentBlockNumberMatcher.isHiddenNumber(number)
                if (matcher.isSameCaller(call.number, call.isHiddenNumber, number, isHiddenNumber)) {
                    ids.add(cursor.getLongValue(Calls._ID).toString())
                }
            }
        }
        return ids
    }

    companion object {
        /**
         * Telecom logs a call with its creation time, the very same value the history stores. The small tolerance
         * only covers systems that log a slightly different time, a legitimate call can't happen that close.
         */
        private const val CALL_LOG_TIME_TOLERANCE_MS = 5000L

        /** Blocked when the screening verdict was respected, missed or rejected on systems that ignore it. */
        private val BLOCKED_CALL_TYPES = listOf(Calls.BLOCKED_TYPE, Calls.MISSED_TYPE, Calls.REJECTED_TYPE)

        /** Calls are swept repeatedly for this long, a silently blocked call can keep ringing for minutes. */
        private const val SWEEP_WINDOW_MS = 15 * 60 * 1000L

        /**
         * Give up on calls that never showed up in the call log after this time. A silently blocked call ends within
         * minutes, and some systems never log it at all, the history keeps the call regardless.
         */
        private const val MAX_PENDING_AGE_MS = 60 * 60 * 1000L

        /** Quick in-process sweeps right after a call was blocked, the job below covers the process being killed. */
        private val IN_PROCESS_SWEEP_DELAYS_MS = longArrayOf(1500L, 5000L, 15000L, 45000L, 120000L, 300000L)

        private val SWEEP_TOKEN = Any()
        private val MISSED_CALL_SWEEP_TOKEN = Any()

        private val cleanupHandler: Handler by lazy {
            val thread = HandlerThread("SilentBlockCallLogCleaner").apply { start() }
            Handler(thread.looper)
        }

        /**
         * Starts removing silently blocked calls from the system call log.
         *
         * @param cancelMissedCallNotification also dismiss the system missed call notification once no unread
         * missed call is left. Only needed if the system ignored the screening verdict and treated the silently
         * blocked call as a regular missed call.
         */
        fun scheduleCleanup(context: Context, cancelMissedCallNotification: Boolean = false) {
            val appContext = context.applicationContext
            SilentBlockCleanupJobService.schedule(appContext)

            if (!cancelMissedCallNotification) {
                // a pending sweep that should cancel the notification must not be dropped
                cleanupHandler.removeCallbacksAndMessages(SWEEP_TOKEN)
            }

            val token = if (cancelMissedCallNotification) MISSED_CALL_SWEEP_TOKEN else SWEEP_TOKEN
            IN_PROCESS_SWEEP_DELAYS_MS.forEach { delay ->
                val sweep = Runnable { cleanSafely(appContext, cancelMissedCallNotification) }
                cleanupHandler.postAtTime(sweep, token, SystemClock.uptimeMillis() + delay)
            }
        }

        /** Runs a single cleanup in the background, e.g. when the call history gets loaded. */
        fun cleanInBackground(context: Context) {
            val appContext = context.applicationContext
            cleanupHandler.post { cleanSafely(appContext, cancelMissedCallNotification = false) }
        }

        private fun cleanSafely(context: Context, cancelMissedCallNotification: Boolean) {
            try {
                SilentBlockCallLogCleaner(context).cleanCallLog()
                if (cancelMissedCallNotification) {
                    cancelMissedCallNotificationIfNothingUnread(context)
                }
            } catch (_: Exception) {
                // the next sweep or the cleanup job will try again
            }
        }

        // cancelling is allowed for the default dialer, the app is always the default dialer when it screens calls
        @SuppressLint("MissingPermission")
        private fun cancelMissedCallNotificationIfNothingUnread(context: Context) {
            if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
                return
            }

            val selection = "${Calls.TYPE} = ? AND ${Calls.NEW} = 1"
            val selectionArgs = arrayOf(Calls.MISSED_TYPE.toString())
            val unreadMissedCalls = context.contentResolver
                .query(Calls.CONTENT_URI, arrayOf(Calls._ID), selection, selectionArgs, null)
                ?.use { it.count }

            if (unreadMissedCalls == 0) {
                context.telecomManager.cancelMissedCallsNotification()
            }
        }
    }
}
