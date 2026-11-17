package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
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

/**
 * ZC-420-G residual R-1 (the sealed route's removal notice; found by the T7aF lane, 15 Oct) — in a group with no key
 * exchange, the sealed removal notice (GR-OPEN G2 finding 4a, `sendSealedRemovalNotice`) that only a public relay took
 * leaves no relay-copy mark, so nothing ever delivers it again and the removed member is never told (its phone keeps the
 * group active). P-G3 (5ff9b3007) marked the sealed key copies; the notice to the removed member was left out.
 *
 * Wanted: the notice is marked, and the next group open on the creator's phone sends the same notice again (a GROUP_KICK
 * naming the removed member, no key, no `gk`, "sig": ""), free over the relays, never on-chain, and only while the
 * removal stands: once the person is in the group again the mark is forgotten and nothing is sent.
 *
 * Every address, key and group id is synthetic. Nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupRemovalNoticeOnlyAPublicRelayTookIsRedeliveredTest {
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

    /** The creator with A and B, neither with a key exchange (an all-Open group); both have a relay key. */
    private fun openWorld(): GroupCopyWorld =
        GroupCopyWorld(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE)).also { w ->
            worlds += w
            val base = w.admin.base
            for (member in listOf(A, B)) {
                base.ourKeys.remove(member)
                base.peerKeys.remove(member)
            }
        }

    private fun fixture() =
        GroupPaidOnceFixture().apply { scheduler = this@OpenGroupRemovalNoticeOnlyAPublicRelayTookIsRedeliveredTest.scheduler }

    /** The GROUP_KICK payloads published to [member], as JSON. */
    private fun kicks(
        w: GroupCopyWorld,
        member: String,
    ): List<JSONObject> =
        w
            .copies(GroupMessageType.GROUP_KICK, member)
            .mapNotNull { ZMSGGroupProtocol.parsePayload(it)?.let { p -> JSONObject(p) } }

    /** Every relay-copy mark that names [member] as the slot's last field. */
    private fun marksOf(
        w: GroupCopyWorld,
        member: String,
    ): List<String> {
        val slots = w.marks.map.keys
        return slots.filter { it.endsWith("|$member") }
    }

    /** Removes B with B's relays answering only from a public relay; returns the world. */
    private suspend fun removeBOnlyAPublicRelayTakes(): GroupCopyWorld {
        val w = openWorld()
        w.answer(B, 0, 1)
        w.net.keptNotShared()
        val f = fixture()
        val vm = w.groupVm(f)
        f.awaitNewJobs(vm) { vm.kickMember(GID, B) }
        if (vm.rekeyUnkeyableConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmRekeyUnkeyable() }
        assertNull("fixture: a removal over the relays asked a price", vm.paidActionConfirmation.value)
        assertEquals("fixture: the removal did not move the creator to key 2", 2, w.admin.base.epochs[GID])
        assertEquals("fixture: B was not sent the sealed removal notice", 1, kicks(w, B).size)
        return w
    }

    /** A restart: nothing kept in the outbox, B's relays answer again, a new screen opens the group. */
    private suspend fun restartAndOpen(w: GroupCopyWorld) {
        w.net.kept = null
        w.answer(B, 1, 1)
        w.net.clear()
        val f2 = fixture()
        w.open(w.groupVm(f2), f2)
    }

    @Test
    fun `no session - a removal notice only a public relay took is marked and sent again at the next group open`() =
        runBlocking {
            val w = removeBOnlyAPublicRelayTakes()
            assertTrue(
                "a sealed removal notice only a public relay took left no relay-copy mark, so the removed member is never told",
                marksOf(w, B).isNotEmpty(),
            )

            restartAndOpen(w)

            val again = kicks(w, B)
            assertEquals("the group open did not tell B again that B was removed", 1, again.size)
            val kick = again.single()
            assertEquals(B, kick.getString("kicked"))
            assertEquals(2, kick.getInt("new_epoch"))
            assertFalse("the re-sent removal notice carries the group key in plaintext", kick.has("gk"))
            assertFalse("the re-sent removal notice carries a wrapped key", kick.has("enc_key"))
            assertEquals("", kick.optString("sig", "missing"))
            assertEquals(
                "the removed member was sent a group key",
                emptyList<String>(),
                w.copies(GroupMessageType.GROUP_KEY, B),
            )
            assertEquals("the mark stayed after a shared relay took the notice", emptyList<String>(), marksOf(w, B))
            assertEquals("a removal notice made an on-chain proposal", emptyList<PaidMemo>(), w.seam.proposals.toList())
        }

    @Test
    fun `the removed person is in the group again - the mark is forgotten and nothing is sent to them`() =
        runBlocking {
            val w = removeBOnlyAPublicRelayTakes()
            assertTrue("fixture: the removal notice left no mark", marksOf(w, B).isNotEmpty())
            w.setRow(B) { it.copy(status = MemberStatus.INVITED) }

            restartAndOpen(w)

            assertEquals("a removal notice went to a person who is in the group again", emptyList<JSONObject>(), kicks(w, B))
            assertEquals("the removal mark stayed although the removal no longer stands", emptyList<String>(), marksOf(w, B))
            assertEquals(emptyList<PaidMemo>(), w.seam.proposals.toList())
        }
}
