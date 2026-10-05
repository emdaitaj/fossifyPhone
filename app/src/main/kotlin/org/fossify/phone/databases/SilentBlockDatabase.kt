package org.fossify.phone.databases

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private storage of the silent block feature: the group list and the secret history of silently blocked calls.
 * It is intentionally kept apart from the regular preferences, so it never ends up in settings exports.
 */
class SilentBlockDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        // allows reading the list while the screening service records a blocked call
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ENTRIES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTACT_ID INTEGER NOT NULL DEFAULT 0,
                $COL_LOOKUP_KEY TEXT NOT NULL DEFAULT '',
                $COL_IS_PRIVATE_CONTACT INTEGER NOT NULL DEFAULT 0,
                $COL_NAME TEXT NOT NULL DEFAULT '',
                $COL_NUMBERS TEXT NOT NULL DEFAULT '[]',
                $COL_IS_ACTIVE INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NUMBER TEXT NOT NULL DEFAULT '',
                $COL_NAME TEXT NOT NULL DEFAULT '',
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_REASON INTEGER NOT NULL,
                $COL_PHONE_ACCOUNT_ID TEXT NOT NULL DEFAULT '',
                $COL_CALL_LOG_CLEANED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX ${TABLE_HISTORY}_${COL_TIMESTAMP}_idx ON $TABLE_HISTORY ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // version 1 is the initial schema, future migrations belong here
    }

    companion object {
        private const val DATABASE_NAME = "silent_block.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_ENTRIES = "entries"
        const val TABLE_HISTORY = "history"

        const val COL_ID = "_id"
        const val COL_CONTACT_ID = "contact_id"
        const val COL_LOOKUP_KEY = "lookup_key"
        const val COL_IS_PRIVATE_CONTACT = "is_private_contact"
        const val COL_NAME = "name"
        const val COL_NUMBERS = "numbers"
        const val COL_IS_ACTIVE = "is_active"
        const val COL_CREATED_AT = "created_at"
        const val COL_NUMBER = "number"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_REASON = "reason"
        const val COL_PHONE_ACCOUNT_ID = "phone_account_id"
        const val COL_CALL_LOG_CLEANED = "call_log_cleaned"

        @Volatile
        private var instance: SilentBlockDatabase? = null

        fun getInstance(context: Context): SilentBlockDatabase {
            return instance ?: synchronized(this) {
                instance ?: SilentBlockDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}
