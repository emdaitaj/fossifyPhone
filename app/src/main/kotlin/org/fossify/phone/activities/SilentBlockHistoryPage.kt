package org.fossify.phone.activities

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.adapters.SilentBlockHistoryAdapter
import org.fossify.phone.databinding.ActivitySilentBlockBinding
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.SilentBlockEntriesRepository
import org.fossify.phone.helpers.SilentBlockExecutor
import org.fossify.phone.helpers.SilentBlockHistoryRepository
import org.fossify.phone.helpers.SilentBlockNumberMatcher
import org.fossify.phone.models.SilentBlockEntry
import org.fossify.phone.models.SilentBlockedCall

/**
 * The secret history page of the silent block screen, listing every silently blocked call.
 */
class SilentBlockHistoryPage(
    private val activity: SimpleActivity,
    private val binding: ActivitySilentBlockBinding,
    private val onDataChanged: () -> Unit,
) {
    private val historyRepository = SilentBlockHistoryRepository(activity)
    private val entriesRepository = SilentBlockEntriesRepository(activity)
    private val adapter = SilentBlockHistoryAdapter(activity, ::showCallOptions)

    var calls: List<SilentBlockedCall> = emptyList()
        private set

    init {
        binding.silentBlockHistoryList.adapter = adapter
    }

    fun showCalls(newCalls: List<SilentBlockedCall>) {
        calls = newCalls
        adapter.submitList(newCalls)
        binding.silentBlockHistoryPlaceholder.beVisibleIf(newCalls.isEmpty())
    }

    fun confirmClearHistory() {
        val message = activity.getString(R.string.silent_block_clear_history_confirmation)
        ConfirmationDialog(activity, message) {
            runUpdate { historyRepository.clearHistory() }
        }
    }

    private fun showCallOptions(call: SilentBlockedCall, anchor: View) {
        val contextTheme = ContextThemeWrapper(activity, activity.getPopupMenuTheme())
        PopupMenu(contextTheme, anchor, Gravity.END).apply {
            if (!call.isHiddenNumber) {
                menu.add(Menu.NONE, MENU_CALL, Menu.NONE, R.string.call)
                menu.add(Menu.NONE, MENU_COPY, Menu.NONE, R.string.copy)
                menu.add(Menu.NONE, MENU_ALLOW, Menu.NONE, R.string.silent_block_allow_caller)
            }
            menu.add(Menu.NONE, MENU_REMOVE, Menu.NONE, R.string.silent_block_remove_from_history)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CALL -> activity.startCallWithConfirmationCheck(call.number, call.name.ifBlank { call.number })
                    MENU_COPY -> activity.copyToClipboard(call.number)
                    MENU_ALLOW -> allowCaller(call)
                    MENU_REMOVE -> runUpdate { historyRepository.deleteCalls(listOf(call.id)) }
                }
                true
            }
            show()
        }
    }

    /**
     * Marks every group list entry with the caller's number as active, or adds the number as an active entry
     * if it's not in the list yet, e.g. when it was blocked as an unknown number.
     */
    private fun allowCaller(call: SilentBlockedCall) {
        runUpdate {
            val matcher = SilentBlockNumberMatcher(activity)
            val matchingEntries = entriesRepository.getEntries().filter { entry ->
                entry.numbers.any { matcher.matches(it, call.number) }
            }

            if (matchingEntries.isEmpty()) {
                val entry = SilentBlockEntry(name = call.name, numbers = listOf(call.number), isActive = true)
                entriesRepository.addEntries(listOf(entry))
            } else {
                entriesRepository.setActive(matchingEntries.map { it.id }, isActive = true)
            }

            activity.toast(activity.getString(R.string.silent_block_caller_allowed, call.name.ifBlank { call.number }))
        }
    }

    private fun runUpdate(action: () -> Unit) {
        SilentBlockExecutor.runUpdate(activity, action, onDataChanged)
    }

    companion object {
        private const val MENU_CALL = 1
        private const val MENU_COPY = 2
        private const val MENU_ALLOW = 3
        private const val MENU_REMOVE = 4
    }
}
