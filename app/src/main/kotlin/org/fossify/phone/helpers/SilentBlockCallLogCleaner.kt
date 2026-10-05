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
     * Returns true if the given system call log row belongs to a silently blocked call. Used for keeping such rows
     * out of the app's call history, in case the cleanup could not remove them yet.
     */
    fun createCallLogFilter(): (number: String, isHiddenNumber: Boolean, timestamp: Long) -> Boolean {
        val calls = try {
            historyRepository.getCalls().sortedBy { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }

        if (calls.isEmpty()) {
            return { _, _, _ -> false }
        }

        val timestamps = calls.map { it.timestamp }.toLongArray()
        return { number, isHiddenNumber, timestamp ->
            // binarySearch returns (-insertionPoint - 1) when there is no exact match
            val searchResult = timestamps.binarySearch(timestamp - CALL_LOG_TIME_TOLERANCE_MS)
            var index = if (searchResult < 0) -searchResult - 1 else searchResult
            var isBlocked = false
            while (!isBlocked && index < calls.size && timestamps[index] <= timestamp + CALL_LOG_TIME_TOLERANCE_MS) {
                isBlocked = isSameCaller(calls[index], number, isHiddenNumber)
                index++
            }
            isBlocked
        }
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
        // outgoing calls are never touched, even if the user called the blocked number back right away
        val selection = "${Calls.DATE} BETWEEN ? AND ? AND ${Calls.TYPE} != ?"
        val selectionArgs = arrayOf(
            (call.timestamp - CALL_LOG_TIME_TOLERANCE_MS).toString(),
            (call.timestamp + CALL_LOG_TIME_TOLERANCE_MS).toString(),
            Calls.OUTGOING_TYPE.toString()
        )
        val projection = arrayOf(Calls._ID, Calls.NUMBER, Calls.NUMBER_PRESENTATION)

        val ids = ArrayList<String>()
        appContext.contentResolver.query(Calls.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getStringValueOrNull(Calls.NUMBER).orEmpty()
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val isHiddenNumber = presentation != Calls.PRESENTATION_ALLOWED
                    || SilentBlockNumberMatcher.isHiddenNumber(number)
                if (isSameCaller(call, number, isHiddenNumber)) {
                    ids.add(cursor.getLongValue(Calls._ID).toString())
                }
            }
        }
        return ids
    }

    private fun isSameCaller(call: SilentBlockedCall, number: String, isHiddenNumber: Boolean): Boolean {
        return if (call.isHiddenNumber || isHiddenNumber) {
            call.isHiddenNumber && isHiddenNumber
        } else {
            matcher.matches(call.number, number)
        }
    }

    companion object {
        /** The call log uses the creation time of the call, the same value the history stores. */
        private const val CALL_LOG_TIME_TOLERANCE_MS = 30 * 1000L

        /** Calls are swept repeatedly for this long, a silently blocked call can keep ringing for minutes. */
        private const val SWEEP_WINDOW_MS = 15 * 60 * 1000L

        /** Give up on calls that never showed up in the call log after this time. */
        private const val MAX_PENDING_AGE_MS = 24 * 60 * 60 * 1000L

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
