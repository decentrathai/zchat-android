package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B1 / GPT-6 Q3 (GROUP-MEMO-STORED-REJECT) — a group memo whose transaction was stored and then rejected is
 * treated as "nothing sent", so the same key, invite or message is paid for a second time.
 *
 * `sendGroupMemoWithRetry` catches every failure that is not a cancellation and returns false. One of those
 * failures is `SubmitRejectedException(nothingStored = false, txIds = [T])`: the SDK has stored T and may
 * still broadcast it (P2a). The 1:1 chat already separates this case (ChatViewModel.provesNothingSent: only
 * `nothingStored == true` or an insufficient-funds failure proves nothing went out). The group code does not:
 *
 * - the owed-key repair re-arms the member as owed, so the next group open pays a second GROUP_KEY;
 * - a rotation writes the member as owed and its follow-up repair pays a second GROUP_KEY at once;
 * - a resent invite is marked Failed, which offers a paid Resend;
 * - a group message reports "not sent" and keeps the text, so sending it again pays again.
 *
 * If T also lands, the member is paid twice; the receiver drops the duplicate, so the second fee is lost.
 *
 * Wanted: a stored-then-rejected memo counts as "may have been sent". It is never re-armed, never written as
 * owed and never offered as a paid retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMemoStoredRejectionIsNotRetriedTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    // sendQuotedGroupMessage pauses 500 ms between members; virtual time is advanced while a test waits.
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(f: GroupPaidOnceFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also {
            vms += it
            it.consumeGroupSendEvent()
        }
    }

    @Test
    fun `an owed key whose re-send was stored then rejected is not owed again and is not paid twice`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    memberPendingKeyEpoch = 1,
                    firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(),
                    consent = true,
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture: the automatic repair made its one on-chain attempt", 1, f.submits())
            assertNull(
                "the repair's transaction was stored and may still be broadcast, but the member was written back " +
                    "as owed the key",
                f.memberRow().pendingKeyEpoch,
            )

            val reopened = viewModel(f)
            f.awaitNewJobs(reopened) { reopened.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals(
                "opening the group again paid a second GROUP_KEY for a key whose first transaction may still land",
                1,
                f.submits(),
            )
        }

    @Test
    fun `a rotation whose key memo was stored then rejected does not owe the member and pays nothing more`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(), consent = true)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture: the rotation quotes P as one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: the rotation committed epoch 2", 2, f.epoch())
            assertEquals(
                "the rotation's follow-up repair paid a second GROUP_KEY for a key memo that may still be broadcast",
                1,
                f.submits(),
            )
            assertNull("the member was written as owed the new key after a stored transaction", f.memberRow().pendingKeyEpoch)

            val reopened = viewModel(f)
            f.awaitNewJobs(reopened) { reopened.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("the next automatic repair paid for the same key again", 1, f.submits())
        }

    @Test
    fun `a resent invite that was stored then rejected is not marked failed and offers no paid Resend`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    memberStatus = MemberStatus.INVITED,
                    memberInviteStatus = InviteStatus.FAILED,
                    firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(),
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.resendInvite(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
            assertEquals("fixture: the resend quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: one on-chain attempt", 1, f.submits())
            assertNotEquals(
                "the invite's transaction may still be broadcast, but the member is shown as Failed with a paid Resend",
                InviteStatus.FAILED,
                f.memberRow().inviteStatus,
            )
            assertNotNull("the member must keep an invite state", f.memberRow().inviteStatus)
            val event = vm.groupSendEvent.value
            assertTrue(
                "the banner says the invite could not be sent (and offers Resend): $event",
                event !is GroupSendResult.InviteFailed,
            )
            assertEquals("the user is told the invite may have been sent", "MaybeSent", event?.javaClass?.simpleName)
        }

    @Test
    fun `a group message that was stored then rejected is not reported as unsent and its text is not kept for a paid re-send`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected())
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            assertEquals("fixture: the message quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: one on-chain attempt", 1, f.submits())
            val event = vm.groupSendEvent.value
            assertTrue(
                "the message may still be broadcast, but the screen says nothing was sent and keeps the text: $event",
                event !is GroupSendResult.AllFailed,
            )
            assertNull("the text was kept, so the next Send pays for the same message again", f.draft())
            assertEquals("the user is told the message may have been sent", "MaybeSent", event?.javaClass?.simpleName)
        }

    /** Control, GREEN before and after: a rejection that stored nothing still leaves the member owed. */
    @Test
    fun `control - a rejection that stored nothing leaves the member owed for the next repair`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    memberPendingKeyEpoch = 1,
                    firstSubmitOutcome =
                        co.electriccoin.zcash.ui.common.model.SubmitResult
                            .Failure(txIds = emptyList(), code = 18, description = "rejected"),
                    consent = true,
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals(1, f.submits())
            assertEquals("nothing was stored, so the member is still owed the key", 1, f.memberRow().pendingKeyEpoch)

            val reopened = viewModel(f)
            f.awaitNewJobs(reopened) { reopened.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("the next open repairs it once", 2, f.submits())
            assertNull(f.memberRow().pendingKeyEpoch)
        }
}
