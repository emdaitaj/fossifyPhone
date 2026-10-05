package org.fossify.phone.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ItemSilentBlockEntryBinding
import org.fossify.phone.models.SilentBlockEntry

/**
 * Shows the silent block group list. The checkbox marks an entry as active, i.e. allowed to call.
 */
class SilentBlockEntriesAdapter(
    activity: SimpleActivity,
    private val onToggle: (SilentBlockEntry) -> Unit,
    private val onRemove: (SilentBlockEntry) -> Unit,
) : ListAdapter<SilentBlockEntry, SilentBlockEntriesAdapter.ViewHolder>(DiffCallback) {
    private val textColor = activity.getProperTextColor()
    private val primaryColor = activity.getProperPrimaryColor()
    private val backgroundColor = activity.getProperBackgroundColor()
    private val blockedColor = activity.getColor(R.color.color_missed_call)
    private val activeStatus = activity.getString(R.string.silent_block_status_active)
    private val blockedStatus = activity.getString(R.string.silent_block_status_blocked)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSilentBlockEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSilentBlockEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: SilentBlockEntry) {
            val name = entry.getDisplayName()
            val status = if (entry.isActive) activeStatus else blockedStatus
            // the first number is shown as the name already when a number has no name
            val numbers = if (entry.name.isBlank()) entry.numbers.drop(1) else entry.numbers

            binding.apply {
                silentBlockEntryCheckbox.setColors(textColor, primaryColor, backgroundColor)
                silentBlockEntryCheckbox.isChecked = entry.isActive

                silentBlockEntryType.setImageResource(
                    if (entry.isContact) R.drawable.ic_person_vector else R.drawable.ic_dialpad_vector
                )
                silentBlockEntryType.applyColorFilter(textColor)

                silentBlockEntryName.text = name
                silentBlockEntryName.setTextColor(textColor)

                silentBlockEntryNumbers.text = numbers.joinToString(", ")
                silentBlockEntryNumbers.setTextColor(textColor)
                silentBlockEntryNumbers.beVisibleIf(numbers.isNotEmpty())

                silentBlockEntryStatus.text = status
                silentBlockEntryStatus.setTextColor(if (entry.isActive) primaryColor else blockedColor)

                silentBlockEntryDelete.applyColorFilter(textColor)
                silentBlockEntryDelete.setOnClickListener { onRemove(entry) }

                root.contentDescription = "$name, $status"
                root.setOnClickListener { onToggle(entry) }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<SilentBlockEntry>() {
        override fun areItemsTheSame(oldItem: SilentBlockEntry, newItem: SilentBlockEntry) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SilentBlockEntry, newItem: SilentBlockEntry) = oldItem == newItem
    }
}
