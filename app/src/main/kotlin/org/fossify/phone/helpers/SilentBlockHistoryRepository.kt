package org.fossify.phone.helpers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.helpers.getQuestionMarks
import org.fossify.phone.databases.SilentBlockDatabase
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_CALL_LOG_CLEANED
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_ID
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_IS_REMOVED
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_NAME
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_NUMBER
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_PHONE_ACCOUNT_ID
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_REASON
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_TIMESTAMP
import org.fossify.phone.databases.SilentBlockDatabase.Companion.TABLE_HISTORY
import org.fossify.phone.models.SilentBlockReason
import org.fossify.phone.models.SilentBlockedCall

/**
 * Access to the secret history of silently blocked calls. Use it from a background thread only.
 *
 * Calls the user removes from the history are only hidden at first, as the system call log cleanup still needs them
 * for finding the rows Telecom writes later on. They are purged once the cleanup is done with them.
 */
class SilentBlockHistoryRepository(context: Context) {
    private val database = SilentBlockDatabase.getInstance(context)

    /**
     * Returns the newest calls first.
     *
     * @param includeRemoved also return calls the user removed from the history, but which weren't purged yet
     */
    fun getCalls(includeRemoved: Boolean = false, limit: Int = MAX_HISTORY_SIZE): List<SilentBlockedCall> {
        return query(
            selection = if (includeRemoved) null else "$COL_IS_REMOVED = 0",
            selectionArgs = null,
            orderBy = "$COL_TIMESTAMP DESC",
            limit = limit
        )
    }

    /** Returns every call in the given time range, including the removed ones. */
    fun getCallsBetween(fromTimestamp: Long, toTimestamp: Long): List<SilentBlockedCall> {
        return query(
            selection = "$COL_TIMESTAMP BETWEEN ? AND ?",
            selectionArgs = arrayOf(fromTimestamp.toString(), toTimestamp.toString()),
            orderBy = "$COL_TIMESTAMP ASC"
        )
    }

    /**
     * Returns calls whose system call log rows might still need to be removed: calls that were never cleaned up yet,
     * plus calls newer than [sweepFromTimestamp] that keep being swept, as some systems log a call more than once.
     */
    fun getCallsPendingCleanup(oldestTimestamp: Long, sweepFromTimestamp: Long): List<SilentBlockedCall> {
        return query(
            selection = getPendingSelection(),
            selectionArgs = arrayOf(oldestTimestamp.toString(), sweepFromTimestamp.toString()),
            orderBy = "$COL_TIMESTAMP ASC"
        )
    }

    fun insertCall(call: SilentBlockedCall): Long {
        val values = ContentValues().apply {
            put(COL_NUMBER, call.number)
            put(COL_NAME, call.name)
            put(COL_TIMESTAMP, call.timestamp)
            put(COL_REASON, call.reason.id)
            put(COL_PHONE_ACCOUNT_ID, call.phoneAccountId)
            put(COL_CALL_LOG_CLEANED, if (call.isCallLogCleaned) 1 else 0)
        }

        val db = database.writableDatabase
        val id = db.insert(TABLE_HISTORY, null, values)

        // keep the history bounded, drop the oldest records
        db.delete(
            TABLE_HISTORY,
            "$COL_ID NOT IN (SELECT $COL_ID FROM $TABLE_HISTORY ORDER BY $COL_TIMESTAMP DESC LIMIT $MAX_HISTORY_SIZE)",
            null
        )
        return id
    }

    fun markCallLogCleaned(ids: Collection<Long>) {
        updateCalls(ids, ContentValues().apply { put(COL_CALL_LOG_CLEANED, 1) })
    }

    /** Hides the calls from the history, see the class description. */
    fun removeCalls(ids: Collection<Long>) {
        updateCalls(ids, ContentValues().apply { put(COL_IS_REMOVED, 1) })
    }

    /** Hides every call from the history, see the class description. */
    fun clearHistory() {
        val values = ContentValues().apply { put(COL_IS_REMOVED, 1) }
        database.writableDatabase.update(TABLE_HISTORY, values, null, null)
    }

    /** Deletes the removed calls the system call log cleanup doesn't need anymore. */
    fun purgeRemovedCalls(oldestTimestamp: Long, sweepFromTimestamp: Long) {
        database.writableDatabase.delete(
            TABLE_HISTORY,
            "$COL_IS_REMOVED = 1 AND NOT (${getPendingSelection()})",
            arrayOf(oldestTimestamp.toString(), sweepFromTimestamp.toString())
        )
    }

    private fun updateCalls(ids: Collection<Long>, values: ContentValues) {
        if (ids.isEmpty()) {
            return
        }

        val db = database.writableDatabase
        ids.chunked(MAX_SQL_ARGS).forEach { chunk ->
            val selection = "$COL_ID IN (${getQuestionMarks(chunk.size)})"
            db.update(TABLE_HISTORY, values, selection, chunk.map { it.toString() }.toTypedArray())
        }
    }

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String,
        limit: Int? = null,
    ): List<SilentBlockedCall> {
        val calls = ArrayList<SilentBlockedCall>()
        database.readableDatabase.query(
            TABLE_HISTORY, null, selection, selectionArgs, null, null, orderBy, limit?.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                calls.add(cursor.toCall())
            }
        }
        return calls
    }

    companion object {
        const val MAX_HISTORY_SIZE = 1000
        private const val MAX_SQL_ARGS = 500

        // expects the oldest timestamp and the sweep start as arguments
        private fun getPendingSelection() =
            "$COL_TIMESTAMP >= ? AND ($COL_CALL_LOG_CLEANED = 0 OR $COL_TIMESTAMP >= ?)"

        private fun Cursor.toCall() = SilentBlockedCall(
            id = getLongValue(COL_ID),
            number = getStringValue(COL_NUMBER).orEmpty(),
            name = getStringValue(COL_NAME).orEmpty(),
            timestamp = getLongValue(COL_TIMESTAMP),
            reason = SilentBlockReason.fromId(getIntValue(COL_REASON)),
            phoneAccountId = getStringValue(COL_PHONE_ACCOUNT_ID).orEmpty(),
            isCallLogCleaned = getIntValue(COL_CALL_LOG_CLEANED) == 1
        )
    }
}
