package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.sendattempt.HandshakeOffer

/**
 * F8 (ZC-112): when the chat shows "Key exchange not sent" with Retry. Only while the chat has such a failed attempt,
 * and never beside a banner that asks for another decision first: a chat request waiting for Accept, or a changed
 * security key (its own banner decides what happens to the session). Display only: what Retry may pay is decided, and
 * asked, by ChatViewModel.retryHandshake.
 */
object HandshakeNotSentBanner {
    fun shows(
        offer: HandshakeOffer?,
        awaitingChatAccept: Boolean,
        isKeyChanged: Boolean,
    ): Boolean = offer != null && !awaitingChatAccept && !isKeyChanged
}
