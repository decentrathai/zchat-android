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
 * ZC-1508 (B1a cross-family review grok-1; VERIFY_B1a.md item 5; FIX_PLAN REVISION 7).
 *
 * The contact moved the chat to Shielded and the first send asks once with the price (M1). At e626e879d the Pay clears
 * the "moved by the contact" mark (ZchatPreferences.setModeMovedToPaidByPeer(false)) the moment priceAccepted is true,
 * before anything is built or submitted. When that send then submits nothing (here: its proposal cannot be built), the
 * "ask once" protection is gone and the next send pays with no question. Wanted: the mark is cleared only when that
 * send's proposal is submitted (onSubmitStarting), so a Pay that submitted nothing leaves the next send asking.
 *
 * Driven through the real send path ([RealModeStorePhone]; the seam fails only the first proposal). Every address and
 * key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayThatSubmittedNothingKeepsThePriceQuestionTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1508keepmarkpeer1"
        val PEER_RELAY_KEY: String = "c1508".padEnd(64, 'e')
    }

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

    private suspend fun movedToShielded(seam: HandshakeSeam): Pair<RealModeStorePhone, ChatViewModel> {
        val phone =
            RealModeStorePhone(
                PEER,
                ConversationMode.TUNNEL,
                consent = true,
                exchangeComplete = true,
                relayKey = PEER_RELAY_KEY,
                seam = seam,
            )
        phone.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
        phone.relaysUp()
        val vm = phone.viewModel()
        invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
        awaitIdle(vm)
        assertEquals("fixture: the contact's switch to Shielded is adopted", ConversationMode.VAULT, phone.mode())
        phone.published.clear()
        return phone to vm
    }

    private suspend fun sendAndPay(
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
        PaymentHarness.eventually { phone.seam.proposalRequests.get() > 0 }
        awaitIdle(vm)
    }

    @Test
    fun `a Pay whose send submitted nothing leaves the next send asking with the price`() =
        runBlocking {
            val buildFails = IllegalStateException("synthetic: the proposal could not be built")
            val (phone, vm) = movedToShielded(HandshakeSeam(PEER, failFirstProposalWith = buildFails))
            sendAndPay(phone, vm)
            assertEquals("fixture: the paid send's proposal was attempted", 1, phone.seam.proposalRequests.get())
            assertEquals("fixture: nothing was submitted", 0, phone.seam.submits.get())

            vm.sendMessage(PEER, "synthetic second message")
            awaitIdle(vm)

            assertTrue(
                "after a Pay that submitted nothing the next send must still ask with the price (M1: the mark is cleared " +
                    "only when that send is submitted); was ${state(vm)}, proposals ${phone.seam.proposalRequests.get()}",
                state(vm) is SendMessageState.NeedsPaidActionConfirmation,
            )
            assertEquals("the second send must build nothing before the answer", 1, phone.seam.proposalRequests.get())
        }

    @Test
    fun `control - after a Pay that submitted the message the next send does not ask again`() =
        runBlocking {
            val (phone, vm) = movedToShielded(HandshakeSeam(PEER))
            sendAndPay(phone, vm)
            PaymentHarness.eventually { phone.seam.submits.get() > 0 }
            assertEquals("fixture: the paid send was submitted once", 1, phone.seam.submits.get())

            vm.sendMessage(PEER, "synthetic second message")
            awaitIdle(vm)

            assertFalse("the price question is asked once", state(vm) is SendMessageState.NeedsPaidActionConfirmation)
        }
}
