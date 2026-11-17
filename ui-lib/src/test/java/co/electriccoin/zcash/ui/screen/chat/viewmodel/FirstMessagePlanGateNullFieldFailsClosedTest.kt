package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.copy
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
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

/**
 * M2 (fix-all 2.13.0, train T5b; t5b SPECS §2 C-M2-2): ChatViewModels in tests are allocated without their constructor,
 * so a harness that forgot to set the plan check leaves the field null. The gate must read that as Unchecked and
 * refuse (fail closed, loudly), never as "fundable": no question, no proposal, no mark, no key. At HEAD there is no
 * gate and Pay sends the KEX. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessagePlanGateNullFieldFailsClosedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplannullfieldpeer1"
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
    fun `a ViewModel whose plan check was never set refuses a first message and pays nothing`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val vm = chat.viewModel(check = null, leaveCheckNull = true)

            chat.typeAndSend(vm, TYPED)
            val asked = chat.asked(vm)
            if (asked) {
                vm.confirmPaidAction()
                awaitIdle(vm)
            }

            assertEquals(0, chat.proposals(), "a ViewModel with no plan check paid for a first message")
            assertFalse(asked, "a ViewModel with no plan check asked the user to pay")
            assertFalse(chat.owedMarkWritten(), "the owed-KEX mark was written")
            assertFalse(chat.keyCreated(), "our key for the contact was created")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNCHECKED"), error(vm))
        }
}
