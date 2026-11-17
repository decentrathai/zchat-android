package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G6a (ZC-055, high) — a member who leaves while its key message waits for a block is not sent the new key.
 *
 * A rotation checks once, at the start of a member's turn, that the member has not left. The on-chain send then may
 * wait minutes for the next block (the previous send's change), and when the block comes it only asks again whether it
 * may pay. A member whose leave was stored during that wait is still sent, and paid for, the new group key.
 *
 * Wanted: the membership is read again before every on-chain attempt; after the block nothing is built for the member
 * who left, and its row gets no owed mark.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyMemoToAMemberWhoLeftDuringTheBlockWaitIsNeverSentTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `after the block no key message is built for the member whose leave was stored during the wait`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            val heights = HeightFeed()
            val provider = GroupViewModel::class.java.getDeclaredField("synchronizerProvider")
            provider.isAccessible = true
            provider.set(s.vm, heights.provider)
            // The first key message meets the "funds still confirming" wait: nothing is built, the send waits for a block.
            var waiting: String? = null
            world.seam.onProposal = { n, memo ->
                if (n == 1) {
                    waiting = memo.recipient
                    throw OrchardSealedException(Zatoshi(50_000L), ironwoodPending = Zatoshi(20_000L))
                }
            }
            var blocks = 0

            GroupLedgerTests.drive(
                s,
                scheduler,
                whileRunning = {
                    if (s.vm.rekeyUnkeyableConfirmation.value != null) s.vm.confirmRekeyUnkeyable()
                    if (s.vm.paidActionConfirmation.value != null) s.vm.confirmPaidAction()
                    if (heights.isWaiting()) {
                        // The member's GROUP_LEAVE is stored while its key message waits; then the block arrives.
                        waiting?.let { member -> world.setRow(member) { it.copy(status = MemberStatus.LEFT) } }
                        heights.advance()
                        blocks++
                    }
                },
            ) {
                s.vm.rotateGroupKey(MA3.GID)
                if (s.vm.rekeyUnkeyableConfirmation.value != null) s.vm.confirmRekeyUnkeyable()
                if (s.vm.paidActionConfirmation.value != null) s.vm.confirmPaidAction()
            }

            val left = waiting!!
            assertTrue("fixture sanity: the send waited for a block", blocks >= 1)
            assertEquals(
                "$left left while its key message waited for a block, and after the block the new key was built and paid for it",
                1,
                world.seam.proposals.count { it.recipient == left },
            )
            assertEquals("a transaction to the member who left was stored", 0, world.seam.to(left).size)
            assertNull("the member who left is marked as owed the key", world.row(left).pendingKeyEpoch)
            assertEquals(MemberStatus.LEFT, world.row(left).status)
            assertEquals("the two members who stayed were each sent the key once", 2, world.seam.stored.size)
        }
}
