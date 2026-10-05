package org.fossify.phone.services

import android.telecom.Call
import android.telecom.CallScreeningService
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.phone.helpers.SilentBlockMatch
import org.fossify.phone.helpers.SilentBlockRegistry
import org.fossify.phone.helpers.SilentBlocker

class SimpleCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // contact lookups can be slow, keep them off the main thread, Telecom waits up to 5 seconds for a response
        ensureBackgroundThread {
            val number = callDetails.handle?.schemeSpecificPart
            val isOnBlockList = number != null && isNumberBlocked(number)

            // numbers on the regular block list keep being rejected as before
            val silentBlockMatch = if (isOnBlockList) null else getSilentBlockMatch(callDetails, number)
            when {
                silentBlockMatch != null -> {
                    silentlyBlockCall(callDetails, silentBlockMatch)
                }

                isOnBlockList -> {
                    respondToCall(callDetails, isBlocked = true)
                }

                number != null && baseConfig.blockUnknownNumbers -> {
                    val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                    val result = SimpleContactsHelper(this).existsSync(number, privateCursor)
                    respondToCall(callDetails, isBlocked = result == ContactLookupResult.NotFound)
                }

                number == null && baseConfig.blockHiddenNumbers -> {
                    respondToCall(callDetails, isBlocked = true)
                }

                else -> {
                    respondToCall(callDetails, isBlocked = false)
                }
            }
        }
    }

    private fun getSilentBlockMatch(callDetails: Call.Details, number: String?): SilentBlockMatch? {
        if (isQPlus() && callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            return null
        }

        return try {
            SilentBlocker(this).evaluateIncomingCall(number, callDetails.handlePresentation)
        } catch (_: Exception) {
            // never let a storage problem block or lose a call
            null
        }
    }

    /**
     * Blocks the call without rejecting it: Telecom neither rings nor shows the call and doesn't post any
     * notification, while the network keeps the call alerting, so the caller hears the ringback tone
     * until the call times out naturally.
     */
    private fun silentlyBlockCall(callDetails: Call.Details, match: SilentBlockMatch) {
        SilentBlockRegistry.markSilenced(callDetails)

        val response = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(false)
            .setSkipCallLog(true)
            .setSkipNotification(true)
            .build()
        respondToCall(callDetails, response)

        try {
            val timestamp = callDetails.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
            SilentBlocker(this).recordBlockedCall(
                number = callDetails.handle?.schemeSpecificPart,
                timestamp = timestamp,
                match = match,
                phoneAccountId = callDetails.accountHandle?.id.orEmpty()
            )
        } catch (_: Exception) {
            // the call is blocked already, only the history record is missing
        }
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSkipCallLog(isBlocked)
            .setSkipNotification(isBlocked)
            .build()

        respondToCall(callDetails, response)
    }
}
