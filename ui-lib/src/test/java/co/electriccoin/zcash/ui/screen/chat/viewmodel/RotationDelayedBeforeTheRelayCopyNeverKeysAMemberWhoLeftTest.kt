package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * F8-G G6b (ZC-055, high) — a member who leaves while its key message is held before the relay copy gets no key.
 *
 * With the debug hold on every group memo armed (the hook the two-screens phone check uses), a member's turn in a
 * rotation starts, the membership check passes, the hold runs, and the free relay copy is then published with no second
 * look: a member who left during the hold is sent the new key over the relays.
 *
 * Wanted: the membership is read again after the hold and before the relay copy; nothing is published to the member
 * who left.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotationDelayedBeforeTheRelayCopyNeverKeysAMemberWhoLeftTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    @Test
    fun `after the hold nothing is published to the member who left during it`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            // Every member is reachable free over the relays.
            listOf(MA3.A, MA3.B, MA3.D).forEach { world.admin.base.nostrKeys[it] = MA3.NPUB.getValue(it) }
            net.register()
            val s = world.screen()
            FaultSeams.arm(FaultSeam.GROUP_SEND_DELAY_MS, 1_000L)
            var left = false

            GroupLedgerTests.drive(
                s,
                scheduler,
                whileRunning = {
                    if (s.vm.rekeyUnkeyableConfirmation.value != null) s.vm.confirmRekeyUnkeyable()
                    if (s.vm.paidActionConfirmation.value != null) s.vm.confirmPaidAction()
                    // The first member's turn is in its hold: that member's GROUP_LEAVE is stored now.
                    if (!left) {
                        left = true
                        world.setRow(MA3.A) { it.copy(status = MemberStatus.LEFT) }
                    }
                },
            ) {
                s.vm.rotateGroupKey(MA3.GID)
            }

            assertEquals(
                "a member left while its key message was held, and the new key was published to it after the hold",
                0,
                net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.A)).size,
            )
            assertEquals(
                "the members who stayed got the key over the relays",
                1,
                net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.B)).size,
            )
            assertEquals(1, net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.D)).size)
            assertEquals("nothing is paid on-chain in this run", 0, world.seam.proposals.size)
        }
}
