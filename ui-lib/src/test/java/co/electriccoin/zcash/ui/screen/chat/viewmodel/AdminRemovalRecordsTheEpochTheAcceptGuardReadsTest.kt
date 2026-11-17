package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-506 — a pin of the two facts the creator's rules for a re-invited member stand on (green before the fix: no test
 * read back what a removal writes).
 *
 * 1. A real removal (Remove member, confirmed) records, for the removed member: when (inside the wall-clock window of
 *    the call), the key epoch the removal rotated INTO, and that it was authenticated.
 * 2. A real Add member for the same person afterwards sends an invite whose key epoch is not lower than that.
 *
 * So an accept, or a message, tied to a key epoch below the recorded one was made for the invitation of before the
 * removal; one at or above it can only come from the new invitation. If either fact changes, the rules that refuse old
 * accepts (OldAcceptMinedAfterAdminRemovalIsNotAComebackTest) and old messages
 * (OldSignedMessageDoesNotReactivateAReinvitedMemberTest) must be looked at again.
 *
 * Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminRemovalRecordsTheEpochTheAcceptGuardReadsTest {
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

    @Test
    fun `a removal records its time, the epoch it rotated into and AUTH, and the next invite carries at least that epoch`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE))
            MA3.memberPhone(A, admin)
            MA3.memberPhone(X, admin)
            listOf(A, X).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
            MA3.alreadyAnnounced(admin, listOf(A, X))
            net.register()
            val seam = AnnounceSeam()
            val f = GroupPaidOnceFixture().apply { scheduler = this@AdminRemovalRecordsTheEpochTheAcceptGuardReadsTest.scheduler }
            val vm = MA3.groupVm(admin, f, seam)
            MA3.load(vm, f)
            assertEquals("fixture: the group starts at key epoch 1", 1, admin.base.epochs[GID])

            val before = System.currentTimeMillis()
            f.awaitNewJobs(vm) { vm.kickMember(GID, X) }
            vm.paidActionConfirmation.value?.let { MA3.confirm(vm, f) }
            val after = System.currentTimeMillis()

            assertEquals("the removal did not take X out", MemberStatus.LEFT, admin.status(X))
            val rotatedInto = admin.base.epochs.getValue(GID)
            assertEquals("the removal did not rotate the key", 2, rotatedInto)
            val entry = admin.state().members[X]
            assertNotNull("the removal left no record for X", entry)
            assertTrue("the removal's time ${entry!!.leftAtMs} is not inside the call [$before, $after]", entry.leftAtMs in before..after)
            assertEquals("the removal does not record the epoch it rotated into", rotatedInto, entry.removedAtEpoch)
            assertEquals("the removal is not recorded as authenticated", ActivatedBy.AUTH, entry.leftBy)

            // The creator invites X again.
            net.clear()
            val paidBefore = seam.proposals.size
            f.awaitNewJobs(vm) { vm.addMemberToGroup(GID, X) }
            vm.paidActionConfirmation.value?.let { MA3.confirm(vm, f) }

            assertEquals("the new invite did not put X back to INVITED", MemberStatus.INVITED, admin.status(X))
            val paidToX = seam.proposals.drop(paidBefore).filter { it.recipient == X }
            val invites =
                (net.to(NPUB.getValue(X)) + paidToX.map { it.memo })
                    .filter { ZMSGGroupProtocol.parseMessageType(it) == GroupMessageType.GROUP_INVITE }
            assertTrue("no invite was sent to X", invites.isNotEmpty())
            for (invite in invites) {
                val keyEpoch = JSONObject(ZMSGGroupProtocol.parsePayload(invite)!!).optInt("key_epoch", -1)
                assertTrue(
                    "the new invite's key epoch $keyEpoch is below the removal's ${entry.removedAtEpoch}",
                    keyEpoch >= entry.removedAtEpoch,
                )
            }
            val record = admin.state().members[X]
            assertEquals("the new invite changed the removal's recorded epoch", entry.removedAtEpoch, record?.removedAtEpoch)
            assertEquals("the new invite changed the removal's recorded time", entry.leftAtMs, record?.leftAtMs)
        }
}
