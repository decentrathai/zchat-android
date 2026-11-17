package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-1041 (medium; T7aF cross-family review, GPT F3) — a stale accepted|K1 record shows an Accept banner whose Keep Open
 * flips a Tunnel chat to Open.
 *
 * The Tunnel offer for the contact's key K1 was accepted (the chat became Tunnel) and its answer has long left the
 * send-attempt ledger. Later the contact's key changed to K2 and K2 was answered the ordinary way. The record still says
 * accepted|K1, no hold is set, no entry names K1 and the sent marker names K2, so the screen shows the Tunnel offer
 * again for a key that is no longer the contact's; its Keep Open puts K1 back on the Accept hold and switches the
 * Tunnel chat to Open.
 *
 * Wanted: an accepted record for a key that is not the contact's current key shows nothing, and Keep Open changes
 * nothing. Every address, key and transaction id is synthetic.
 */
class StaleAcceptedTunnelOfferIsNotShownTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `an accepted record for a replaced key shows no offer and Keep Open leaves the Tunnel chat as it is`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.TUNNEL, accountSource = OpenChatTunnelOfferPhone.funded())
            val phone = OpenChatTunnelOfferPhone(world)
            val k1 = E2EEncryption.generateKeyPair().publicKey
            val k2 = E2EEncryption.generateKeyPair().publicKey
            phone.modes[world.peer] = ConversationMode.TUNNEL
            phone.offer[world.peer] = OpenChatTunnelOffer.accepted(k1)
            world.base.peerKeys[world.peer] = k2
            world.prefs.receivedKex[world.peer] = k2
            world.prefs.sentKexAck[world.peer] = k2
            val vm = phone.screen()

            assertEquals("a Tunnel offer is shown for a key that is no longer the contact's", "NONE", phone.stage(vm))

            phone.call(vm, "keepChatOpen")
            HandshakeHarness.awaitIdle(vm)
            assertEquals("Keep Open on a stale offer switched the Tunnel chat to Open", ConversationMode.TUNNEL, phone.modes[world.peer])
            assertNull("Keep Open put a replaced key back on the Accept hold", phone.hold[world.peer])
            assertEquals("nothing is paid", 0, world.seam.proposals.get())
        }
}
