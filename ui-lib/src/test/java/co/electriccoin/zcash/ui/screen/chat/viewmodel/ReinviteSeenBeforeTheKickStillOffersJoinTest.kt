package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * ZC-294 (r8-findings SPECS §9) — a re-invite seen while this phone is still in the group is not burned.
 *
 * The creator removes a member and invites it again. The two messages can reach the member's phone in the other
 * order: the re-invite (sent at T2) first, while the group is still active there, and the removal (sent at T1 < T2)
 * after it. An invite for an active group was recorded as used, so after the removal it could never offer Join: an
 * on-chain copy replayed by the scan was refused by its id, and a relay copy is delivered once.
 *
 * Wanted: an invite from the group's creator seen while this phone is still in the group is neither used nor lost. When
 * the phone is removed afterwards and the invite is newer than the removal, it offers Join like any re-invite (the
 * needs-join record names it); nothing is sent or paid before Join. The newest such invite of the creator is the one
 * kept, and no other member's invite can take its place. Everything that was refused before stays refused: the
 * original invite, a re-invite older than the removal, an invite that is not the creator's, our own sent invite, and a
 * replay for a group deleted here.
 *
 * Consent GRANTED everywhere; nothing is proposed for payment. All addresses, keys, txids and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReinviteSeenBeforeTheKickStillOffersJoinTest {
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

    private val originalGi: TransactionId = AnnounceDriver.txId(100)
    private val kickTx: TransactionId = AnnounceDriver.txId(150)
    private val reinviteTx: TransactionId = AnnounceDriver.txId(200)

    /** The removal's source time (T1) and the re-invite's (T2): the re-invite was sent after the removal. */
    private val removedAt = T + 10_000
    private val reinvitedAt = T + 20_000

    /** A member's phone still in the group (key epoch 1), which it joined with [originalGi]; the creator's relay key is known. */
    private fun activePhone(): Pair<AnnouncePhone, E2EKeyPair> {
        val phone = AnnouncePhone()
        val admin = AnnounceDriver.memberPhone(phone)
        phone.states[GID] = AnnounceState(usedInviteIds = setOf(originalGi.txIdString()))
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        return phone to admin
    }

    /** The creator's re-invite at key epoch 2 (the epoch its removal rotates to). */
    private fun reinvite(
        phone: AnnouncePhone,
        key: String = AnnounceDriver.newGroupKey(),
    ): String = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), key, keyEpoch = 2)

    /** The creator's signed removal of this phone, mined at [removedAt]. @return its memo, as the scan replays it. */
    private suspend fun kickSelf(
        phone: AnnouncePhone,
        admin: E2EKeyPair,
        vm: ChatViewModel,
    ): String {
        val kick = AnnounceDriver.kick(phone, admin, phone.self, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
        AnnounceDriver.receive(vm, kick, Instant.ofEpochMilli(removedAt), kickTx)
        HandshakeHarness.awaitIdle(vm)
        assertFalse("fixture: the creator's kick did not remove this phone", phone.info(GID).isActive)
        assertTrue("fixture: the group is not marked as removed", phone.info(GID).removedByAdmin)
        return kick
    }

    /** The id of the invite the needs-join record of [groupId] offers Join for; null when no re-invite waits. */
    private fun offeredInviteId(
        phone: AnnouncePhone,
        groupId: String = GID,
    ): String? =
        phone.needsJoin[groupId]?.let { record ->
            JSONObject(record).optJSONObject("reopen")?.optString("invite_id")?.takeIf { it.isNotEmpty() }
        }

    private fun assertNothingHappened(
        phone: AnnouncePhone,
        seam: HandshakeSeam,
        what: String,
    ) {
        assertTrue("$what changed the active group", phone.info(GID).isActive)
        assertNull("$what left a needs-join record on an active group", phone.needsJoin[GID])
        assertEquals("$what changed the key epoch of an active group", 1, phone.epochs[GID])
        assertTrue("$what sent something: $published", published.isEmpty())
        assertEquals("$what: something was proposed for payment", 0, seam.proposalRequests.get())
    }

    private fun assertNotOffered(
        phone: AnnouncePhone,
        seam: HandshakeSeam,
        what: String,
    ) {
        assertFalse("$what reopened the group", phone.info(GID).isActive)
        assertNull("$what offers Join", phone.needsJoin[GID])
        assertTrue("$what sent something: $published", published.isEmpty())
        assertEquals("$what: something was proposed for payment", 0, seam.proposalRequests.get())
    }

    @Test
    fun `relays - the creator's sealed re-invite seen while still in the group offers Join once the kick from before it arrives`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val newKey = AnnounceDriver.newGroupKey()

            AnnounceDriver.receive(vm, reinvite(phone, newKey), Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "a re-invite seen while still in the group")

            kickSelf(phone, admin, vm)

            assertEquals("the re-invite seen before the kick does not offer Join", I2_EVENT, offeredInviteId(phone))
            assertFalse("the re-invite reopened the group without Join", phone.info(GID).isActive)
            assertTrue("something was sent before Join: $published", published.isEmpty())
            assertEquals("something was proposed for payment", 0, seam.proposalRequests.get())

            assertTrue("Join was refused", vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(vm)

            assertTrue("Join did not reopen the group", phone.info(GID).isActive)
            assertEquals("the key epoch is not the re-invite's", 2, phone.epochs[GID])
            assertEquals("the key is not the re-invite's", newKey, phone.keys["$GID|2"])
            assertEquals(MemberStatus.ACTIVE, phone.status(GID, phone.self))
            assertTrue("the invite used for the join is not recorded as used", I2_EVENT in phone.state(GID).usedInviteIds)
            assertEquals(
                "the one reply to the creator did not go free over the relays",
                1,
                published.count { (text, to) ->
                    to == ADMIN_NOSTR && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_ACCEPT
                },
            )
            assertEquals("something was proposed for payment", 0, seam.proposalRequests.get())
        }

    @Test
    fun `relays - the re-invite seen while still in the group offers Join once the removal announce from before it arrives`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)

            AnnounceDriver.receive(vm, reinvite(phone), Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "a re-invite seen while still in the group")

            // The creator's verified "this phone is no longer in the group", numbered and sent at the removal's time.
            val removal = AnnounceDriver.gp(AnnounceOp.R, phone.self, removedAt, 1, admin)
            AnnounceDriver.receive(vm, removal, Instant.ofEpochMilli(removedAt), sender = ADMIN, eventId = "synthetic-event-removal")
            HandshakeHarness.awaitIdle(vm)
            assertFalse("fixture: the removal announce did not remove this phone", phone.info(GID).isActive)

            assertEquals("the re-invite seen before the removal announce does not offer Join", I2_EVENT, offeredInviteId(phone))
            assertTrue("something was sent before Join: $published", published.isEmpty())
            assertEquals("something was proposed for payment", 0, seam.proposalRequests.get())
        }

    @Test
    fun `on-chain - a re-invite processed while still in the group and replayed by the scan after the kick offers Join`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val i2 = reinvite(phone)

            repeat(2) {
                AnnounceDriver.receive(vm, i2, Instant.ofEpochMilli(reinvitedAt), reinviteTx)
                HandshakeHarness.awaitIdle(vm)
                assertNothingHappened(phone, seam, "an on-chain re-invite processed while still in the group")
            }

            val kick = kickSelf(phone, admin, vm)

            // Every later scan pass replays the kick and the re-invite.
            repeat(3) { pass ->
                AnnounceDriver.receive(vm, kick, Instant.ofEpochMilli(removedAt), kickTx)
                AnnounceDriver.receive(vm, i2, Instant.ofEpochMilli(reinvitedAt), reinviteTx)
                HandshakeHarness.awaitIdle(vm)
                assertEquals(
                    "pass ${pass + 1}: the on-chain re-invite processed before the kick does not offer Join",
                    reinviteTx.txIdString(),
                    offeredInviteId(phone),
                )
                assertFalse("pass ${pass + 1}: the re-invite reopened the group without Join", phone.info(GID).isActive)
                assertTrue("pass ${pass + 1}: something was sent before Join: $published", published.isEmpty())
                assertEquals(0, seam.proposalRequests.get())
            }
        }

    @Test
    fun `on-chain - a re-invite first seen unmined while still in the group offers Join when the scan replays it mined after the kick`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val i2 = reinvite(phone)

            // Not mined yet: no source time, so nothing about it can be compared with a removal.
            AnnounceDriver.receive(vm, i2, null, reinviteTx)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "an unmined re-invite seen while still in the group")

            kickSelf(phone, admin, vm)
            AnnounceDriver.receive(vm, i2, Instant.ofEpochMilli(reinvitedAt), reinviteTx)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("the re-invite replayed after the kick does not offer Join", reinviteTx.txIdString(), offeredInviteId(phone))
            assertTrue("something was sent before Join: $published", published.isEmpty())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `of two re-invites of the creator seen while still in the group, the newer one offers Join, in either order of arrival`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)

            // The newer one (I3) arrives first, then the older one (I2); both are newer than the removal.
            AnnounceDriver.receive(vm, reinvite(phone), Instant.ofEpochMilli(reinvitedAt + 5_000), sender = ADMIN, eventId = I3_EVENT)
            AnnounceDriver.receive(vm, reinvite(phone), Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "two re-invites seen while still in the group")

            kickSelf(phone, admin, vm)

            assertEquals("the newest re-invite of the creator is not the one that offers Join", I3_EVENT, offeredInviteId(phone))
            assertTrue("something was sent before Join: $published", published.isEmpty())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a newer invite of another member seen while still in the group does not take the place of the creator's re-invite`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val q = "usyntheticma2otherpeerq00001"
            phone.link(q)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            // Q holds a key exchange with this phone and wraps "a new key" for the group's id, naming itself or the creator.
            val fromQ = AnnounceDriver.gi(q, phone.sessionWith(q), AnnounceDriver.newGroupKey(), keyEpoch = 3)
            val spoof = AnnounceDriver.gi(ADMIN, phone.sessionWith(q), AnnounceDriver.newGroupKey(), keyEpoch = 3)

            AnnounceDriver.receive(vm, reinvite(phone), Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
            AnnounceDriver.receive(vm, fromQ, Instant.ofEpochMilli(reinvitedAt + 5_000), sender = q, eventId = "synthetic-event-q")
            AnnounceDriver.receive(vm, spoof, Instant.ofEpochMilli(reinvitedAt + 6_000), AnnounceDriver.txId(301))
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "invites seen while still in the group")

            kickSelf(phone, admin, vm)

            assertEquals("the creator's re-invite was replaced by another member's invite", I2_EVENT, offeredInviteId(phone))
            assertEquals("the creator was replaced", ADMIN, phone.info(GID).creatorAddress)
            assertTrue("something was sent before Join: $published", published.isEmpty())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `Open group - the creator's plain-key re-invite seen while still in the group offers Join after the sealed kick from before it`() =
        runBlocking {
            NostrChatBridge.unregisterPublisher()
            RelayNet().register()
            val (phone, chat) = OpenGroup.plainMember(OpenGroup.X)
            val removedAtMs = MA3.now() - 100_000L
            val reinvitedAtMs = MA3.now() - 1_000L
            val newKey = AnnounceDriver.newGroupKey()

            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = newKey), sender = OpenGroup.C, timeMs = reinvitedAtMs)
            assertTrue("a plain-key re-invite seen while still in the group changed it", phone.info(OpenGroup.GID).isActive)
            assertNull("a plain-key re-invite left a needs-join record on an active group", phone.needsJoin[OpenGroup.GID])
            assertEquals("a plain-key re-invite changed the key epoch of an active group", 1, phone.epochs[OpenGroup.GID])

            MA3.relay(chat, OpenGroup.sealedKick(OpenGroup.X, newEpoch = 2, gk = null), sender = OpenGroup.C, timeMs = removedAtMs)
            assertFalse("fixture: the creator's sealed kick did not remove this phone", phone.info(OpenGroup.GID).isActive)

            assertTrue(
                "the plain-key re-invite seen before the sealed kick does not offer Join",
                offeredInviteId(phone, OpenGroup.GID) != null,
            )
            assertTrue("Join refused", GroupInviteDriver.join(chat, OpenGroup.GID))
            assertTrue("Join did not reopen the group", phone.info(OpenGroup.GID).isActive)
            assertEquals("the reopened group does not use the re-invite's key", newKey, phone.keys["${OpenGroup.GID}|2"])
        }

    @Test
    fun `control - the original invite, replayed under its id and delivered again under another, is not offered after the kick`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val original = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 1)

            AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), originalGi)
            AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), sender = ADMIN, eventId = "synthetic-event-original-gi")
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "the original invite replayed while still in the group")

            kickSelf(phone, admin, vm)
            assertNotOffered(phone, seam, "the original invite, seen again before the kick,")

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), originalGi)
                AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), sender = ADMIN, eventId = "synthetic-event-original-gi")
                AnnounceDriver.receive(vm, original, Instant.ofEpochMilli(T), sender = ADMIN, eventId = "synthetic-event-original-gi-2")
                HandshakeHarness.awaitIdle(vm)
                assertNotOffered(phone, seam, "pass ${pass + 1}: the original invite replayed after the kick")
            }
        }

    @Test
    fun `control - a re-invite older than the removal, seen while still in the group, is not offered after the kick`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val olderRelay = reinvite(phone)
            val olderChain = reinvite(phone)
            val olderAt = removedAt - 5_000

            AnnounceDriver.receive(vm, olderRelay, Instant.ofEpochMilli(olderAt), sender = ADMIN, eventId = I2_EVENT)
            AnnounceDriver.receive(vm, olderChain, Instant.ofEpochMilli(olderAt), reinviteTx)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "a re-invite seen while still in the group")

            kickSelf(phone, admin, vm)
            assertNotOffered(phone, seam, "a re-invite older than the removal")

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, olderChain, Instant.ofEpochMilli(olderAt), reinviteTx)
                HandshakeHarness.awaitIdle(vm)
                assertNotOffered(phone, seam, "pass ${pass + 1}: a re-invite older than the removal, replayed by the scan,")
            }
        }

    @Test
    fun `control - an invite of another member or our own sent invite, seen while still in the group, is not offered after the kick`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val q = "usyntheticma2otherpeerq00001"
            phone.link(q)
            phone.link(X)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val fromQ = AnnounceDriver.gi(q, phone.sessionWith(q), AnnounceDriver.newGroupKey(), keyEpoch = 3)
            val spoof = AnnounceDriver.gi(ADMIN, phone.sessionWith(q), AnnounceDriver.newGroupKey(), keyEpoch = 3)
            val own = AnnounceDriver.gi(phone.self, phone.sessionWith(X), AnnounceDriver.newGroupKey(), keyEpoch = 3)

            suspend fun deliver() {
                AnnounceDriver.receive(vm, fromQ, Instant.ofEpochMilli(reinvitedAt), sender = q, eventId = "synthetic-event-q")
                AnnounceDriver.receive(vm, fromQ, Instant.ofEpochMilli(reinvitedAt), AnnounceDriver.txId(300))
                AnnounceDriver.receive(vm, spoof, Instant.ofEpochMilli(reinvitedAt + 1), AnnounceDriver.txId(301))
                AnnounceDriver.receive(
                    vm,
                    own,
                    Instant.ofEpochMilli(reinvitedAt + 2),
                    AnnounceDriver.txId(302),
                    ownSend = true,
                    ownRecipient = X,
                )
                HandshakeHarness.awaitIdle(vm)
            }

            deliver()
            assertNothingHappened(phone, seam, "invites that are not the creator's, seen while still in the group,")

            kickSelf(phone, admin, vm)
            assertNotOffered(phone, seam, "an invite that is not the creator's")

            repeat(3) { pass ->
                deliver()
                assertNotOffered(phone, seam, "pass ${pass + 1}: an invite that is not the creator's, replayed after the kick,")
            }
            assertEquals("the creator was replaced", ADMIN, phone.info(GID).creatorAddress)
        }

    @Test
    fun `control - after this phone left the group itself, a re-invite seen before the leave is not offered by a late kick`() =
        runBlocking {
            val (phone, admin) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)

            AnnounceDriver.receive(vm, reinvite(phone), Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "a re-invite seen while still in the group")

            // The user leaves the group (what the group screen's Leave stores: inactive, not removed, left now).
            phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(phone.info(GID).copy(isActive = false))
            phone.states[GID] = phone.state(GID).copy(inactiveSince = System.currentTimeMillis())

            // The creator's kick from before the re-invite arrives late, at a newer key epoch.
            kickSelf(phone, admin, vm)

            assertNotOffered(phone, seam, "a re-invite from before our own leave")
        }

    @Test
    fun `control - a group deleted here is not recreated by a replay of a re-invite seen while it was active`() =
        runBlocking {
            val (phone, _) = activePhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.relayAcks(published)
            val onChain = reinvite(phone)
            val sealed = reinvite(phone)

            AnnounceDriver.receive(vm, onChain, Instant.ofEpochMilli(reinvitedAt), reinviteTx)
            AnnounceDriver.receive(vm, sealed, Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
            HandshakeHarness.awaitIdle(vm)
            assertNothingHappened(phone, seam, "a re-invite seen while still in the group")

            // The user deletes the group. This store keeps the whole announce state across the delete; the real store
            // keeps a tombstone of it (GroupAnnounceStoreTest pins what the tombstone holds).
            phone.proxy.deleteGroup(GID)

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, onChain, Instant.ofEpochMilli(reinvitedAt), reinviteTx)
                AnnounceDriver.receive(vm, sealed, Instant.ofEpochMilli(reinvitedAt), sender = ADMIN, eventId = I2_EVENT)
                HandshakeHarness.awaitIdle(vm)
                assertNull("pass ${pass + 1}: a replayed invite recreated the deleted group", phone.infos[GID])
                assertNull("pass ${pass + 1}: a replayed invite left a needs-join record", phone.needsJoin[GID])
                assertTrue("pass ${pass + 1}: something was sent: $published", published.isEmpty())
                assertEquals(0, seam.proposalRequests.get())
            }
        }

    private companion object {
        const val I2_EVENT = "synthetic-event-reinvite-i2"
        const val I3_EVENT = "synthetic-event-reinvite-i3"
    }
}
