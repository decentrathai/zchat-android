package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1992 (Fable review of d7caa3e59, 17 Nov 2026, finding 1; register high; money-path rule 2) — a first message to a new
 * contact failed (its key exchange's build failed), and it is the contact's only row. The user taps Retry: the claim stores
 * the row as SENDING with submitStartedAtMillis. The user deletes it while the retried key exchange is being built: Delete's
 * cancelKexOwedOnlyFor returns early (the row is neither waiting for funds nor FAILED), the row is only hidden, the run stops
 * for the hidden row (stopForDeletedRow), which handles only the ZBOOT quote. The owed-KEX mark stays, so the next tip's
 * retryOwedKeyExchanges pays the key exchange alone (payOwedKex(forSend = false)) for a message that no longer exists.
 *
 * Wanted: after the delete and two blocks nothing is paid (0 submits), no owed-KEX mark stands for the contact and no ZBOOT
 * allowance is left. The control shows the same Retry with no delete pays the key exchange and the message. Real ChatViewModel
 * send, Retry, block tick and delete over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetriedFirstMessageDeletedInItsKexBuildLeavesNoOwedKexTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1992retrydeletekex01"
        const val TYPED = "synthetic first words failed then retried"
        const val BLOCKS = 2

        /** The retried key exchange and the retried message. */
        const val RETRY_SEND = 2

        /** Build 1 was the failed first run's key exchange; build 2 is the Retry's key exchange. */
        const val RETRY_KEX_BUILD = 2
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
    fun `control - with no delete the retried first message pays its key exchange and itself`() =
        runBlocking {
            val (world, vm, _) = retryHeldInItsKexBuild(RETRY_KEX_BUILD)
            try {
                world.funds.buildGate.complete(Unit)
                assertTrue(
                    "fixture: the Retry did not pay the key exchange and the message " +
                        "(${world.seam.submits.get()} submits, ${HandshakeHarness.sendMessageState(vm)})",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= RETRY_SEND },
                )
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    @Test
    fun `a retried first message deleted while its key exchange builds leaves no owed key exchange to pay`() =
        runBlocking {
            val (world, vm, rowId) = retryHeldInItsKexBuild(RETRY_KEX_BUILD)
            try {
                vm.hideMessage(rowId)
                world.funds.buildGate.complete(Unit)
                world.paymentsSettled()
                assertEquals("the Retry paid for a message the user deleted in its key exchange build", 0, world.seam.submits.get())
                repeat(BLOCKS) {
                    world.block(vm)
                    world.paymentsSettled()
                }
                assertEquals(
                    "a later block paid a key exchange (or the message) for a first message the user deleted",
                    0,
                    world.seam.submits.get(),
                )
                assertFalse(
                    "an owed-KEX mark stands for a contact whose only message the user deleted (writes ${world.chat.kexOwedWrites})",
                    PEER in world.chat.kexOwed,
                )
                assertFalse("a ZBOOT allowance stands for the deleted message's contact", world.chat.prefs.isZbootQuoted(PEER))
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    /**
     * A first message accepted with the plan Fundable whose key exchange build fails: the row stands FAILED, the KEX owed.
     * Then Retry, with build [parkAt] held open. Returns the row's id once the Retry's run is in that build.
     */
    private suspend fun retryHeldInItsKexBuild(parkAt: Int): Triple<FundsConfirmingChat, ChatViewModel, String> =
        retriedFirstMessageHeldAt(PEER, TYPED, parkAt)
}

/**
 * ZC-1992 / ZC-1993: in [world] a first message to [peer] fails in its key exchange's build (build 1), so its row stands
 * FAILED with the KEX owed; then Retry (answered Pay when it asks), with build [parkAt] held open. Returns once the Retry's
 * run is in that build, its claim on the row.
 */
internal suspend fun retriedFirstMessageHeldAt(
    peer: String,
    typed: String,
    parkAt: Int,
): Triple<FundsConfirmingChat, ChatViewModel, String> {
    val world = FundsConfirmingChat(peer).apply { plan = "Fundable" }
    world.funds.parkFirstBuild = true
    val vm = world.screen().also(::canHideMessages)
    world.chat.typeAndSend(vm, typed)
    world.question(vm)
    vm.confirmPaidAction()
    withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
    world.funds.firstBuildGate.completeExceptionally(IllegalStateException("synthetic build failure"))
    val rowId = world.row()?.id
    assertNotNull("fixture: the first message left no row", rowId)
    assertTrue(
        "fixture: the failed first message's row is not FAILED in memory (${world.row()})",
        PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
            PaymentHarness.pendingMessages(vm).any { it.id == rowId && it.status == MessageStatus.FAILED }
        },
    )
    world.paymentsSettled()
    assertEquals("fixture: the failed first message paid something", 0, world.seam.submits.get())
    assertTrue("fixture: the failed first message left no owed KEX", peer in world.chat.kexOwed)

    world.funds.parkBuild = parkAt
    assertTrue("fixture: Retry was refused (${HandshakeHarness.sendMessageState(vm)})", vm.retryMessage(peer, rowId!!))
    if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) vm.confirmPaidAction()
    withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.buildEntered.await() }
    val claimed = world.row()
    assertTrue(
        "fixture: the Retry's row is not claimed as SENDING with its marker ($claimed)",
        claimed?.id == rowId && claimed.status == MessageStatus.SENDING.name && claimed.submitStartedAtMillis != null,
    )
    return Triple(world, vm, rowId)
}
