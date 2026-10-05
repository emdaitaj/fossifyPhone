package org.fossify.phone.activities

import android.os.Bundle
import android.view.WindowManager
import com.google.android.material.tabs.TabLayout
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.onTabSelectionChanged
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivitySilentBlockBinding
import org.fossify.phone.helpers.SILENT_BLOCK_SECRET_CODE
import org.fossify.phone.helpers.SilentBlockEntriesRepository
import org.fossify.phone.helpers.SilentBlockExecutor
import org.fossify.phone.helpers.SilentBlockHistoryRepository

/**
 * The secret silent block screen with the group list and the history of silently blocked calls.
 *
 * It isn't reachable from any menu, only by dialing [SILENT_BLOCK_SECRET_CODE] in the app's dialpad. It's hidden
 * from screenshots and the recent apps preview and closes itself whenever the user leaves it, so getting back
 * requires dialing the code again.
 */
class SilentBlockActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySilentBlockBinding::inflate)
    private lateinit var listPage: SilentBlockListPage
    private lateinit var historyPage: SilentBlockHistoryPage
    private lateinit var settingsPage: SilentBlockSettingsPage
    private var isAwaitingResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(binding.root)
        setupEdgeToEdge(
            padBottomSystem = listOf(
                binding.silentBlockEntriesList,
                binding.silentBlockHistoryList,
                binding.silentBlockSettingsPage
            ),
            moveBottomSystem = listOf(binding.silentBlockFab)
        )

        listPage = SilentBlockListPage(this, binding, ::refreshData)
        historyPage = SilentBlockHistoryPage(this, binding, ::refreshData)
        settingsPage = SilentBlockSettingsPage(this, binding, ::refreshData)

        setupOptionsMenu()
        setupTabs(savedInstanceState?.getInt(SELECTED_TAB) ?: LIST_TAB)
        binding.silentBlockDefaultDialerWarning.setOnClickListener {
            isAwaitingResult = true
            launchSetDefaultDialerIntent()
        }

        refreshData()
        listPage.syncContactEntries()
    }

    override fun onResume() {
        super.onResume()
        isAwaitingResult = false
        setupTopAppBar(binding.silentBlockAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.silentBlockHolder)

        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        binding.apply {
            silentBlockTabs.setBackgroundColor(getProperBackgroundColor())
            silentBlockTabs.setTabTextColors(textColor, primaryColor)
            silentBlockTabs.setSelectedTabIndicatorColor(primaryColor)
            silentBlockDefaultDialerWarning.setTextColor(primaryColor)
            // also refreshed when coming back from the default phone app request
            silentBlockDefaultDialerWarning.beVisibleIf(!isDefaultDialer())
        }
    }

    override fun onStop() {
        super.onStop()
        // leaving the secret screen locks it again
        if (!isChangingConfigurations && !isAwaitingResult) {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED_TAB, binding.silentBlockTabs.selectedTabPosition)
    }

    private fun setupOptionsMenu() {
        binding.silentBlockToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.silent_block_mark_all_active -> listPage.setAllActive(true)
                R.id.silent_block_mark_all_blocked -> listPage.setAllActive(false)
                R.id.silent_block_clear_history -> historyPage.confirmClearHistory()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun refreshMenuItems() {
        val isListTab = binding.silentBlockTabs.selectedTabPosition == LIST_TAB
        binding.silentBlockToolbar.menu.apply {
            findItem(R.id.silent_block_mark_all_active).isVisible = isListTab && listPage.entries.any { !it.isActive }
            findItem(R.id.silent_block_mark_all_blocked).isVisible = isListTab && listPage.entries.any { it.isActive }
            findItem(R.id.silent_block_clear_history).isVisible = !isListTab && historyPage.calls.isNotEmpty()
        }
    }

    private fun setupTabs(selectedTab: Int) {
        binding.silentBlockTabs.apply {
            addTab(newTab().setText(R.string.silent_block_list_tab))
            addTab(newTab().setText(R.string.silent_block_history_tab))
            addTab(newTab().setText(R.string.silent_block_settings_tab))
            onTabSelectionChanged(tabSelectedAction = { showTab(it) })
            getTabAt(selectedTab)?.select()
            showTab(getTabAt(selectedTabPosition))
        }
    }

    private fun showTab(tab: TabLayout.Tab?) {
        val position = tab?.position ?: LIST_TAB
        binding.apply {
            silentBlockListPage.beVisibleIf(position == LIST_TAB)
            silentBlockHistoryPage.beVisibleIf(position == HISTORY_TAB)
            silentBlockSettingsPage.beVisibleIf(position == SETTINGS_TAB)
            when (position) {
                LIST_TAB -> setupMaterialScrollListener(silentBlockEntriesList, silentBlockAppbar)
                HISTORY_TAB -> setupMaterialScrollListener(silentBlockHistoryList, silentBlockAppbar)
                else -> setupMaterialScrollListener(silentBlockSettingsPage, silentBlockAppbar)
            }

            if (position == LIST_TAB) {
                silentBlockFab.show()
            } else {
                silentBlockFab.hide()
            }
        }
        refreshMenuItems()
    }

    // queued behind pending changes, so it always shows their outcome
    @Suppress("TooGenericExceptionCaught")
    private fun refreshData() {
        SilentBlockExecutor.execute {
            try {
                val entries = SilentBlockEntriesRepository(this).getEntries()
                val calls = SilentBlockHistoryRepository(this).getCalls()
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        listPage.showEntries(entries)
                        historyPage.showCalls(calls)
                        refreshMenuItems()
                    }
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    companion object {
        private const val SELECTED_TAB = "selected_tab"
        private const val LIST_TAB = 0
        private const val HISTORY_TAB = 1
        private const val SETTINGS_TAB = 2
    }
}
