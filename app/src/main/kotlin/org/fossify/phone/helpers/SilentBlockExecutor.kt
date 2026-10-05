package org.fossify.phone.helpers

import android.app.Activity
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
        executor.execute {
            try {
                action()
            } catch (_: Exception) {
                // a failing task must never take the app down, the callers report their errors themselves
            }
        }
    }

    /**
     * Runs [action] in order with other storage work, reports any error and then calls [onDone] on the UI thread.
     */
    @Suppress("TooGenericExceptionCaught")
    fun runUpdate(activity: Activity, action: () -> Unit, onDone: () -> Unit) {
        execute {
            try {
                action()
            } catch (e: Exception) {
                // e.g. storage errors or a contacts permission revoked meanwhile
                activity.showErrorToast(e)
            }

            activity.runOnUiThread(onDone)
        }
    }
}
