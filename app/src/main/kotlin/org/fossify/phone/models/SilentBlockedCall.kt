package org.fossify.phone.models

/**
 * An incoming call that was silently blocked. These records are only visible in the secret silent block screen
 * and are also used for removing the matching rows from the system call log.
 *
 * @property number the caller's number as received from the network, empty for private or hidden numbers
 * @property timestamp the creation time of the call, identical to the date the system call log uses
 */
data class SilentBlockedCall(
    val id: Long = 0L,
    val number: String,
    val name: String = "",
    val timestamp: Long,
    val reason: SilentBlockReason,
    val phoneAccountId: String = "",
    val isCallLogCleaned: Boolean = false,
) {
    val isHiddenNumber: Boolean
        get() = number.isBlank()
}

enum class SilentBlockReason(val id: Int) {
    /** The caller is in the silent block group list and is not marked as active. */
    GROUP_LIST(1),

    /** The caller is not saved in contacts and silently blocking unknown numbers is enabled. */
    UNKNOWN_NUMBER(2),

    /** The caller's number is private or hidden and silently blocking those is enabled. */
    HIDDEN_NUMBER(3);

    companion object {
        fun fromId(id: Int): SilentBlockReason = entries.firstOrNull { it.id == id } ?: GROUP_LIST
    }
}
