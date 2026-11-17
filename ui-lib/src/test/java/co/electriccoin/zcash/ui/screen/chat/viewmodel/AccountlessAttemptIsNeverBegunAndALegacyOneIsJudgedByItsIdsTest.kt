package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R18 (ZC-112; review finding GPT 9) — no attempt begins without a known account, and a record with no account is
 * judged by its transaction ids only.
 *
 * An attempt's entry names the account that made it, so a later process can tell "another process of this same wallet
 * stored nothing" from "another wallet". Without an account that rule cannot be used.
 *
 * Wanted: with no ZCHAT account known, a key exchange and a key reply store nothing and end as "nothing sent". A record
 * of an earlier build that carries no account is settled only by what the chain says about its own ids: mined, or dead
 * on two passes; an id the wallet does not list decides nothing. One with no ids is never "not sent": it stays and
 * shows nothing.
 *
 * Every address, key and transaction id is synthetic.
 */
class AccountlessAttemptIsNeverBegunAndALegacyOneIsJudgedByItsIdsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `with no account known a key exchange and a key reply store nothing`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.asResponder()
            world.scriptAccountHash(null)

            world.chatOpen(vm)
            vm.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("nothing is stored for an attempt that cannot be recorded against an account", 0, world.seam.stored.size)
            assertEquals(0, world.seam.submits.get())
            assertTrue("the key reply's marker is not left set", world.prefs.sentKexAck.isEmpty())
        }

    @Test
    fun `a record with no account is settled by its ids, and one with no ids never is`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            assertTrue("this build keeps no record of an automatic send attempt", world.ledger.exists)
            val id = SendAttemptHarness.txId(8_301)

            fun legacy(txIds: List<String>) {
                val seeded =
                    world.ledger.seedLive(
                        "KEX",
                        world.peer,
                        world.ourKeys.publicKey,
                        startedAt = 3_000L + txIds.size,
                        attemptHeight = LedgerWorld.H,
                        accountHash = world.accountHash(),
                        txIds = txIds,
                        expiries = txIds.associateWith { world.seam.expiry },
                    )
                // As an earlier build wrote it: no account, and no promise that its ids were recorded before the submit.
                world.ledger.put(seeded.with("accountHash" to null, "idsBeforeSubmit" to false))
            }

            // Its id is listed unmined, past expiry on two passes: NOT_SENT.
            legacy(listOf(id))
            world.wallet.txs = listOf(SendAttemptHarness.ChainTx(8_301, world.peer, null, null, world.seam.expiry, amount = 1_000L))
            world.twoPassesPastExpiry(vm)
            assertEquals("NOT_SENT", world.ledger.one("KEX")?.state)

            // With no ids: never decided, whatever the wallet holds and however far the tip is.
            world.ledger.remove(world.ledger.one("KEX")!!)
            legacy(emptyList())
            world.wallet.txs = listOf(SendAttemptHarness.ChainTx(8_399, "usyntheticsomeoneelse0000001", null, LedgerWorld.H + 1, null))
            repeat(4) { world.pass(vm, world.wallet.scanned + 200 + it) }
            assertEquals("a record with no account and no ids stays as it is", "LIVE", world.ledger.one("KEX")?.state)
            assertTrue(world.notice(vm).isEmpty())
        }
}
