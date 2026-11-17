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

/**
 * ZC-284 (R8 findings, SPECS §6.4, step 4) — a Join that cannot reopen its group deletes the re-invite it was for.
 *
 * The creator removed this phone from a group and invited it again; the re-invite waits for Join (the needs-join record
 * carries the new key). Join deletes that record first and only then reopens the group. When the reopen's first write is
 * refused by the store, Join gives up with the group still closed and the record already gone: the Join banner
 * disappears with nothing joined.
 *
 * Wanted: the record is cleared only after the reopen worked. A Join that cannot reopen answers false, keeps the
 * re-invite waiting, leaves no held copy of the reply (on disk or in memory) and sends nothing; the next Join reopens
 * the group and sends the one reply, free over the relays.
 *
 * The removed phone is the ReinviteReopensInactiveGroupTest fixture. All addresses, keys, txids and group ids are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinThatCannotReopenKeepsTheInviteTest {
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

    @Test
    fun `a Join whose reopen is refused by the store keeps the re-invite waiting, and the next Join reopens and replies once`() =
        runBlocking {
            val phone = removedPhone()
            val rosterWritesFail = AtomicBoolean(false)
            val store =
                JoinHeldCopy.intercepting(phone.proxy) { name, _ ->
                    if (name == "saveGroupMembersDurably" && rosterWritesFail.get()) false else PASS
                }
            val seam = HandshakeSeam(ADMIN)
            val vm =
                HandshakeHarness.chatViewModelWith(store, seam).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(phone.self))
                }
            AnnounceDriver.relayAcks(published)
            val newKey = AnnounceDriver.newGroupKey()
            val fresh = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), newKey, keyEpoch = 3)
            AnnounceDriver.receive(vm, fresh, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(200))
            HandshakeHarness.awaitIdle(vm)
            assertNotNull("fixture sanity: the re-invite waits for Join", phone.needsJoin[GID])
            assertFalse("fixture sanity: the group is still closed", phone.info(GID).isActive)

            rosterWritesFail.set(true)
            assertFalse("Join must answer false when the group cannot be reopened", vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(vm)

            assertFalse("fixture sanity: the group was not reopened", phone.info(GID).isActive)
            assertNotNull(
                "a Join that could not reopen the group deleted its re-invite: the banner is gone with nothing joined",
                phone.needsJoin[GID],
            )
            assertTrue("a refused Join left a held copy of its reply on disk", phone.heldAccepts.isEmpty())
            assertFalse("a refused Join left its reply held in memory", heldInMemory(GID))
            assertEquals("a refused Join sent a reply", 0, repliesPublished())
            assertEquals(0, seam.proposalRequests.get())

            // The store takes the write again: the same re-invite reopens the group and its one reply goes free.
            rosterWritesFail.set(false)
            assertTrue("the re-invite could not be joined once the store worked again", vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(vm)

            assertTrue("Join did not reopen the group", phone.info(GID).isActive)
            assertEquals("the key is not the invite's", newKey, phone.keys["$GID|3"])
            assertEquals(MemberStatus.ACTIVE, phone.status(GID, phone.self))
            assertEquals("the one reply to the creator did not go free over the relays", 1, repliesPublished())
            assertNull("a joined re-invite still waits for Join", phone.needsJoin[GID])
            assertTrue("a delivered reply left its held copy on disk", phone.heldAccepts.isEmpty())
            assertFalse(heldInMemory(GID))
            assertEquals(0, seam.proposalRequests.get())
        }
}
