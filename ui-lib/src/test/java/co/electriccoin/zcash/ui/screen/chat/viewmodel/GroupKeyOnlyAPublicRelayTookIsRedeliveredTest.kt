package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-420-G (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.6 items 1 and 2, §4.3; registers ZC-420, high, and ZC-716) — a
 * group key, or a removal, that only a public relay took for a member is delivered again for free at the creator's next
 * group open, a removal as a removal.
 *
 * C (the creator) rotates the group key, or removes X, while B's relays answer (shared 0, readable 1). Today B is recorded
 * as delivered (that stays: B is not owed, so the paying owed-key pass is not involved) and nothing remembers the copy: a
 * B on ZChat 2.12.4 keeps the old key, cannot read the group's next messages, and after a removal keeps listing X, whose
 * posts it still shows.
 *
 * Wanted: one "gk" mark for B with the new key number (and, for a removal, the removed address); after a restart the
 * group open publishes one signed GROUP_KEY "resync" of the current key that B's processGroupKey adopts, or, for a
 * removal, one signed GROUP_KICK of the current key naming X that B's processGroupKick applies (X LEFT on B's roster, B on
 * the new key): a plain key first would make the removal impossible to apply later (both versions apply a removal only
 * when its key number is newer). X added again since: the plain key, B's roster untouched. The key number moved on, or B
 * left: the mark goes, nothing is sent. B owed again: the mark goes and only the owed-key pass sends. B with no relay key
 * now: the mark stays. With the standing consent and no relay at the repair: nothing is proposed, beside the control that an
 * owed member in the same state is paid for once, as today. The owed-key re-send marks its copy too. A removal whose roster
 * write was refused (ZC-778 (c), ZC-791: X is still ACTIVE on C) is re-delivered as the plain key: the removal is not
 * recorded on this phone, and removing again is a new action. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupKeyOnlyAPublicRelayTookIsRedeliveredTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val worlds = mutableListOf<GroupCopyWorld>()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        worlds.forEach { it.close() }
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun world(vararg members: String = arrayOf(A, B)): GroupCopyWorld =
        GroupCopyWorld(members.map { it to MemberStatus.ACTIVE }).also { worlds += it }

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@GroupKeyOnlyAPublicRelayTookIsRedeliveredTest.scheduler }

    private val slot = RelayCopySlots.groupKey(GID, B)

    /** C rotates the key (or removes [kicked]) while B's relays answer (0, 1) and the outbox keeps the wrap. */
    private suspend fun rekey(
        w: GroupCopyWorld,
        kicked: String? = null,
        prefs: ZchatPreferences? = null,
    ) {
        w.answer(B, 0, 1)
        w.net.keptNotShared()
        val f = fixture()
        val vm = w.groupVm(f)
        prefs?.let {
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(vm, it)
        }
        f.awaitNewJobs(vm) { if (kicked == null) vm.rotateGroupKey(GID) else vm.kickMember(GID, kicked) }
        assertNull("fixture: a rotation of members reachable over the relays asked a price", vm.paidActionConfirmation.value)
    }

    /** A restart: no kept wrap, a new GroupViewModel, B's relays answering [b]; the copies published from here on. */
    private suspend fun reopen(
        w: GroupCopyWorld,
        b: Pair<Int, Int> = 1 to 1,
    ) {
        w.net.kept = null
        w.answer(B, b.first, b.second)
        w.net.clear()
        val f = fixture()
        w.open(w.groupVm(f), f)
    }

    private fun assertNothingProposed(w: GroupCopyWorld) =
        assertEquals("a key copy only a public relay took made an on-chain proposal", emptyList<PaidMemo>(), w.seam.proposals.toList())

    /** B's own phone lists X too (B saw X join). */
    private fun listXOnB(w: GroupCopyWorld) {
        val phoneB = w.phones.getValue(B).base
        val withX = phoneB.roster(GID) + GroupCopyWorld.memberRow(X)
        phoneB.rosters[GID] = ZMSGGroupProtocol.serializeGroupMembers(withX)
    }

    /** B's own phone takes [memo] from C over the relays. */
    private suspend fun deliverToB(
        w: GroupCopyWorld,
        memo: String,
    ) {
        val vmB = MA3.chatVm(w.phones.getValue(B))
        MA3.relay(vmB, memo, sender = C)
    }

    @Test
    fun `a rotation only a public relay took for B leaves B delivered and one gk mark with the new key number`() =
        runBlocking {
            val w = world()
            rekey(w)

            assertEquals("fixture: the rotation did not move C to key 2", 2, w.admin.base.epochs[GID])
            assertNull("B is owed the key (today's result is: delivered)", w.row(B).pendingKeyEpoch)
            assertEquals("no gk mark for B's key copy", "2", w.marks.extra(slot))
            assertFalse("A's shared copy left a mark", RelayCopySlots.groupKey(GID, A) in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `after a restart the group open re-delivers one signed GROUP_KEY resync of the current key that B adopts`() =
        runBlocking {
            val w = world()
            rekey(w)
            assertTrue("fixture: the rotation left no mark", slot in w.marks.map)

            reopen(w)

            val keys = w.copies(GroupMessageType.GROUP_KEY, B)
            assertEquals("not exactly one GROUP_KEY re-delivered to B", 1, keys.size)
            val payload = ZMSGGroupProtocol.parseGroupKeyPayload(GID, ZMSGGroupProtocol.parsePayload(keys.single())!!)!!
            assertEquals(2, payload.epoch)
            assertEquals("resync", payload.reason)
            assertTrue(
                "the re-delivered key is not signed with C's pairwise key for B",
                E2EEncryption.verify(
                    MA3.keyFor(w.admin, B).publicKey,
                    ZMSGGroupProtocol.groupKeySignedData(GID, C, 2, payload.encryptedGroupKey, "resync"),
                    payload.signature!!,
                ),
            )
            assertFalse("the mark stayed after a shared relay took the key", slot in w.marks.map)
            assertNothingProposed(w)

            deliverToB(w, keys.single())
            val phoneB = w.phones.getValue(B)
            assertEquals("B's processGroupKey did not adopt the re-delivered key", 2, phoneB.base.epochs[GID])
            assertEquals(w.admin.base.keys["$GID|2"], phoneB.base.keys["$GID|2"])
        }

    @Test
    fun `with the standing consent and no relay at the repair nothing is proposed, while an owed member is paid once as today`() =
        runBlocking {
            val marked = world()
            rekey(marked)
            assertTrue("fixture: the rotation left no mark", slot in marked.marks.map)
            reopen(marked, b = 0 to 0)
            assertNothingProposed(marked)
            assertTrue("the mark went though no relay took the copy", slot in marked.marks.map)
            marked.close()

            // Control: an owed member (no mark) in the same state is paid for by the owed-key pass under the consent.
            val owed = world()
            owed.setRow(B) { it.copy(pendingKeyEpoch = 1) }
            reopen(owed, b = 0 to 0)
            assertEquals(
                "control: the owed-key pass did not pay for an owed member once",
                1,
                owed.seam.proposals.count { it.recipient == B },
            )
        }

    @Test
    fun `the key number moved on, or B left, drops the mark and sends nothing`() =
        runBlocking {
            val moved = world()
            rekey(moved)
            assertTrue("fixture: the rotation left no mark", slot in moved.marks.map)
            moved.admin.base.keys["$GID|3"] = AnnounceDriver.newGroupKey()
            moved.admin.base.epochs[GID] = 3
            reopen(moved)
            assertTrue("an old key number was re-delivered", moved.copies(GroupMessageType.GROUP_KEY, B).isEmpty())
            assertFalse("the mark of an old key number stayed", slot in moved.marks.map)
            moved.close()

            val left = world()
            rekey(left)
            assertTrue("fixture: the rotation left no mark", slot in left.marks.map)
            left.setRow(B) { it.copy(status = MemberStatus.LEFT) }
            reopen(left)
            assertTrue("a member who left was sent the key", left.copies(GroupMessageType.GROUP_KEY, B).isEmpty())
            assertFalse("the mark of a member who left stayed", slot in left.marks.map)
        }

    @Test
    fun `B owed again at the open drops the mark and only the owed-key pass sends`() =
        runBlocking {
            val w = world()
            rekey(w)
            assertTrue("fixture: the rotation left no mark", slot in w.marks.map)
            w.setRow(B) { it.copy(pendingKeyEpoch = 2) }

            reopen(w)

            assertEquals("not exactly one key to B (the owed-key pass)", 1, w.copies(GroupMessageType.GROUP_KEY, B).size)
            assertFalse("the mark stayed", slot in w.marks.map)
        }

    @Test
    fun `the owed-key re-send marks a copy only a public relay took, and the next open re-delivers it`() =
        runBlocking {
            val w = world()
            w.setRow(B) { it.copy(pendingKeyEpoch = 1) }
            reopen(w, b = 0 to 1)
            assertNull("fixture: the owed-key pass left B owed", w.row(B).pendingKeyEpoch)
            assertEquals("the owed-key re-send left no gk mark", "1", w.marks.extra(slot))

            reopen(w)

            assertEquals(1, w.copies(GroupMessageType.GROUP_KEY, B).size)
            assertFalse(slot in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `B with no relay key now keeps the mark`() =
        runBlocking {
            val w = world()
            rekey(w)
            assertTrue("fixture: the rotation left no mark", slot in w.marks.map)
            w.admin.base.nostrKeys
                .remove(B)

            reopen(w)

            assertTrue(w.copies(GroupMessageType.GROUP_KEY, B).isEmpty())
            assertTrue("the mark of a member with no relay key now was dropped", slot in w.marks.map)
        }

    @Test
    fun `a removal only a public relay took for B is re-delivered as the removal, and B applies it`() =
        runBlocking {
            val w = world(A, B, X)
            listXOnB(w)
            rekey(w, kicked = X)
            assertEquals("no gk mark naming the removal of X", "2|$X", w.marks.extra(slot))

            reopen(w)

            val kicks = w.copies(GroupMessageType.GROUP_KICK, B)
            assertEquals("the removal was not re-delivered as one GROUP_KICK", 1, kicks.size)
            assertTrue("a plain key went beside the removal", w.copies(GroupMessageType.GROUP_KEY, B).isEmpty())
            val kick = ZMSGGroupProtocol.parseGroupKickPayload(ZMSGGroupProtocol.parsePayload(kicks.single())!!)!!
            assertEquals(X, kick.kicked)
            assertEquals(2, kick.newEpoch)
            assertTrue(
                "the re-delivered removal is not signed with C's pairwise key for B",
                E2EEncryption.verify(
                    MA3.keyFor(w.admin, B).publicKey,
                    ZMSGGroupProtocol.groupKickSignedData(GID, X, C, 2, kick.encryptedGroupKey),
                    kick.signature!!,
                ),
            )
            assertFalse(slot in w.marks.map)
            assertNothingProposed(w)

            deliverToB(w, kicks.single())
            val phoneB = w.phones.getValue(B)
            assertEquals("B's processGroupKick did not take X off its roster", MemberStatus.LEFT, phoneB.status(X))
            assertEquals("B is not on the new key", 2, phoneB.base.epochs[GID])
        }

    @Test
    fun `X added again since the removal gets B the plain key, and B's roster is untouched`() =
        runBlocking {
            val w = world(A, B, X)
            listXOnB(w)
            rekey(w, kicked = X)
            assertEquals("fixture: no removal mark", "2|$X", w.marks.extra(slot))
            w.setRow(X) { it.copy(status = MemberStatus.INVITED) }

            reopen(w)

            assertTrue("X, added again, was removed on B's phone", w.copies(GroupMessageType.GROUP_KICK, B).isEmpty())
            val keys = w.copies(GroupMessageType.GROUP_KEY, B)
            assertEquals("not one plain key for B", 1, keys.size)
            deliverToB(w, keys.single())
            val phoneB = w.phones.getValue(B)
            assertEquals(MemberStatus.ACTIVE, phoneB.status(X))
            assertEquals(2, phoneB.base.epochs[GID])
        }

    @Test
    fun `a removal mark whose key number moved on is dropped, a rotation is still the plain key, and no relay key keeps it`() =
        runBlocking {
            val moved = world(A, B, X)
            rekey(moved, kicked = X)
            assertEquals("fixture: no removal mark", "2|$X", moved.marks.extra(slot))
            moved.admin.base.keys["$GID|3"] = AnnounceDriver.newGroupKey()
            moved.admin.base.epochs[GID] = 3
            reopen(moved)
            assertTrue(moved.copies(GroupMessageType.GROUP_KICK, B).isEmpty())
            assertTrue(moved.copies(GroupMessageType.GROUP_KEY, B).isEmpty())
            assertFalse("the removal mark of an old key number stayed", slot in moved.marks.map)
            moved.close()

            val noKey = world(A, B, X)
            rekey(noKey, kicked = X)
            assertEquals("fixture: no removal mark", "2|$X", noKey.marks.extra(slot))
            noKey.admin.base.nostrKeys
                .remove(B)
            reopen(noKey)
            assertTrue("the removal mark of a member with no relay key now was dropped", slot in noKey.marks.map)
        }

    @Test
    fun `a removal whose roster write was refused is re-delivered as the plain key, the removal not being recorded here`() =
        runBlocking {
            val w = world(A, B, X)
            val refusing =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    if (method.name == "saveGroupMembersDurably" && args!!.size == 3) {
                        false
                    } else {
                        try {
                            method.invoke(w.prefs, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                } as ZchatPreferences
            rekey(w, kicked = X, prefs = refusing)
            assertEquals("fixture: the refused removal did not still move C to key 2", 2, w.admin.base.epochs[GID])
            assertEquals("fixture: X is not still ACTIVE on C", MemberStatus.ACTIVE, w.row(X).status)
            assertEquals("the key copy of the refused removal left no mark", "2|$X", w.marks.extra(slot))

            reopen(w)

            assertTrue("a removal not recorded on this phone was re-delivered", w.copies(GroupMessageType.GROUP_KICK, B).isEmpty())
            assertEquals("not one plain key for B", 1, w.copies(GroupMessageType.GROUP_KEY, B).size)
            assertNothingProposed(w)
        }
}
