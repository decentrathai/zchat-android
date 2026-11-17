package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R16 (ZC-112; review findings GPT 6, G-3, GPT-3 #6) — a mined attempt is kept until it is reorg-deep, and one a
 * reorg took back is recovered.
 *
 * Wanted: a mined attempt is recorded MINED with its height in the same write, blocks a second attempt of its key until
 * the tip is the reorg margin past that height, and is then deleted with no marker write (nothing was touched, so
 * nothing is restored). If a shallow reorg unmines it and it then expires, it goes back to LIVE and is proven not sent
 * like any other: the notice is shown. An attempt with two transactions settles only when both are deep; one mined and
 * one dead is never offered again.
 *
 * Every address, key and transaction id is synthetic.
 */
class MinedAttemptIsKeptUntilEveryComponentIsReorgDeepAndAnOrphanedOneIsRecoveredTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a mined key exchange blocks until it is deep and is then deleted with no marker write`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.storedNeverMined()
            world.chatOpen(vm)
            val h = LedgerWorld.H + 3
            world.wallet.txs = listOf(world.chainTx(1, minedHeight = h))

            world.pass(vm, h + 1)
            val mined = world.ledger.one("KEX")
            assertEquals("MINED", mined?.state)
            assertEquals("the height is written with the state", h, mined?.minedHeight)

            world.pass(vm, h + LedgerWorld.MARGIN - 1)
            assertEquals("not yet deep: kept", "MINED", world.ledger.one("KEX")?.state)
            world.agePaidRecords(minutes = 61)
            world.chatOpen(vm, force = true)
            assertEquals("a mined attempt that is not yet deep blocks a second one", 1, world.seam.proposals.get())

            val mark = world.prefs.mark()
            world.pass(vm, h + LedgerWorld.MARGIN)
            assertTrue("deep: the entry is deleted (${world.ledger.all()})", world.ledger.all().isEmpty())
            assertTrue(
                "no marker is written at the delete",
                world.prefs.writesSince(mark).none { it.name == "setOwnBootSent" || it.name == "setHandshakePaidRecord" },
            )
        }

    @Test
    fun `a key exchange a reorg took back is live again, then proven not sent and shown`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.storedNeverMined()
            world.chatOpen(vm)
            val h = LedgerWorld.H + 3
            world.wallet.txs = listOf(world.chainTx(1, minedHeight = h))
            world.pass(vm, h + 3)
            assertEquals("MINED", world.ledger.one("KEX")?.state)

            // A shallow reorg: the wallet lists it unmined again.
            world.walletListsStoredUnmined()
            world.pass(vm, h + 4)
            val live = world.ledger.one("KEX")
            assertEquals("LIVE", live?.state)
            assertEquals("its ids are kept", listOf(world.seam.stored[0]), live?.txIds)
            assertEquals(null, live?.minedHeight)

            world.twoPassesPastExpiry(vm)
            assertEquals("NOT_SENT", world.ledger.one("KEX")?.state)
            assertTrue("the notice is shown (without retention the handshake would stay stuck)", world.notice(vm).containsKey(world.peer))
        }

    @Test
    fun `an attempt with two transactions settles only when both are deep, and a partial one is never offered`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            assertTrue("this build keeps no record of an automatic send attempt", world.ledger.exists)
            val a = SendAttemptHarness.txId(8_101)
            val b = SendAttemptHarness.txId(8_102)
            val h = LedgerWorld.H + 3
            world.ledger.seedLive(
                "KEX",
                world.peer,
                world.ourKeys.publicKey,
                startedAt = 1_000L,
                attemptHeight = LedgerWorld.H,
                accountHash = world.accountHash(),
                txIds = listOf(a, b),
                expiries = mapOf(a to world.seam.expiry, b to world.seam.expiry),
            )

            fun tx(
                number: Int,
                mined: Long?,
            ) = SendAttemptHarness.ChainTx(number, world.peer, null, mined, world.seam.expiry, amount = 1_000L)

            world.wallet.txs = listOf(tx(8_101, h), tx(8_102, h + 8))
            world.pass(vm, h + 9)
            assertEquals("the highest height of its transactions", h + 8, world.ledger.one("KEX")?.minedHeight)
            world.pass(vm, h + LedgerWorld.MARGIN)
            assertEquals("the first is deep, the second is not: kept", "MINED", world.ledger.one("KEX")?.state)
            world.pass(vm, h + 8 + LedgerWorld.MARGIN)
            assertTrue("both deep: deleted", world.ledger.all().isEmpty())

            // One mined, one dead: never NOT_SENT, never a notice.
            world.ledger.seedLive(
                "KEX",
                world.peer,
                world.ourKeys.publicKey,
                startedAt = 2_000L,
                attemptHeight = LedgerWorld.H,
                accountHash = world.accountHash(),
                txIds = listOf(a, b),
                expiries = mapOf(a to world.seam.expiry, b to world.seam.expiry),
            )
            world.wallet.txs = listOf(tx(8_101, world.seam.expiry + 30), tx(8_102, null))
            world.pass(vm, world.seam.expiry + 31)
            world.pass(vm, world.seam.expiry + 32)
            assertEquals("a partly mined attempt stays live", "LIVE", world.ledger.one("KEX")?.state)
            assertTrue("and is never offered again", world.notice(vm).isEmpty())
        }
}
