package co.electriccoin.zcash.ui.screen.chat.model

/**
 * One rule for what a chat shows about calls. Call buttons: a call-capable mode or a held relay key (ZC-1880). The
 * "calls locked" banner: a call-capable mode only (CallsLockedBanner decides the stage). Neither on the ZChat Team chat
 * (ZC-1883, owner W-146). Pure.
 */
object ChatCallAffordance {
    fun buttonsShown(
        mode: ConversationMode,
        hasNostrCallChannel: Boolean,
        isTeamChat: Boolean,
    ): Boolean = !isTeamChat && (mode.supportsCalls || hasNostrCallChannel)

    fun bannerCallsSupported(
        mode: ConversationMode,
        isTeamChat: Boolean,
    ): Boolean = !isTeamChat && mode.supportsCalls
}
