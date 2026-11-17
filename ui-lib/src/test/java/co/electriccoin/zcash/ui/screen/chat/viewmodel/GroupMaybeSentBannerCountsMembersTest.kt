package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C4 (round 7 adversarial review of B1, item 3) — the "may have been sent" group banner.
 *
 * `GroupSendResult.MaybeSent(groupId, count)` carries one number and no kind:
 *
 * - for a group message the number is a count of MEMBERS, but the banner reads "%d messages may have been sent";
 * - an invite emits `MaybeSent(groupId, 1)`, which reads "1 message may have been sent";
 * - when a message reached some members, may have reached others and provably reached nobody else, only the
 *   "may have been sent" part is shown, although the draft is cleared: the members nothing was sent to are
 *   never mentioned.
 *
 * Wanted: the event says what may have been sent (a MESSAGE or an INVITE), to how many MEMBERS, and to how many
 * members nothing was sent. The event's fields are read by reflection so this file compiles against the old
 * one-number event and fails on it.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMaybeSentBannerCountsMembersTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
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
    fun `a group message that may have been sent to one member is reported as a message to one member`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected())
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            val event = vm.groupSendEvent.value
            assertTrue("fixture: the message may have been sent: $event", event is GroupSendResult.MaybeSent)
            assertEquals("the banner must say a MESSAGE may have been sent: $event", "MESSAGE", field(event, "kind").toString())
            assertEquals("the banner must count members: $event", 1, field(event, "members"))
            assertEquals("nothing was refused before the submit: $event", 0, field(event, "notSent"))
        }

    @Test
    fun `a resent invite that may have been sent is reported as an invite, not a message`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    memberStatus = MemberStatus.INVITED,
                    memberInviteStatus = InviteStatus.FAILED,
                    firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(),
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.resendInvite(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            val event = vm.groupSendEvent.value
            assertTrue("fixture: the invite may have been sent: $event", event is GroupSendResult.MaybeSent)
            assertEquals("an invite is called a message on the banner: $event", "INVITE", field(event, "kind").toString())
            assertEquals(1, field(event, "members"))
            assertEquals(0, field(event, "notSent"))
        }

    /**
     * Three on-chain members: P's memo is refused before anything is stored (NOTHING_SENT), E1's is stored then
     * rejected (MAYBE_SENT) and E2's is sent.
     */
    @Test
    fun `a group message with mixed outcomes names the members that may have it and those nothing was sent to`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    failFirstProposalWith = PublicCrossingException(Zatoshi(90_000), Zatoshi(0)),
                    firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(),
                    extraActiveMembers = listOf(MEMBER_E1, MEMBER_E2),
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            assertEquals("fixture: three on-chain members are quoted", 3, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: E1 and E2 reached the submit, P did not", 2, f.submits())
            val event = vm.groupSendEvent.value
            assertTrue("one member may have the message: $event", event is GroupSendResult.MaybeSent)
            assertEquals("MESSAGE", field(event, "kind").toString())
            assertEquals("one member may have the message: $event", 1, field(event, "members"))
            assertEquals(
                "nothing was sent to P, and the draft is cleared, but the banner does not say so: $event",
                1,
                field(event, "notSent"),
            )
        }

    /** One declared field of [event], or a failure naming the event (the old event has no such field). */
    private fun field(
        event: GroupSendResult?,
        name: String,
    ): Any? {
        val f =
            event!!.javaClass.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError("${event.javaClass.simpleName} has no field '$name': $event")
        f.isAccessible = true
        return f.get(event)
    }

    private companion object {
        const val MEMBER_E1 = "usyntheticpaidoncemembere001"
        const val MEMBER_E2 = "usyntheticpaidoncemembere002"
    }
}
