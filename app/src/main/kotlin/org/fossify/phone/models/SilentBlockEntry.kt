package org.fossify.phone.models

import org.fossify.commons.models.contacts.Contact

/**
 * A contact or a plain phone number that belongs to the silent block group list.
 *
 * While silent block mode is enabled, every entry that is not [isActive] is silently blocked: its calls are
 * neither rejected nor shown, the caller just keeps hearing the ringback tone until the network times out.
 *
 * @property contactId aggregated contact id for contact entries, 0 for plain number entries
 * @property lookupKey stable system contact lookup key, can be empty if it couldn't be read
 * @property isPrivateContact true for private contacts of Fossify Contacts, which are not system contacts
 * @property numbers every number known for the entry; for contacts a snapshot that is kept in sync with the
 * contact, so a number stays blocked even if the contact gets deleted later
 */
data class SilentBlockEntry(
    val id: Long = 0L,
    val contactId: Int = 0,
    val lookupKey: String = "",
    val isPrivateContact: Boolean = false,
    val name: String = "",
    val numbers: List<String> = emptyList(),
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isContact: Boolean
        get() = contactId != 0

    val isSystemContact: Boolean
        get() = isContact && !isPrivateContact

    /** Identifies the person behind a contact entry, see [getContactKey]. */
    val contactKey: String
        get() = getContactKey(contactId, isPrivateContact)

    fun getDisplayName(): String = name.ifBlank { numbers.firstOrNull().orEmpty() }

    companion object {
        /**
         * System and private contacts have separate id ranges, so the key needs both. Raw contacts of one person
         * stored in multiple accounts share the aggregated contact id and thus the key.
         */
        fun getContactKey(contactId: Int, isPrivate: Boolean) = "$isPrivate|$contactId"

        fun getContactKey(contact: Contact) = getContactKey(contact.contactId, contact.isPrivate())
    }
}
