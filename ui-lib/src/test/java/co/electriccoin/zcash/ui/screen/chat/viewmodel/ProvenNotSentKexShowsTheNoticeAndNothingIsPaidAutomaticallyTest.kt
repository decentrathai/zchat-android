package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R2 (ZC-112) — a key exchange that never reached the chain stays "sent" for good, and nobody is told.
 *
 * A Tunnel chat's first open pays our KEX. When that transaction is stored but the network never confirms it, it
 * expires. Today the bootstrap marker and the paid record still say "sent": the chat waits for a reply that cannot
 * come, no screen says so, and the only recovery is a Reconnect or Call tap, which pays under the standing consent
 * with no price shown.
 *
 * Wanted: once the chain proves the KEX can no longer be mined (unmined past its expiry plus the reorg margin, on two
 * passes at strictly rising tips), the chat shows "Key exchange not sent" with Retry. Until that proof, and after it,
 * NOTHING is paid by itself: every automatic trigger builds no proposal, and the pass that proves it writes no
 * marker and no owed flag. Controls: a KEX that is mined shows no notice.
 *
 * Every address, key and transaction id is synthetic.
 */
class ProvenNotSentKexShowsTheNoticeAndNothingIsPaidAutomaticallyTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `no notice before the proof, and nothing is paid again while the first key exchange is unresolved`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            assertEquals("fixture sanity: the first chat open paid one KEX", 1, world.seam.submits.get())

            // Below the expiry, and one single pass at expiry + margin: the chain has not proven anything yet.
            world.pass(vm, world.seam.expiry - 1)
            world.pass(vm, world.seam.expiry + LedgerWorld.MARGIN)
            assertTrue("no notice before the proof is complete: ${world.notice(vm)}", world.notice(vm).isEmpty())

            // The hour of the paid record lapsed: today a forced bootstrap or a Reconnect now pays a second KEX.
            world.agePaidRecords(minutes = 61)
            repeat(3) { world.chatOpen(vm) }
            world.chatOpen(vm, force = true)
            vm.reSendOurIdentity(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals(
                "a second key exchange was built while the first one is still unresolved on the chain (stored, " +
                    "unmined, not proven dead): a forced bootstrap or a Reconnect after the hour pays it again",
                1,
                world.seam.proposals.get(),
            )
        }

    @Test
    fun `at the second pass at a higher tip the notice is shown and the pass wrote no marker`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            val bootSentBefore = world.base.ownBootSent[world.peer]
            val paidBefore = HashMap(world.base.paidRecords)
            val mark = world.prefs.mark()

            world.twoPassesPastExpiry(vm)

            val entry = world.ledger.one("KEX")
            assertEquals("the attempt is proven not sent: ${world.ledger.all()}", "NOT_SENT", entry?.state)
            assertEquals("the chat shows the notice, drawn from that failed attempt", entry?.startedAt, world.notice(vm)[world.peer])
            val written = world.prefs.writeNamesSince(mark).filter { it in MARKER_WRITES }
            assertEquals("the pass that proves it writes no marker, owed flag, allowance or paid record", emptyList<String>(), written)
            assertEquals("the bootstrap marker is as it was", bootSentBefore, world.base.ownBootSent[world.peer])
            assertEquals("the paid record is as it was", paidBefore, HashMap(world.base.paidRecords))
        }

    @Test
    fun `after the proof nothing is paid by itself`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexProvenNotSent(vm)
            val tip = world.wallet.scanned

            world.agePaidRecords(minutes = 61)
            world.everyAutomaticKexTrigger(vm, tip)

            assertEquals(
                "after the chain proved the key exchange never went out, an automatic trigger (a chat open, a forced " +
                    "bootstrap, a block, the owed-KEX tick) built a second one. Only the user's quoted Retry may pay again",
                1,
                world.seam.proposals.get(),
            )
            assertEquals(1, world.seam.submits.get())
            assertTrue("the notice still stands", world.notice(vm).containsKey(world.peer))
        }

    @Test
    fun `control - a key exchange that was mined shows no notice`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.storedNeverMined()
            world.chatOpen(vm)
            world.wallet.txs = listOf(world.chainTx(1, minedHeight = LedgerWorld.H + 3))

            world.twoPassesPastExpiry(vm)

            assertTrue("a mined key exchange is not a failed one: ${world.notice(vm)}", world.notice(vm).isEmpty())
            assertEquals("fixture sanity: it was paid once", 1, world.seam.submits.get())
            if (world.ledger.exists) {
                assertTrue("no entry is ever NOT_SENT for a mined transaction", world.ledger.all().none { it.state == "NOT_SENT" })
            }
        }

    private companion object {
        val MARKER_WRITES =
            setOf(
                "setOwnBootSent",
                "setSentNostrBootPubkey",
                "setSentKexAckPubkey",
                "setKexOwed",
                "setKexOwedReason",
                "setKexOwedAccount",
                "setZbootQuoted",
                "restoreQuotedZbootAsOwed",
                "setHandshakePaidRecord",
            )
    }
}
