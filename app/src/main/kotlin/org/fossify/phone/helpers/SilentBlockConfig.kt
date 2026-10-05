package org.fossify.phone.helpers

import android.content.Context
import androidx.core.content.edit

/**
 * Settings of the silent block feature. They live in a separate private preferences file, so they never show up
 * in exported app settings and can only be changed from the secret silent block screen.
 */
class SilentBlockConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Master switch. When disabled, nobody is silently blocked and no contact is hidden. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Silently block numbers that are neither saved in contacts nor in the group list. */
    var blockUnknownNumbers: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_UNKNOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_UNKNOWN, value) }

    /** Silently block private, hidden and unavailable caller IDs. */
    var blockHiddenNumbers: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_HIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_HIDDEN, value) }

    /** Hide silently blocked contacts from the contact lists of the app. */
    var hideBlockedContacts: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CONTACTS, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_CONTACTS, value) }

    companion object {
        private const val PREFS_NAME = "silent_block_prefs"
        private const val KEY_ENABLED = "silent_block_enabled"
        private const val KEY_BLOCK_UNKNOWN = "silent_block_unknown_numbers"
        private const val KEY_BLOCK_HIDDEN = "silent_block_hidden_numbers"
        private const val KEY_HIDE_CONTACTS = "silent_block_hide_contacts"

        private val SECRET_CODE_SEPARATORS = setOf(' ', '-', '.', '(', ')', '/', ' ')

        /** True if the dialed input is the secret code that opens the silent block screen. */
        fun isSecretCode(input: String): Boolean {
            return input.filterNot { it in SECRET_CODE_SEPARATORS } == SILENT_BLOCK_SECRET_CODE
        }
    }
}
