package co.electriccoin.zcash.ui.screen.chat.viewmodel

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

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.3 control): a fundable plan is asked about as before and, after Pay,
 * the KEX and then the message are built (two proposals; the quoted ZBOOT waits for the contact's answer). Green before
 * and after; after the fix the check is asked twice (before the question and in the send). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageFundablePlanStillPaysBothTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanfundablepeer01"
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
    fun `control - a fundable first message is asked about and pays the KEX and the message`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val check = FirstMessagePlanApi.ScriptedCheck("Fundable")
            val vm = chat.viewModel(check)

            chat.typeAndSend(vm, TYPED)
            chat.fixture.assertAskedFirst(vm, messages = 3)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(2, chat.proposals(), "a fundable first message did not pay its KEX and its message")
            assertEquals(null, restored(vm), "a sent message was handed back to the input")
            if (FirstMessagePlanApi.hasField()) {
                assertEquals(2, check.calls.size, "the plan was not checked before the question and in the send")
            }
        }
}
