package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
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
import org.junit.Before
import org.junit.Test

/**
 * ZC-420-G P-G3, phone check of train T6b (Fold creator, HONOR member, 15 Oct, failed twice) — in a group whose members
 * have no key exchange with the creator, a rotated key that only a public relay took is never delivered again, so the
 * member cannot read any later message.
 *
 * On the phone: the creator rotated with the member's relays faulted (shared 0, readable 1): the key memo was "sent FREE
 * (1 relay ack)" with no relay-copy mark; after a force-stop and a group open only the message copy ("gm") was re-sent;
 * the member never showed the next messages. ZC-420-G marks and repairs the signed key copy only: the sealed key of a
 * member with no session (GR-OPEN G2, `sendSealedKey`) carries no mark (its residual R-1 left "the sealed route" out),
 * and the repair forgets any key mark of a member with no session.
 *
 * Wanted: the sealed key copy is marked like the signed one, and the next group open delivers the current key again in
 * its sealed form, free over the relays, never on-chain.
 *
 * Every address, key and group id is synthetic. Nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupKeyOnlyAPublicRelayTookIsRedeliveredTest {
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

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@OpenGroupKeyOnlyAPublicRelayTookIsRedeliveredTest.scheduler }

    private val slot = RelayCopySlots.groupKey(GID, B)

    /** The sealed key copies (a GROUP_KEY carrying the key in `gk`) published to [member], as JSON payloads. */
    private fun sealedKeys(
        w: GroupCopyWorld,
        member: String,
    ): List<JSONObject> =
        w
            .copies(GroupMessageType.GROUP_KEY, member)
            .mapNotNull { ZMSGGroupProtocol.parsePayload(it)?.let { p -> JSONObject(p) } }
            .filter { it.has("gk") }

    @Test
    fun `no session - a rotated key only a public relay took is marked and re-delivered sealed at the next group open`() =
        runBlocking {
            val w = openWorld()
            // B's relays: only a public relay takes the copy; the outbox keeps it unshared.
            w.answer(B, 0, 1)
            w.net.keptNotShared()
            val f = fixture()
            val vm = w.groupVm(f)
            f.awaitNewJobs(vm) { vm.rotateGroupKey(GID) }
            if (vm.rekeyUnkeyableConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmRekeyUnkeyable() }
            assertNull("fixture: a rotation of members reachable over the relays asked a price", vm.paidActionConfirmation.value)
            assertEquals("fixture: the rotation did not move the creator to key 2", 2, w.admin.base.epochs[GID])
            assertEquals("fixture: B was not sent the sealed key", 1, sealedKeys(w, B).size)
            assertEquals(
                "a sealed key copy only a public relay took left no relay-copy mark, so nothing ever delivers it again",
                "2",
                w.marks.extra(slot),
            )

            // A restart: nothing kept in the outbox, a new screen, B's relays answer again.
            w.net.kept = null
            w.answer(B, 1, 1)
            w.net.clear()
            val f2 = fixture()
            w.open(w.groupVm(f2), f2)

            val again = sealedKeys(w, B)
            assertEquals("the group open did not deliver the current key to B again", 1, again.size)
            assertEquals(2, again.single().getInt("epoch"))
            assertEquals("the re-delivered key is not the creator's key 2", w.admin.base.keys["$GID|2"], again.single().getString("gk"))
            assertFalse("the mark stayed after a shared relay took the key", slot in w.marks.map)
            assertEquals("a sealed key copy made an on-chain proposal", emptyList<PaidMemo>(), w.seam.proposals.toList())
        }
}
