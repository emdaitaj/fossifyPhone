package org.fossify.phone.helpers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.helpers.getQuestionMarks
import org.fossify.phone.databases.SilentBlockDatabase
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_CONTACT_ID
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_CREATED_AT
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_ID
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_IS_ACTIVE
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_IS_PRIVATE_CONTACT
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_LOOKUP_KEY
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_NAME
import org.fossify.phone.databases.SilentBlockDatabase.Companion.COL_NUMBERS
import org.fossify.phone.databases.SilentBlockDatabase.Companion.TABLE_ENTRIES
import org.fossify.phone.models.SilentBlockEntry

/**
 * Access to the silent block group list. The list is read on every incoming call and every contact list refresh,
 * so it is cached in memory and the cache is invalidated on every write. Writes and the first read access the
 * database, use [preload] for loading the list in the background before the contact lists need it.
 */
class SilentBlockEntriesRepository(context: Context) {
    private val database = SilentBlockDatabase.getInstance(context)

    fun getEntries(): List<SilentBlockEntry> {
        cachedEntries?.let { return it }
        synchronized(lock) {
            return cachedEntries ?: queryEntries().also { cachedEntries = it }
        }
    }

    fun addEntries(entries: List<SilentBlockEntry>) {
        if (entries.isEmpty()) {
            return
        }

        write {
            entries.forEach { entry ->
                insert(TABLE_ENTRIES, null, entry.toContentValues())
            }
        }
    }

    /**
     * Stores refreshed contact details of the given entries. The active state is left untouched on purpose,
     * so a contact sync running in the background can never revert the user toggling an entry meanwhile.
     */
    fun updateContactDetails(entries: List<SilentBlockEntry>) {
        if (entries.isEmpty()) {
            return
        }

        write {
            entries.forEach { entry ->
                val values = ContentValues().apply {
                    put(COL_CONTACT_ID, entry.contactId)
                    put(COL_LOOKUP_KEY, entry.lookupKey)
                    put(COL_IS_PRIVATE_CONTACT, if (entry.isPrivateContact) 1 else 0)
                    put(COL_NAME, entry.name)
                    put(COL_NUMBERS, encodeNumbers(entry.numbers))
                }
                update(TABLE_ENTRIES, values, "$COL_ID = ?", arrayOf(entry.id.toString()))
            }
        }
    }

    fun setActive(ids: Collection<Long>, isActive: Boolean) {
        if (ids.isEmpty()) {
            return
        }

        val values = ContentValues().apply {
            put(COL_IS_ACTIVE, if (isActive) 1 else 0)
        }

        write {
            ids.chunked(MAX_SQL_ARGS).forEach { chunk ->
                val selection = "$COL_ID IN (${getQuestionMarks(chunk.size)})"
                update(TABLE_ENTRIES, values, selection, chunk.map { it.toString() }.toTypedArray())
            }
        }
    }

    fun deleteEntries(ids: Collection<Long>) {
        if (ids.isEmpty()) {
            return
        }

        write {
            ids.chunked(MAX_SQL_ARGS).forEach { chunk ->
                val selection = "$COL_ID IN (${getQuestionMarks(chunk.size)})"
                delete(TABLE_ENTRIES, selection, chunk.map { it.toString() }.toTypedArray())
            }
        }
    }

    private fun write(action: SQLiteDatabase.() -> Unit) {
        synchronized(lock) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.action()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                cachedEntries = null
            }
        }
    }

    private fun queryEntries(): List<SilentBlockEntry> {
        val entries = ArrayList<SilentBlockEntry>()
        database.readableDatabase.query(
            TABLE_ENTRIES, null, null, null, null, null, "$COL_CREATED_AT ASC, $COL_ID ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursor.toEntry())
            }
        }
        return entries
    }

    private fun Cursor.toEntry() = SilentBlockEntry(
        id = getLongValue(COL_ID),
        contactId = getIntValue(COL_CONTACT_ID),
        lookupKey = getStringValue(COL_LOOKUP_KEY).orEmpty(),
        isPrivateContact = getIntValue(COL_IS_PRIVATE_CONTACT) == 1,
        name = getStringValue(COL_NAME).orEmpty(),
        numbers = decodeNumbers(getStringValue(COL_NUMBERS).orEmpty()),
        isActive = getIntValue(COL_IS_ACTIVE) == 1,
        createdAt = getLongValue(COL_CREATED_AT)
    )

    private fun SilentBlockEntry.toContentValues() = ContentValues().apply {
        put(COL_CONTACT_ID, contactId)
        put(COL_LOOKUP_KEY, lookupKey)
        put(COL_IS_PRIVATE_CONTACT, if (isPrivateContact) 1 else 0)
        put(COL_NAME, name)
        put(COL_NUMBERS, encodeNumbers(numbers))
        put(COL_IS_ACTIVE, if (isActive) 1 else 0)
        put(COL_CREATED_AT, createdAt)
    }

    companion object {
        private const val MAX_SQL_ARGS = 500

        /** Loads the group list into the memory cache in the background. */
        fun preload(context: Context) {
            val appContext = context.applicationContext
            SilentBlockExecutor.execute { SilentBlockEntriesRepository(appContext).getEntries() }
        }
        private val lock = Any()
        private val numbersSerializer = ListSerializer(String.serializer())

        @Volatile
        private var cachedEntries: List<SilentBlockEntry>? = null

        private fun encodeNumbers(numbers: List<String>): String {
            return Json.encodeToString(numbersSerializer, numbers.distinct())
        }

        private fun decodeNumbers(json: String): List<String> {
            return try {
                Json.decodeFromString(numbersSerializer, json)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
