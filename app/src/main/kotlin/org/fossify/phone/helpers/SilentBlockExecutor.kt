package org.fossify.phone.helpers

import android.app.Activity
import android.database.SQLException
import org.fossify.commons.extensions.showErrorToast
import java.util.concurrent.Executors

/**
 * Runs silent block storage work of the UI on one background thread, strictly in submission order. Quick
 * consecutive changes, like toggling an entry twice, can never be applied out of order this way, and a reload
 * queued after a change always sees that change.
 */
object SilentBlockExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SilentBlockStorage").apply { isDaemon = true }
    }

    fun execute(action: () -> Unit) {
        executor.execute(action)
    }

    /**
     * Runs [action] in order with other storage work, reports storage errors and then calls [onDone] on the UI thread.
     */
    fun runUpdate(activity: Activity, action: () -> Unit, onDone: () -> Unit) {
        execute {
            try {
                action()
            } catch (e: SQLException) {
                activity.showErrorToast(e)
            }

            activity.runOnUiThread(onDone)
        }
    }
}
