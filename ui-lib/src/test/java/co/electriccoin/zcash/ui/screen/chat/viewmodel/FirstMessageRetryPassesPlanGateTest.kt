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
import kotlin.test.assertNotNull

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC review R2-2; t5b SPECS C-M2-6): a Retry of a FAILED first message (nothing
 * submitted, no key of ours yet) asks no question and goes straight to doSendMessage, where the KEX is owed first. It
 * must meet the same gate: with the plan NotFundable, no proposal, no owed-KEX mark, no key; the row stays FAILED with
 * the row's refusal (ChatSendCopy.FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW, which speaks of Retry, not of the input box).
 * At HEAD the Retry pays the KEX. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageRetryPassesPlanGateTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanretrypeer00001"
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
    fun `a Retry of a first message whose plan cannot be funded pays nothing and keeps the row FAILED`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("NotFundable"))
            val id = "pending_${System.nanoTime()}"
            chat.seedFailedFirstMessage(vm, id, TYPED)

            vm.retryMessage(PEER, id)
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "the Retry paid the KEX of a plan the wallet cannot fund")
            assertFalse(chat.owedMarkWritten(), "the Retry wrote the owed-KEX mark")
            assertFalse(chat.keyCreated(), "the Retry created our key for the contact")
            val row = assertNotNull(chat.storedRow(id), "the row is gone from the store")
            assertEquals("FAILED", row.status, "the row is not FAILED on disk: $row")
            assertFalse(row.isPending, "the row is still pending on disk")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW"), row.failureReason, "the row does not say why")
            val onScreen = PaymentHarness.pendingMessages(vm).single { it.id == id }
            assertEquals("FAILED", onScreen.status.name, "the row is not FAILED on screen")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW"), error(vm))
        }
}
