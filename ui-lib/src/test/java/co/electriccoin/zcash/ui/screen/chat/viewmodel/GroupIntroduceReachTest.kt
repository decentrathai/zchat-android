package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Round 10b R10B-2 (F-R9B-1) — the group screen says "Some members couldn't be reached over the relays yet" while
 * nothing has failed, next to the composer's true "every member is reachable".
 *
 * GroupViewModel.introduceInfo counts as unreachable every owed pair whose recipient has a relay key and no ledger
 * entry. That includes pairs no pass has tried yet: a pass starts when the screen opens, its sends are spaced 1-20 s
 * apart, and it waits while the relays are not ready.
 *
 * Wanted: a pair counts as not reached only after a relay send to it was tried and not acked; an ack (or a ledger
 * write) clears it. The creator C has A and B, both with a relay key: 2 pairs (A about B, B about A).
 * - Nothing tried yet (the relays are not ready, so the pass waits): 0.
 * - After a pass whose relay sends are not acked: 2.
 * - After a later pass that is acked: 0.
 *
 * Consent GRANTED; nothing is paid. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupIntroduceReachTest {
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
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun admin(): AnnouncerPhone {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE))
        listOf(A, B).forEach { MA3.memberPhone(it, admin) }
        admin.base.nostrKeys[A] = NPUB.getValue(A)
        admin.base.nostrKeys[B] = NPUB.getValue(B)
        return admin
    }

    private fun screen(): Pair<GroupPaidOnceFixture, AnnounceSeam> =
        GroupPaidOnceFixture().apply { scheduler = this@GroupIntroduceReachTest.scheduler } to AnnounceSeam()

    @Test
    fun `before any relay send is tried no member counts as not reached`() =
        runBlocking {
            val admin = admin()
            val (f, seam) = screen()
            val vm = MA3.groupVm(admin, f, seam)

            val conversation = MA3.load(vm, f)

            assertEquals("fixture: a relay copy went out while the relays were not ready", 0, net.published.size)
            assertEquals(
                "2 pairs nobody has tried to reach count as couldn't be reached",
                0,
                MA3.introduce(conversation, "unreachablePairs"),
            )
            assertEquals(0, seam.proposals.size)
        }

    @Test
    fun `after a pass whose relay sends are not acked both pairs count as not reached`() =
        runBlocking {
            val admin = admin()
            net.acks = { 0 }
            net.register()
            val (f, seam) = screen()
            val vm = MA3.groupVm(admin, f, seam)

            val conversation = MA3.load(vm, f)

            assertEquals("fixture: the pass did not try both pairs", 2, net.gps().size)
            assertEquals(2, MA3.introduce(conversation, "unreachablePairs"))
            assertEquals(0, seam.proposals.size)
        }

    @Test
    fun `a later acked pass clears the count`() =
        runBlocking {
            val admin = admin()
            net.acks = { 0 }
            net.register()
            val (f, seam) = screen()
            val vm = MA3.groupVm(admin, f, seam)
            assertEquals(2, MA3.introduce(MA3.load(vm, f), "unreachablePairs"))

            net.acks = { 1 }
            val conversation = MA3.load(vm, f)

            assertEquals(0, MA3.introduce(conversation, "unreachablePairs"))
            assertEquals(0, seam.proposals.size)
        }
}
