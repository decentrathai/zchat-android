package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1395 (money-path rule 2): a first message confirmed under account A while another send runs is queued. The user then
 * switches to account B; when the earlier send ends, processNextQueuedMessage runs doSendMessage for the row, which pays
 * our KEX and the message from whichever account is selected now (the owed-KEX account is even recorded as B). The row's
 * acceptedAccountKey is compared only by the funds-wait resume.
 *
 * Must hold: when the queue drains under B nothing is built or paid from B; the row waits or fails with a reason. With A
 * selected again its KEX and message are paid once each, by A. Real ChatViewModel send path over [AccountSwitchMoneyChat];
 * "another send runs" is the send state the queue reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedFirstMessagePaidOnlyByTheAccountThatConfirmedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1395queuedfirst01"
        const val TYPED = "synthetic first words queued behind a running send"
        const val SETTLE_MS = 2_000L
        const val QUIET_MS = 300L
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

    /** Asks under A, then confirms while another send runs: the confirmed first message is queued, nothing built. */
    private suspend fun confirmQueuedUnderA(chat: AccountSwitchMoneyChat): ChatViewModel {
        val vm = chat.viewModel()
        vm.sendMessage(PEER, TYPED)
        HandshakeHarness.awaitIdle(vm)
        assertTrue(
            "fixture sanity: the first message was not asked its price: ${HandshakeHarness.sendMessageState(vm)}",
            HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation,
        )
        // Another send started while the question was open.
        chat.sendState(vm).value = SendMessageState.Sending
        vm.confirmPaidAction()
        HandshakeHarness.awaitIdle(vm)
        assertNotNull("fixture sanity: the confirmed first message was not queued as a row", chat.row())
        assertEquals("fixture sanity: a queued message was built", 0, chat.seam.builds.size)
        return vm
    }

    @Test
    fun `a first message confirmed under A and queued is not paid by B when the queue drains, and A still pays it once`() =
        runBlocking {
            val chat = AccountSwitchMoneyChat(PEER)
            val vm = confirmQueuedUnderA(chat)
            val id = chat.row()!!.id

            chat.accounts.selectOtherZchat()
            chat.earlierSendEnds(vm)
            HandshakeHarness.awaitIdle(vm)
            PaymentHarness.eventually(QUIET_MS) { chat.seam.builds.isNotEmpty() }

            assertEquals(
                "account B paid a first message account A confirmed, when the queue drained: ${chat.seam.builds}",
                emptyList<AccountSwitchMoneyChat.Built>(),
                chat.builtBy(chat.keyB),
            )

            // Back on A: the row is paid once by A, by itself or by its Retry.
            chat.accounts.selectZchat()
            chat.tick(vm)
            HandshakeHarness.awaitIdle(vm)
            val row = chat.row()
            if (row != null && row.id == id && row.status == MessageStatus.FAILED.name) {
                vm.retryMessage(PEER, id)
                HandshakeHarness.awaitIdle(vm)
                if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) vm.confirmPaidAction()
            } else if (chat.sendState(vm).value !is SendMessageState.Sending) {
                chat.earlierSendEnds(vm)
            }
            HandshakeHarness.awaitIdle(vm)
            assertTrue(
                "account A did not pay its first message once back on A: ${chat.seam.builds}",
                PaymentHarness.eventually(SETTLE_MS) { chat.builtBy(chat.keyA).size >= 2 },
            )
            PaymentHarness.eventually(QUIET_MS) { chat.seam.builds.size > 2 }
            assertEquals(
                "not one KEX and one message from A: ${chat.seam.builds}",
                listOf(AccountSwitchMoneyChat.Built(chat.keyA, kex = true), AccountSwitchMoneyChat.Built(chat.keyA, kex = false)),
                chat.seam.builds.toList(),
            )
        }

    @Test
    fun `control - with no switch the queued first message is paid once by A when the queue drains`() =
        runBlocking {
            val chat = AccountSwitchMoneyChat(PEER)
            val vm = confirmQueuedUnderA(chat)

            chat.earlierSendEnds(vm)
            HandshakeHarness.awaitIdle(vm)
            assertTrue(
                "the queued first message was not paid: ${chat.seam.builds}",
                PaymentHarness.eventually(SETTLE_MS) { chat.seam.builds.size >= 2 },
            )
            PaymentHarness.eventually(QUIET_MS) { chat.seam.builds.size > 2 }
            assertEquals(
                listOf(AccountSwitchMoneyChat.Built(chat.keyA, kex = true), AccountSwitchMoneyChat.Built(chat.keyA, kex = false)),
                chat.seam.builds.toList(),
            )
        }
}
