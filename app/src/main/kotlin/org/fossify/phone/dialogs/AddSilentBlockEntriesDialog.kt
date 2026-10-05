package org.fossify.phone.dialogs

import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.helpers.SilentBlockContactsHelper
import org.fossify.phone.models.SilentBlockEntry

/**
 * Lets the user choose between adding contacts and adding a plain number to the silent block group list.
 *
 * @param callback receives the new entries, possibly on a background thread
 */
class AddSilentBlockEntriesDialog(
    private val activity: SimpleActivity,
    private val existingEntries: List<SilentBlockEntry>,
    private val callback: (newEntries: List<SilentBlockEntry>) -> Unit,
) {
    private val contactsHelper = SilentBlockContactsHelper(activity)

    init {
        val items = arrayListOf(
            RadioItem(ADD_CONTACTS, activity.getString(R.string.silent_block_add_contacts)),
            RadioItem(ADD_NUMBER, activity.getString(R.string.silent_block_add_number))
        )

        RadioGroupDialog(activity, items, titleId = R.string.silent_block_add) { choice ->
            if (choice == ADD_CONTACTS) {
                addContacts()
            } else {
                AddSilentBlockNumberDialog(activity, existingEntries) { entry -> callback(listOf(entry)) }
            }
        }
    }

    private fun addContacts() {
        // private contacts of Fossify Contacts can be listed even if the permission gets denied
        activity.handlePermission(PERMISSION_READ_CONTACTS) {
            contactsHelper.getAllContacts { contacts ->
                val availableContacts = contacts.filterNot { isAlreadyAdded(it) }

                activity.runOnUiThread {
                    when {
                        activity.isFinishing || activity.isDestroyed -> Unit
                        contacts.isEmpty() -> activity.toast(R.string.no_contacts_found)
                        availableContacts.isEmpty() -> activity.toast(R.string.silent_block_all_contacts_added)
                        else -> SelectSilentBlockContactsDialog(activity, availableContacts) { selected, isActive ->
                            ensureBackgroundThread {
                                callback(contactsHelper.createEntries(selected, isActive))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isAlreadyAdded(contact: Contact): Boolean {
        val contactKey = SilentBlockEntry.getContactKey(contact)
        return existingEntries.any { it.isContact && it.contactKey == contactKey }
    }

    companion object {
        private const val ADD_CONTACTS = 1
        private const val ADD_NUMBER = 2
    }
}
