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

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.2 "Unchecked", §2.3): a plan check that cannot answer refuses (fail
 * closed): nothing paid, no question, no mark, no key, ChatSendCopy.FIRST_MESSAGE_PLAN_UNCHECKED, the text handed back.
 * Both when the check throws a non-shortfall error and when it answers Unchecked. At HEAD the question is asked and Pay
 * sends the KEX. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessagePlanCheckFailsClosedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanuncheckedpeer1"
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
    fun `a plan check that throws refuses and pays nothing`() =
        runBlocking {
            assertRefusedUnchecked(FirstMessagePlanApi.ScriptedCheck(throwing = IllegalStateException("synthetic probe failure")))
        }

    @Test
    fun `a plan check that answers Unchecked refuses and pays nothing`() =
        runBlocking {
            assertRefusedUnchecked(FirstMessagePlanApi.ScriptedCheck("Unchecked"))
        }

    private suspend fun assertRefusedUnchecked(check: FirstMessagePlanApi.ScriptedCheck) {
        val chat = FirstMessagePlanChat(PEER)
        val vm = chat.viewModel(check)

        chat.typeAndSend(vm, TYPED)
        val asked = chat.asked(vm)
        if (asked) {
            vm.confirmPaidAction()
            awaitIdle(vm)
        }

        assertEquals(0, chat.proposals(), "a first message whose plan could not be checked paid something")
        assertFalse(asked, "the user was asked to pay for a plan that could not be checked")
        assertFalse(chat.owedMarkWritten(), "the owed-KEX mark was written")
        assertFalse(chat.keyCreated(), "our key for the contact was created")
        assertEquals(copy("FIRST_MESSAGE_PLAN_UNCHECKED"), error(vm))
        assertEquals(PEER to TYPED, restored(vm), "the typed text was not handed back to the input")
    }
}
