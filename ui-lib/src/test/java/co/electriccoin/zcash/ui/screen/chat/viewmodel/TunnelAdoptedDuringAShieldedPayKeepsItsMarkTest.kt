package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1522 (B1e verification, VERIFY_B1e.md item 1 "The residue I did find"; money-path rule 2).
 *
 * The contact moves the chat to Shielded (mark set), the user taps Pay, and while that message's proposal is being built
 * the contact's switch to Tunnel arrives and is adopted: our ZBOOT was never sent, so the adoption writes the mark again,
 * now for Tunnel. At b44c97418 the Shielded message's onSubmitStarting (ZC-1508) clears the mark regardless of the mode,
 * so the chat ends Tunnel with no mark and the next chat open sends our ZBOOT under the standing consent (on-chain when
 * the relays take nothing). Wanted: the submit clears only the mark its own question answered; a Tunnel adopted during
 * the build keeps its mark, and the next chat open with the relays at 0 acknowledgements pays nothing.
 *
 * Driven through the real send path ([RealModeStorePhone]; the seam delivers the Tunnel ZMODE inside the first proposal
 * request). Control: with no ZMODE during the build, the submit clears the mark as today. Every address and key is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelAdoptedDuringAShieldedPayKeepsItsMarkTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1522tunnelmarkpeer"
        val PEER_RELAY_KEY: String = "c1522".padEnd(64, 'e')
    }

    /** Runs inside the first proposal request (the build window of the paid Shielded message). */
    @Volatile private var duringBuild: (() -> Unit)? = null

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun mark(phone: RealModeStorePhone): Boolean = phone.real.getModeSwitchRecord(PEER)?.movedToPaidByPeer == true

    private fun zmode(
        vm: ChatViewModel,
        mode: ConversationMode,
        sinceMillis: Long,
    ) = invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(mode, sinceMillis, PEER))

    /** Keys exchanged, our ZBOOT never marked sent, the standing consent given; the contact moved the chat to Shielded. */
    private suspend fun movedToShielded(): Pair<RealModeStorePhone, ChatViewModel> {
        val phone =
            RealModeStorePhone(
                PEER,
                ConversationMode.TUNNEL,
                consent = true,
                exchangeComplete = true,
                relayKey = PEER_RELAY_KEY,
                seam = HandshakeSeam(PEER, onFirstAttempt = { duringBuild?.invoke() }),
            )
        phone.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
        phone.relaysUp()
        val vm = phone.viewModel()
        assertEquals("fixture: our ZBOOT was never marked sent", null, phone.store.getSentNostrBootPubkey(PEER))
        zmode(vm, ConversationMode.VAULT, System.currentTimeMillis() - 10_000L)
        awaitIdle(vm)
        assertEquals("fixture: the contact's switch to Shielded is adopted", ConversationMode.VAULT, phone.mode())
        assertTrue("fixture: the Shielded adoption set the moved-by-the-contact mark", mark(phone))
        phone.published.clear()
        return phone to vm
    }

    private suspend fun sendPayAndSubmit(
        phone: RealModeStorePhone,
        vm: ChatViewModel,
    ) {
        vm.sendMessage(PEER, "synthetic first message after the switch")
        awaitIdle(vm)
        val asked = state(vm)
        assertTrue("fixture: the first send asks with the price, was $asked", asked is SendMessageState.NeedsPaidActionConfirmation)
        assertEquals(
            "fixture: the question is the contact-moved-to-Shielded one",
            PaidAction.PEER_MOVED_TO_SHIELDED,
            (asked as SendMessageState.NeedsPaidActionConfirmation).confirmation.action,
        )
        vm.confirmPaidAction()
        awaitIdle(vm)
        PaymentHarness.eventually { phone.seam.submits.get() > 0 }
        awaitIdle(vm)
        assertEquals("fixture: the paid Shielded message was built once", 1, phone.seam.proposalRequests.get())
        assertEquals("fixture: the paid Shielded message was submitted once", 1, phone.seam.submits.get())
    }

    /** What AndroidChat's chat-open effect calls for this chat (as AdoptedTunnelNeverPaysOurZbootTest.openChat). */
    private fun openChat(
        vm: ChatViewModel,
        phone: RealModeStorePhone,
    ) {
        vm.askAgainForHandshakeConsent()
        vm.retryKexAckIfResponder(PEER)
        vm.retryOwedKeyExchange(PEER)
        vm.repairUnconfirmedRelayIdentity(PEER)
        if (phone.mode() != ConversationMode.VAULT) vm.ensureNostrBootstrapSent(PEER)
    }

    private fun relaysTakeNothing(phone: RealModeStorePhone) {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { memo, to ->
            phone.published += memo to to
            NostrChatBridge.PublishResult(0, null)
        }
    }

    private suspend fun tunnelAdoptedDuringThePaidBuild(): Pair<RealModeStorePhone, ChatViewModel> {
        val (phone, vm) = movedToShielded()
        var markAfterTunnelAdoption: Boolean? = null
        duringBuild = {
            duringBuild = null
            zmode(vm, ConversationMode.TUNNEL, System.currentTimeMillis())
            markAfterTunnelAdoption = mark(phone)
        }
        sendPayAndSubmit(phone, vm)
        assertEquals("fixture: the contact's switch to Tunnel was adopted during the build", ConversationMode.TUNNEL, phone.mode())
        assertEquals("fixture: the Tunnel adoption wrote the mark (our ZBOOT was never sent)", true, markAfterTunnelAdoption)
        return phone to vm
    }

    @Test
    fun `a Tunnel adopted while the paid Shielded message was being built keeps its moved-by-the-contact mark`() =
        runBlocking {
            val (phone, _) = tunnelAdoptedDuringThePaidBuild()

            assertTrue(
                "the Shielded message's submit cleared the mark a later Tunnel adoption wrote: the chat is Tunnel with no " +
                    "mark, so our ZBOOT pays under the standing consent (ZC-1522; money-path rule 2)",
                mark(phone),
            )
        }

    @Test
    fun `after that race the next chat open with the relays taking nothing pays no ZBOOT`() =
        runBlocking {
            val (phone, vm) = tunnelAdoptedDuringThePaidBuild()
            relaysTakeNothing(phone)

            openChat(vm, phone)
            awaitIdle(vm)
            PaymentHarness.eventually { phone.seam.proposalRequests.get() > 1 }

            assertEquals(
                "a chat open after the race paid our ZBOOT by itself (the adopted Tunnel lost its mark; ZC-1522, W-122)",
                1,
                phone.seam.proposalRequests.get(),
            )
            assertEquals(1, phone.seam.submits.get())
        }

    @Test
    fun `control - with no switch during the build the paid submit clears the mark`() =
        runBlocking {
            val (phone, vm) = movedToShielded()
            sendPayAndSubmit(phone, vm)

            assertEquals("fixture: the chat stays Shielded", ConversationMode.VAULT, phone.mode())
            assertFalse("the Pay's submit answered the question: the mark is cleared (ZC-1508)", mark(phone))
        }
}
