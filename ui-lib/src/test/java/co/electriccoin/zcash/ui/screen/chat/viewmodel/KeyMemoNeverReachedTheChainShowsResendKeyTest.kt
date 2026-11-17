package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
 * F8-G G2 (ZC-112 part 2) — a key message that never reached the chain is shown, and re-sent only by a quoted tap.
 *
 * The creator rotates the group key; each member's new key goes out as its own on-chain message. When such a
 * transaction is stored but never mined, the roster today says the member has the new key, for good: nothing on any
 * screen says otherwise and nothing can send it again (the stored ids are never read).
 *
 * Wanted: once the chain proves the transaction can no longer be mined, Group Settings shows "Key not delivered" beside
 * that member with Resend. The roster is not touched (no owed mark), so opening the group under the standing consent
 * pays nothing. Resend asks the price of ONE key message, and Pay sends the CURRENT key once. A second tap on the
 * out-of-date badge asks nothing and pays nothing. A key message that was mined shows no badge.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyMemoNeverReachedTheChainShowsResendKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `the member shows Key not delivered, the roster is untouched, and a group open pays nothing`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()

            val badges = world.keyMemosProvenNotSent(s, chat)

            assertEquals("each member whose key message died shows the badge", setOf(MA3.A, MA3.B, MA3.D), badges.keys)
            for (member in listOf(MA3.A, MA3.B, MA3.D)) {
                assertNull("the roster marks $member as owed: an automatic pass could pay for it", world.row(member).pendingKeyEpoch)
            }
            assertNull("the failed key messages raise a banner", s.vm.groupSendEvent.value)

            // Nothing is paid by itself: not at a group open under the standing consent, not at a later pass.
            repeat(3) { world.open(s) }
            world.pass(chat, world.wallet.scanned + 1)
            assertEquals("a key message was paid again with no tap", 3, world.seam.proposals.size)
            assertEquals("the badges stay until the user taps Resend", 3, world.keyBadges(s).size)
        }

    @Test
    fun `Resend asks the price of one key message, pays the current key once, and a stale badge pays nothing`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            val badges = world.keyMemosProvenNotSent(s, chat)
            val offer = badges.getValue(MA3.A)
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            assertTrue("this build has no Resend for a key", world.tapResend(s, "resendKeyToMember", MA3.A, offer))
            assertEquals("Resend raises no price question for the key", "GROUP_KEY_SYNC", world.question(s))
            assertEquals("the question counts more than this one member", 1, world.questionCount(s))
            assertEquals("something was paid before the question was answered", 3, world.seam.proposals.size)
            world.confirm(s)

            assertEquals("Pay builds exactly one more key message", 4, world.seam.proposals.size)
            assertEquals("Pay submits exactly one more key message", 4, world.seam.submits.get())
            val resent = world.storedOf(GroupMessageType.GROUP_KEY, MA3.A).last()
            assertEquals("the Resend carries the current key number", world.epoch(), keyEpochOf(resent.memo.memo))
            val entry = world.entries("KEY_MEMO", MA3.A).single()
            assertEquals("the member's entry is the new attempt", "LIVE", entry.state)
            assertEquals("the new attempt holds the new id", listOf(resent.id), entry.txIds)
            assertEquals("the failed attempt's id is kept as superseded", 1, entry.superseded.size)
            assertTrue("the badge of the member is still shown", MA3.A !in world.keyBadges(s))
            assertNull("the Resend wrote an owed mark", world.row(MA3.A).pendingKeyEpoch)

            // The same badge, tapped again from a screen that still shows it.
            world.tapResend(s, "resendKeyToMember", MA3.A, offer)
            assertNull("a stale badge raises a price question", world.question(s))
            assertEquals("a stale badge pays", 4, world.seam.proposals.size)
        }

    @Test
    fun `a key message that was mined shows no badge, and a member with no secure session is never built for`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            world.rotate(s)
            world.walletLists(mined = { GroupLedgerWorld.H + 1 })

            world.twoPassesPastExpiry(chat)

            assertTrue("a mined key message shows a badge", world.keyBadges(s).isEmpty())
            assertTrue("this build keeps no record of a paid key message's attempt", world.ledger.exists)
            assertTrue(
                "a mined key message is not settled from the chain: ${world.ledger.all()}",
                world.ledger.of("KEY_MEMO").all { it.state == "MINED" } || world.ledger.of("KEY_MEMO").isEmpty(),
            )
            assertEquals("fixture sanity: three key messages were paid", 3, world.seam.proposals.size)
        }
}
