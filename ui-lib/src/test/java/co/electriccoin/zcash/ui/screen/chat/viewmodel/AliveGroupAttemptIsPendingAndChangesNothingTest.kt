package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G9 (ZC-112 part 2; finding GPT 3) — while an earlier attempt of the same group memo is unresolved, a second one
 * builds nothing and changes nothing.
 *
 * An earlier attempt of a key message, an invite or a member announce can be on its way (stored, not mined yet), mined
 * and not yet deep, or proven not sent and waiting for the user's Resend. Today nothing records it, so a second attempt
 * (the owed-key pass at a group open, a Resend, "Introduce them") builds and pays the same memo again, or the first is
 * counted as delivered for good.
 *
 * Wanted: with such an entry in the ledger, each of these builds no proposal, and every obligation stays exactly as it
 * was: the roster row (the invite badge, the owed-key mark, the stored ids), the announce record, and the ledger itself
 * (the gate only reads). And after a proof, no automatic trigger of the group kinds pays anything.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AliveGroupAttemptIsPendingAndChangesNothingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    @Test
    fun `an owed key whose earlier attempt is unresolved, mined or failed is not paid by a group open, and its row stays owed`() =
        runBlocking {
            for (state in listOf("LIVE", "MINED", "NOT_SENT")) {
                GroupLedgerTests.resetProcess()
                val world = GroupLedgerWorld(scheduler = scheduler)
                world.setRow(MA3.A) { it.copy(pendingKeyEpoch = 1, pendingKeyTxIds = listOf("synthetic-earlier")) }
                val seeded = world.seed("KEY_MEMO", world.memberSubject(MA3.A), "epoch:1")
                when (state) {
                    "MINED" -> world.ledger.seedMined(seeded, GroupLedgerWorld.H + 1)
                    "NOT_SENT" -> world.ledger.seedNotSent(seeded, GroupLedgerWorld.H + 60)
                }
                val s = world.screen()
                val before = world.ledger.all().toString()

                repeat(2) { world.open(s) }

                assertEquals("[$state] the owed key was built again although its earlier attempt stands", 0, world.seam.proposals.size)
                assertEquals("[$state] the owed mark changed", 1, world.row(MA3.A).pendingKeyEpoch)
                assertEquals("[$state] the stored ids changed", listOf("synthetic-earlier"), world.row(MA3.A).pendingKeyTxIds)
                assertEquals("[$state] the gate wrote the ledger", emptyList<String>(), world.ledger.writes())
                assertEquals("[$state] the entry changed", before, world.ledger.all().toString())
            }
        }

    @Test
    fun `a Resend of an invite whose earlier attempt is on its way builds nothing and writes no badge`() =
        runBlocking {
            val world = GroupLedgerWorld.activeAndInvited(scheduler)
            world.setRow(MA3.B) { it.copy(inviteStatus = InviteStatus.FAILED) }
            world.seed("INVITE", world.memberSubject(MA3.B), "epoch:1")
            val s = world.screen()
            val writes = world.store.rosterWrites.size

            world.resendInvite(s, MA3.B)

            assertEquals("the invite was built again although its earlier attempt is on its way", 0, world.seam.proposals.size)
            assertEquals("the badge was rewritten", InviteStatus.FAILED, world.row(MA3.B).inviteStatus)
            assertEquals("the roster was written", writes, world.store.rosterWrites.size)
            assertEquals("the gate wrote the ledger", emptyList<String>(), world.ledger.writes())
        }

    @Test
    fun `an announce pair whose earlier attempt is on its way is not paid by Introduce them, and its record goes back`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            net.register()
            val s = world.screen()
            assertEquals(6, MA3.introduce(MA3.load(s.vm, s.f), "owedOnChainPairs"))
            val pairKey = MA3.A + "|" + MA3.B + "|A"
            world.seed("ANNOUNCE", MA3.GID, pairKey)

            assertTrue(MA3.callGroupVm(s.vm, s.f, "introduceMembers"))
            world.confirm(s)

            assertEquals("the pair whose earlier attempt is on its way was paid again", 5, world.seam.proposals.size)
            assertTrue(
                "the pair's record does not go back to what it was: ${world.admin.ledger().map { it.rAddr + it.sAddr + it.state }}",
                world.admin.ledger().none { it.rAddr == MA3.A && it.sAddr == MA3.B },
            )
            val ofThePair = world.ledger.of("ANNOUNCE").filter { it.identity == pairKey }
            assertEquals(listOf("LIVE"), ofThePair.map { it.state })
        }

    @Test
    fun `after a proof no automatic trigger of the group pays - not an open, not a pass, not a rotation's follow-up`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            world.keyMemosProvenNotSent(s, chat)
            val rosterBefore = world.admin.base.rosters[MA3.GID]

            repeat(3) { world.open(s) }
            repeat(3) { world.pass(chat, world.wallet.scanned + 1) }
            world.run(s) { s.vm.retryPendingGroupKeys(MA3.GID) }
            world.open(world.screen())

            assertEquals("a key message was paid again by itself after its proof", 3, world.seam.proposals.size)
            assertEquals("the roster was written after the proof", rosterBefore, world.admin.base.rosters[MA3.GID])
            assertEquals(
                "the failed entries are still what the badges stand for",
                3,
                world.ledger.of("KEY_MEMO").count { it.state == "NOT_SENT" },
            )
        }
}
