package org.fossify.phone.helpers

import android.content.Context
import android.provider.ContactsContract
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.getQuestionMarks
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.models.SilentBlockEntry

/**
 * Connects the silent block group list with the user's contacts: turns contacts into list entries and keeps contact
 * entries up to date when contacts get renamed, get new numbers or get merged.
 */
class SilentBlockContactsHelper(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Loads every contact with a phone number, including private contacts of Fossify Contacts, one item per person.
     * Hidden contacts are included on purpose, the group list must be manageable. The callback runs in background.
     */
    fun getAllContacts(callback: (List<Contact>) -> Unit) {
        if (!appContext.hasPermission(PERMISSION_READ_CONTACTS)) {
            ensureBackgroundThread { callback(getPrivateContacts()) }
            return
        }

        ContactsHelper(appContext).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            ensureBackgroundThread {
                val allContacts = mergeDuplicates(contacts + getPrivateContacts())
                    .filter { it.phoneNumbers.isNotEmpty() }
                    .sorted()
                callback(allContacts)
            }
        }
    }

    /** Creates group list entries for the given contacts. Call it from a background thread. */
    fun createEntries(contacts: List<Contact>, isActive: Boolean): List<SilentBlockEntry> {
        val lookupKeys = getLookupKeys(contacts.filterNot { it.isPrivate() }.map { it.contactId })
        return contacts.map { contact ->
            SilentBlockEntry(
                contactId = contact.contactId,
                lookupKey = if (contact.isPrivate()) "" else lookupKeys[contact.contactId].orEmpty(),
                isPrivateContact = contact.isPrivate(),
                name = contact.getNameToDisplay(),
                numbers = getNumbers(contact),
                isActive = isActive
            )
        }
    }

    /**
     * Returns the contact entries that changed since they were added, e.g. a contact got renamed or got a new number,
     * updated to their current state. Numbers are never dropped, so a blocked person stays blocked even if a number
     * gets removed from the contact. Call it from a background thread.
     */
    fun getUpdatedEntries(entries: List<SilentBlockEntry>, contacts: List<Contact>): List<SilentBlockEntry> {
        val contactsByKey = contacts.associateBy { SilentBlockEntry.getContactKey(it) }
        val updatedEntries = entries.filter { it.isContact }.mapNotNull { entry ->
            val contact = contactsByKey[entry.contactKey]
                ?: findMovedContact(entry, contactsByKey)
                ?: return@mapNotNull null

            val numbers = (entry.numbers + getNumbers(contact)).distinctBy { SilentBlockNumberMatcher.normalize(it) }
            val name = contact.getNameToDisplay().ifBlank { entry.name }
            entry.copy(contactId = contact.contactId, name = name, numbers = numbers).takeIf { it != entry }
        }

        // merging contacts changes their ids, refresh the lookup keys of such entries
        val originalContactIds = entries.associate { it.id to it.contactId }
        val movedContactIds = updatedEntries
            .filter { it.isSystemContact && originalContactIds[it.id] != it.contactId }
            .map { it.contactId }
        val lookupKeys = getLookupKeys(movedContactIds)
        return updatedEntries.map { entry ->
            lookupKeys[entry.contactId]?.let { entry.copy(lookupKey = it) } ?: entry
        }
    }

    private fun getPrivateContacts(): List<Contact> {
        val privateCursor = appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return try {
            MyContactsContentProvider.getContacts(appContext, privateCursor)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // a person stored in multiple accounts consists of multiple raw contacts sharing the same contact id
    private fun mergeDuplicates(contacts: List<Contact>): List<Contact> {
        return contacts
            .groupBy { SilentBlockEntry.getContactKey(it) }
            .values
            .map { group ->
                if (group.size == 1) {
                    group.first()
                } else {
                    val phoneNumbers = group
                        .flatMap { it.phoneNumbers }
                        .distinctBy { SilentBlockNumberMatcher.normalize(it.value) }
                    group.first().copy(phoneNumbers = ArrayList(phoneNumbers))
                }
            }
    }

    private fun findMovedContact(entry: SilentBlockEntry, contactsByKey: Map<String, Contact>): Contact? {
        if (!entry.isSystemContact || entry.lookupKey.isEmpty()) {
            return null
        }

        val newContactId = try {
            val lookupUri = ContactsContract.Contacts.getLookupUri(entry.contactId.toLong(), entry.lookupKey)
            val contactUri = ContactsContract.Contacts.lookupContact(appContext.contentResolver, lookupUri)
            contactUri?.lastPathSegment?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

        return newContactId?.let { contactsByKey[SilentBlockEntry.getContactKey(it, isPrivate = false)] }
    }

    private fun getLookupKeys(contactIds: List<Int>): Map<Int, String> {
        val lookupKeys = HashMap<Int, String>()
        if (contactIds.isEmpty() || !appContext.hasPermission(PERMISSION_READ_CONTACTS)) {
            return lookupKeys
        }

        contactIds.distinct().chunked(MAX_SQL_ARGS).forEach { chunk ->
            try {
                queryLookupKeys(chunk, lookupKeys)
            } catch (_: Exception) {
                // entries without a lookup key are still matched by their numbers
            }
        }
        return lookupKeys
    }

    private fun queryLookupKeys(contactIds: List<Int>, lookupKeys: MutableMap<Int, String>) {
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY)
        val selection = "${ContactsContract.Contacts._ID} IN (${getQuestionMarks(contactIds.size)})"
        val selectionArgs = contactIds.map { it.toString() }.toTypedArray()
        appContext.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, selection, selectionArgs, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val lookupKey = cursor.getStringValue(ContactsContract.Contacts.LOOKUP_KEY).orEmpty()
                if (lookupKey.isNotEmpty()) {
                    lookupKeys[cursor.getIntValue(ContactsContract.Contacts._ID)] = lookupKey
                }
            }
        }
    }

    private fun getNumbers(contact: Contact): List<String> {
        return contact.phoneNumbers
            .map { it.value.ifBlank { it.normalizedNumber } }
            .filter { it.isNotBlank() }
            .distinctBy { SilentBlockNumberMatcher.normalize(it) }
    }

    companion object {
        private const val MAX_SQL_ARGS = 500
    }
}
