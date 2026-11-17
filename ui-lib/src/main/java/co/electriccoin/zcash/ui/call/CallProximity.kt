package co.electriccoin.zcash.ui.call

/**
 * ZC-1567: whether a call holds the proximity screen-off wake lock, which turns the screen off while the phone is at the
 * face so the ear cannot tap Mute or the call controls. Every live voice call whose sound comes from the phone itself:
 * the earpiece, and the loudspeaker too (a voice call starts there without a headset, and people still hold the phone to
 * the ear). Never for video (the user looks at the screen) and never on a headset (the phone is not at the ear). Pure.
 */
object CallProximity {
    fun screenOffNearFace(
        inCall: Boolean,
        isVideo: Boolean,
        route: VoiceCallManager.AudioRoute,
    ): Boolean =
        inCall &&
            !isVideo &&
            (route == VoiceCallManager.AudioRoute.EARPIECE || route == VoiceCallManager.AudioRoute.SPEAKER)
}
