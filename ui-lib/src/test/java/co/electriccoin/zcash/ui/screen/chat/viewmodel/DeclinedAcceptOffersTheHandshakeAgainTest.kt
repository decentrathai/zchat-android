package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1047 (medium, money; T7aF phone run 19 Oct, M3 on the Fold) — after "Not now" on a paid handshake there is no way
 * to finish it later, and the sender's payment for its key exchange is stranded.
 *
 * On the phone: a Tunnel chat held the contact's paid key exchange ("Key exchange in this chat — tap Accept"). Accept
 * cleared the hold and asked "Allow handshake transactions?"; the user chose "Not now". The banner was gone, the
 * question never came back (not after reopening, not after a restart), and nothing in the app could answer the key
 * exchange any more.
 *
 * Wanted: an Accept whose answer sent nothing offers the held key exchange again (the hold comes back, so the Accept
 * banner shows), a new Accept asks the price question again, and nothing is paid without it; Allow then pays exactly
 * the one answer. Every address, key and transaction id is synthetic.
 */
class DeclinedAcceptOffersTheHandshakeAgainTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun asked(vm: ChatViewModel): Boolean = HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent

    @Test
    fun `Not now on Accept's question offers the key exchange again and a new Accept asks again`() =
        runBlocking {
            val world =
                LedgerWorld(mode = ConversationMode.TUNNEL, consent = false, accountSource = OpenChatTunnelOfferPhone.funded())
            val phone = OpenChatTunnelOfferPhone(world)
            val contactKey = phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            assertTrue("fixture: the Accept banner is not shown", vm.isInboundKexAwaitingAccept(world.peer))

            vm.acceptInboundKexRequest(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertTrue("fixture: Accept did not ask the handshake price question", asked(vm))
            // "Not now".
            vm.dismissOrchardShieldingWarning()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Not now paid", 0, world.seam.proposals.get())
            assertTrue(
                "after Not now the held key exchange is offered nowhere: the Accept banner is gone and nothing asks again",
                vm.isInboundKexAwaitingAccept(world.peer),
            )

            vm.acceptInboundKexRequest(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertTrue("a new Accept after Not now did not ask the price question again", asked(vm))
            assertEquals("a new Accept paid before its question was answered", 0, world.seam.proposals.get())

            vm.grantHandshakeSpendConsent()
            HandshakeHarness.awaitIdle(vm)
            assertEquals("Allow did not pay exactly the one answer", 1, world.seam.proposals.get())
            assertEquals("the answer is for the held key", contactKey, world.prefs.sentKexAck[world.peer])
        }
}
