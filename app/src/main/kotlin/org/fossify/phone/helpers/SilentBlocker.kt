package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.telecom.TelecomManager
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.models.SilentBlockEntry
import org.fossify.phone.models.SilentBlockReason
import org.fossify.phone.models.SilentBlockedCall

/**
 * Decides which calls are blocked silently and which contacts are hidden because of that.
 *
 * Silent blocking means the call is neither rejected nor shown: there is no ringing, no popup, no notification and
 * no call log entry, while the caller keeps hearing the ringback tone until the network times the call out.
 *
 * Call [evaluateIncomingCall] and [recordBlockedCall] from a background thread, they query contacts and storage.
 * [removeHiddenContacts] is cheap enough for the main thread, where the contact lists of the app get delivered,
 * as the group list is cached in memory after the first read.
 */
class SilentBlocker(context: Context) {
    private val appContext = context.applicationContext
    private val config = SilentBlockConfig(appContext)
    private val entriesRepository = SilentBlockEntriesRepository(appContext)
    private val historyRepository = SilentBlockHistoryRepository(appContext)
    private val matcher = SilentBlockNumberMatcher(appContext)

    /**
     * Returns the reason for silently blocking an incoming call, or null if the call should go through.
     *
     * @param number the caller's number, null or empty for hidden callers
     * @param presentation one of the TelecomManager.PRESENTATION_* constants
     */
    fun evaluateIncomingCall(number: String?, presentation: Int): SilentBlockMatch? {
        if (!config.isEnabled) {
            return null
        }

        val isAllowedPresentation = presentation == TelecomManager.PRESENTATION_ALLOWED
        if (!isAllowedPresentation || number == null || SilentBlockNumberMatcher.isHiddenNumber(number)) {
            return if (config.blockHiddenNumbers) SilentBlockMatch(SilentBlockReason.HIDDEN_NUMBER) else null
        }

        val contacts = lookupSystemContacts(number)
        val matchingEntries = EntryIndex(entriesRepository.getEntries()).findEntries(number, contacts)
        return when {
            matchingEntries.isNotEmpty() -> {
                // an entry marked as active always wins, e.g. if a number is both in an active and a blocked entry
                if (matchingEntries.any { it.isActive }) {
                    null
                } else {
                    val name = contacts.firstOrNull()?.name ?: matchingEntries.first().name
                    SilentBlockMatch(SilentBlockReason.GROUP_LIST, name)
                }
            }

            config.blockUnknownNumbers && contacts.isEmpty() && isUnknownNumber(number) -> {
                SilentBlockMatch(SilentBlockReason.UNKNOWN_NUMBER)
            }

            else -> null
        }
    }

    /**
     * Stores the call in the secret history and starts removing it from the system call log. Recording the same
     * call twice, e.g. from the screening service and from the in-call service, results in a single record.
     */
    fun recordBlockedCall(number: String?, timestamp: Long, match: SilentBlockMatch, phoneAccountId: String) {
        val call = SilentBlockedCall(
            number = if (SilentBlockNumberMatcher.isHiddenNumber(number)) "" else number.orEmpty().trim(),
            name = match.name,
            timestamp = timestamp,
            reason = match.reason,
            phoneAccountId = phoneAccountId
        )

        synchronized(recordLock) {
            val isDuplicate = historyRepository
                .getCallsBetween(timestamp - DUPLICATE_WINDOW_MS, timestamp + DUPLICATE_WINDOW_MS)
                .any { isSameCaller(it, call) }

            if (!isDuplicate) {
                historyRepository.insertCall(call)
            }
        }

        SilentBlockCallLogCleaner.scheduleCleanup(appContext)
    }

    /** Removes contacts that are silently blocked right now, if hiding them is enabled. */
    fun removeHiddenContacts(contacts: MutableList<Contact>) {
        if (contacts.isEmpty() || !config.isEnabled || !config.hideBlockedContacts) {
            return
        }

        val entries = getEntriesSafely()
        if (entries.all { it.isActive }) {
            return
        }

        val index = EntryIndex(entries)
        contacts.removeAll { contact ->
            val matchingEntries = index.findEntries(contact)
            matchingEntries.isNotEmpty() && matchingEntries.none { it.isActive }
        }
    }

