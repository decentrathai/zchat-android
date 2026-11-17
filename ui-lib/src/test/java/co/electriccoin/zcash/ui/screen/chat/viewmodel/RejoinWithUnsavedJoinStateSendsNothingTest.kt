package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.heldInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.PASS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-292 (R8 findings, SPECS §7) — a Join whose join state was not saved reopens the group and sends the reply anyway.
 *
 * The creator removed this phone from a group and invited it again; the re-invite waits for Join. Join reopens the
 * group in steps: the roster, the key, then the announce state (the removal is over, this phone joined at the
 * invite's key epoch, the invite is used), then the group is marked active and the reply is sent. The result of the
 * announce-state write is ignored. When the store refuses it, the group is active and the creator has the reply, but
 * the stored state still says "removed": after a restart an older removal can take this phone out again, and a replay
 * of the same invite is not known as used.
 *
 * Wanted: a Join whose join state was not saved answers false, leaves the group closed, sends nothing (free or paid),
 * keeps the re-invite waiting and leaves no held copy of the reply; the next Join, with a working store, reopens the
 * group and sends the one reply.
 *
 * The removed phone is the ReinviteReopensInactiveGroupTest fixture. All addresses, keys, txids and group ids are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RejoinWithUnsavedJoinStateSendsNothingTest {
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

    private val removedAt = T + 6_000

    /** A member's phone the creator removed: the group is inactive (removed) at key epoch 2. */
    private fun removedPhone(): AnnouncePhone {
        val phone = AnnouncePhone()
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
        return phone
    }

    private fun repliesPublished(): Int =
        published.count { (text, to) ->
            to == ADMIN_NOSTR && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_ACCEPT
        }

    private suspend fun vmOn(
        store: ZchatPreferences,
        phone: AnnouncePhone,
        seam: HandshakeSeam,
    ): ChatViewModel =
        HandshakeHarness.chatViewModelWith(store, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(phone.self))
        }

    /** The creator's re-invite at key epoch 3 arrives and waits for Join; @return its group key. */
    private suspend fun reinviteWaitsForJoin(
        vm: ChatViewModel,
        phone: AnnouncePhone,
    ): String {
        AnnounceDriver.relayAcks(published)
        val newKey = AnnounceDriver.newGroupKey()
        val fresh = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), newKey, keyEpoch = 3)
        AnnounceDriver.receive(vm, fresh, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(200))
        HandshakeHarness.awaitIdle(vm)
        assertNotNull("fixture sanity: the re-invite waits for Join", phone.needsJoin[GID])
        assertFalse("fixture sanity: the group is still closed", phone.info(GID).isActive)
        assertEquals("fixture sanity: the stored state says removed", removedAt, phone.state(GID).inactiveSince)
        return newKey
    }

    private fun assertReopenedAndRepliedOnce(
        phone: AnnouncePhone,
        newKey: String,
        seam: HandshakeSeam,
    ) {
        assertTrue("Join did not reopen the group", phone.info(GID).isActive)
        assertEquals("the key is not the invite's", newKey, phone.keys["$GID|3"])
        assertEquals(MemberStatus.ACTIVE, phone.status(GID, phone.self))
        val state = phone.state(GID)
        assertNull("the stored state still says removed", state.inactiveSince)
        assertEquals("the join was not recorded at the invite's key epoch", 3, state.selfJoinEpoch)
        // ZC-291: the join is numbered at the later of the highest announce number seen and the invite's source time.
        assertEquals("the join was not recorded at the re-invite's source time", maxOf(state.hw, T + 20_000), state.selfJoinN)
        assertTrue("the invite was not recorded as used", AnnounceDriver.txId(200).txIdString() in state.usedInviteIds)
        assertEquals("the one reply to the creator did not go free over the relays", 1, repliesPublished())
        assertNull("a joined re-invite still waits for Join", phone.needsJoin[GID])
        assertTrue("a delivered reply left its held copy on disk", phone.heldAccepts.isEmpty())
        assertFalse(heldInMemory(GID))
        assertEquals(0, seam.proposalRequests.get())
    }

    private fun refusedJoinSendsNothing(refusal: () -> Any?) =
        runBlocking {
            val phone = removedPhone()
            val stateWritesFail = AtomicBoolean(false)
            val refusedWrites = AtomicInteger(0)
            val store =
                JoinHeldCopy.intercepting(phone.proxy) { name, _ ->
                    if (name == "saveGroupAnnounceState" && stateWritesFail.get()) {
                        refusedWrites.incrementAndGet()
                        refusal()
                    } else {
                        PASS
                    }
                }
            val seam = HandshakeSeam(ADMIN)
            val vm = vmOn(store, phone, seam)
            val newKey = reinviteWaitsForJoin(vm, phone)
            val stateBefore = phone.state(GID)

            stateWritesFail.set(true)
            val joined = vm.joinGroupInvite(GID)
            HandshakeHarness.awaitIdle(vm)

            assertTrue("fixture sanity: Join's reopen never asked the store to save the join state", refusedWrites.get() >= 1)
            assertEquals("fixture sanity: the refused write changed nothing", stateBefore, phone.state(GID))
            assertEquals(
                "a Join whose join state was not saved sent its reply: the creator counts this phone in, and its stored " +
                    "state still says removed",
                0,
                repliesPublished(),
            )
            assertEquals("a Join whose join state was not saved asked for a paid reply", 0, seam.proposalRequests.get())
            assertFalse("a Join whose join state was not saved opened the group", phone.info(GID).isActive)
            assertFalse("Join must answer false when its join state was not saved", joined)
            assertNotNull("a Join whose join state was not saved deleted its re-invite", phone.needsJoin[GID])
            assertTrue("a refused Join left a held copy of its reply on disk", phone.heldAccepts.isEmpty())
            assertFalse("a refused Join left its reply held in memory", heldInMemory(GID))

            // The store takes the write again: the same re-invite reopens the group and its one reply goes free.
            stateWritesFail.set(false)
            assertTrue("the re-invite could not be joined once the store worked again", vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(vm)
            assertReopenedAndRepliedOnce(phone, newKey, seam)
        }

    @Test
    fun `a Join whose join state the store does not confirm sends nothing, keeps the group closed and the re-invite waiting`() =
        refusedJoinSendsNothing { false }

    @Test
    fun `a Join whose join state write throws sends nothing, keeps the group closed and the re-invite waiting`() =
        refusedJoinSendsNothing { throw IllegalStateException("synthetic: the store is not writable") }

    @Test
    fun `control - a working store reopens the group, records the join and sends the one reply`() =
        runBlocking {
            val phone = removedPhone()
            val seam = HandshakeSeam(ADMIN)
            val vm = vmOn(JoinHeldCopy.intercepting(phone.proxy) { _, _ -> PASS }, phone, seam)
            val newKey = reinviteWaitsForJoin(vm, phone)

            assertTrue("a re-invite could not be joined on a working store", vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(vm)
            assertReopenedAndRepliedOnce(phone, newKey, seam)
        }
}
