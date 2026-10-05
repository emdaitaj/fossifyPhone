package org.fossify.phone.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ItemSilentBlockHistoryBinding
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.models.SilentBlockReason
import org.fossify.phone.models.SilentBlockedCall

/**
 * Shows the secret history of silently blocked calls.
 */
class SilentBlockHistoryAdapter(
    private val activity: SimpleActivity,
    private val onClick: (call: SilentBlockedCall, anchor: View) -> Unit,
) : ListAdapter<SilentBlockedCall, SilentBlockHistoryAdapter.ViewHolder>(DiffCallback) {
    private val textColor = activity.getProperTextColor()
    private val blockedColor = activity.getColor(R.color.color_missed_call)
    private val privateNumber = activity.getString(R.string.silent_block_private_number)

    // SIM labels only make sense on devices with multiple SIM cards
    private val simLabels: Map<String, String> = activity.getAvailableSIMCardLabels()
        .takeIf { it.size > 1 }
        ?.associate { it.handle.id to it.label }
        .orEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSilentBlockHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun getReasonText(reason: SilentBlockReason): String {
        val reasonId = when (reason) {
            SilentBlockReason.GROUP_LIST -> R.string.silent_block_reason_group_list
            SilentBlockReason.UNKNOWN_NUMBER -> R.string.silent_block_reason_unknown
            SilentBlockReason.HIDDEN_NUMBER -> R.string.silent_block_reason_hidden
        }
        return activity.getString(reasonId)
    }

    inner class ViewHolder(private val binding: ItemSilentBlockHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(call: SilentBlockedCall) {
            val name = when {
                call.name.isNotBlank() -> call.name
                call.isHiddenNumber -> privateNumber
                else -> call.number
            }

            val details = listOfNotNull(
                call.number.takeIf { it.isNotBlank() && it != name },
                getReasonText(call.reason),
                simLabels[call.phoneAccountId]
            ).joinToString(" • ")

            val date = call.timestamp.formatDateOrTime(
                context = activity,
                hideTimeOnOtherDays = false,
                showCurrentYear = false
            )

            binding.apply {
                silentBlockHistoryIcon.applyColorFilter(blockedColor)
                silentBlockHistoryName.text = name
                silentBlockHistoryName.setTextColor(textColor)
                silentBlockHistoryDetails.text = details
                silentBlockHistoryDetails.setTextColor(textColor)
                silentBlockHistoryDate.text = date
                silentBlockHistoryDate.setTextColor(textColor)

                root.contentDescription = "$name, $details, $date"
                root.setOnClickListener { onClick(call, root) }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<SilentBlockedCall>() {
        override fun areItemsTheSame(oldItem: SilentBlockedCall, newItem: SilentBlockedCall) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SilentBlockedCall, newItem: SilentBlockedCall) = oldItem == newItem
    }
}
