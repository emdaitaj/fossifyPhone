package org.fossify.phone.activities

import android.database.SQLException
import android.view.View
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyMaterialSwitch
import org.fossify.phone.R
import org.fossify.phone.adapters.SilentBlockEntriesAdapter
import org.fossify.phone.databinding.ActivitySilentBlockBinding
import org.fossify.phone.dialogs.AddSilentBlockEntriesDialog
import org.fossify.phone.helpers.SilentBlockConfig
import org.fossify.phone.helpers.SilentBlockContactsHelper
import org.fossify.phone.helpers.SilentBlockEntriesRepository
import org.fossify.phone.models.SilentBlockEntry

/**
 * The group list page of the silent block screen: the silent block options plus adding, removing and
 * (de)activating group list entries.
 */
class SilentBlockListPage(
    private val activity: SimpleActivity,
    private val binding: ActivitySilentBlockBinding,
    private val onDataChanged: () -> Unit,
) {
    private val config = SilentBlockConfig(activity)
    private val repository = SilentBlockEntriesRepository(activity)
    private val adapter = SilentBlockEntriesAdapter(activity, onToggle = ::toggleEntry, onRemove = ::confirmRemoveEntry)

    var entries: List<SilentBlockEntry> = emptyList()
        private set

    init {
        binding.silentBlockEntriesList.adapter = adapter
        binding.silentBlockFab.setOnClickListener {
            AddSilentBlockEntriesDialog(activity, entries) { newEntries ->
                runUpdate { repository.addEntries(newEntries) }
            }
        }

        binding.apply {
            setupSwitch(silentBlockEnabledHolder, silentBlockEnabled, config.isEnabled) { config.isEnabled = it }
            setupSwitch(silentBlockUnknownHolder, silentBlockUnknown, config.blockUnknownNumbers) {
                config.blockUnknownNumbers = it
            }
            setupSwitch(silentBlockHiddenHolder, silentBlockHidden, config.blockHiddenNumbers) {
                config.blockHiddenNumbers = it
            }
            setupSwitch(silentBlockHideContactsHolder, silentBlockHideContacts, config.hideBlockedContacts) {
                config.hideBlockedContacts = it
            }
        }
        updateSettingsState()
    }

    /** Keeps names and numbers of contact entries up to date, e.g. after a contact got renamed or a new number. */
    fun syncContactEntries() {
        val contactsHelper = SilentBlockContactsHelper(activity)
        contactsHelper.getAllContacts { contacts ->
            if (contacts.isEmpty()) {
                return@getAllContacts
            }

            try {
                val updatedEntries = contactsHelper.getUpdatedEntries(repository.getEntries(), contacts)
                if (updatedEntries.isNotEmpty()) {
                    repository.updateContactDetails(updatedEntries)
                    activity.runOnUiThread { onDataChanged() }
                }
            } catch (_: SQLException) {
                // the entries keep their previous details, their stored numbers are still matched
            }
        }
    }

    fun showEntries(newEntries: List<SilentBlockEntry>) {
        entries = newEntries
        adapter.submitList(newEntries)

        val activeCount = newEntries.count { it.isActive }
        binding.apply {
            silentBlockListSummary.text = activity.getString(
                R.string.silent_block_summary,
                activeCount,
                newEntries.size - activeCount
            )
            silentBlockListSummary.beVisibleIf(newEntries.isNotEmpty())
            silentBlockEntriesPlaceholder.beVisibleIf(newEntries.isEmpty())
        }
    }

    fun setAllActive(isActive: Boolean) {
        val ids = entries.filter { it.isActive != isActive }.map { it.id }
        if (ids.isNotEmpty()) {
            showEntries(entries.map { it.copy(isActive = isActive) })
            runUpdate { repository.setActive(ids, isActive) }
        }
    }

    private fun toggleEntry(clickedEntry: SilentBlockEntry) {
        // the clicked item can be outdated if the list is being refreshed right now
        val entry = entries.firstOrNull { it.id == clickedEntry.id } ?: return
        val isActive = !entry.isActive
        showEntries(entries.map { if (it.id == entry.id) it.copy(isActive = isActive) else it })
        runUpdate { repository.setActive(listOf(entry.id), isActive) }
    }

    private fun confirmRemoveEntry(entry: SilentBlockEntry) {
        val message = activity.getString(R.string.silent_block_remove_entry_confirmation, entry.getDisplayName())
        ConfirmationDialog(activity, message) {
            runUpdate { repository.deleteEntries(listOf(entry.id)) }
        }
    }

    private fun setupSwitch(holder: View, switch: MyMaterialSwitch, isChecked: Boolean, onChanged: (Boolean) -> Unit) {
        switch.isChecked = isChecked
        holder.setOnClickListener {
            switch.toggle()
            onChanged(switch.isChecked)
            updateSettingsState()
        }
    }

    // the other options only matter while silent block mode is enabled
    private fun updateSettingsState() {
        val isEnabled = config.isEnabled
        binding.apply {
            arrayOf(silentBlockUnknownHolder, silentBlockHiddenHolder, silentBlockHideContactsHolder).forEach {
                it.alpha = if (isEnabled) 1f else DISABLED_ALPHA
            }
            silentBlockListHint.setText(
                if (isEnabled) R.string.silent_block_list_hint else R.string.silent_block_mode_disabled
            )
        }
    }

    private fun runUpdate(action: () -> Unit) {
        ensureBackgroundThread {
            try {
                action()
            } catch (e: SQLException) {
                activity.showErrorToast(e)
            }

            activity.runOnUiThread { onDataChanged() }
        }
    }

    companion object {
        private const val DISABLED_ALPHA = 0.5f
    }
}
