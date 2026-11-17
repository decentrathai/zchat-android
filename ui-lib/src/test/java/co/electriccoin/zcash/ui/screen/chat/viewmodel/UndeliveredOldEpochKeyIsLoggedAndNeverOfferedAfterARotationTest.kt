package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G11 (ZC-112 part 2; finding GPT 7) — a key message for an OLD key number that never reached the chain is neither
 * closed silently by the next rotation nor offered again.
 *
 * A receiver ignores a group key whose number is not above the one it holds, and the creator only ever re-sends the
 * CURRENT key. So once the group has moved to the next key number, sending the old key again would pay for a message
 * the member's phone drops.
 *
 * Wanted: a rotation leaves an unresolved key-message entry of the previous number in the ledger (it is judged from
 * the chain like any other). When that entry is then proven not sent, it shows no badge and no Resend, nothing is paid
 * for the old number on any trigger, and the pass deletes it. The member is covered for the CURRENT number by the
 * rotation's own key message, a different entry. (Whether a receiver should accept an older key is the owner's
 * decision and not part of this batch.)
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UndeliveredOldEpochKeyIsLoggedAndNeverOfferedAfterARotationTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `the old entry survives the rotation, and once proven it shows no badge, pays nothing and is deleted`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            // Rotation to key 2: three key messages, stored, never mined.
            world.storedNeverMined()
            world.rotate(s)
            assertEquals(2, world.epoch())
            // Rotation to key 3 while those are unresolved; its own three key messages will be mined.
            world.rotate(s)
            assertEquals(3, world.epoch())
            assertEquals("fixture sanity: six key messages were paid", 6, world.seam.stored.size)

            assertEquals(
                "the rotation to key 3 closed (or never recorded) the unresolved key messages of key 2: ${world.ledger.all()}",
                3,
                world.ledger.of("KEY_MEMO").count { it.identity == "epoch:2" && it.state == "LIVE" },
            )
            assertEquals(
                "the rotation's own key messages are not recorded",
                3,
                world.ledger.of("KEY_MEMO").count { it.identity == "epoch:3" },
            )

            world.walletLists(mined = { n -> if (n > 3) GroupLedgerWorld.H + 1 else null })
            world.twoPassesPastExpiry(chat)
            world.pass(chat, world.wallet.scanned + 1)

            assertTrue("a key message of an old number shows a badge", world.keyBadges(s).isEmpty())
            assertTrue(
                "the proven-dead entries of key 2 are kept: ${world.ledger.all()}",
                world.ledger.of("KEY_MEMO").none { it.identity == "epoch:2" },
            )
            repeat(2) { world.open(s) }
            world.run(s) { s.vm.retryPendingGroupKeys(MA3.GID) }
            assertEquals("something was paid for the old key number", 6, world.seam.proposals.size)
            for (member in listOf(MA3.A, MA3.B, MA3.D)) assertNull(world.row(member).pendingKeyEpoch)
        }

    @Test
    fun `while the key number has not moved the same proof shows the badge for the current key`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()

            val badges = world.keyMemosProvenNotSent(s, chat)

            assertEquals(setOf(MA3.A, MA3.B, MA3.D), badges.keys)
            assertTrue(world.ledger.of("KEY_MEMO").all { it.identity == "epoch:2" && it.state == "NOT_SENT" })
        }
}
