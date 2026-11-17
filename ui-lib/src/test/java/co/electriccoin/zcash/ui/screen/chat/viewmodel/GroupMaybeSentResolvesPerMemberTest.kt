package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.GroupMsgPayload
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G1 (ZC-112 part 2, ZC-619) — a group message is settled per member, and one member's dead copy is re-sent only
 * by a quoted tap that finds the text after the history scan.
 *
 * A group message is one on-chain memo per member. Today nothing per member survives the send: a copy whose
 * transaction never mines is counted as delivered, and nothing can send it again.
 *
 * Wanted, in this order: the send stores its own row ("grps-…"); the history scan then replaces that row by the chain
 * rows ("<txid>_<seq>"), so a lookup by the first row's id finds nothing; the chain then proves the three copies: one
 * mined, one dead, one still alive. Group Settings shows "Message not delivered" beside the one member whose copy
 * died, with no banner and no automatic send. Resend reads the text by (our address, sequence number, key number)
 * from the stored rows, asks the price of ONE message, and Pay sends one copy that decrypts under the original key to
 * the same text with the same sender, sequence number and key number. A second tap pays nothing. With no stored row
 * that holds the text, or with a second stored row under our address that holds another text, Resend asks nothing and
 * pays nothing. A member who left, or a message whose key is gone, is offered no Resend. A message that reached some
 * members keeps its tick.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMaybeSentResolvesPerMemberTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private companion object {
        const val TEXT = "hello group, synthetic"
    }

    private fun rows(world: GroupLedgerWorld) = OpenGroup.rows(world.messages)

    private fun chainRowWithText(row: JSONObject): Boolean = row.optString("decrypted") == TEXT && row.getString("id").endsWith("_1")

    /** The history scan of [s] over a wallet that lists the three copies: the first mined, the second expired, the third pending. */
    private suspend fun scan(world: GroupLedgerWorld, s: GroupScreen) {
        val copies = world.storedOf(GroupMessageType.GROUP_MSG)
        OpenGroupChain.rescan(
            s.vm,
            listOf(
                OpenGroupChain.sent(1, copies[0].memo.memo, GroupLedgerWorld.H + 1),
                OpenGroupChain.failed(2, copies[1].memo.memo),
                OpenGroupChain.pending(3, copies[2].memo.memo),
            ),
        )
    }

    /** The send, then the scan, then the proof: the first copy mined, the second dead on two passes, the third alive. */
    private suspend fun sentScannedAndProven(world: GroupLedgerWorld, s: GroupScreen, chat: ChatViewModel) {
        world.sendMessage(s, TEXT)
        assertEquals("fixture sanity: one copy per member was paid", 3, world.storedOf(GroupMessageType.GROUP_MSG).size)
        assertEquals("fixture sanity: the send stored one row of its own", 1, rows(world).size)
        assertTrue("fixture sanity: that row is the send's own", rows(world).single().getString("id").startsWith("grps-"))
        s.vm.consumeGroupSendEvent()

        scan(world, s)
        assertTrue(
            "fixture sanity: after the history scan no stored row has the send's own id: ${world.admin.base.storedMessageIds(MA3.GID)}",
            rows(world).none { it.getString("id").startsWith("grps-") },
        )
        assertEquals("fixture sanity: the stored rows are the chain rows, one per copy", 3, rows(world).size)
        assertTrue(
            "fixture sanity: each chain row holds the text",
            rows(world).all { chainRowWithText(it) },
        )

        world.walletLists(
            expiry = { n -> if (n == 3) GroupLedgerWorld.H + 10_000 else world.seam.expiry },
            mined = { n -> if (n == 1) GroupLedgerWorld.H + 1 else null },
        )
        world.twoPassesPastExpiry(chat)
    }

    private fun payloadOf(stored: GroupLedgerSeam.Stored): GroupMsgPayload {
        val payload = ZMSGGroupProtocol.parsePayload(stored.memo.memo)!!
        return ZMSGGroupProtocol.parseGroupMsgPayload(payload)!!
    }

    private fun recipient(world: GroupLedgerWorld, n: Int): String = world.storedOf(GroupMessageType.GROUP_MSG)[n - 1].memo.recipient

    @Test
    fun `only the member whose copy died shows the badge, and Resend after the scan pays one copy with the same text`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            sentScannedAndProven(world, s, chat)
            val dead = recipient(world, 2)

            val badges = world.messageBadges(s)
            assertEquals(
                "the copy to $dead was stored, never mined and is past its expiry on two passes, but Group Settings does " +
                    "not show \"Message not delivered\" beside exactly that member (ledger: ${world.ledger.all()})",
                setOf(dead),
                badges.keys,
            )
            // The mined copy is settled from the chain (and, mined long ago, already deleted at depth).
            assertTrue(world.entries("GROUP_MESSAGE", recipient(world, 1)).all { it.state == "MINED" })
            assertEquals("LIVE", world.entries("GROUP_MESSAGE", recipient(world, 3)).single().state)
            assertNull("the dead copy raises a banner", s.vm.groupSendEvent.value)
            world.open(s)
            world.pass(chat, world.wallet.scanned + 1)
            assertEquals("a copy was sent again with no tap", 3, world.seam.proposals.size)
            // A message that reached some members keeps its tick (ZC-493 marks only "every copy expired").
            val conversation = (s.vm.groupDetailState.value as GroupDetailState.Success).conversation
            val shown = conversation.messages.single { it.decryptedContent == TEXT }
            assertTrue("a message that reached a member is drawn as failed", !shown.isFailed)

            assertTrue("this build has no Resend for a message copy", world.tapResend(s, "resendMessageCopy", dead, badges.getValue(dead)))
            assertEquals("Resend raises no price question", "GROUP_MESSAGE", world.question(s))
            assertEquals("the question counts more than this one member", 1, world.questionCount(s))
            assertEquals("something was paid before the question was answered", 3, world.seam.proposals.size)
            world.confirm(s)

            assertEquals("Pay builds exactly one more copy", 4, world.seam.proposals.size)
            assertEquals("Pay submits exactly one more copy", 4, world.seam.submits.get())
            val copy = world.storedOf(GroupMessageType.GROUP_MSG).last()
            assertEquals("the copy goes to the member whose copy died", dead, copy.memo.recipient)
            val first = payloadOf(world.storedOf(GroupMessageType.GROUP_MSG)[1])
            val again = payloadOf(copy)
            val key = ZMSGGroupProtocol.decodeGroupKey(MA3.groupKey)
            assertEquals("the copy decrypts to another text", TEXT, ZMSGGroupProtocol.decryptMessage(again.nonce, again.ciphertext, key))
            assertEquals("the copy names another sender", first.sender, again.sender)
            assertEquals("the copy carries another sequence number", first.seq, again.seq)
            assertEquals("the copy carries another key number", first.epoch, again.epoch)
            assertTrue("the copy reuses the first copy's nonce", first.nonce != again.nonce)
            assertTrue("the badge is still shown after the Resend", world.messageBadges(s).isEmpty())
            assertEquals("LIVE", world.entries("GROUP_MESSAGE", dead).single().state)
            assertEquals("the Resend stored a second row of the message", 3, rows(world).size)

            // A second tap on the badge a stale screen still shows.
            world.tapResend(s, "resendMessageCopy", dead, badges.getValue(dead))
            assertNull("a stale badge raises a price question", world.question(s))
            assertEquals("a stale badge pays", 4, world.seam.proposals.size)
        }

    @Test
    fun `with no stored row that holds the text the Resend is refused and pays nothing`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            sentScannedAndProven(world, s, chat)
            val dead = recipient(world, 2)
            val failed = world.entries("GROUP_MESSAGE", dead).singleOrNull()
            assertEquals("the dead copy is not recorded as failed: ${world.ledger.all()}", "NOT_SENT", failed?.state)
            val badge = world.messageBadges(s).getValue(dead)
            world.messages.remove(MA3.GID)

            world.tapResend(s, "resendMessageCopy", dead, badge)

            assertNull("a copy whose text is gone raises a price question", world.question(s))
            assertEquals(3, world.seam.proposals.size)
            assertEquals("the entry of the refused Resend changed", "NOT_SENT", world.entries("GROUP_MESSAGE", dead).single().state)
        }

    @Test
    fun `a second stored row under our address with another text refuses the Resend - no question, nothing paid`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            sentScannedAndProven(world, s, chat)
            val dead = recipient(world, 2)
            assertEquals(
                "the dead copy is not recorded as failed: ${world.ledger.all()}",
                "NOT_SENT",
                world.entries("GROUP_MESSAGE", dead).singleOrNull()?.state,
            )
            val badge = world.messageBadges(s).getValue(dead)
            // Stored the way the live path stores another member's on-chain memo that claims our address: id = its txid.
            val mine = rows(world).first()
            val forged = JSONObject(mine.toString())
            forged.put("id", SendAttemptHarness.txId(424_242))
            forged.put("decrypted", "a text this phone never wrote")
            world.messages[MA3.GID] = JSONArray(rows(world) + forged).toString()

            world.tapResend(s, "resendMessageCopy", dead, badge)

            assertNull("a message with two texts under our address raises a price question", world.question(s))
            assertEquals("a text this phone cannot tell is the user's was paid for", 3, world.seam.proposals.size)
            assertEquals("NOT_SENT", world.entries("GROUP_MESSAGE", dead).single().state)
        }

    @Test
    fun `a member who left, or a message whose key is gone, is offered no Resend`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            sentScannedAndProven(world, s, chat)
            val dead = recipient(world, 2)
            val badge = world.messageBadges(s)[dead]
            assertTrue("the dead copy shows no badge: ${world.ledger.all()}", badge != null)

            val key = world.keys.remove(MA3.GID + "|1")!!
            assertTrue("a copy whose key is gone still shows the badge", world.messageBadges(s).isEmpty())
            world.tapResend(s, "resendMessageCopy", dead, badge!!)
            assertNull(world.question(s))
            world.keys[MA3.GID + "|1"] = key

            world.setRow(dead) { it.copy(status = MemberStatus.LEFT) }
            assertTrue("a member who left still shows the badge", world.messageBadges(s).isEmpty())
            world.tapResend(s, "resendMessageCopy", dead, badge)
            assertNull("a copy to a member who left raises a price question", world.question(s))
            world.pass(chat, world.wallet.scanned + 1)
            assertTrue("the entry of a member who left is kept: ${world.ledger.all()}", world.entries("GROUP_MESSAGE", dead).isEmpty())
            assertEquals(3, world.seam.proposals.size)
        }
}
