package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R24 (ZC-112; review finding G4-4) — after a Retry's transaction ids are recorded, a failed submit never brings
 * the notice back by itself: only the chain's proof does.
 *
 * Once the ids of the Retry's transaction are committed, that transaction is stored in the wallet and may still be
 * broadcast. Writing the failed state back at that point would offer a second Retry while the first payment can still
 * land: two payments for one quote.
 *
 * Wanted: after the ids commit the entry is LIVE whatever the submit answers; no notice; a second Retry is impossible
 * (nothing to tap, and every automatic trigger builds nothing). Only after that transaction is past its expiry plus the
 * reorg margin on two passes at rising tips is it NOT_SENT again, and the notice returns.
 *
 * Every address, key and transaction id is synthetic.
 */
class FailedSubmitNeverBecomesNotSentWithoutTheProofTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a Retry whose submit failed stays live until the chain proves it dead`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            val first = world.ledger.one("KEX")!!
            // The Retry's submit fails at the transport: its transaction is stored and may still go out.
            world.seam.expiry = world.wallet.scanned + 40
            world.storedNeverMined()

            world.retryAndConfirm(vm, offer)

            assertEquals("fixture sanity: the Retry's transaction was submitted once", 2, world.seam.submits.get())
            val live = world.ledger.one("KEX")!!
            assertEquals("after the ids commit the entry is LIVE, never written back as NOT_SENT", "LIVE", live.state)
            assertEquals(listOf(world.seam.stored[1]), live.txIds)
            assertEquals(first.txIds, live.superseded)
            assertTrue("no notice", world.notice(vm).isEmpty())

            // No second Retry: the old offer asks nothing, and nothing automatic pays.
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals(null, world.question(vm))
            world.agePaidRecords(minutes = 61)
            world.chatOpen(vm, force = true)
            assertEquals(2, world.seam.proposals.get())

            // Only the proof brings the notice back.
            world.wallet.txs =
                listOf(
                    SendAttemptHarness.ChainTx(
                        world.seam.number(1),
                        world.peer,
                        null,
                        null,
                        first.expiries.values.first(),
                        amount = 1_000L,
                    ),
                    world.chainTx(2),
                )
            world.pass(vm, world.seam.expiry + LedgerWorld.MARGIN - 1)
            assertTrue("not before its expiry plus the margin", world.notice(vm).isEmpty())
            world.twoPassesPastExpiry(vm)
            val again = world.ledger.one("KEX")!!
            assertEquals("NOT_SENT", again.state)
            assertEquals("the notice returns, for the new failed attempt", again.startedAt, world.notice(vm)[world.peer])
            assertTrue(again.startedAt != offer)
        }
}