    private fun getEntriesSafely(): List<SilentBlockEntry> {
        return try {
            entriesRepository.getEntries()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isSameCaller(first: SilentBlockedCall, second: SilentBlockedCall): Boolean {
        return if (first.isHiddenNumber || second.isHiddenNumber) {
            first.isHiddenNumber && second.isHiddenNumber
        } else {
            matcher.matches(first.number, second.number)
        }
    }

    private fun lookupSystemContacts(number: String): List<ContactIdentity> {
        if (!appContext.hasPermission(PERMISSION_READ_CONTACTS)) {
            return emptyList()
        }

        val identities = ArrayList<ContactIdentity>()
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.CONTACT_ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME)
        try {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    identities.add(
                        ContactIdentity(
                            contactId = cursor.getIntValue(PhoneLookup.CONTACT_ID),
                            lookupKey = cursor.getStringValue(PhoneLookup.LOOKUP_KEY).orEmpty(),
                            name = cursor.getStringValue(PhoneLookup.DISPLAY_NAME).orEmpty()
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // contacts provider unavailable, fall back to matching the stored numbers only
        }
        return identities
    }

    // also checks private contacts of Fossify Contacts, which are not part of the system contacts
    private fun isUnknownNumber(number: String): Boolean {
        val privateCursor = appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return try {
            SimpleContactsHelper(appContext).existsSync(number, privateCursor) == ContactLookupResult.NotFound
        } catch (_: Exception) {
            false
        } finally {
            privateCursor?.close()
        }
    }

    private inner class EntryIndex(entries: List<SilentBlockEntry>) {
        private val entriesByNumberKey = HashMap<String, MutableList<Pair<String, SilentBlockEntry>>>()
        private val entriesByContact = HashMap<String, MutableList<SilentBlockEntry>>()
        private val systemContactEntries = entries.filter { it.isSystemContact }

        init {
            entries.forEach { entry ->
                entry.numbers.forEach { number ->
                    val key = SilentBlockNumberMatcher.comparableKey(number)
                    if (key.isNotEmpty()) {
                        entriesByNumberKey.getOrPut(key) { mutableListOf() }.add(number to entry)
                    }
                }

                if (entry.isContact) {
                    val key = getContactKey(entry.contactId, isPrivate = !entry.isSystemContact)
                    entriesByContact.getOrPut(key) { mutableListOf() }.add(entry)
                }
            }
        }

        fun findEntries(number: String, contacts: List<ContactIdentity>): Set<SilentBlockEntry> {
            val result = LinkedHashSet<SilentBlockEntry>()
            contacts.forEach { contact ->
                result.addAll(
                    systemContactEntries.filter { entry ->
                        entry.contactId == contact.contactId || entry.lookupKey == contact.lookupKey
                    }
                )
            }

            addEntriesMatchingNumber(number, result)
            return result
        }

        fun findEntries(contact: Contact): Set<SilentBlockEntry> {
            val result = LinkedHashSet<SilentBlockEntry>()
            entriesByContact[getContactKey(contact.contactId, contact.isPrivate())]?.let { result.addAll(it) }
            contact.phoneNumbers.forEach { phoneNumber ->
                addEntriesMatchingNumber(phoneNumber.value.ifBlank { phoneNumber.normalizedNumber }, result)
            }
            return result
        }

        private fun addEntriesMatchingNumber(number: String, result: MutableSet<SilentBlockEntry>) {
            val candidates = entriesByNumberKey[SilentBlockNumberMatcher.comparableKey(number)] ?: return
            candidates.forEach { (entryNumber, entry) ->
                if (entry !in result && matcher.matches(entryNumber, number)) {
                    result.add(entry)
                }
            }
        }

        private fun getContactKey(contactId: Int, isPrivate: Boolean) = "$isPrivate|$contactId"
    }

    private data class ContactIdentity(val contactId: Int, val lookupKey: String, val name: String)

    companion object {
        // the screening service and the in-call service report the very same creation time of a call
        private const val DUPLICATE_WINDOW_MS = 2000L
        private val recordLock = Any()
    }
}

data class SilentBlockMatch(val reason: SilentBlockReason, val name: String = "")
