package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone matrix 1 Nov, Seeker. One price question at 00:35 covered the key exchange, the message and the relay identity.
 * The key exchange was paid, the message leg then stopped, and:
 * - ZC-1340: the row read "Failed, Not sent" with no word that a setup step of it was paid (Activity showed Sent 0.00017);
 * - ZC-1336: the second Retry finds no key exchange owed, so retryWithNewFigure counts no ZBOOT leg and stores none; when the
 *   contact answered, "Allow handshake transactions?" came up at 01:15 for a leg the question had covered.
 *
 * Real send path over [FundsConfirmingChat]. The message leg's refusal enters at the proposal data source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryThatPaidItsSetupStepTellsAndKeepsItsQuestionTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1336retrysetup01"
        const val TYPED = "synthetic first words whose setup step paid"
        const val BLOCKS_UNTIL_THE_QUEUE_GIVES_UP = 10
        val TELLS_SETUP = Regex("(?i)setup step|key exchange|handshake")
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

    private val legs = QuotedLegsOnDisk()

    /** The accepted row goes at a block; its key exchange is paid and its message leg is refused. */
    private suspend fun setupPaidMessageStopped(
        world: FundsConfirmingChat,
        vm: ChatViewModel,
    ): String {
        val row = world.acceptWhileConfirming(vm, TYPED)
        world.funds.feeFor = {
            if (world.seam.submits.get() >= 1) throw InsufficientFundsException(have = Zatoshi(0), need = Zatoshi(17_000))
            FundsConfirmingChat.NORMAL_FEE
        }
        world.plan = "Fundable"
        world.block(vm)
        world.paymentsSettled()
        assertEquals("fixture: the key exchange was not the one payment", 1, world.seam.submits.get())
        // Its message leg goes back to the queue and is refused again at each block, until the queue's retries end (phone: 9.5 min).
        var blocks = 0
        while (!rowStopped(world) && blocks++ < BLOCKS_UNTIL_THE_QUEUE_GIVES_UP) {
            world.block(vm)
            PaymentHarness.eventually(FundsConfirmingChat.QUIET_MS) { rowStopped(world) }
        }
        return row.id
    }

    private fun rowStopped(world: FundsConfirmingChat) = world.row()?.status == MessageStatus.FAILED.name

    @Test
    fun `a row whose setup step was paid says so`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val id = setupPaidMessageStopped(world, vm)

            assertTrue("fixture: the row did not stop: ${world.row()?.status}", rowStopped(world))
            val reason = world.row()?.failureReason.orEmpty()
            assertTrue("row $id reads as not sent with nothing about its paid setup step: $reason", TELLS_SETUP.containsMatchIn(reason))
        }

    private suspend fun retryAndConfirm(
        vm: ChatViewModel,
        id: String,
        world: FundsConfirmingChat,
        submitsBefore: Int,
    ) {
        vm.resetSendState()
        vm.retryMessage(PEER, id)
        PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
            HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation ||
                world.seam.submits.get() > submitsBefore
        }
        if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) vm.confirmPaidAction()
    }

    @Test
    fun `the phone's order - cold start failed the row, a Retry paid the key exchange, a second Retry paid the message`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            world.consent = false
            val before = world.screen().also(legs::over)
            val id = world.acceptWhileConfirming(before, TYPED).id

            val vm = world.restart(before).also(legs::over)
            // ZC-1335: a cold start no longer fails the row, so the row the phone had at this point is seeded.
            val waiting = requireNotNull(world.row()) { "fixture: the accepted row is gone after the restart" }
            world.chat.prefs.commitPendingMessage(waiting.copy(isPending = false, status = MessageStatus.FAILED.name))
            PaymentHarness.loadPendingMessagesFromPrefs(vm)
            assertTrue("fixture: the row is not stopped: ${world.row()?.status}", rowStopped(world))

            world.plan = "Fundable"
            world.funds.feeFor = {
                if (world.seam.submits.get() >= 1) throw InsufficientFundsException(have = Zatoshi(0), need = Zatoshi(17_000))
                FundsConfirmingChat.NORMAL_FEE
            }
            retryAndConfirm(vm, id, world, 0)
            assertTrue(
                "fixture: the first Retry paid no key exchange",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() == 1 },
            )
            var blocks = 0
            while (!rowStopped(world) && blocks++ < BLOCKS_UNTIL_THE_QUEUE_GIVES_UP) {
                world.block(vm)
                PaymentHarness.eventually(FundsConfirmingChat.QUIET_MS) { rowStopped(world) }
            }
            assertTrue("fixture: the row did not stop again: ${world.row()?.status}", rowStopped(world))

            world.funds.feeFor = { FundsConfirmingChat.NORMAL_FEE }
            retryAndConfirm(vm, id, world, 1)
            assertTrue(
                "fixture: the second Retry paid no message",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() == 2 },
            )

            assertTrue("the question covered three legs; after this Retry order the third has no allowance", PEER in legs.zbootQuoted)
        }

    @Test
    fun `the relay identity the one question covered stays covered through a second Retry`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            world.consent = false
            val vm = world.screen().also(legs::over)
            val id = setupPaidMessageStopped(world, vm)
            assertTrue("fixture: the row did not stop: ${world.row()?.status}", rowStopped(world))

            world.funds.feeFor = { FundsConfirmingChat.NORMAL_FEE }
            vm.resetSendState()
            vm.retryMessage(PEER, id)
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation || world.seam.submits.get() == 2
            }
            if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) vm.confirmPaidAction()
            assertTrue(
                "fixture: the message did not go on the Retry",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() == 2 },
            )

            assertTrue(
                "the question covered three legs, and after the Retry the third has no allowance: it will ask again",
                PEER in legs.zbootQuoted,
            )
        }
}
