package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.copy
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
import kotlin.test.assertTrue

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.2 "Where the gate lives", review R2-2; t5b SPECS C-M2-6): the check
 * before the question is a courtesy; the gate that decides runs in doSendMessage, before the owed-KEX mark. A note
 * spent while the question was open (Fundable when asked, NotFundable at the send) must still pay nothing: no
 * proposal, no mark, no key, no row left SENDING, the refusal shown and the typed text handed back. At HEAD Pay sends
 * the KEX. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessagePlanRecheckedAfterConfirmTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanrecheckpeer001"
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
    fun `a plan that stopped being fundable while the question was open pays nothing after Pay`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val check = FirstMessagePlanApi.ScriptedCheck("Fundable", "NotFundable")
            val vm = chat.viewModel(check)

            chat.typeAndSend(vm, TYPED)
            assertTrue(chat.asked(vm), "fixture sanity: a fundable first message is asked about")
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "the send paid after the plan stopped being fundable")
            assertFalse(chat.owedMarkWritten(), "the owed-KEX mark was written")
            assertFalse(chat.keyCreated(), "our key for the contact was created")
            val onScreen = PaymentHarness.pendingMessages(vm)
            assertTrue(onScreen.none { it.status.name == "SENDING" }, "a row was left SENDING on screen")
            val onDisk = chat.fixture.proxy.getPendingMessages()
            assertTrue(onDisk.none { it.status == "SENDING" || (it.isPending && it.status == null) }, "a row was left SENDING on disk")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE"), error(vm))
            assertEquals(PEER to TYPED, restored(vm), "the typed text was not handed back to the input")
            assertEquals(2, check.calls.size, "the send did not check the plan again after Pay")
        }
}
