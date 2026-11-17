package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-420-G (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.5, §4.3; register ZC-420, high) — a group message copy that only a
 * public relay took is remembered, named, and delivered again for free at the next group open.
 *
 * C (the creator, this phone) writes to a group with A and B, both reachable over the relays. The relays of A answer
 * (shared 1, readable 1); those of B answer (0, 1): only a public relay took B's copy, and ZChat 2.12.4, which reads
 * ZChat's relay and nos.lol only, never sees it. Today the copy counts as delivered (that stays: it is the money
 * decision, PaidRelayFallbacksKeepTheirCountTest), nothing remembers it, the outbox keeping it in memory loses it at a
 * restart, and nothing on screen says so.
 *
 * Wanted: the send makes no proposal, stores and shows the message, raises RelayUnconfirmed naming B only, and leaves one
 * "gm" mark for B's copy. A new GroupViewModel (a restart: no kept wrap) that opens the group with B's relays answering
 * (1, 1) publishes exactly one GROUP_MSG to B: the same text, sequence number and key number, signed with C's pairwise key
 * for B; the mark goes; nothing is proposed. The other cases: a kept wrap a shared relay now holds confirms it with no
 * publish; B left, or the text is gone: the mark goes, nothing is published; B has no relay key now: the mark stays; no
 * relay at the repair under the standing consent: nothing is proposed; 20 older marks of members with no relay key do not
 * use up the open's attempts; at an open the names come back (atOpen) and the reload after a send raises none; the view
 * never takes an atOpen result as the outcome of a send; a member whose address resolves to another canonical address since
 * the send is still matched; a partial delivery's public-only member is named at the next open; a group with no owed member
 * repairs; a new send of the same copy forgets its old mark before the relay step. Controls: all (1, 1) and B (0, 0) are as
 * today. All addresses, keys, ids and texts are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageOnlyAPublicRelayTookIsRedeliveredTest {
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

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@GroupMessageOnlyAPublicRelayTookIsRedeliveredTest.scheduler }

    private fun slot(
        member: String = B,
        epoch: Int = 1,
        seq: Long = 1,
    ) = RelayCopySlots.groupMessage(GID, member, epoch, seq)

    private fun gmMarks(w: GroupCopyWorld): List<String> {
        val prefix = RelayCopySlots.ofGroup("gm", GID)
        return w.marks.map.keys
            .filter { it.startsWith(prefix) }
    }

    /** C sends [text]; B's relays answer [b] and A's [a]; the same process keeps the wrap (not shared). */
    private suspend fun send(
        w: GroupCopyWorld,
        text: String = TEXT,
        b: Pair<Int, Int> = 0 to 1,
        a: Pair<Int, Int> = 1 to 1,
    ): Pair<GroupViewModel, GroupPaidOnceFixture> {
        w.answer(A, a.first, a.second)
        w.answer(B, b.first, b.second)
        w.net.keptNotShared()
        val f = fixture()
        val vm = w.groupVm(f)
        w.open(vm, f)
        vm.consumeGroupSendEvent()
        f.awaitNewJobs(vm) { vm.sendGroupMessage(GID, text) }
        assertNull("fixture: a group message to members reachable over the relays asked a price", vm.paidActionConfirmation.value)
        return vm to f
    }

    /** A restart: no kept wrap, a new GroupViewModel, the relays of B answering [b]. */
    private suspend fun reopen(
        w: GroupCopyWorld,
        b: Pair<Int, Int> = 1 to 1,
    ): GroupViewModel {
        w.net.kept = null
        w.answer(B, b.first, b.second)
        w.net.clear()
        val f = fixture()
        val vm = w.groupVm(f)
        w.open(vm, f)
        return vm
    }

    private fun assertNothingProposed(w: GroupCopyWorld) =
        assertEquals("a relay copy only a public relay took made an on-chain proposal", emptyList<PaidMemo>(), w.seam.proposals.toList())

    @Test
    fun `the send keeps today's result, names B only and marks B's copy`() =
        runBlocking {
            val w = world()
            val (vm, _) = send(w)

            assertNothingProposed(w)
            val stored =
                w.admin.base.messages[GID]
                    .orEmpty()
            assertTrue("the message is not stored", TEXT in stored)
            val event = RelayUnconfirmedApi.read(vm.groupSendEvent.value)
            assertNotNull("the send raised no RelayUnconfirmed result: ${vm.groupSendEvent.value}", event)
            assertEquals("the banner does not name exactly B", listOf("Bea"), event!!.first)
            assertFalse("the send's own result is marked as a group-open notice", event.second)
            assertEquals("not exactly one gm mark, for B's copy", listOf(slot()), gmMarks(w))
        }

    @Test
    fun `after a restart the group open publishes exactly one GROUP_MSG to B with the same text, number and key, and the mark goes`() =
        runBlocking {
            val w = world()
            send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)
            assertTrue("fixture: B is owed nothing", w.roster().none { it.pendingKeyEpoch != null })

            reopen(w)

            val copies = w.copies(GroupMessageType.GROUP_MSG, B)
            assertEquals("not exactly one GROUP_MSG re-delivered to B", 1, copies.size)
            val payload = ZMSGGroupProtocol.parseGroupMsgPayload(ZMSGGroupProtocol.parsePayload(copies.single())!!)!!
            assertEquals(1L, payload.seq)
            assertEquals(1, payload.epoch)
            assertEquals(C, payload.sender)
            val encodedKey =
                w.admin.base.keys
                    .getValue("$GID|1")
            val key = ZMSGGroupProtocol.decodeGroupKey(encodedKey)
            val text = ZMSGGroupProtocol.decryptMessage(payload.nonce, payload.ciphertext, key)
            assertEquals("the re-delivered copy is not the same text", TEXT, text)
            assertTrue(
                "the re-delivered copy is not signed with C's pairwise key for B",
                E2EEncryption.verify(
                    MA3.keyFor(w.admin, B).publicKey,
                    ZMSGGroupProtocol.groupMsgSignedData(GID, C, 1, 1, payload.ciphertext),
                    payload.signature,
                ),
            )
            assertTrue("A was sent a copy again", w.copies(GroupMessageType.GROUP_MSG, A).isEmpty())
            assertFalse("the mark stayed after a shared relay took the copy", slot() in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `in the same process a kept wrap a shared relay holds now confirms the copy with no publish`() =
        runBlocking {
            val w = world()
            val (vm, f) = send(w)
            w.net.keptShared()
            w.net.clear()

            w.open(vm, f)

            assertTrue("the kept wrap was not asked for", w.net.keptAsks.isNotEmpty())
            assertTrue("a copy was rebuilt although the kept wrap is on a shared relay", w.copies(GroupMessageType.GROUP_MSG, B).isEmpty())
            assertFalse("the mark stayed", slot() in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `a member who left, or a message whose text is gone, drops the mark and publishes nothing`() =
        runBlocking {
            val left = world()
            send(left)
            assertTrue("fixture: the send left no mark", slot() in left.marks.map)
            left.setRow(B) { it.copy(status = MemberStatus.LEFT) }
            reopen(left)
            assertTrue("a member who left was sent the copy", left.copies(GroupMessageType.GROUP_MSG, B).isEmpty())
            assertFalse("the mark of a member who left stayed", slot() in left.marks.map)
            left.close()

            val gone = world()
            send(gone)
            assertTrue("fixture: the send left no mark", slot() in gone.marks.map)
            gone.admin.base.messages
                .remove(GID)
            reopen(gone)
            assertTrue("a copy was sent with no stored text", gone.copies(GroupMessageType.GROUP_MSG, B).isEmpty())
            assertFalse("the mark of a message with no stored text stayed", slot() in gone.marks.map)
            assertNothingProposed(gone)
        }

    @Test
    fun `a member with no relay key now keeps the mark and is sent nothing`() =
        runBlocking {
            val w = world()
            send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)
            w.admin.base.nostrKeys
                .remove(B)

            reopen(w)

            assertTrue(w.copies(GroupMessageType.GROUP_MSG, B).isEmpty())
            assertTrue("the mark of a member with no relay key now was dropped", slot() in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `no relay at the repair under the standing consent proposes nothing and keeps the mark`() =
        runBlocking {
            val w = world()
            send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)
            // B would be paid for by a later message quote, and the handshake-spend consent is given (MA3 phones).
            w.admin.base.spendConsents[GID] = setOf(B)

            reopen(w, b = 0 to 0)

            assertNothingProposed(w)
            assertTrue("the mark went though no relay took the copy", slot() in w.marks.map)
        }

    @Test
    fun `controls - every relay shared leaves no mark and no event, and B with no relay is today's partial delivery`() =
        runBlocking {
            val shared = world()
            val (vm, _) = send(shared, b = 1 to 1)
            assertNull("an all-shared send raised ${vm.groupSendEvent.value}", vm.groupSendEvent.value)
            assertTrue("an all-shared send left a mark", gmMarks(shared).isEmpty())
            assertNothingProposed(shared)
            shared.close()

            val none = world()
            val (vm2, _) = send(none, b = 0 to 0)
            val event = vm2.groupSendEvent.value
            assertTrue("B with no relay is not today's partial delivery: $event", event is GroupSendResult.PartialDelivery)
            assertTrue("a copy no relay took left a mark", gmMarks(none).isEmpty())
            assertNothingProposed(none)
        }

    @Test
    fun `twenty older marks of members with no relay key do not stop a repairable mark in the same open`() =
        runBlocking {
            val w = world()
            send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)
            val others = (1..20).map { "usyntheticg420memberno%013d".format(it) }
            w.editRoster { rows -> rows + others.map { GroupCopyWorld.memberRow(it) } }
            val t = System.currentTimeMillis() - 7_200_000L
            others.forEachIndexed { i, m -> w.marks.map[slot(m)] = "synthetic-old-$i|${t + i}|" }

            reopen(w)

            val toB = w.copies(GroupMessageType.GROUP_MSG, B)
            assertEquals("B's repairable copy was not re-delivered past 20 marks that cannot go now", 1, toB.size)
            assertFalse(slot() in w.marks.map)
            assertEquals("a mark that cannot go now was dropped", 20, others.count { slot(it) in w.marks.map })
            assertNothingProposed(w)
        }

    @Test
    fun `at a group open the names come back while the copy is still not confirmed`() =
        runBlocking {
            val w = world()
            send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)

            val vm = reopen(w, b = 0 to 1)

            val event = RelayUnconfirmedApi.read(vm.groupSendEvent.value)
            assertNotNull("the group open did not name B again: ${vm.groupSendEvent.value}", event)
            assertEquals(listOf("Bea"), event!!.first)
            assertTrue("the group-open notice is not marked atOpen", event.second)
            assertTrue("the mark went though only a public relay took the copy again", slot() in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `the reload after a send raises no group-open notice`() =
        runBlocking {
            val w = world()
            val (vm, f) = send(w)
            assertTrue("fixture: the first send left no mark", slot() in w.marks.map)
            vm.consumeGroupSendEvent()
            // The next message reaches every relay; the first copy's kept wrap is still not on a shared relay.
            w.answer(B, 1, 1)
            f.awaitNewJobs(vm) { vm.sendGroupMessage(GID, "second synthetic line") }

            assertNull("the reload after a delivered send raised ${vm.groupSendEvent.value}", vm.groupSendEvent.value)
            assertTrue("fixture: the first copy's mark went", slot() in w.marks.map)
        }

    @Test
    fun `the group screen never takes a group-open notice as the outcome of a send`() {
        val view = ChatResourceText.source(DETAIL_VIEW)
        val effect = view.substringAfter("LaunchedEffect(isSendingMessage, sendResult)").substringBefore("LaunchedEffect(messageText")
        val wait = effect.lines().firstOrNull { it.trim().startsWith("if (awaitingSendOutcome") }
        assertNotNull("fixture: the composer's wait in the send-result effect", wait)
        assertTrue(
            "an atOpen RelayUnconfirmed result ends the composer's wait and clears its text: $wait",
            "RelayUnconfirmed" in wait!! && "atOpen" in wait,
        )
    }

    @Test
    fun `a member whose address resolves to another canonical address since the send is still matched`() =
        runBlocking {
            val w = world()
            send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)
            // An alias learned later: B's address now resolves to another form.
            w.admin.base.aliases[B] = B2

            reopen(w)

            assertEquals("the mark of a member whose address drifted was not re-delivered", 1, w.copies(GroupMessageType.GROUP_MSG, B).size)
            assertFalse(slot() in w.marks.map)
        }

    @Test
    fun `a send that ended as a partial delivery names its public-only member at the next open`() =
        runBlocking {
            val w = world()
            val (vm, _) = send(w, a = 0 to 0)
            val sent = vm.groupSendEvent.value
            assertTrue("fixture: not a partial delivery: $sent", sent is GroupSendResult.PartialDelivery)
            assertTrue("the public-only copy of a partial delivery left no mark", slot() in w.marks.map)

            val next = reopen(w, b = 0 to 1)

            val event = RelayUnconfirmedApi.read(next.groupSendEvent.value)
            assertNotNull("the next open did not name B: ${next.groupSendEvent.value}", event)
            assertEquals(listOf("Bea"), event!!.first)
        }

    @Test
    fun `a new send of the same copy forgets its old mark before the relay step`() =
        runBlocking {
            val w = world()
            // MA2's store numbers every message 1: two sends make the same copy.
            val (vm, f) = send(w)
            assertTrue("fixture: the send left no mark", slot() in w.marks.map)
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(vm, w.admin.proxy)
            w.answer(B, 0, 0)
            f.awaitNewJobs(vm) { vm.sendGroupMessage(GID, TEXT) }

            assertFalse("the old mark stayed on a copy no relay took this time", slot() in w.marks.map)
        }

    private companion object {
        const val TEXT = "synthetic group line for B"
        const val B2 = "usyntheticma3memberb000000002"
        const val DETAIL_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt"
    }
}
