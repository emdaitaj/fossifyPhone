package org.fossify.phone.services

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getStateCompat
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.helpers.SilentBlockCallLogCleaner
import org.fossify.phone.helpers.SilentBlockRegistry
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private val silencedCalls = mutableSetOf<Call>()
    private val silenceHandler = Handler(Looper.getMainLooper())

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (isSilentlyBlocked(call)) {
            silenceCall(call)
            return
        }

        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority
            || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
            || !canUseFullScreenIntent()
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (silencedCalls.remove(call)) {
            SilentBlockRegistry.remove(call.details)
            // the system treated the call as a regular one, so it also wrote it into the call log as a missed call
            SilentBlockCallLogCleaner.scheduleCleanup(this, cancelMissedCallNotification = true)
            return
        }

        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        silenceHandler.removeCallbacksAndMessages(null)
        silencedCalls.clear()
        callNotificationManager.cancelNotification()
    }

    /**
     * Normally Telecom never hands a silently blocked call to the in-call service. Some modified systems ignore
     * the screening verdict though, so such calls are recognized here as well and kept away from the UI.
     */
    private fun isSilentlyBlocked(call: Call): Boolean {
        return !call.isOutgoing()
            && call.getStateCompat() == Call.STATE_RINGING
            && SilentBlockRegistry.isSilenced(call.details)
    }

    /**
     * Keeps the call ringing on the network without showing it: it's not handed to the [CallManager], so there is
     * no incoming call screen and no notification, and the ringtone is stopped. The caller keeps hearing the
     * ringback tone until the call times out.
     */
    private fun silenceCall(call: Call) {
        silencedCalls.add(call)
        silenceRinger()

        // the ringer might not have started yet when the call is added, make sure it gets silenced as well
        SILENCE_RETRY_DELAYS_MS.forEach { delay ->
            silenceHandler.postDelayed({
                if (call in silencedCalls && call.getStateCompat() == Call.STATE_RINGING) {
                    silenceRinger()
                }
            }, delay)
        }
    }

    // allowed for the default dialer, which the app always is when it receives calls
    @SuppressLint("MissingPermission")
    private fun silenceRinger() {
        try {
            telecomManager.silenceRinger()
        } catch (_: Exception) {
            // nothing else can be done, the call stays hidden at least
        }
    }

    companion object {
        private val SILENCE_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1500L, 3000L)
    }
}
