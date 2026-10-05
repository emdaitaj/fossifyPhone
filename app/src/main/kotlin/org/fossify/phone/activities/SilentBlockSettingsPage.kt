package org.fossify.phone.activities

import android.view.View
import org.fossify.commons.views.MyMaterialSwitch
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivitySilentBlockBinding
import org.fossify.phone.helpers.SILENT_BLOCK_SECRET_CODE
import org.fossify.phone.helpers.SilentBlockConfig

/**
 * The options page of the silent block screen.
 */
class SilentBlockSettingsPage(
    private val activity: SimpleActivity,
    private val binding: ActivitySilentBlockBinding,
    private val onSettingsChanged: () -> Unit,
) {
    private val config = SilentBlockConfig(activity)

    init {
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
            silentBlockHowItWorks.text = activity.getString(
                R.string.silent_block_how_it_works,
                SILENT_BLOCK_SECRET_CODE
            )
        }
        updateSettingsState()
    }

    private fun setupSwitch(holder: View, switch: MyMaterialSwitch, isChecked: Boolean, onChanged: (Boolean) -> Unit) {
        switch.isChecked = isChecked
        holder.setOnClickListener {
            switch.toggle()
            onChanged(switch.isChecked)
            updateSettingsState()
            onSettingsChanged()
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

    companion object {
        private const val DISABLED_ALPHA = 0.5f
    }
}
