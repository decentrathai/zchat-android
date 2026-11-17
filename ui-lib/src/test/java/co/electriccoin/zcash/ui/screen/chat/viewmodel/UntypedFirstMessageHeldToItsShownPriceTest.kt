package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1396 (money-path rule 1: never beyond the quote): the first-message question states "At most … your message at M".
 * The price the Pay accepted is recorded (acceptedFirstMessages) only when mayWaitForFunds, which needs
 * restoreTextOnCancel. A first send that is not typed text — the Retry of a Tunnel row switched to Shielded
 * (resendSwitchedRowShielded passes restoreTextOnCancel = false) — has no message ceiling, so a message leg dearer than
 * the question (a multi-note wallet) is paid above the shown figure with no new question.
 *
 * Must hold: on that path a message leg above the figure the question showed pays nothing (or asks again with the new
 * figure); the total paid never exceeds the shown total. The control (typed text, the same wallet) is green at HEAD.
 * Real ChatViewModel send over [FundsConfirmingChat]; the seam is sendMessage's restoreTextOnCancel flag, the one
 * difference resendSwitchedRowShielded makes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UntypedFirstMessageHeldToItsShownPriceTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1396untypedfirst1"
        const val TEXT = "synthetic words of a Tunnel row switched to Shielded"

        /** The note left after the key exchange costs the message far more than its shown leg. */
        const val FEE_ABOVE_THE_LEG = 100_000L
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `a first send that is not typed text never pays its message above the figure the question showed`() =
        runBlocking { dearerMessageLeg(restoreTextOnCancel = false) }

    @Test
    fun `control - typed text with the same dearer message leg pays nothing above the shown figure`() =
        runBlocking { dearerMessageLeg(restoreTextOnCancel = true) }

    private suspend fun dearerMessageLeg(restoreTextOnCancel: Boolean) {
        val world = FundsConfirmingChat(PEER)
        world.plan = "Fundable"
        val vm = world.screen()

        vm.sendMessage(PEER, TEXT, restoreTextOnCancel = restoreTextOnCancel)
        HandshakeHarness.awaitIdle(vm)
        val shown = world.question(vm)
        val messageLeg =
            (HandshakeHarness.sendMessageState(vm) as SendMessageState.NeedsPaidActionConfirmation).confirmation.messageLegCost?.value

        // The key exchange goes at its price; the message's build costs more than its shown leg.
        world.funds.feeFor = { if (world.seam.submits.get() == 0) FundsConfirmingChat.NORMAL_FEE else FEE_ABOVE_THE_LEG }
        vm.confirmPaidAction()
        HandshakeHarness.awaitIdle(vm)
        world.paymentsSettled()

        val paid = world.funds.paidTo(PEER)
        assertTrue(
            "paid $paid against a shown total of ${shown.second} (message leg shown at $messageLeg, " +
                "restoreTextOnCancel=$restoreTextOnCancel, payments ${world.funds.paid.map { it.second }})",
            paid <= shown.second,
        )
    }
}
