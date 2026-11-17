package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * Round 8 MA2, design test 12 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.3, review H1 / H2 / F1) — a re-invite reopens a group
 * this phone was removed from, strictly, and only after Join.
 *
 * Today a group invite for a group this phone already has is skipped ("already exists"), so a member the creator
 * removed and then invited again can never come back (its group stays "You were removed"). Reopening on any GI would
 * be worse: the scan replays the original invite on every pass, our own sent invites come back too, any contact with
 * a key exchange can send a GI for the id, and an old key epoch would undo a kick.
 *
 * Wanted:
 * - a fresh, received GI whose key unwraps only under the session with the peer holding the creator's key, never used
 *   before, newer than the removal, at a key epoch not lower than the group's, waits for Join (the needs-join record;
 *   no reply, nothing proposed), over three scan passes; Join makes the group active with the key and roster from the
 *   GI, keeps the messages, and sends the one reply free over the relays;
 * - the group STAYS inactive, with no reply, no needs-join record and no proposal, over three passes, for: the
 *   original GI replayed (on-chain and over the relays), our own sent GI, a GI from a contact who is not the creator,
 *   and a lower key epoch; the key epoch is never lowered;
 * - after the reopen, the old `R self` replayed does not remove this phone again.
 *
 * Consent GRANTED everywhere. All addresses, keys, txids and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReinviteReopensInactiveGroupTest {
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

    private val originalGi: TransactionId = AnnounceDriver.txId(100)
    private val removedAt = T + 6_000

    /**
     * A member's phone the creator removed: the group is inactive (removed), at key epoch 2, one message stored, the
     * original invite recorded as used and the removal (`R self` at [removedAt]) applied. [legacy]: removed before the
     * upgrade, so no announce state at all.
     */
    private fun removedPhone(legacy: Boolean = false): Pair<AnnouncePhone, E2EKeyPair> {
        val phone = AnnouncePhone()
        val admin = AnnounceDriver.memberPhone(phone, epoch = 2)
        phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(phone.info(GID).copy(isActive = false, removedByAdmin = true))
        phone.rosters[GID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = ADMIN, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.LEFT),
                    GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE),
                ),
            )
        phone.messages[GID] =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "synthetic-old-message")
                        .put("groupId", GID)
                        .put("txId", "synthetic-old-message")
                        .put("seq", 1)
                        .put("epoch", 1)
                        .put("sender", X)
                        .put("decrypted", "kept")
                        .put("timestamp", T + 3_000),
                ).toString()
        if (!legacy) {
            phone.states[GID] =
                AnnounceState(
                    hw = removedAt,
                    rosterAnnounced = true,
                    inactiveSince = removedAt,
                    usedInviteIds = setOf(originalGi.txIdString()),
                    members = mapOf(phone.self to MemberAnnounceEntry(n = removedAt, op = AnnounceOp.R, epoch = 2)),
                )
        }
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        return phone to admin
    }

    private fun assertStaysOut(
        phone: AnnouncePhone,
        seam: HandshakeSeam,
        what: String,
        epoch: Int = 2,
    ) {
        assertFalse("$what reopened the group", phone.info(GID).isActive)
        assertNull("$what left a needs-join record", phone.needsJoin[GID])
        assertTrue(
            "$what sent a group reply: $published",
            published.none {
                ZMSGGroupProtocol.parseMessageType(it.first) ==
                    GroupMessageType.GROUP_ACCEPT
            }
        )
        assertEquals("$what: something was proposed for payment", 0, seam.proposalRequests.get())
        assertEquals("$what changed the key epoch", epoch, phone.epochs[GID])
    }

    @Test
    fun `a fresh GI from the creator waits for Join, then Join reopens the group with its key and keeps the messages`() =
        runBlocking {
            val (phone, admin) = removedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val newKey = AnnounceDriver.newGroupKey()
            val fresh = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), newKey, keyEpoch = 3)
            val freshId = AnnounceDriver.txId(200)
            val oldRemoval = AnnounceDriver.gp(AnnounceOp.R, phone.self, removedAt, 2, admin)

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, oldRemoval, Instant.ofEpochMilli(removedAt), AnnounceDriver.txId(150))
                AnnounceDriver.receive(vm, fresh, Instant.ofEpochMilli(T + 20_000), freshId)
                HandshakeHarness.awaitIdle(vm)
                assertFalse("pass ${pass + 1}: the re-invite reopened the group without Join", phone.info(GID).isActive)
                assertNotNull("pass ${pass + 1}: the fresh re-invite from the creator does not wait for Join", phone.needsJoin[GID])
                assertTrue("pass ${pass + 1}: a reply was sent before Join: $published", published.isEmpty())
                assertEquals(0, seam.proposalRequests.get())
            }

            assertTrue("Join was refused", vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(vm)

            val info = phone.info(GID)
            assertTrue("Join did not reopen the group", info.isActive)
            assertFalse("the reopened group still says this phone was removed", info.removedByAdmin)
            assertEquals("the creator was replaced", ADMIN, info.creatorAddress)
            assertEquals("the key epoch is not the invite's", 3, phone.epochs[GID])
            assertEquals("the key is not the invite's", newKey, phone.keys["$GID|3"])
            assertEquals(MemberStatus.ACTIVE, phone.status(GID, phone.self))
            assertEquals(MemberStatus.ACTIVE, phone.status(GID, ADMIN))
            assertEquals("the messages were not kept", listOf("synthetic-old-message"), phone.storedMessageIds(GID))
            val state = phone.state(GID)
            // ZC-291: the join is numbered at the later of the high-water mark (removedAt) and the invite's source time.
            assertEquals("selfJoinN is not the re-invite's source time", T + 20_000, state.selfJoinN)
            assertEquals(3, state.selfJoinEpoch)
            assertTrue("the invite used for the join is not recorded", freshId.txIdString() in state.usedInviteIds)
            assertEquals(
                "the one reply to the creator did not go free over the relays",
                1,
                published.count { (text, to) ->
                    to == ADMIN_NOSTR &&
                        ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_ACCEPT
                },
            )

            // Every later pass: the old removal and the invites replay, and nothing changes.
            repeat(3) { pass ->
                AnnounceDriver.receive(
                    vm,
                    AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), 2),
                    Instant.ofEpochMilli(T),
                    originalGi
                )
                AnnounceDriver.receive(vm, oldRemoval, Instant.ofEpochMilli(removedAt), AnnounceDriver.txId(150))
                AnnounceDriver.receive(
                    vm,
                    oldRemoval,
                    Instant.ofEpochMilli(removedAt),
                    sender = ADMIN,
                    eventId = "synthetic-event-old-removal"
                )
                AnnounceDriver.receive(vm, fresh, Instant.ofEpochMilli(T + 20_000), freshId)
                HandshakeHarness.awaitIdle(vm)
                assertTrue("pass ${pass + 1} after Join: the old removal replayed removed this phone again", phone.info(GID).isActive)
                assertNull("pass ${pass + 1} after Join: a needs-join record came back", phone.needsJoin[GID])
            }
            assertEquals("a reply was sent again", 1, published.size)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `the original GI replayed on-chain and over the relays leaves the group inactive`() =
        runBlocking {
            val (phone, _) = removedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val original = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 2)

            repeat(3) {
                AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), originalGi)
                AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), sender = ADMIN, eventId = "synthetic-event-original-gi")
                HandshakeHarness.awaitIdle(vm)
                assertStaysOut(phone, seam, "the original invite replayed")
            }
        }

    @Test
    fun `our own sent GI leaves the group inactive`() =
        runBlocking {
            val (phone, _) = removedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            // A GI this phone sent (to X), naming this phone as inviter, at a fresh time and a higher epoch.
            phone.link(X)
            val own = AnnounceDriver.gi(phone.self, phone.sessionWith(X), AnnounceDriver.newGroupKey(), keyEpoch = 3)

            repeat(3) {
                AnnounceDriver.receive(
                    vm,
                    own,
                    Instant.ofEpochMilli(T + 20_000),
                    AnnounceDriver.txId(201),
                    ownSend = true,
                    ownRecipient = X
                )
                HandshakeHarness.awaitIdle(vm)
                assertStaysOut(phone, seam, "our own sent invite")
            }
        }

    @Test
    fun `a GI from a contact who is not the creator leaves the group inactive`() =
        runBlocking {
            val (phone, _) = removedPhone()
            val q = "usyntheticma2otherpeerq00001"
            phone.link(q)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            // Q holds a key exchange with this phone and wraps "a new key" for the group's id; it names itself, or the
            // creator, as the inviter.
            val fromQ = AnnounceDriver.gi(q, phone.sessionWith(q), AnnounceDriver.newGroupKey(), keyEpoch = 3)
            val spoof = AnnounceDriver.gi(ADMIN, phone.sessionWith(q), AnnounceDriver.newGroupKey(), keyEpoch = 3)

            repeat(3) {
                AnnounceDriver.receive(vm, fromQ, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(300))
                AnnounceDriver.receive(vm, spoof, Instant.ofEpochMilli(T + 20_001), AnnounceDriver.txId(301))
                AnnounceDriver.receive(vm, fromQ, Instant.ofEpochMilli(T + 20_002), sender = q, eventId = "synthetic-event-q")
                HandshakeHarness.awaitIdle(vm)
                assertStaysOut(phone, seam, "a GI from a non-creator contact")
            }
            assertEquals("the creator was replaced", ADMIN, phone.info(GID).creatorAddress)
        }

    @Test
    fun `a GI at a lower key epoch leaves the group inactive and never lowers the epoch`() =
        runBlocking {
            val (phone, _) = removedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val lower = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 1)

            repeat(3) {
                AnnounceDriver.receive(vm, lower, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(400))
                HandshakeHarness.awaitIdle(vm)
                assertStaysOut(phone, seam, "a GI at a lower key epoch")
            }
        }

    @Test
    fun `a group left before the upgrade ignores its old invite and still takes a newer one`() =
        runBlocking {
            val (phone, _) = removedPhone(legacy = true)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val original = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 2)

            repeat(3) {
                AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), originalGi)
                HandshakeHarness.awaitIdle(vm)
                assertStaysOut(phone, seam, "the original invite of a group left before the upgrade")
            }
            assertNotNull("the first sight of a legacy inactive group did not record when it went inactive", phone.state(GID).inactiveSince)

            val later = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 2)
            AnnounceDriver.receive(vm, later, Instant.now().plusSeconds(120), AnnounceDriver.txId(500))
            HandshakeHarness.awaitIdle(vm)
            assertNotNull("a newer re-invite of a group left before the upgrade does not wait for Join", phone.needsJoin[GID])
            assertFalse(phone.info(GID).isActive)
            assertEquals(0, seam.proposalRequests.get())
        }
}
