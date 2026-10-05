package org.fossify.phone.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.SilentBlockContactPickerAdapter
import org.fossify.phone.databinding.DialogSelectSilentBlockContactsBinding

/**
 * Lets the user pick several contacts at once for the silent block group list.
 */
class SelectSilentBlockContactsDialog(
    private val activity: SimpleActivity,
    private val contacts: List<Contact>,
    private val callback: (selectedContacts: List<Contact>, isActive: Boolean) -> Unit,
) {
    private val binding = DialogSelectSilentBlockContactsBinding.inflate(activity.layoutInflater, null, false)
    private var dialog: AlertDialog? = null
    private val okText = activity.getString(R.string.ok)

    // show the selection count on the confirm button, custom dialog titles can't be changed later on
    private val adapter = SilentBlockContactPickerAdapter(activity, contacts) { selectedCount ->
        val buttonText = if (selectedCount > 0) "$okText ($selectedCount)" else okText
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = buttonText
    }

    init {
        binding.selectSilentBlockContactsList.adapter = adapter
        binding.selectSilentBlockContactsSearch.onTextChangeListener { query -> filterContacts(query) }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.silent_block_add_contacts) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selectedContacts = adapter.getSelectedContacts()
                        if (selectedContacts.isEmpty()) {
                            activity.toast(R.string.silent_block_no_contacts_selected)
                        } else {
                            callback(selectedContacts, binding.selectSilentBlockContactsActive.isChecked)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }

    private fun filterContacts(query: String) {
        val text = query.trim()
        val filtered = if (text.isEmpty()) {
            contacts
        } else {
            val isNumberQuery = text.any { it.isDigit() }
            contacts.filter { contact ->
                contact.getNameToDisplay().contains(text, ignoreCase = true)
                    || (isNumberQuery && contact.doesContainPhoneNumber(text, convertLetters = false))
            }
        }

        adapter.submitList(filtered)
        binding.selectSilentBlockContactsPlaceholder.beVisibleIf(filtered.isEmpty())
    }
}
