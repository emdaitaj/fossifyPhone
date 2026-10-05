package org.fossify.phone.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.DialogAddSilentBlockNumberBinding
import org.fossify.phone.helpers.SilentBlockNumberMatcher
import org.fossify.phone.models.SilentBlockEntry

/**
 * Adds a plain phone number, e.g. one that is not saved in contacts, to the silent block group list.
 */
class AddSilentBlockNumberDialog(
    private val activity: SimpleActivity,
    private val existingEntries: List<SilentBlockEntry>,
    private val callback: (entry: SilentBlockEntry) -> Unit,
) {
    private val binding = DialogAddSilentBlockNumberBinding.inflate(activity.layoutInflater, null, false)
    private val matcher = SilentBlockNumberMatcher(activity)

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.silent_block_add_number) { alertDialog ->
                    alertDialog.showKeyboard(binding.addSilentBlockNumber)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (addNumber()) {
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }

    private fun addNumber(): Boolean {
        val number = binding.addSilentBlockNumber.value
        val digitCount = SilentBlockNumberMatcher.normalize(number).trimStart('+').length
        val isAlreadyAdded = existingEntries.any { entry -> entry.numbers.any { matcher.matches(it, number) } }

        return when {
            digitCount < MIN_NUMBER_LENGTH -> {
                activity.toast(R.string.silent_block_invalid_number)
                false
            }

            isAlreadyAdded -> {
                activity.toast(R.string.silent_block_number_already_added)
                false
            }

            else -> {
                val entry = SilentBlockEntry(
                    name = binding.addSilentBlockName.value,
                    numbers = listOf(number),
                    isActive = binding.addSilentBlockNumberActive.isChecked
                )
                callback(entry)
                true
            }
        }
    }

    companion object {
        // short codes of carriers and services can be as short as this
        private const val MIN_NUMBER_LENGTH = 3
    }
}
