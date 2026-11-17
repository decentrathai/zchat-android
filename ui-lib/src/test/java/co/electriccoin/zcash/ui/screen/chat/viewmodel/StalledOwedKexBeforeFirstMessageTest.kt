package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1289: ZC-1276's limit covers the message's own build. A first message pays our KEX first (payOwedKex, on the
 * process-wide send scope), with no limit around it: a KEX build that parks leaves the row Sending for good, whether the
 * chat stays open or was left, with nothing paid, and it holds the contact's owed-KEX lock, which a Retry would then wait
 * on for good.
 *
 * Must hold, within the hour StalledSendNeverStaysSendingTest uses: the row is not sent, with its reason and a working
 * Retry that pays the KEX and the message, and the stalled build waking up afterwards pays nothing more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StalledOwedKexBeforeFirstMessageTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usyntheticzc1289stalledkex01"
        const val TYPED = "synthetic first words behind a stalled key exchange"
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS
        const val ONE_HOUR_MS = 3_600_000L
        const val BOTH_LEGS = 2
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAcceptScope")
            .apply { isAccessible = true }
            .set(null, sendScopes.heldAcceptScope)
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
    fun `a first message whose key exchange stalled does not stay sending in a chat that stays open`() =
        runBlocking { stalledKexSettles(leave = false) }

    @Test
    fun `a first message whose key exchange stalled does not stay sending after the chat is left and opened again`() =
        runBlocking { stalledKexSettles(leave = true) }

    private suspend fun stalledKexSettles(leave: Boolean) {
        val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
        world.funds.parkFirstBuild = true
        val chat = world.screen()
        world.chat.typeAndSend(chat, TYPED)
        world.question(chat)
        chat.confirmPaidAction()
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
        val id = world.row()!!.id
        assertTrue("fixture sanity: the running send is live", LiveSenders.isLive(id))
        assertEquals("fixture sanity: nothing paid", 0, world.seam.submits.get())

        try {
            val shownOn =
                if (leave) {
                    world.leave(chat)
                    world.screen().also(PaymentHarness::loadPendingMessagesFromPrefs)
                } else {
                    chat
                }
            scheduler.advanceTimeBy(ONE_HOUR_MS)
            scheduler.runCurrent()

            val settled = PaymentHarness.eventually(SETTLE_MS) { world.shown(shownOn, id)?.status == MessageStatus.FAILED }
            val row = world.shown(shownOn, id)
            assertTrue(
                "after an hour a first message whose key exchange never built still shows " +
                    "${row?.status} (pending ${row?.isPending}, live ${LiveSenders.isLive(id)}, paid ${world.seam.submits.get()})",
                settled,
            )
            assertEquals("a stalled key exchange paid something", 0, world.seam.submits.get())
            assertFalse("a row shown as not sent is still in flight", row!!.isPending)
            assertFalse("a row shown as not sent does not say why", row.failureReason.isNullOrBlank())
            assertFalse("a row shown as not sent is still held live, so its Retry is refused", LiveSenders.isLive(id))

            // Retry with the old build still parked: it may not wait on that build's lock.
            assertTrue("Retry was refused for a row shown as not sent", shownOn.retryMessage(PEER, id))
            assertTrue(
                "Retry did not pay both legs: ${world.seam.submits.get()}",
                PaymentHarness.eventually(SETTLE_MS) { world.seam.submits.get() >= BOTH_LEGS },
            )

            world.funds.firstBuildGate.complete(Unit)
            assertTrue("the stalled build paid again after it woke up: ${world.seam.submits.get()}", world.staysAt(BOTH_LEGS))
        } finally {
            // Never left parked for the teardown: the fakes pay nothing real.
            world.funds.firstBuildGate.complete(Unit)
        }
    }
}
