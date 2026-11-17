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
 * ZC-1993 (Fable review of d7caa3e59, 17 Nov 2026, finding 2; register medium; money-path rule 2) — the message leg has no
 * deleted-row check for a row the Delete only hides (a SENDING row is not removed); the only gate before its submit,
 * recordSubmitStarted, catches a row removed from disk. So:
 * (A) a queued message whose key exchange another message already paid, deleted while its own proposal builds, is still
 *     submitted and paid;
 * (B) a retried first message deleted after its key exchange went out, before its message's submit, is still submitted.
 *
 * Wanted: a message the user deleted before its submit is never submitted (0 message submits for it). The controls show the
 * same held builds with no delete pay the message. Real ChatViewModel send, queue, Retry and delete over
 * [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDeletedWhileItsProposalBuildsIsNotSubmittedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1993deletemessagebuild01"
        const val FIRST = "synthetic first words that go"
        const val SECOND = "synthetic second words deleted while built"
        const val RETRIED = "synthetic first words failed retried deleted"

        /** The first message's key exchange and the first message itself. */
        const val FIRST_SEND = 2

        /** Builds: 1 the first's key exchange, 2 the first message, 3 the queued message. */
        const val QUEUED_MESSAGE_BUILD = 3

        /** Builds: 1 the failed run's key exchange, 2 the Retry's key exchange, 3 the Retry's message. */
        const val RETRIED_MESSAGE_BUILD = 3

        /** The Retry's key exchange alone. */
        const val RETRY_KEX = 1
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
    fun `control - with no delete the queued message held in its build is paid`() =
        runBlocking {
            val (world, _, _) = queuedMessageHeldInItsBuild()
            try {
                world.funds.buildGate.complete(Unit)
                assertTrue(
                    "fixture: the queued message was not paid (${world.seam.submits.get()} submits)",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= FIRST_SEND + 1 },
                )
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    @Test
    fun `a queued message deleted while its proposal builds is not submitted`() =
        runBlocking {
            val (world, vm, queuedId) = queuedMessageHeldInItsBuild()
            try {
                vm.hideMessage(queuedId)
                world.funds.buildGate.complete(Unit)
                world.paymentsSettled()
                assertEquals(
                    "a queued message the user deleted while its proposal was built was submitted (submits beyond the first's two)",
                    FIRST_SEND,
                    world.seam.submits.get(),
                )
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    @Test
    fun `control - with no delete the retried first message held in its message build is paid`() =
        runBlocking {
            val (world, vm, _) = retriedFirstMessageHeldAt(PEER, RETRIED, RETRIED_MESSAGE_BUILD)
            try {
                world.funds.buildGate.complete(Unit)
                assertTrue(
                    "fixture: the Retry did not pay its key exchange and message " +
                        "(${world.seam.submits.get()} submits, ${HandshakeHarness.sendMessageState(vm)})",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= RETRY_KEX + 1 },
                )
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    @Test
    fun `a retried first message deleted after its key exchange went out is not submitted`() =
        runBlocking {
            val (world, vm, rowId) = retriedFirstMessageHeldAt(PEER, RETRIED, RETRIED_MESSAGE_BUILD)
            try {
                assertEquals("fixture: the Retry's key exchange was not paid before its message build", RETRY_KEX, world.seam.submits.get())
                vm.hideMessage(rowId)
                world.funds.buildGate.complete(Unit)
                world.paymentsSettled()
                assertEquals(
                    "a retried first message the user deleted after its key exchange went out was submitted",
                    RETRY_KEX,
                    world.seam.submits.get(),
                )
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    /**
     * The ZC-1977 queue: the first message parks in its key exchange build, the second is queued behind it; then the first
     * goes on and the queued message's own build (build 3) is held. Returns the queued row's id once it is in that build.
     */
    private suspend fun queuedMessageHeldInItsBuild(): Triple<FundsConfirmingChat, ChatViewModel, String> {
        val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
        world.funds.parkFirstBuild = true
        world.funds.parkBuild = QUEUED_MESSAGE_BUILD
        val vm = world.screen().also(::canHideMessages)
        world.chat.typeAndSend(vm, FIRST)
        world.question(vm)
        vm.confirmPaidAction()
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
        val firstId = world.row()!!.id
        assertTrue("fixture: the second message was refused", vm.sendMessage(PEER, SECOND))
        val queued =
            world.chat.prefs
                .getPendingMessages()
                .filter { it.peerAddress == PEER && it.isOutgoing && it.id != firstId }
        assertEquals("fixture: the second message is not one stored row", 1, queued.size)

        world.funds.firstBuildGate.complete(Unit)
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.buildEntered.await() }
        assertEquals("fixture: the first message was not paid before the queued one's build", FIRST_SEND, world.seam.submits.get())
        assertTrue("fixture: the held build is not the queued row's send", LiveSenders.isLive(queued.single().id))
        return Triple(world, vm, queued.single().id)
    }
}
