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
 * ZC-055 (high), phone check S-C C2 of train T6b (Fold, 15 Oct, failed twice) — in a group where no member has a key
 * exchange with the creator (every member joined free over the relays), a member who leaves while its key message is
 * held is still sent the new key.
 *
 * On the phone: the creator rotated the key with the debug hold armed (60 s per group memo); the first member was keyed;
 * the second member left during its own hold ("Processing GROUP_LEAVE"); when the hold ended the new key was published
 * to it ("Group memo to … sent FREE over NOSTR"), with no "no longer meant for this member" line. F8-G's check
 * (`stillWanted`, read again after the hold) is passed by the signed key message only: the sealed key of a member with
 * no session (GR-OPEN G2, `sendSealedKey`) goes through the same send with no such check, so the leave stored during
 * the hold is never read.
 *
 * Wanted: the membership is read again after the hold on the sealed route too; nothing is published to the member who
 * left, the members who stayed are keyed.
 *
 * Every address, key and group id is synthetic. Nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupRotationNeverKeysAMemberWhoLeftDuringTheHoldTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    @Test
    fun `no session - after the hold nothing is published to the member who left during it`() =
        runBlocking {
            // No member has a key exchange with the creator; each has a relay key (an all-Open group, as on the phone).
            val world = GroupLedgerWorld(sessions = false, scheduler = scheduler)
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

            assertEquals("fixture sanity: the rotation moved the key number", 2, world.epoch())
            assertEquals(
                "a member with no session left while its sealed key was held, and the new key was published to it after the hold",
                0,
                net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.A)).size,
            )
            assertEquals(
                "the members who stayed got the sealed key over the relays",
                1,
                net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.B)).size,
            )
            assertEquals(1, net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.D)).size)
            assertEquals("the member who left stays LEFT", MemberStatus.LEFT, world.row(MA3.A).status)
            assertEquals("nothing is paid on-chain in this run", 0, world.seam.proposals.size)
        }
}
