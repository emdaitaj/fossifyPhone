package org.fossify.phone.helpers

import android.telecom.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory list of calls the screening service decided to block silently.
 *
 * Telecom never hands a disallowed call to the in-call service, but some modified systems ignore the screening
 * verdict. The in-call service checks this registry, so even then such a call is neither shown nor rung.
 */
object SilentBlockRegistry {
    private const val ENTRY_LIFETIME_MS = 10 * 60 * 1000L

    private val silencedCalls = ConcurrentHashMap<String, Long>()

    fun markSilenced(details: Call.Details) {
        val now = System.currentTimeMillis()
        silencedCalls.entries.removeAll { it.value < now }
        silencedCalls[getKey(details)] = now + ENTRY_LIFETIME_MS
    }

    fun isSilenced(details: Call.Details): Boolean {
        val expiresAt = silencedCalls[getKey(details)] ?: return false
        return expiresAt >= System.currentTimeMillis()
    }

    fun remove(details: Call.Details) {
        silencedCalls.remove(getKey(details))
    }

    // Call.Details of the screening service and of the in-call service describe the same Telecom call,
    // so the creation time and the caller's number identify it reliably
    private fun getKey(details: Call.Details): String {
        val number = SilentBlockNumberMatcher.normalize(details.handle?.schemeSpecificPart.orEmpty())
        return "${details.creationTimeMillis}|$number"
    }
}
