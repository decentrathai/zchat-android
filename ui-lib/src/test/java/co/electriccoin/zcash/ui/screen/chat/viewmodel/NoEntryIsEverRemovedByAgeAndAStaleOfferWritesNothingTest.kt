package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R17 (ZC-112; review findings GPT 8, G-2, G-5) — no entry ends by age, a doubt about the identity writes nothing,
 * and a positively new identity drops the old attempt without touching a marker.
 *
 * Wanted: an unresolved attempt whose transaction the wallet never lists stays, however old, and keeps blocking a
 * second attempt. While the identity an attempt was made for cannot be read (the key store does not answer) nothing is
 * written and nothing is shown. After a Reset (a new key) the old attempt's notice goes and the
 * next pass deletes its entry: no marker, owed flag or allowance is written, and the new key is unaffected.
 *
 * Every address, key and transaction id is synthetic.
 */
class NoEntryIsEverRemovedByAgeAndAStaleOfferWritesNothingTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `an old unresolved attempt the wallet never lists stays and still blocks`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            assertTrue("this build keeps no record of an automatic send attempt", world.ledger.exists)
            val days400 = 400L * 24 * 60 * 60 * 1000
            val unlisted = SendAttemptHarness.txId(8_201)
            world.ledger.seedLive(
                "KEX",
                world.peer,
                world.ourKeys.publicKey,
                startedAt = System.currentTimeMillis() - days400,
                attemptHeight = LedgerWorld.H - 400_000,
                accountHash = world.accountHash(),
                txIds = listOf(unlisted),
                expiries = mapOf(unlisted to LedgerWorld.H - 399_000),
            )
            // The wallet holds other transactions, but not this one (it was wiped or restored).
            world.wallet.txs = listOf(SendAttemptHarness.ChainTx(8_299, "usyntheticsomeoneelse0000001", null, LedgerWorld.H - 5, null))

            repeat(6) { world.pass(vm, LedgerWorld.H + 100 + it) }

            assertEquals("an id the wallet does not list is never proof, and no entry ends by age", "LIVE", world.ledger.one("KEX")?.state)
            world.chatOpen(vm, force = true)
            assertEquals("and it still blocks a second key exchange", 0, world.seam.proposals.get())
        }

    @Test
    fun `while the identity cannot be read nothing is written and nothing is shown`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            // The key store cannot be read: whether this key is still the current one is a doubt.
            world.prefs.unreadable = setOf("getE2EOurPublicKey")
            val writes = world.ledger.writes().size

            world.twoPassesPastExpiry(vm)

            assertEquals("a doubt writes nothing", writes, world.ledger.writes().size)
            assertEquals("LIVE", world.ledger.one("KEX")?.state)
            assertTrue("and shows nothing", world.notice(vm).isEmpty())

            world.prefs.unreadable = emptySet()
            world.twoPassesPastExpiry(vm, from = world.wallet.scanned + 1)
            assertTrue(
                "once the identity reads again the proof completes and the notice is shown",
                world.notice(vm).containsKey(world.peer),
            )
        }

    @Test
    fun `a Reset drops the old attempt and writes no marker`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexProvenNotSent(vm)
            // Reset encryption: a new key for this contact.
            world.base.session(world.peer, complete = false, mode = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL)
            val mark = world.prefs.mark()

            world.pass(vm, world.wallet.scanned + 1)

            assertTrue("the notice of the old key is gone", world.notice(vm).isEmpty())
            assertTrue("the old key's entry is deleted: ${world.ledger.all()}", world.ledger.all().isEmpty())
            assertEquals(
                "the pass writes no marker, owed flag or allowance for it",
                emptyList<String>(),
                world.prefs.writeNamesSince(mark).filter { it != "addPendingMessage" && it != "commitPendingMessage" },
            )
        }
}
