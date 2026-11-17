package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.model.CallLogType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * QA2 Q2-74 (ZC-1657, owner B-6 under W-137): when the system incoming-call card is up. ZChat's own ringing screen
 * answers a call while the app is in front, so the card (with its own Decline/Answer) is posted only while the call
 * rings and the app is not in front; SyncForegroundService posts it when this turns true (also when the app goes to the
 * background mid-ring) and cancels it when it turns false.
 */
internal object IncomingCallNotice {
    fun wanted(
        state: VoiceCallManager.CallState,
        appInForeground: Boolean,
    ): Boolean = state is VoiceCallManager.CallState.Ringing && !appInForeground
}

/**
 * ZC-1964 (owner W-148): a call answered from the lock-screen card (or left by Home) has no call screen in front, so the
 * ongoing-call notification carries its Hang up (a CallActionReceiver broadcast, no unlock). Shown while the call is
 * connecting or live and our screen is not in front; cancelled otherwise.
 */
internal object OngoingCallNotice {
    fun wanted(
        state: VoiceCallManager.CallState,
        appInForeground: Boolean,
    ): Boolean =
        !appInForeground &&
            (state is VoiceCallManager.CallState.Connecting || state is VoiceCallManager.CallState.InCall)

    /**
     * ZC-1981: the Connecting or InCall state the ongoing card is for, or null for no card. The call screen is visible
     * only while it is shown ([callScreenShown], CallController) and the app is in front; the card is re-evaluated on
     * every change of any input, so Home or closing the call screen mid-call posts it without a call-state change.
     */
    fun cardFor(
        state: Flow<VoiceCallManager.CallState>,
        callScreenShown: Flow<Boolean>,
        appInForeground: Flow<Boolean>,
    ): Flow<VoiceCallManager.CallState?> =
        combine(state, callScreenShown, appInForeground) { st, shown, inFront ->
            st.takeIf { wanted(it, appInForeground = shown && inFront) }
        }.distinctUntilChanged()

    /** ZC-1982: what a tap on the ongoing card's Hang up does. */
    enum class HangUpTap { HANG_UP, IGNORE, CANCEL_CARD }

    /**
     * ZC-1982: a tap on the Hang up of the card posted for [cardCallId], with the call manager's [state] (null: no call
     * manager, e.g. after a process death). It ends only the card's own connecting or live call; another call that is
     * connecting or live is left alone (its own card stays); with no such call the stale card is cancelled.
     */
    fun hangUpTap(
        cardCallId: String?,
        state: VoiceCallManager.CallState?,
    ): HangUpTap {
        val liveCallId =
            when (state) {
                is VoiceCallManager.CallState.Connecting -> state.callId
                is VoiceCallManager.CallState.InCall -> state.callId
                else -> return HangUpTap.CANCEL_CARD
            }
        return if (cardCallId != null && cardCallId == liveCallId) HangUpTap.HANG_UP else HangUpTap.IGNORE
    }
}

/**
 * QA2 Q2-80 (ZC-1659): what a finished call is logged as. Extracted from SyncForegroundService.recordCallLog without a
 * change: a connected call is outgoing or incoming; a declined or busy one is declined; an outgoing one nobody answered
 * stays outgoing; an incoming one nobody answered is missed. A glare double set-up is internal noise: null, not logged.
 */
internal object CallLogClassifier {
    fun classify(
        reason: CallEndReason,
        outgoing: Boolean,
        connected: Boolean,
    ): CallLogType? {
        if (reason is CallEndReason.Glare) return null
        return when {
            connected -> if (outgoing) CallLogType.OUTGOING else CallLogType.INCOMING
            reason is CallEndReason.Declined || reason is CallEndReason.Busy -> CallLogType.DECLINED
            outgoing -> CallLogType.OUTGOING // no answer
            else -> CallLogType.MISSED
        }
    }
}

/**
 * QA2 Q2-80 (ZC-1659, owner B-7 under W-137): the notification of a call nobody answered while ZChat was not in front.
 * It follows the chat notifications' privacy setting: the caller's name for "full preview" and "sender only", only
 * "Missed call" for "new message", nothing for "silent".
 */
internal object MissedCallNotice {
    fun wanted(
        type: CallLogType,
        appInForeground: Boolean,
        privacy: NotificationPrivacy,
    ): Boolean = type == CallLogType.MISSED && !appInForeground && privacy != NotificationPrivacy.SILENT

    fun title(
        privacy: NotificationPrivacy,
        callerLabel: String,
        isVideo: Boolean,
    ): CallScreenLine? =
        when (privacy) {
            NotificationPrivacy.FULL_PREVIEW, NotificationPrivacy.SENDER_ONLY ->
                CallScreenLine(if (isVideo) R.string.call_missed_video_from else R.string.call_missed_from, listOf(callerLabel))
            NotificationPrivacy.NEW_MESSAGE ->
                CallScreenLine(if (isVideo) R.string.call_log_missed_video else R.string.call_log_missed)
            NotificationPrivacy.SILENT -> null
        }

    /** One id per contact, so a second missed call replaces the first and two contacts never replace each other's. */
    fun notificationId(peerAddress: String): Int {
        val id = "missed-call:$peerAddress".hashCode()
        return if (id == CallNotificationController.INCOMING_NOTIFICATION_ID) id + 1 else id
    }
}
