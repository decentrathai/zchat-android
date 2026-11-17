package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R7 (ZC-121, part B) — a setup message cancelled before it was handed to the SDK withdraws its own stamp at once.
 *
 * The key reply's marker and the key exchange's paid record are stamped before the proposal is built. Leaving the chat
 * while the wallet is still building the proposal cancels the send: nothing was handed to the SDK, so nothing can be on
 * the wire. Today the stamp stays (the cancel is rethrown with it), and no later chat open sends the key reply.
 *
 * Wanted: a cancel before the submit started withdraws this attempt's own stamp, so the next open pays the one key
 * reply. A cancel after the submit started keeps the stamp, as before (the transaction may be on the wire).
 *
 * Every address, key and transaction id is synthetic.
 */
class KexAckCancelledBeforeItsSubmitUnstampsAtOnceTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a key reply cancelled while its proposal is being built is unstamped at once, and the next open pays one`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.asResponder()
            val hold = CompletableDeferred<Unit>()
            world.seam.holdProposals = hold

            vm.retryKexAckIfResponder(world.peer)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { world.seam.proposalEntered.await() }
            assertTrue("fixture sanity: the marker was stamped before the proposal", world.prefs.sentKexAck.containsKey(world.peer))
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()

            assertTrue(
                "the send was cancelled before anything was handed to the SDK, but the key reply's marker still says " +
                    "\"sent\": no later chat open will send it, and the contact never gets our key",
                world.prefs.sentKexAck.isEmpty(),
            )
            assertEquals(0, world.seam.stored.size)

            world.seam.holdProposals = null
            world.restart()
            val again = world.openVm()
            again.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(again)
            assertEquals("the next open pays the one key reply", 1, world.seam.submits.get())
        }

    @Test
    fun `a key exchange cancelled before its submit withdraws its own paid record only`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val hold = CompletableDeferred<Unit>()
            world.seam.holdProposals = hold

            vm.ensureNostrBootstrapSent(world.peer)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { world.seam.proposalEntered.await() }
            assertTrue("fixture sanity: the paid record was stamped", world.base.paidRecords.isNotEmpty())
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()

            assertTrue("the cancelled attempt's own paid record is withdrawn", world.base.paidRecords.isEmpty())
        }

    @Test
    fun `control - a cancel after the submit started keeps the stamp`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val theirs = world.asResponder()
            val hold = CompletableDeferred<Unit>()
            world.seam.holdSubmits = hold

            vm.retryKexAckIfResponder(world.peer)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { world.seam.submitEntered.await() }
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()

            assertEquals("the transaction may be on the wire: the marker stays", theirs, world.prefs.sentKexAck[world.peer])
            hold.complete(Unit)
            Unit
        }
}
