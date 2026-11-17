package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1046 (high; T7aF phone run 19 Oct, P-G3 r1 and r2, Fold creator and HONOR member) — a group key copy only a public
 * relay took is repaired only from Group Settings, and the message that arrived before the repair is never shown.
 *
 * On the phone: C (the Fold) rotated the key while B's relays only answered publicly ("gk copy ... marked"), then sent a
 * message ("gm copy ... marked"). After a restart, the group open re-sent the message copy but not the key copy; B could
 * not read it ("Failed to decrypt GROUP_MSG (msg epoch 2, tried [2,1,0])"). The key copy went only when Group Settings was
 * opened (and the user came back); B then adopted the key, but the message that had arrived before it never appeared,
 * not after two reopens.
 *
 * (a) The key-copy repair at the group open needs the screen's own address, which a new screen reads asynchronously; it
 *     returned silently before it was known (the same cause as ZC-1045, fixed there). Pinned here as a guard.
 * (b) B drops a group message it cannot decrypt for want of a newer key, and nothing reads it again once that key is
 *     adopted (the relay never delivers the same event twice). Wanted: the message is kept and shown once the key that
 *     reads it arrives. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageBeforeItsKeyIsShownOnceTheKeyArrivesTest {
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

    private fun world(): GroupCopyWorld = GroupCopyWorld(listOf(A, B).map { it to MemberStatus.ACTIVE }).also { worlds += it }

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@GroupMessageBeforeItsKeyIsShownOnceTheKeyArrivesTest.scheduler }

    /** C rotates the key while B's relays answer (0 shared, 1 readable): B is not keyed and a "gk" mark is left. */
    private suspend fun rekey(w: GroupCopyWorld) {
        w.answer(B, 0, 1)
        w.net.keptNotShared()
        val f = fixture()
        val vm = w.groupVm(f)
        f.awaitNewJobs(vm) { vm.rotateGroupKey(GID) }
        assertEquals("fixture: the rotation did not move C to key 2", 2, w.admin.base.epochs[GID])
        assertTrue("fixture: the rotation left no gk mark", RelayCopySlots.groupKey(GID, B) in w.marks.map)
    }

    /** A restart of C: a new group screen opened [beforeAddress] its own address is known, B's relays answering. */
    private suspend fun reopen(
        w: GroupCopyWorld,
        beforeAddress: Boolean = false,
    ) {
        w.net.kept = null
        w.answer(B, 1, 1)
        w.net.clear()
        val f = fixture()
        val vm = w.groupVm(f)
        if (!beforeAddress) {
            w.open(vm, f)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val address =
            GroupViewModel::class.java
                .getDeclaredField("_currentUserAddress")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<String?>
        val self = address.value
        address.value = null
        f.awaitNewJobs(vm) {
            vm.loadGroupDetail(GID)
            address.value = self
        }
    }

    @Test
    fun `guard - a group screen opened before it knows its own address still repairs the key copy`() =
        runBlocking {
            val w = world()
            rekey(w)
            reopen(w, beforeAddress = true)
            assertEquals(
                "the key copy only a public relay took was not re-delivered at the first open after a restart",
                1,
                w.copies(GroupMessageType.GROUP_KEY, B).size,
            )
        }

    @Test
    fun `a message B could not read for want of the new key is shown once B adopts that key`() =
        runBlocking {
            val w = world()
            rekey(w)
            val phoneB = w.phones.getValue(B)
            val vmB = MA3.chatVm(phoneB)
            val text = "synthetic zc1046 sent under the new key"
            val adminKeys = w.admin.base.keys
            val gm = OpenGroup.gm(adminKeys.getValue("$GID|2"), C, text, epoch = 2, seq = 41L)

            MA3.relay(vmB, gm, sender = C)
            assertNull("fixture: B already holds key 2", phoneB.base.keys["$GID|2"])
            val storedOnB = phoneB.base.messages
            val storedBefore = storedOnB[GID].orEmpty()
            assertTrue("fixture: B stored a message it cannot read", text !in storedBefore)

            reopen(w)
            val key = w.copies(GroupMessageType.GROUP_KEY, B).singleOrNull()
            assertNotNull("fixture: the group open did not re-deliver the key copy", key)
            MA3.relay(vmB, key!!, sender = C)
            assertEquals("fixture: B did not adopt key 2", 2, phoneB.base.epochs[GID])

            assertTrue(
                "the message that arrived before its key is never shown after B adopted the key",
                text in storedOnB[GID].orEmpty(),
            )
        }
}
