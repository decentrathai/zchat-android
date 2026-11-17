package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.restored
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.2 "Short because of confirming funds", review R2-4; t5b SPECS C-M2-8):
 * a first message whose plan is short only because Ironwood is still confirming is not a reason to add ZEC.
 *
 * Must hold (ZC-1271 replaced the refusal): the plan check answers WaitForFunds -> the price is asked; after Pay no
 * proposal, no owed-KEX mark, no key; the row is saved with the accepted total; no wait text and no text handed back: the
 * row waits for the funds. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageWaitsWhenFundsConfirmTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanwaitspeer00001"
        const val TYPED = "synthetic first words to a new contact"
    }

    @Before
    fun setUp() {
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
    }

    @Test
    fun `a first message whose funds are still confirming waits and pays nothing`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("WaitForFunds"))

            chat.typeAndSend(vm, TYPED)
            val asked = chat.asked(vm)
            assertTrue(asked, "the user was not asked the price while the plan waits for funds to confirm")
            val state = HandshakeHarness.sendMessageState(vm) as SendMessageState.NeedsPaidActionConfirmation
            val acceptedTotal = state.confirmation.totalCost.value
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "a first message whose funds are still confirming paid something")
            assertFalse(chat.owedMarkWritten(), "the owed-KEX mark was written")
            assertFalse(chat.keyCreated(), "our key for the contact was created")
            val row = chat.prefs.getPendingMessages().firstOrNull { it.peerAddress == PEER && it.isOutgoing }
            assertNotNull(row, "the accepted first message was not saved as a row")
            assertEquals(acceptedTotal, FundsConfirmingChat.acceptedPrice(row), "the saved row does not hold the accepted price")
            assertNotEquals(CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE, error(vm), "the send still refuses")
            assertNull(restored(vm), "an accepted message was handed back to the input")
        }
}
