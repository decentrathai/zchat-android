package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ZC-636 (T7B-HANDSHAKE-SPEC rev 2, section 2.4) — a Tunnel "Accept the new key" whose quote did not count our ZBOOT
 * (our current relay identity was delivered, and paid, less than an hour ago) runs Reset's wipe, which clears the
 * delivered-identity marker. Once the hour of that paid ZBOOT ends, the next block or chat open pays our ZBOOT again
 * under the standing consent: 17,000 zat outside the accept's quote, for an identity the contact already has.
 *
 * Must hold: after such an accept, the clock past the hour, blocks and a chat open with no relay pay 0 ZBOOTs. Control:
 * an accept whose quote counted our ZBOOT (none paid within the hour) pays it exactly once.
 *
 * [KeyChangeAcceptChat] in a Tunnel chat with the standing consent given; no relay publisher (every ZBOOT would go
 * on-chain). Every key and address is synthetic.
 */
class ATunnelAcceptPaysNoZbootItDidNotQuoteTest : KeyChangeAcceptTestBase() {
    private val zbootSlot = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)

    private suspend fun afterTheAccept(
        chat: KeyChangeAcceptChat,
        vm: ChatViewModel,
    ) {
        repeat(3) {
            chat.owed.tick(vm)
            chat.settle(vm)
        }
        chat.owed.openChat(vm)
        vm.ensureNostrBootstrapSent(PEER)
        chat.settle(vm)
    }

    @Test
    fun `a Tunnel accept quoted without our ZBOOT pays none after the hour of the last one`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL, consent = true)
            val vm = chat.viewModel()
            val identity = ZbootSetup.currentIdentity(vm)
            // Our current identity was delivered, and its ZBOOT paid, 59 minutes ago.
            chat.owed.store.setSentNostrBootPubkey(PEER, identity)
            chat.owed.paidRecords[zbootSlot] = HandshakePaidRecord.encode(identity, System.currentTimeMillis() - 59 * MINUTE)
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("fixture: the refused key is not remembered", chat.record())

            val asked = chat.accept(vm, newKeys.publicKey)
            assertEquals("fixture: the accept quotes only our key exchange", "KEY_CHANGE_RECONNECT" to 1, asked)
            chat.assertAdopted(newKeys, "after the accept")
            assertEquals("fixture: the accept paid its key exchange", listOf("KEX"), chat.seam.submitted.toList())

            // The hour of the earlier ZBOOT ends.
            chat.owed.paidRecords[zbootSlot] = HandshakePaidRecord.encode(identity, System.currentTimeMillis() - 61 * MINUTE)
            afterTheAccept(chat, vm)

            assertEquals("a ZBOOT the accept did not quote was paid: ${chat.seam.submitted}", 0, chat.seam.submittedOf("ZBOOT"))
        }

    @Test
    fun `control - a Tunnel accept that quoted our ZBOOT pays it exactly once`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL, consent = true)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)

            assertEquals("fixture: the accept quotes both", "KEY_CHANGE_RECONNECT" to 2, chat.accept(vm, newKeys.publicKey))
            afterTheAccept(chat, vm)

            assertEquals("the quoted ZBOOT was not paid exactly once: ${chat.seam.submitted}", 1, chat.seam.submittedOf("ZBOOT"))
        }

    private companion object {
        const val PEER = "usyntheticzc636tunnelaccept1"
        const val MINUTE = 60_000L
    }
}
