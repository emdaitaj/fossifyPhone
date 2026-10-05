package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
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
     * Decides how an incoming call is handled.
     *
     * @param number the caller's number, null or empty for hidden callers
     * @param presentation one of the TelecomManager.PRESENTATION_* constants
     */
    fun evaluateIncomingCall(number: String?, presentation: Int): SilentBlockDecision {
        if (!config.isEnabled) {
            return SilentBlockDecision.NotHandled
        }

        if (SilentBlockNumberMatcher.isHiddenPresentation(presentation)) {
            return if (config.blockHiddenNumbers) {
                SilentBlockDecision.Block(SilentBlockReason.HIDDEN_NUMBER)
            } else {
                SilentBlockDecision.NotHandled
            }
        }

        // e.g. payphones, there is nothing that could be matched
        if (number == null || SilentBlockNumberMatcher.isHiddenNumber(number)) {
            return SilentBlockDecision.NotHandled
        }

        val contacts = lookupSystemContacts(number)
        val matchingEntries = EntryIndex(entriesRepository.getEntries()).findEntries(number, contacts.orEmpty())
        return when {
            // an entry marked as active always wins, e.g. if a number is both in an active and a blocked entry
            matchingEntries.any { it.isActive } -> SilentBlockDecision.Allow
            matchingEntries.isNotEmpty() -> {
                val name = contacts?.firstOrNull()?.name ?: matchingEntries.first().name
                SilentBlockDecision.Block(SilentBlockReason.GROUP_LIST, name)
            }

            // a failed contacts lookup must never make a saved contact count as unknown
            config.blockUnknownNumbers && contacts?.isEmpty() == true && !isPrivateContact(number) -> {
                SilentBlockDecision.Block(SilentBlockReason.UNKNOWN_NUMBER)
            }

            else -> SilentBlockDecision.NotHandled
        }
    }

    /**
     * Stores the call in the secret history and starts removing it from the system call log. Recording the same
     * call twice, e.g. from the screening service and from the in-call service, results in a single record.
     */
    fun recordBlockedCall(
        number: String?,
        timestamp: Long,
        decision: SilentBlockDecision.Block,
        phoneAccountId: String,
    ) {
        val isHidden = decision.reason == SilentBlockReason.HIDDEN_NUMBER
            || SilentBlockNumberMatcher.isHiddenNumber(number)
        val call = SilentBlockedCall(
            number = if (isHidden) "" else number.orEmpty().trim(),
            name = decision.name,
            timestamp = timestamp,
            reason = decision.reason,
            phoneAccountId = phoneAccountId
        )

        synchronized(recordLock) {
            val isDuplicate = historyRepository
                .getCallsBetween(timestamp - DUPLICATE_WINDOW_MS, timestamp + DUPLICATE_WINDOW_MS)
                .any { matcher.isSameCaller(it.number, it.isHiddenNumber, call.number, call.isHiddenNumber) }

            if (!isDuplicate) {
                historyRepository.insertCall(call)
            }
        }

        SilentBlockCallLogCleaner.scheduleCleanup(appContext)
    }

    /**
     * Removes the history record of a silently blocked call that got answered after all, e.g. with a headset button
     * on a system that ignored the screening verdict.
     */
    fun forgetBlockedCall(number: String?, isHiddenNumber: Boolean, timestamp: Long) {
        synchronized(recordLock) {
            val ids = historyRepository
                .getCallsBetween(timestamp - DUPLICATE_WINDOW_MS, timestamp + DUPLICATE_WINDOW_MS)
                .filter { matcher.isSameCaller(it.number, it.isHiddenNumber, number.orEmpty(), isHiddenNumber) }
                .map { it.id }
            historyRepository.removeCalls(ids)
        }
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

    /** Returns the system contacts with this number, or null if they couldn't be determined. */
    private fun lookupSystemContacts(number: String): List<ContactIdentity>? {
        if (!appContext.hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.CONTACT_ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME)
        return try {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val identities = ArrayList<ContactIdentity>()
                while (cursor.moveToNext()) {
                    identities.add(
                        ContactIdentity(
                            contactId = cursor.getIntValue(PhoneLookup.CONTACT_ID),
                            lookupKey = cursor.getStringValue(PhoneLookup.LOOKUP_KEY).orEmpty(),
                            name = cursor.getStringValue(PhoneLookup.DISPLAY_NAME).orEmpty()
                        )
                    )
                }
                identities
            }
        } catch (_: Exception) {
            // contacts provider unavailable, only the stored numbers can be matched
            null
        }
    }

    // private contacts of Fossify Contacts are not part of the system contacts
    private fun isPrivateContact(number: String): Boolean {
        val privateCursor = appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return try {
            MyContactsContentProvider.getSimpleContacts(appContext, privateCursor)
                .any { it.doesHavePhoneNumber(number) }
        } catch (_: Exception) {
            // treat the caller as known, blocking a saved contact by mistake is worse than letting a call through
            true
        } finally {
            privateCursor?.close()
        }
    }

    private inner class EntryIndex(entries: List<SilentBlockEntry>) {
        private val entriesByNumberKey = HashMap<String, MutableList<Pair<String, SilentBlockEntry>>>()
        private val entriesByContact = entries.filter { it.isContact }.groupBy { it.contactKey }
        private val systemContactEntries = entries.filter { it.isSystemContact }

        init {
            entries.forEach { entry ->
                entry.numbers.forEach { number ->
                    val key = SilentBlockNumberMatcher.comparableKey(number)
                    if (key.isNotEmpty()) {
                        entriesByNumberKey.getOrPut(key) { mutableListOf() }.add(number to entry)
                    }
                }
            }
        }

        fun findEntries(number: String, contacts: List<ContactIdentity>): Set<SilentBlockEntry> {
            val result = LinkedHashSet<SilentBlockEntry>()
            contacts.forEach { contact ->
                result.addAll(
                    systemContactEntries.filter { entry ->
                        entry.contactId == contact.contactId
                            || (entry.lookupKey.isNotEmpty() && entry.lookupKey == contact.lookupKey)
                    }
                )
            }

            addEntriesMatchingNumber(number, result)
            return result
        }

        fun findEntries(contact: Contact): Set<SilentBlockEntry> {
            val result = LinkedHashSet<SilentBlockEntry>()
            entriesByContact[SilentBlockEntry.getContactKey(contact)]?.let { result.addAll(it) }
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
    }

    private data class ContactIdentity(val contactId: Int, val lookupKey: String, val name: String)

    companion object {
        // the screening service and the in-call service report the very same creation time of a call
        private const val DUPLICATE_WINDOW_MS = 2000L
        private val recordLock = Any()
    }
}

/** How silent block handles an incoming call. */
sealed interface SilentBlockDecision {
    /** The call is blocked silently. */
    data class Block(val reason: SilentBlockReason, val name: String = "") : SilentBlockDecision

    /** The caller is an active group list entry, so the call rings even if it would be rejected as unknown. */
    data object Allow : SilentBlockDecision

    /** Silent block doesn't apply, the regular blocking rules decide. */
    data object NotHandled : SilentBlockDecision
}
