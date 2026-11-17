package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G4 (ZC-112 part 2) — an on-chain member announce that never reached the chain is owed again.
 *
 * "Introduce them" pays one on-chain announce per pair of members. Its record is written as delivered when the
 * transaction is stored, with no id, and a delivered pair is never owed again. When the transaction is never mined, the
 * two members are never introduced and nothing on the screen says so.
 *
 * Wanted: once the chain proves an announce can no longer be mined, its delivered-looking record is removed, the pair
 * is owed again and the existing introduce banner returns. Nothing is paid until "Introduce them" is tapped and its
 * price confirmed. A mined announce keeps its record.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnounceMaybeSentExpiredOwedAgainTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    private suspend fun introduced(world: GroupLedgerWorld, s: GroupScreen) {
        net.register()
        assertEquals("fixture sanity: six pairs are owed on-chain", 6, MA3.introduce(MA3.load(s.vm, s.f), "owedOnChainPairs"))
        assertTrue(MA3.callGroupVm(s.vm, s.f, "introduceMembers"))
        assertEquals("fixture sanity: the tap quotes six announces", "GROUP_INTRODUCE", world.question(s))
        world.confirm(s)
        assertEquals("fixture sanity: six announces were paid", 6, world.seam.proposals.size)
        assertEquals("fixture sanity: no pair is owed after the tap", 0, MA3.introduce(MA3.load(s.vm, s.f), "owedOnChainPairs"))
        s.vm.consumeGroupSendEvent()
    }

    @Test
    fun `the pairs are owed again, the introduce banner returns, and nothing is paid until the tap is confirmed`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            world.storedNeverMined()
            introduced(world, s)
            world.walletLists()

            world.twoPassesPastExpiry(chat)

            val conversation = MA3.load(s.vm, s.f)
            assertEquals(
                "six announces were stored, never mined and are past their expiry on two passes, but no pair is owed " +
                    "again (announce records: ${world.admin.ledger().map { it.state }}): these members are never introduced",
                6,
                MA3.introduce(conversation, "owedOnChainPairs"),
            )
            assertEquals("the introduce banner does not return", true, MA3.introduce(conversation, "showBanner"))
            assertTrue(
                "the announce attempts' entries are deleted after their effect: ${world.ledger.all()}",
                world.ledger.of("ANNOUNCE").isEmpty(),
            )

            // Nothing is paid by itself.
            repeat(2) { world.open(s) }
            world.pass(chat, world.wallet.scanned + 1)
            assertEquals("an announce was paid again with no tap", 6, world.seam.proposals.size)

            // "Introduce them" is the tap that pays, under its own quote.
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            assertTrue(MA3.callGroupVm(s.vm, s.f, "introduceMembers"))
            assertEquals("the tap pays before its price is confirmed", 6, world.seam.proposals.size)
            assertEquals(6, world.questionCount(s))
            world.confirm(s)
            assertEquals("the confirmed tap pays each owed pair once", 12, world.seam.proposals.size)
        }

    @Test
    fun `a mined announce keeps its record and no pair is owed`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            introduced(world, s)
            world.walletLists(mined = { GroupLedgerWorld.H + 1 })

            world.twoPassesPastExpiry(chat)

            assertEquals("a mined announce is owed again", 0, MA3.introduce(MA3.load(s.vm, s.f), "owedOnChainPairs"))
            assertTrue(
                "a mined announce lost its record",
                world.admin.ledger().size == 6 && world.admin.ledger().all { it.state == AnnounceLedgerState.CHAIN_SENT },
            )
            assertTrue("this build keeps no record of a paid announce's attempt", world.ledger.exists)
            assertTrue(
                "the entries are settled from the chain: ${world.ledger.all()}",
                world.ledger.of("ANNOUNCE").all { it.state == "MINED" },
            )
            assertEquals(6, world.seam.proposals.size)
        }
}
