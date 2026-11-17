package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1506 (B1a cross-family review grok-3; VERIFY_B1a.md item 3; FIX_PLAN REVISION 7; money-path rule 1).
 *
 * The contact moved the chat to Shielded; the first send asks once with "its price" (PaidAction.PEER_MOVED_TO_SHIELDED,
 * totalCost = onChainSendMinimumCost). At e626e879d the Pay calls sendMessage(costConfirmed = true) with no ceiling, so
 * a proposal that costs more than the figure on screen (for example a wallet of many small notes: more inputs, a higher
 * network fee) is submitted anyway. Wanted: the Pay carries a ceiling equal to the shown figure; a proposal whose total
 * exceeds it stops with ChatSendCopy.FAILED_ABOVE_ACCEPTED_PRICE (the existing state) and nothing is submitted.
 *
 * Zero-spend guard plus a paying control (the same fixture with the proposal at its usual cost pays once). Every
 * address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerMovedToShieldedPayStopsAboveTheShownPriceTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1506ceilingpeer01"
        val PEER_RELAY_KEY: String = "c1506".padEnd(64, 'e')

        // Far above any figure the question can show for one short message (outputs + platform fee + ZIP-317 fee).
        const val NETWORK_FEE_FAR_ABOVE_THE_FIGURE = 2_000_000L
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

    /** A Tunnel chat the contact (2.13) just moved to Shielded; the first send's price question is on screen. */
    private suspend fun askedAfterTheContactMovedTheChat(proposalFee: Long?): Triple<RealModeStorePhone, ChatViewModel, Long> {
        val phone = RealModeStorePhone(PEER, ConversationMode.TUNNEL, consent = true, exchangeComplete = true, relayKey = PEER_RELAY_KEY)
        phone.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
        phone.relaysUp()
        val vm = phone.viewModel()
        invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
        awaitIdle(vm)
        assertEquals("fixture: the contact's switch to Shielded is adopted", ConversationMode.VAULT, phone.mode())
        phone.seam.proposalFee = proposalFee

        vm.sendMessage(PEER, "synthetic first message after the switch")
        awaitIdle(vm)
        val asked = state(vm)
        assertTrue("fixture: the first send asks with the price, was $asked", asked is SendMessageState.NeedsPaidActionConfirmation)
        asked as SendMessageState.NeedsPaidActionConfirmation
        assertEquals(
            "fixture: the question is the contact-moved-to-Shielded one",
            PaidAction.PEER_MOVED_TO_SHIELDED,
            asked.confirmation.action,
        )
        assertEquals("fixture: nothing is built before Pay", 0, phone.seam.proposalRequests.get())
        return Triple(phone, vm, asked.confirmation.totalCost.value)
    }

    private fun rows(vm: ChatViewModel): List<ChatMessage> = PaymentHarness.pendingMessages(vm)

    @Test
    fun `Pay never submits a Shielded message whose proposal costs more than the figure shown`() =
        runBlocking {
            val (phone, vm, shown) = askedAfterTheContactMovedTheChat(proposalFee = NETWORK_FEE_FAR_ABOVE_THE_FIGURE)
            assertTrue(
                "fixture: the proposal's network fee alone is above the figure shown ($shown)",
                NETWORK_FEE_FAR_ABOVE_THE_FIGURE > shown,
            )

            vm.confirmPaidAction()
            awaitIdle(vm)
            PaymentHarness.eventually { phone.seam.proposalRequests.get() > 0 }
            awaitIdle(vm)

            assertEquals(
                "Pay submitted a proposal above the figure the question showed ($shown zat); money-path rule 1",
                0,
                phone.seam.submits.get(),
            )
            assertTrue(
                "the message must stop with \"${ChatSendCopy.FAILED_ABOVE_ACCEPTED_PRICE}\"; rows ${rows(vm).map { it.failureReason }}",
                rows(vm).any { it.failureReason == ChatSendCopy.FAILED_ABOVE_ACCEPTED_PRICE },
            )
        }

    @Test
    fun `control - Pay at the usual cost submits the message once`() =
        runBlocking {
            val (phone, vm, _) = askedAfterTheContactMovedTheChat(proposalFee = null)

            vm.confirmPaidAction()
            awaitIdle(vm)
            PaymentHarness.eventually { phone.seam.submits.get() > 0 }

            assertEquals("fixture: Pay at the usual cost pays once", 1, phone.seam.submits.get())
        }
}
