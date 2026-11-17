package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ZC-739-R3 (review of T7bH round 2, 20 Oct 2026, finding 2, P3) — ZC-739-R2 makes "Not their key" drop any reconnect
 * owed key exchange, to free the one an interrupted "Accept the new key" wrote for the refused key. A Reconnect the user
 * confirmed and paid for BEFORE the contact's new key arrived carries the same reason, so it is dropped too: the key
 * exchange the user quoted for the old key is never sent although that key is kept.
 *
 * Must hold: "Not their key" drops only what an accept wrote; a Reconnect quoted before the key change stays owed and is
 * paid once after the decision. [KeyChangeAcceptChat] in a Tunnel chat, standing consent given; every key and address
 * is synthetic.
 */
class NotTheirKeyKeepsAReconnectQuotedBeforeTheKeyChangeTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a Reconnect quoted before the key change stays owed after Not their key and is paid once`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL, consent = true)
            val vm = chat.viewModel()
            // Our current relay identity was delivered: no ZBOOT is due, only the quoted key exchange.
            chat.owed.store.setSentNostrBootPubkey(PEER, ZbootSetup.currentIdentity(vm))
            // The user confirmed a Reconnect earlier: its key exchange is owed with the reconnect reason.
            chat.owed.kexOwed += PEER
            chat.owed.kexOwedReason[PEER] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
            val refused = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, refused)
            assertNotNull("fixture: the refused key is not remembered", chat.record())
            assertEquals("fixture: something was paid while the new key waited", 0, chat.seam.submitted.size)

            vm.resetSendState()
            KeyAcceptApi.dismiss(vm, PEER, refused.publicKey)
            chat.settle(vm)

            assertFalse("fixture: Not their key did not take the warning down", chat.flag())
            assertEquals(
                "the Reconnect the user quoted before the key change was dropped by Not their key: ${chat.seam.submitted}",
                listOf("KEX"),
                chat.seam.submitted.toList(),
            )
        }

    private companion object {
        const val PEER = "usyntheticzc739r3reconnect01"
    }
}
