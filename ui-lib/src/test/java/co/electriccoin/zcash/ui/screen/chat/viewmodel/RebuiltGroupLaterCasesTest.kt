package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.NOW_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.own
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownKeyChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-757 (FIRST-CONTACT-SPEC 6.7) — the cases built after the batch: (e) a second restore after a key change that left no
 * on-chain trace numbers its key above the first one; (g) a removal that went over the relays only cannot be known and
 * the member is listed again (residual R-12; the notice's sentence about the list covers it); (i) a group whose keys were
 * made by 2.12.4 (nothing unwraps after a restore) is listed with no key, refuses a send with the sentence and offers the
 * key change; (l) the compat pin: 2.12.4's key-change test and 64-wide window, copied verbatim from the tag, take the key
 * number a rebuilt group makes and still open an old message by its own number. (k) waits for the phone check F-4.
 *
 * All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupLaterCasesTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        net.register()
        RebuiltGroupFixture.setClock(NOW_MS, NOW_MS - 3_600_000L)
    }

    @After
    fun tearDown() {
        RebuiltGroupFixture.setClock(null, 0L)
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private suspend fun gvm(admin: AnnouncerPhone): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@RebuiltGroupLaterCasesTest.scheduler }
        return MA3.groupVm(admin, f, AnnounceSeam()) to f
    }

    /** A restored creator's phone with our own mined invitation to A at 0 and our own key change to 3 read back. */
    private suspend fun restored(): AnnouncerPhone {
        val admin = AnnouncerPhone(C)
        RebuiltGroupFixture.member(A, admin, epoch = 3)
        val vm = MA3.chatVm(admin)
        own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
        own(vm, ownKeyChange(admin, A, 3), tx = 2, to = A, height = 110)
        return admin
    }

    @Test
    fun `(e) a second restore after a key change that left no on-chain trace numbers its key above the first one`() =
        runBlocking {
            val first = restored()
            val (vm1, f1) = gvm(first)
            RebuiltGroupFixture.rotate(vm1, f1)
            val firstNumber = first.base.epochs.getValue(GID)
            // That key change went over the relays only: the chain still says 3. A later restore, a day on.
            RebuiltGroupFixture.setClock(NOW_MS + 24L * 3_600_000L, NOW_MS + 23L * 3_600_000L)
            val second = restored()
            assertEquals("fixture: the second restore reads 3 from the chain", 3, second.base.epochs[GID])
            val (vm2, f2) = gvm(second)
            RebuiltGroupFixture.rotate(vm2, f2)
            assertTrue(
                "the second restore's key number ${second.base.epochs[GID]} is not above the first one $firstNumber",
                second.base.epochs.getValue(GID) > firstNumber,
            )
        }

    @Test
    fun `(g) a removal that went over the relays only is not known - the member is listed again, and the notice asks to check`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 1)
            RebuiltGroupFixture.member(B, admin, epoch = 1)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
            own(vm, ownInvite(admin, B), tx = 2, to = B, height = 101)
            // B was removed over the relays only: nothing of it is in the history.
            val rowB = admin.base.roster(GID).single { it.address == B }
            assertEquals("B is not listed again (R-12 pinned as it is)", MemberStatus.INVITED, rowB.status)
            assertEquals(InviteStatus.SENT, rowB.inviteStatus)
            val (gvm, _) = gvm(admin)
            assertEquals("the notice that asks to check the list is not shown", "NEEDS_KEY", gvm.rebuiltGroupNotice(GID)?.toString())
        }

    @Test
    fun `(i) keys made by 2_12_4 - the group is listed with no key, refuses a send with the sentence, and offers the key change`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            val vm = MA3.chatVm(admin)
            // A 2.12.4 key pair is random: after a restore no session exists, so our own invitation does not unwrap.
            own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
            assertTrue("fixture: the group is not listed", admin.base.infos.containsKey(GID))
            val keys = admin.base.keys.keys
            assertTrue("the group has a key although nothing unwraps", keys.none { it.startsWith("$GID|") })
            val (gvm, f) = gvm(admin)
            val refused = RebuiltGroupFixture.sendResult(gvm, f)
            assertEquals("the send is not refused with the 'needs a new key' sentence", "NeedsNewKey", refused?.javaClass?.simpleName)
            RebuiltGroupFixture.rotate(gvm, f)
            val n = admin.base.epochs.getValue(GID)
            assertTrue("the key change was not offered or made", admin.base.keys.containsKey("$GID|$n"))
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(l) 2_12_4 takes the key number a rebuilt group makes and still opens an old message by its own number`() =
        runBlocking {
            val admin = restored()
            val (gvm, f) = gvm(admin)
            RebuiltGroupFixture.rotate(gvm, f)
            val n = admin.base.epochs.getValue(GID)
            assertTrue("fixture: the number is in the tens of millions", n > 10_000_000)
            assertTrue("a 2.12.4 member at 7 refuses the key change", Frozen2124.takesKeyChange(stored = 7, epoch = n))
            val tried = Frozen2124.candidateEpochs(messageEpoch = 2, currentEpoch = n)
            assertEquals("a 2.12.4 member does not try the message's own number first", 2, tried.first())
            assertTrue("a 2.12.4 member walks more than the 64-wide window: ${tried.size}", tried.size <= 66)
        }

    /**
     * 2.12.4's code, copied from the tag v2.12.4 (a1fb4136f), ChatViewModel.kt: `processGroupKey` line 8747
     * (`if (rot.epoch <= zchatPreferences.getGroupKeyEpoch(groupId)) { … ignored … return }`) and `processGroupMsg` lines
     * 8307-8311 with `MAX_GROUP_EPOCH_LOOKBACK = 64` (line 6414). Only the preferences reads are replaced by arguments.
     */
    private object Frozen2124 {
        private const val MAX_GROUP_EPOCH_LOOKBACK = 64

        fun takesKeyChange(
            stored: Int,
            epoch: Int,
        ): Boolean = !(epoch <= stored)

        fun candidateEpochs(
            messageEpoch: Int,
            currentEpoch: Int,
        ): List<Int> {
            val windowStart = maxOf(0, currentEpoch - MAX_GROUP_EPOCH_LOOKBACK)
            return (listOf(messageEpoch, currentEpoch) + (windowStart..currentEpoch))
                .filter { it >= 0 }
                .distinct()
        }
    }
}
