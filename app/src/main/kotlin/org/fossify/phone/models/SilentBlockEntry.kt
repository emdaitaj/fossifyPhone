package org.fossify.phone.models

/**
 * A contact or a plain phone number that belongs to the silent block group list.
 *
 * While silent block mode is enabled, every entry that is not [isActive] is silently blocked: its calls are
 * neither rejected nor shown, the caller just keeps hearing the ringback tone until the network times out.
 *
 * @property contactId aggregated contact id for contact entries, 0 for plain number entries
 * @property lookupKey stable system contact lookup key, empty for plain numbers and Fossify private contacts
 * @property numbers every number known for the entry; for contacts a snapshot that is kept in sync with the
 * contact, so a number stays blocked even if the contact gets deleted later
 */
data class SilentBlockEntry(
    val id: Long = 0L,
    val contactId: Int = 0,
    val lookupKey: String = "",
    val name: String = "",
    val numbers: List<String> = emptyList(),
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isContact: Boolean
        get() = contactId != 0

    val isSystemContact: Boolean
        get() = isContact && lookupKey.isNotEmpty()

    fun getDisplayName(): String = name.ifBlank { numbers.firstOrNull().orEmpty() }
}
