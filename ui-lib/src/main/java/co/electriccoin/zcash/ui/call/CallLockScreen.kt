package co.electriccoin.zcash.ui.call

/**
 * ZC-1912 (owner W-148 "Call screen over lock"): the pure rules for an incoming call on a locked phone.
 * IncomingCallActivity, CallNotificationController, AndroidCallRingPlayer and FullScreenIntentPrompt ask them.
 */
object CallLockScreen {
    /** The call-only screen shows over the lock screen and turns the screen on only while a call rings or runs. */
    fun showOverLock(state: VoiceCallManager.CallState): Boolean =
        state is VoiceCallManager.CallState.Ringing ||
            state is VoiceCallManager.CallState.Dialling ||
            state is VoiceCallManager.CallState.Connecting ||
            state is VoiceCallManager.CallState.InCall

    /**
     * The card's Answer is the CallActionReceiver accept broadcast (answers without an unlock) for a voice call on a
     * locked phone that already allows the microphone. Otherwise it opens IncomingCallActivity: it can ask for the
     * microphone (ZC-1922) and the camera, and on an unlocked phone it brings the call screen forward (a receiver may
     * not open a screen from a notification on Android 12+).
     *
     * ZC-1964 (owner W-148 "Answer works for voice without unlocking"): also when the full-screen intent is not allowed
     * or unknown ([fullScreenIntentAllowed] no longer decides). An activity Answer makes SystemUI ask for the PIN first,
     * so on the HONOR the call rang out. ZC-1924's concern (a call connected with no Hang up when no call screen is over
     * the lock screen) is answered by the ongoing-call notification (OngoingCallNotice, showOngoing). The argument stays
     * so the card still states the permission it was posted with; decided when the card is posted.
     */
    @Suppress("UNUSED_PARAMETER")
    fun answerByBroadcast(
        isVideo: Boolean,
        micGranted: Boolean,
        keyguardLocked: Boolean,
        fullScreenIntentAllowed: Boolean?,
    ): Boolean = !isVideo && micGranted && keyguardLocked

    /**
     * True on Android 14+ (API 34) when the full-screen intent is not allowed (HONOR's default, owner test 16 Nov):
     * the call card then cannot open the call screen. Unknown ([canUseFullScreenIntent] null) is not treated as denied.
     */
    fun needsFullScreenIntentGuide(
        sdkInt: Int,
        canUseFullScreenIntent: Boolean?,
    ): Boolean = sdkInt >= API_34 && canUseFullScreenIntent == false

    /**
     * ZC-1951: the card's Answer answers only the call the card was posted for ([cardCallId]), and only while it still
     * rings. A missing id never answers (the call screen only opens), so a tap on call A's card cannot answer a call B
     * that rings seconds after A ended.
     */
    fun answerFromCard(
        cardCallId: String?,
        state: VoiceCallManager.CallState,
    ): Boolean = cardCallId != null && (state as? VoiceCallManager.CallState.Ringing)?.callId == cardCallId

    /**
     * ZC-1956: each call's Answer on the card is its own PendingIntent. Android matches PendingIntents by action, data,
     * type, component and categories, never by extras, so the call id is also the Answer intent's data; otherwise
     * FLAG_UPDATE_CURRENT rewrites a still-shown card's Answer to the next call's id.
     */
    fun answerIdentity(callId: String?): String? = callId?.let { "zchat-call:answer/$it" }

    /** ZC-1935: what the call screen does once the Answer permission request returns. */
    enum class AnswerAfterPermissions { ACCEPT, END_MIC_DENIED, IGNORE }

    /**
     * ZC-1935: the permission result acts only on the call whose Answer was tapped ([requestedCallId]) while it still
     * rings: answered with the microphone, ended ("Microphone permission needed", ZC-1922) without it. Any other call
     * that rings by then, or a call no longer ringing, is left alone. A refused camera never ends a video call: it is
     * answered with this phone's camera off (VoiceCallManager.acceptIncoming), which stays off until a tap (ZC-1904).
     */
    fun answerAfterPermissions(
        requestedCallId: String?,
        state: VoiceCallManager.CallState,
        micGranted: Boolean,
    ): AnswerAfterPermissions {
        val ringing = state as? VoiceCallManager.CallState.Ringing ?: return AnswerAfterPermissions.IGNORE
        if (requestedCallId == null || ringing.callId != requestedCallId) return AnswerAfterPermissions.IGNORE
        return if (micGranted) AnswerAfterPermissions.ACCEPT else AnswerAfterPermissions.END_MIC_DENIED
    }

    /**
     * ZC-1936: whether the microphone is allowed once the Answer permission request returns. The request asks only for
     * what is missing, so with the microphone already allowed a video Answer asks only for the camera and [result] has no
     * RECORD_AUDIO; the phone's own state ([micGrantedNow], from checkSelfPermission) decides, or the request's grant.
     */
    fun micGrantedAfterRequest(
        result: Map<String, Boolean>,
        micGrantedNow: Boolean,
    ): Boolean = micGrantedNow || result[RECORD_AUDIO] == true

    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val API_34 = 34
}
