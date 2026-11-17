package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1418: which "calls locked" banner a chat shows while its call channel is missing. Once the contact has answered
 * (the key exchange is complete) and our own next setup step (the on-chain ZBOOT) waits for ZEC, the banner says so,
 * with the figure, and offers no Reconnect: the contact did reply, and Reconnect is a paid re-send that does not help.
 * Reconnect is offered only while the contact really has not answered. Display only: what a setup step may pay is
 * decided, and asked, by ChatViewModel.
 */
object CallsLockedBanner {
    enum class Stage { NONE, WAITING_FOR_CONTACT, OWN_SETUP_NEEDS_FUNDS }

    fun stage(
        callsSupported: Boolean,
        hasNostrCallChannel: Boolean,
        contactAnswered: Boolean,
        ownSetupWaitsForFunds: Boolean,
    ): Stage =
        when {
            !callsSupported || hasNostrCallChannel -> Stage.NONE
            contactAnswered && ownSetupWaitsForFunds -> Stage.OWN_SETUP_NEEDS_FUNDS
            else -> Stage.WAITING_FOR_CONTACT
        }

    fun offersReconnect(stage: Stage): Boolean = stage == Stage.WAITING_FOR_CONTACT
}
