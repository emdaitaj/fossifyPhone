package org.fossify.phone.helpers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.helpers.getQuestionMarks
import org.fossify.phone.databases.SilentBlockDatabase
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_CALL_LOG_CLEANED
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_ID
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
 */
class SilentBlockHistoryRepository(context: Context) {
    private val database = SilentBlockDatabase.getInstance(context)

    /** Returns the newest calls first. */
    fun getCalls(limit: Int = MAX_HISTORY_SIZE): List<SilentBlockedCall> {
        return query(selection = null, selectionArgs = null, orderBy = "$COL_TIMESTAMP DESC", limit = limit)
    }

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
            selection = "$COL_TIMESTAMP >= ? AND ($COL_CALL_LOG_CLEANED = 0 OR $COL_TIMESTAMP >= ?)",
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
        val values = ContentValues().apply {
            put(COL_CALL_LOG_CLEANED, 1)
        }

        updateOrDelete(ids) { selection, args ->
            update(TABLE_HISTORY, values, selection, args)
        }
    }

    fun deleteCalls(ids: Collection<Long>) {
        updateOrDelete(ids) { selection, args ->
            delete(TABLE_HISTORY, selection, args)
        }
    }

    fun clearHistory() {
        database.writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    private fun updateOrDelete(
        ids: Collection<Long>,
        action: SQLiteDatabase.(String, Array<String>) -> Unit
    ) {
        if (ids.isEmpty()) {
            return
        }

        val db = database.writableDatabase
        ids.chunked(MAX_SQL_ARGS).forEach { chunk ->
            val selection = "$COL_ID IN (${getQuestionMarks(chunk.size)})"
            db.action(selection, chunk.map { it.toString() }.toTypedArray())
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

    private fun Cursor.toCall() = SilentBlockedCall(
        id = getLongValue(COL_ID),
        number = getStringValue(COL_NUMBER).orEmpty(),
        name = getStringValue(COL_NAME).orEmpty(),
        timestamp = getLongValue(COL_TIMESTAMP),
        reason = SilentBlockReason.fromId(getIntValue(COL_REASON)),
        phoneAccountId = getStringValue(COL_PHONE_ACCOUNT_ID).orEmpty(),
        isCallLogCleaned = getIntValue(COL_CALL_LOG_CLEANED) == 1
    )

    companion object {
        const val MAX_HISTORY_SIZE = 1000
        private const val MAX_SQL_ARGS = 500
    }
}
