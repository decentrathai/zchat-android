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
 * ZC-1990 (GPT review of fix/2130-msg, 17 Nov 2026; register high; money-path rule 2) — the ZC-1977 queue on two chat
 * screens of one process: screen A sent the first message to a new contact (its key exchange is being built) and queued the
 * second behind it; screen B, open on the same chat, shows the queued row, and the user deletes it there (B.hideMessage).
 * The send path's deleted-row re-checks (resumedRowDeleted via rowHiddenHere) read only A's own hiddenMessages, and the
 * stored row is only hidden (a queued SENDING row), so A's queue still sends and pays the deleted message, and after a failed
 * first key exchange pays that key exchange for it first.
 *
 * Wanted: a queued message the user deleted on any screen of the process pays nothing: not its message, not a key exchange.
 * The fix is meant to keep a process-wide set of the rows hidden in this process (hideMessage writes it, rowHiddenHere reads
 * it), so this test needs no store read on the send path; that set must be cleared by [LiveSenders.resetForTest] or
 * [AutoSpendVm.resetProcessState], which run before and after every test here. The control shows the same two screens with
 * no delete pay the key exchange and the message. Real ChatViewModel send, queue and delete over [FundsConfirmingChat];
 * every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedMessageDeletedOnAnotherScreenPaysNothingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1990deleteotherscreen01"
        const val FIRST = "synthetic first words that go"
        const val SECOND = "synthetic second words queued then deleted elsewhere"

        /** The first message's key exchange and the first message itself. */
        const val FIRST_SEND = 2
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
    fun `control - with no delete the queued message pays the key exchange the first one failed to pay`() =
        runBlocking {
            val (world, screens, _) = secondQueuedOnScreenA()
            try {
                world.funds.firstBuildGate.completeExceptionally(IllegalStateException("synthetic build failure"))
                assertTrue(
                    "fixture: the queued message did not pay the owed key exchange and itself " +
                        "(${world.seam.submits.get()} submits, ${HandshakeHarness.sendMessageState(screens.first)})",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= FIRST_SEND },
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    @Test
    fun `a queued message deleted on another screen is never paid`() =
        runBlocking {
            val (world, screens, queuedId) = secondQueuedOnScreenA()
            try {
                screens.second.hideMessage(queuedId)
                world.funds.firstBuildGate.complete(Unit)
                assertTrue(
                    "fixture: the first message was not paid (${world.seam.submits.get()} submits)",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= FIRST_SEND },
                )
                world.paymentsSettled()
                assertEquals(
                    "screen A's queue paid for a queued message the user deleted on screen B (submits beyond the first's two)",
                    FIRST_SEND,
                    world.seam.submits.get(),
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    @Test
    fun `a queued message deleted on another screen pays no key exchange when the first one failed to pay it`() =
        runBlocking {
            val (world, screens, queuedId) = secondQueuedOnScreenA()
            try {
                screens.second.hideMessage(queuedId)
                world.funds.firstBuildGate.completeExceptionally(IllegalStateException("synthetic build failure"))
                world.paymentsSettled()
                assertEquals(
                    "a key exchange (or the message) was paid for a queued message the user deleted on screen B ($queuedId)",
                    0,
                    world.seam.submits.get(),
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    /**
     * Screens A and B open on the chat. On A the first message is accepted with the plan Fundable and parks in its first
     * build (its key exchange's); the second, sent meanwhile on A, is queued behind it. Returns (A, B) and the queued row's id.
     */
    private suspend fun secondQueuedOnScreenA(): Triple<FundsConfirmingChat, Pair<ChatViewModel, ChatViewModel>, String> {
        val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
        world.funds.parkFirstBuild = true
        val a = world.screen().also(::canHideMessages)
        val b = world.screen().also(::canHideMessages)
        world.chat.typeAndSend(a, FIRST)
        world.question(a)
        a.confirmPaidAction()
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
        val firstId = world.row()!!.id
        assertEquals("fixture: nothing paid yet", 0, world.seam.submits.get())

        assertTrue("fixture: the second message was refused", a.sendMessage(PEER, SECOND))
        val queued =
            world.chat.prefs
                .getPendingMessages()
                .filter { it.peerAddress == PEER && it.isOutgoing && it.id != firstId }
        assertEquals("fixture: the second message is not one stored row", 1, queued.size)
        assertEquals("fixture: the second message is not queued on screen A", 1, queueSize(a))
        assertEquals("fixture: the second message is queued on screen B", 0, queueSize(b))
        assertEquals("fixture: the second message paid before the first", 0, world.seam.submits.get())
        return Triple(world, a to b, queued.single().id)
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
