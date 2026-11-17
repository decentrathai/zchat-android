package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1977 (Fable review of fix/2130-msg, 16 Nov 2026; register medium; money-path rule 2) — two messages to a new contact:
 * the first is sending (its key exchange is being built), so the second is queued behind it (a stored SENDING row and a
 * messageQueue entry). The user deletes the queued one. The Delete never takes the entry out of messageQueue (a queued
 * SENDING row is only hidden; a removed one makes the run skip every ZC-1959 guard, as resumesStoredRow reads false), so
 * when the queue reaches the deleted entry it is sent and paid, and when the first message's key exchange failed, the
 * deleted entry pays that key exchange first.
 *
 * Wanted: a queued message the user deleted pays nothing: not its message, not a key exchange. The controls show the same
 * queue with no delete pays the queued message (and, after a failed first key exchange, the key exchange too). Real
 * ChatViewModel send, queue and delete over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedQueuedFirstMessagePaysNothingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1977deletequeued01"
        const val FIRST = "synthetic first words that go"
        const val SECOND = "synthetic second words queued then deleted"

        /** The first message's key exchange and the first message itself. */
        const val FIRST_SEND = 2

        /** The queued message alone (its key exchange went with the first message). */
        const val QUEUED_MESSAGE = 1
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
    fun `control - with no delete the queued message is paid after the first`() =
        runBlocking {
            val (world, _, _) = secondQueuedBehindTheFirstsKexBuild()
            try {
                world.funds.firstBuildGate.complete(Unit)
                assertTrue(
                    "fixture: the queue did not pay the queued message (${world.seam.submits.get()} submits)",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
                        world.seam.submits.get() >= FIRST_SEND + QUEUED_MESSAGE
                    },
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    @Test
    fun `a queued message the user deleted is never paid`() =
        runBlocking {
            val (world, vm, queuedId) = secondQueuedBehindTheFirstsKexBuild()
            try {
                vm.hideMessage(queuedId)
                world.funds.firstBuildGate.complete(Unit)
                assertTrue(
                    "fixture: the first message was not paid (${world.seam.submits.get()} submits)",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= FIRST_SEND },
                )
                world.paymentsSettled()
                assertEquals(
                    "the queue paid for a queued message the user deleted (submits beyond the first message's two)",
                    FIRST_SEND,
                    world.seam.submits.get(),
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    @Test
    fun `control - with no delete the queued message pays the key exchange the first one failed to pay`() =
        runBlocking {
            val (world, vm, _) = secondQueuedBehindTheFirstsKexBuild()
            try {
                world.funds.firstBuildGate.completeExceptionally(IllegalStateException("synthetic build failure"))
                assertTrue(
                    "fixture: the queued message did not pay the owed key exchange and itself " +
                        "(${world.seam.submits.get()} submits, ${HandshakeHarness.sendMessageState(vm)})",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= FIRST_SEND },
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    @Test
    fun `a queued message the user deleted pays no key exchange when the first one failed to pay it`() =
        runBlocking {
            val (world, vm, queuedId) = secondQueuedBehindTheFirstsKexBuild()
            try {
                vm.hideMessage(queuedId)
                world.funds.firstBuildGate.completeExceptionally(IllegalStateException("synthetic build failure"))
                world.paymentsSettled()
                assertEquals(
                    "a key exchange (or the message) was paid for a queued message the user deleted",
                    0,
                    world.seam.submits.get(),
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    /**
     * The first message is accepted with the plan Fundable and parks in its first build (its key exchange's); the second,
     * sent meanwhile, is queued behind it. Returns the queued row's id.
     */
    private suspend fun secondQueuedBehindTheFirstsKexBuild(): Triple<FundsConfirmingChat, ChatViewModel, String> {
        val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
        world.funds.parkFirstBuild = true
        val vm = world.screen().also(::canHideMessages)
        world.chat.typeAndSend(vm, FIRST)
        world.question(vm)
        vm.confirmPaidAction()
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
        val firstId = world.row()!!.id
        assertEquals("fixture: nothing paid yet", 0, world.seam.submits.get())

        assertTrue("fixture: the second message was refused", vm.sendMessage(PEER, SECOND))
        val queued =
            world.chat.prefs
                .getPendingMessages()
                .filter { it.peerAddress == PEER && it.isOutgoing && it.id != firstId }
        assertEquals("fixture: the second message is not one stored row", 1, queued.size)
        assertEquals("fixture: the second message is not queued", 1, queueSize(vm))
        assertEquals("fixture: the second message paid before the first", 0, world.seam.submits.get())
        return Triple(world, vm, queued.single().id)
    }

    private fun queueSize(vm: ChatViewModel): Int {
        val queue =
            ChatViewModel::class.java
                .getDeclaredField("messageQueue")
                .apply { isAccessible = true }
                .get(vm) as MutableList<*>
        return synchronized(queue) { queue.size }
    }
}
