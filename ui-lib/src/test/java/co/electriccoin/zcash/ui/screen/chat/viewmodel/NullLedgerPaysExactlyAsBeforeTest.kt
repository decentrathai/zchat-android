package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R11 — CONTROL, green before and after the fix.
 *
 * (a) With no ledger installed (the world of every test written before F8) the key exchange and the key reply pay
 * exactly as before: one submit each, their markers set. (b) With a ledger whose writes all fail, a locked message, a
 * note-only kind, still pays as before: its entry only decides whether a note appears later, so it is fail-open.
 *
 * Every address, key and transaction id is synthetic.
 */
class NullLedgerPaysExactlyAsBeforeTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `control - with no ledger the key exchange and the key reply pay as before`() =
        runBlocking {
            val world = LedgerWorld(withLedger = false)
            val vm = world.openVm()
            world.chatOpen(vm)
            assertEquals(1, world.seam.submits.get())
            assertEquals(true, world.base.ownBootSent[world.peer])
            assertTrue(world.base.paidRecords.isNotEmpty())

            val responder = LedgerWorld(peer = "usyntheticledgerpeer00000002", withLedger = false)
            val vm2 = responder.openVm()
            val theirs = responder.asResponder()
            vm2.retryKexAckIfResponder(responder.peer)
            HandshakeHarness.awaitIdle(vm2)
            assertEquals(1, responder.seam.submits.get())
            assertEquals(theirs, responder.prefs.sentKexAck[responder.peer])
        }

    @Test
    fun `control - a locked message is paid although the ledger takes no write`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.VAULT, exchangeComplete = true)
            val vm = world.openVm()
            world.ledger.failAnyWrite = true

            vm.sendScheduledMessage(world.peer, "synthetic locked text", System.currentTimeMillis() + 3_600_000L)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a note-only kind is fail-open: the user's paid send goes out", 1, world.seam.submits.get())
        }
}
