package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * ZC-291 (r8-findings SPECS §8) — a removal announce from before the re-invite does not take this phone out of the
 * group again after its Join.
 *
 * A member's phone ignores every member announce while its group is inactive, so the verified `R self` that goes with
 * its removal never raises the state's high-water mark when it arrives late. Join then recorded the join at that old
 * high-water mark, and the same `R self` (the creator's group screen re-sends every current R, and a relay or the scan
 * can replay it) is numbered above it: at the same key epoch it removed this phone again, and the invite it used cannot
 * offer Join a second time.
 *
 * Wanted: the re-invite's source time travels with its needs-join record (`source_ms`), and Join records the join at
 * the later of the high-water mark and that time. Announce numbers are the creator's clock, so a removal numbered
 * before the re-invite changes nothing after Join, and a removal numbered after it still removes this phone. A record
 * written before this change (no `source_ms`) joins at the high-water mark as before.
 *
 * Consent GRANTED everywhere; nothing is proposed for payment. All addresses, keys, txids and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RejoinIsNotUndoneByARemovalFromBeforeTheInviteTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val oldHw = T + 1_000
    private val kickedAt = T + 6_000
    private val invitedAt = T + 20_000
    private val removalTx: TransactionId = AnnounceDriver.txId(150)
    private val inviteTx: TransactionId = AnnounceDriver.txId(200)

    /**
     * A member's phone the creator removed with a signed kick at [kickedAt]: the group is inactive (removed) at key
     * epoch 2, and the removal's member announce has not been applied (the last announce applied was numbered
     * [oldHw]).
     */
    private fun kickedPhone(): Pair<AnnouncePhone, E2EKeyPair> {
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
        phone.states[GID] =
            AnnounceState(
                hw = oldHw,
                rosterAnnounced = true,
                inactiveSince = kickedAt,
                usedInviteIds = setOf(AnnounceDriver.txId(100).txIdString()),
            )
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        return phone to admin
    }

    /**
     * The removal's `R self` (numbered [kickedAt], key epoch 2) arrives while the group is inactive and is ignored;
     * then the creator's re-invite at the same key epoch, with source time [invitedAt], waits for Join. [overRelays]:
     * the re-invite comes sealed over the relays instead of mined on-chain. Returns the old removal's memo.
     */
    private suspend fun removalIgnoredThenReinvited(
        phone: AnnouncePhone,
        admin: E2EKeyPair,
        vm: ChatViewModel,
        overRelays: Boolean = false,
    ): String {
        val oldRemoval = AnnounceDriver.gp(AnnounceOp.R, phone.self, kickedAt, 2, admin)
        AnnounceDriver.receive(vm, oldRemoval, Instant.ofEpochMilli(kickedAt), removalTx)
        HandshakeHarness.awaitIdle(vm)
        assertEquals("fixture: the removal announce was applied while the group was inactive", oldHw, phone.state(GID).hw)
        assertFalse("fixture: the group is not inactive", phone.info(GID).isActive)

        val reinvite = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 2)
        if (overRelays) {
            AnnounceDriver.receive(vm, reinvite, Instant.ofEpochMilli(invitedAt), sender = ADMIN, eventId = "synthetic-event-reinvite")
        } else {
            AnnounceDriver.receive(vm, reinvite, Instant.ofEpochMilli(invitedAt), inviteTx)
        }
        HandshakeHarness.awaitIdle(vm)
        assertNotNull("fixture: the creator's re-invite does not wait for Join", phone.needsJoin[GID])
        assertFalse("fixture: the re-invite reopened the group without Join", phone.info(GID).isActive)
        return oldRemoval
    }

    private suspend fun join(
        phone: AnnouncePhone,
        vm: ChatViewModel,
    ) {
        assertTrue("fixture: Join was refused", vm.joinGroupInvite(GID))
        HandshakeHarness.awaitIdle(vm)
        assertTrue("fixture: Join did not reopen the group", phone.info(GID).isActive)
        assertEquals("fixture: the group did not reopen at the re-invite's key epoch", 2, phone.state(GID).selfJoinEpoch)
    }

    private fun assertStillIn(
        phone: AnnouncePhone,
        what: String,
    ) {
        assertTrue("$what removed this phone again after its Join", phone.info(GID).isActive)
        assertFalse("$what marked the group as removed", phone.info(GID).removedByAdmin)
        assertEquals("$what changed this phone's own row", MemberStatus.ACTIVE, phone.status(GID, phone.self))
    }

    @Test
    fun `a removal ignored while inactive, delivered again after Join on a newer re-invite, does not remove this phone`() =
        runBlocking {
            val (phone, admin) = kickedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val oldRemoval = removalIgnoredThenReinvited(phone, admin, vm)

            join(phone, vm)

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, oldRemoval, Instant.ofEpochMilli(kickedAt), removalTx)
                HandshakeHarness.awaitIdle(vm)
                assertStillIn(phone, "pass ${pass + 1}: the removal from before the re-invite, replayed by the scan,")
                // The creator's group screen sends its current R again over the relays.
                AnnounceDriver.receive(
                    vm,
                    oldRemoval,
                    Instant.ofEpochMilli(invitedAt + 5_000),
                    sender = ADMIN,
                    eventId = "synthetic-event-old-removal-${pass + 1}",
                )
                HandshakeHarness.awaitIdle(vm)
                assertStillIn(phone, "pass ${pass + 1}: the removal from before the re-invite, sent again over the relays,")
            }
            assertEquals("the join is not recorded at the re-invite's source time", invitedAt, phone.state(GID).selfJoinN)
            assertEquals("something was proposed for payment", 0, seam.proposalRequests.get())
        }

    @Test
    fun `the same holds for a re-invite that came over the relays`() =
        runBlocking {
            val (phone, admin) = kickedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val oldRemoval = removalIgnoredThenReinvited(phone, admin, vm, overRelays = true)

            join(phone, vm)

            AnnounceDriver.receive(
                vm,
                oldRemoval,
                Instant.ofEpochMilli(invitedAt + 5_000),
                sender = ADMIN,
                eventId = "synthetic-event-old-removal",
            )
            HandshakeHarness.awaitIdle(vm)
            assertStillIn(phone, "the removal from before the relay re-invite")
            assertEquals("the join is not recorded at the re-invite's source time", invitedAt, phone.state(GID).selfJoinN)
            assertEquals("something was proposed for payment", 0, seam.proposalRequests.get())
        }

    @Test
    fun `control - a removal numbered after the re-invite still removes this phone`() =
        runBlocking {
            val (phone, admin) = kickedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            removalIgnoredThenReinvited(phone, admin, vm)
            join(phone, vm)

            val laterRemoval = AnnounceDriver.gp(AnnounceOp.R, phone.self, invitedAt + 1_000, 2, admin)
            AnnounceDriver.receive(vm, laterRemoval, Instant.ofEpochMilli(invitedAt + 1_000), AnnounceDriver.txId(250))
            HandshakeHarness.awaitIdle(vm)

            assertFalse("a removal numbered after the re-invite did not remove this phone", phone.info(GID).isActive)
            assertTrue("the group does not say this phone was removed", phone.info(GID).removedByAdmin)
            assertEquals(MemberStatus.LEFT, phone.status(GID, phone.self))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `control - a high-water mark above the re-invite's time stays the join number`() =
        runBlocking {
            val (phone, admin) = kickedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            removalIgnoredThenReinvited(phone, admin, vm)
            val ahead = invitedAt + 60_000
            phone.states[GID] = phone.state(GID).copy(hw = ahead)

            join(phone, vm)

            assertEquals("Join lowered the join number below the high-water mark", ahead, phone.state(GID).selfJoinN)
        }

    @Test
    fun `control - a needs-join record written before source_ms existed joins at the high-water mark`() =
        runBlocking {
            val (phone, admin) = kickedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            removalIgnoredThenReinvited(phone, admin, vm)
            // The record as the build before this change wrote it: the same fields, no source time.
            val record = JSONObject(phone.needsJoin.getValue(GID))
            record.getJSONObject("reopen").remove("source_ms")
            phone.needsJoin[GID] = record.toString()

            join(phone, vm)

            assertEquals("a record without a source time did not join at the high-water mark", oldHw, phone.state(GID).selfJoinN)
            assertTrue("the invite used for the join is not recorded", inviteTx.txIdString() in phone.state(GID).usedInviteIds)
        }

    @Test
    fun `a plain-key re-invite to an Open group records the join at its source time too`() =
        runBlocking {
            NostrChatBridge.unregisterPublisher()
            RelayNet().register()
            val (phone, chat) = OpenGroup.plainMember(OpenGroup.X)
            val removedAtMs = MA3.now() - 100_000L
            MA3.relay(chat, OpenGroup.sealedKick(OpenGroup.X, newEpoch = 2, gk = null), sender = OpenGroup.C, timeMs = removedAtMs)
            assertFalse("fixture: the creator's sealed kick did not remove this phone", phone.info(OpenGroup.GID).isActive)
            val reinvitedAtMs = MA3.now() - 1_000L

            MA3.relay(
                chat,
                OpenGroup.plainInvite(keyEpoch = 2, key = AnnounceDriver.newGroupKey()),
                sender = OpenGroup.C,
                timeMs = reinvitedAtMs,
            )
            assertNotNull("fixture: the plain-key re-invite does not wait for Join", phone.needsJoin[OpenGroup.GID])
            assertTrue("fixture: Join refused", GroupInviteDriver.join(chat, OpenGroup.GID))
            assertTrue("fixture: Join did not reopen the group", phone.info(OpenGroup.GID).isActive)

            val state = phone.state(OpenGroup.GID)
            assertEquals(
                "the join is not recorded at the later of the high-water mark and the plain-key re-invite's source time",
                maxOf(state.hw, reinvitedAtMs),
                state.selfJoinN,
            )
        }
}
