package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.common.sendattempt.HandshakeOffer
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakeNotSentBanner
import co.electriccoin.zcash.ui.screen.chat.viewmodel.LedgerSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112) — the "Key exchange not sent" notice: shown only for a chat with such a failed attempt, never beside a
 * banner that asks for another decision first, and its Retry hands back the marker the notice was drawn from.
 */
class HandshakeNotSentBannerTest {
    private val offer = HandshakeOffer(SendAttemptKind.KEX, startedAt = 42L)

    @Test
    fun `the notice shows only with an offer, and hides while Accept or the key-changed banner shows`() {
        assertTrue(HandshakeNotSentBanner.shows(offer, awaitingChatAccept = false, isKeyChanged = false))
        assertFalse("no failed attempt, no notice", HandshakeNotSentBanner.shows(null, awaitingChatAccept = false, isKeyChanged = false))
        assertFalse("the chat request comes first", HandshakeNotSentBanner.shows(offer, awaitingChatAccept = true, isKeyChanged = false))
        assertFalse("the changed key comes first", HandshakeNotSentBanner.shows(offer, awaitingChatAccept = false, isKeyChanged = true))
    }

    @Test
    fun `the view draws it with the shipped Retry label and hands back the marker`() {
        val view = LedgerSources.main("ui/screen/chat/view/ChatDetailView.kt")
        val banner =
            view.substring(
                view.indexOf(".shows(handshakeNotSent, awaitingChatAccept, isKeyChanged)"),
                view.indexOf("// Key-Changed Warning Banner"),
            )
        assertTrue(banner.contains("R.string.chat_handshake_not_sent_short"))
        assertTrue(banner.contains("R.string.chat_handshake_not_sent_full"))
        assertTrue("the Retry label is the shipped one", banner.contains("R.string.chat_retry"))
        assertTrue("Retry hands back the marker the notice was drawn from", banner.contains("onRetryHandshake(it.startedAt)"))
        assertTrue(
            "both parameters are defaulted",
            view.contains("HandshakeOffer? = null") && view.contains("onRetryHandshake: (Long) -> Unit = {}"),
        )

        val screen = LedgerSources.main("ui/screen/chat/AndroidChat.kt")
        assertTrue(screen.contains("handshakeNotSent = handshakeNotSent[peerAddress]"))
        assertTrue(screen.contains("viewModel.retryHandshake(peerAddress, offerStartedAt)"))
    }
}
