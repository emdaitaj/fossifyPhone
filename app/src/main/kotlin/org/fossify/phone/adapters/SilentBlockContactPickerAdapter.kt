package org.fossify.phone.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ItemSilentBlockContactPickBinding
import org.fossify.phone.models.SilentBlockEntry

/**
 * A searchable multi-select list of contacts, used for adding contacts to the silent block group list.
 * The selection survives filtering, so users can search for several people one after another.
 */
class SilentBlockContactPickerAdapter(
    activity: SimpleActivity,
    private val allContacts: List<Contact>,
    private val onSelectionChanged: (selectedCount: Int) -> Unit,
) : ListAdapter<Contact, SilentBlockContactPickerAdapter.ViewHolder>(DiffCallback) {
    private val textColor = activity.getProperTextColor()
    private val primaryColor = activity.getProperPrimaryColor()
    private val backgroundColor = activity.getProperBackgroundColor()
    private val selectedKeys = HashSet<String>()

    init {
        submitList(allContacts)
    }

    fun getSelectedContacts(): List<Contact> = allContacts.filter { SilentBlockEntry.getContactKey(it) in selectedKeys }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSilentBlockContactPickBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSilentBlockContactPickBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: Contact) {
            val key = SilentBlockEntry.getContactKey(contact)
            binding.apply {
                silentBlockPickCheckbox.setColors(textColor, primaryColor, backgroundColor)
                silentBlockPickCheckbox.isChecked = key in selectedKeys
                silentBlockPickName.text = contact.getNameToDisplay()
                silentBlockPickName.setTextColor(textColor)
                silentBlockPickNumbers.text = contact.phoneNumbers.joinToString(", ") { it.value }
                silentBlockPickNumbers.setTextColor(textColor)

                root.setOnClickListener {
                    if (!selectedKeys.remove(key)) {
                        selectedKeys.add(key)
                    }
                    silentBlockPickCheckbox.isChecked = key in selectedKeys
                    onSelectionChanged(selectedKeys.size)
                }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact) =
            SilentBlockEntry.getContactKey(oldItem) == SilentBlockEntry.getContactKey(newItem)

        override fun areContentsTheSame(oldItem: Contact, newItem: Contact) = oldItem == newItem
    }
}
