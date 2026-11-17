package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * ZC-506 Part A — every "I joined" reply (GROUP_ACCEPT) this app builds says which invitation it answers: it carries
 * `key_epoch`, the key epoch of that invite. The creator's phone uses it to refuse a reply made for an invitation from
 * before it removed the member (OldAcceptMinedAfterAdminRemovalIsNotAComebackTest).
 *
 * A reply is built in three places, and each is driven here on a member's phone:
 * - a first invite from a known contact: the reply goes free over the relays when the user taps Join (W-137, ZC-922:
 *   it went at once before);
 * - a first invite from an address the user never chatted with: the reply is kept in the group's needs-join record
 *   until the user taps Join;
 * - a new invite to a group this phone was removed from: the reply is kept in the needs-join record, and Join sends it.
 *
 * The value is always written: there is no shorter form "when it would not fit". So the sizes are pinned for the four
 * lengths a mainnet unified address has (106, 141, 178 and 213 characters; the group id is 29 characters): up to 178
 * characters the reply fits one 512-byte memo with any key epoch (a ten-digit epoch included); at 213 characters it is
 * over 512 bytes with or without the value, so it can only travel over the relays, and it still carries the value.
 *
 * Today no reply carries it. Consent GRANTED; nothing is proposed or paid. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinAcceptEchoesTheInviteEpochTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun selfOf(length: Int): String = "u1" + "q".repeat(length - 2)

    private fun keyEpochOf(memo: String): Int? {
        assertEquals("not a GROUP_ACCEPT: ${memo.take(24)}", GroupMessageType.GROUP_ACCEPT, ZMSGGroupProtocol.parseMessageType(memo))
        val json = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
        return if (json.has("key_epoch")) json.getInt("key_epoch") else null
    }

    private fun bytesOf(memo: String): Int = memo.toByteArray(Charsets.UTF_8).size

    private fun acceptsPublished(): List<String> =
        published.filter { ZMSGGroupProtocol.parseMessageType(it.first) == GroupMessageType.GROUP_ACCEPT }.map { it.first }

    /** The memo kept in the needs-join record of [GID] (the reply Join will send). */
    private fun heldMemo(phone: AnnouncePhone): String {
        val record = phone.needsJoin[GID]
        assertNotNull("the invite does not wait for Join", record)
        return JSONObject(record!!).getString("memo")
    }

    /** A first invite from the known contact [ADMIN] at [epoch], over the relays: the reply this phone published. */
    private suspend fun firstInviteReply(
        self: String,
        epoch: Int,
    ): Pair<String, HandshakeSeam> {
        published.clear()
        val phone = AnnouncePhone(self)
        phone.link(ADMIN)
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.relayAcks(published)
        val invite = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = epoch)

        AnnounceDriver.receive(vm, invite, Instant.ofEpochMilli(T + 1_000), sender = ADMIN, eventId = "synthetic-first-invite-$epoch")
        HandshakeHarness.awaitIdle(vm)
        // [test-change-ok: W-137 ZC-922 every invite waits for Join] A known contact's invite waits for Join too; the free
        // relay reply goes when the user taps Join (it went at once before). Nothing was published before the tap.
        if (phone.needsJoin[GID] != null) {
            assertEquals("a reply went before Join", 0, acceptsPublished().size)
            assertTrue("Join did not release the reply", GroupInviteDriver.join(vm, GID))
        }

        assertEquals("the group was not joined at the invite's key epoch", epoch, phone.epochs[GID])
        val accepts = acceptsPublished()
        assertEquals("the reply to a known contact's invite did not go once over the relays", 1, accepts.size)
        NostrChatBridge.unregisterPublisher()
        return accepts.single() to seam
    }

    /** A first invite, on-chain, from an address this phone never chatted with: the reply kept for Join. */
    private suspend fun strangerInviteReply(
        self: String,
        epoch: Int,
    ): Pair<String, HandshakeSeam> {
        val stranger = SyntheticUnifiedAddress.mainnet(seed = 5061)
        val phone = AnnouncePhone(self)
        val seam = HandshakeSeam(stranger)
        val vm = AnnounceDriver.chatVm(phone, seam)
        val invite =
            "ZMSG:3.0:GROUP:GI:$GID:" +
                JSONObject()
                    .put("name", "Synthetic invite group")
                    .put("inviter", stranger)
                    .put("key_epoch", epoch)
                    .put("group_key", AnnounceDriver.newGroupKey())
                    .put("members", JSONArray().put(stranger).put(self))
                    .toString()

        AnnounceDriver.receive(vm, invite, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(61))
        HandshakeHarness.awaitIdle(vm)

        return heldMemo(phone) to seam
    }

    /**
     * A phone the creator removed (the fixture of ReinviteReopensInactiveGroupTest: inactive at key epoch 2, the removal
     * applied), then a new invite from the creator at [epoch]: the reply kept for Join, and the one Join published.
     */
    private suspend fun reinviteReplies(
        self: String,
        epoch: Int,
    ): Triple<String, String, HandshakeSeam> {
        published.clear()
        val removedAt = T + 6_000
        val phone = AnnouncePhone(self)
        AnnounceDriver.memberPhone(phone, epoch = 2)
        phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(phone.info(GID).copy(isActive = false, removedByAdmin = true))
        phone.rosters[GID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = ADMIN, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.LEFT),
                    GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE),
                ),
            )
        phone.states[GID] =
            AnnounceState(
                hw = removedAt,
                rosterAnnounced = true,
                inactiveSince = removedAt,
                usedInviteIds = setOf(AnnounceDriver.txId(100).txIdString()),
                members = mapOf(phone.self to MemberAnnounceEntry(n = removedAt, op = AnnounceOp.R, epoch = 2)),
            )
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.relayAcks(published)
        val invite = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = epoch)

        AnnounceDriver.receive(vm, invite, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(200))
        HandshakeHarness.awaitIdle(vm)
        val held = heldMemo(phone)
        assertTrue("a reply was sent before Join", acceptsPublished().isEmpty())

        assertTrue("Join was refused", vm.joinGroupInvite(GID))
        HandshakeHarness.awaitIdle(vm)
        val accepts = acceptsPublished()
        assertEquals("Join did not send its one reply over the relays", 1, accepts.size)
        NostrChatBridge.unregisterPublisher()
        return Triple(held, accepts.single(), seam)
    }

    @Test
    fun `the free reply to a known contact's first invite carries the invite's key epoch, at every address length`() =
        runBlocking {
            for (length in LENGTHS) {
                val (reply, seam) = firstInviteReply(selfOf(length), epoch = 1)

                assertEquals("$length characters: the reply does not say which invite it answers", 1, keyEpochOf(reply))
                if (length <= 178) {
                    assertTrue("$length characters: the reply is ${bytesOf(reply)} bytes, over one memo", bytesOf(reply) <= MEMO_BYTES)
                } else {
                    // The default chat identity: over one memo with or without the value, so it travels over the relays.
                    assertTrue("$length characters: the reply is ${bytesOf(reply)} bytes", bytesOf(reply) > MEMO_BYTES)
                }
                assertEquals("$length characters: something was proposed for payment", 0, seam.proposalRequests.get())
            }
        }

    @Test
    fun `the reply kept for Join on a stranger's invite carries the invite's key epoch, at every address length`() =
        runBlocking {
            for (length in LENGTHS) {
                val (held, seam) = strangerInviteReply(selfOf(length), epoch = 4)

                assertEquals("$length characters: the kept reply does not say which invite it answers", 4, keyEpochOf(held))
                assertEquals("$length characters: something was proposed for payment", 0, seam.proposalRequests.get())
            }
        }

    @Test
    fun `the reply to a new invite after a removal carries that invite's key epoch, kept and as Join sends it`() =
        runBlocking {
            for (length in LENGTHS) {
                val (held, sent, seam) = reinviteReplies(selfOf(length), epoch = 3)

                assertEquals("$length characters: the kept reply does not say which invite it answers", 3, keyEpochOf(held))
                assertEquals("$length characters: the reply Join sent does not say which invite it answers", 3, keyEpochOf(sent))
                if (length <= 178) assertTrue("$length characters: ${bytesOf(sent)} bytes", bytesOf(sent) <= MEMO_BYTES)
                assertEquals("$length characters: something was proposed for payment", 0, seam.proposalRequests.get())
            }
        }

    @Test
    fun `no key epoch ever yields a reply without the value, and at 178 characters every one fits a memo`() =
        runBlocking {
            for (epoch in listOf(0, 1, 99, 99_999, Int.MAX_VALUE)) {
                val (reply, seam) = firstInviteReply(selfOf(178), epoch)
                assertEquals("key epoch $epoch: the reply does not carry it", epoch, keyEpochOf(reply))
                assertTrue("key epoch $epoch: the reply is ${bytesOf(reply)} bytes, over one memo", bytesOf(reply) <= MEMO_BYTES)
                assertEquals(0, seam.proposalRequests.get())

                val (held, _) = strangerInviteReply(selfOf(213), epoch)
                assertEquals("key epoch $epoch: the reply kept for Join does not carry it", epoch, keyEpochOf(held))
            }
        }

    private companion object {
        /** Mainnet unified addresses: Orchard; Orchard + transparent; Orchard + Sapling; all three (the chat identity). */
        val LENGTHS = listOf(106, 141, 178, 213)
        const val MEMO_BYTES = 512
    }
}
