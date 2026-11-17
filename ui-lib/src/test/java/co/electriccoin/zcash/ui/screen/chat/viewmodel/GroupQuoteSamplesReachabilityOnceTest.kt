package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * B1 / GPT-6 Q3b (GROUP-QUOTE-DOUBLE-SAMPLE) — a group control's prompt checks the free relay route twice, so
 * the count on screen and the permission it grants can disagree, down to paying with no prompt at all.
 *
 * `askForQuotedPlan` freezes the quote (who may be paid on-chain) from one reachability check, then
 * `askBeforePaying` checks every recipient AGAIN to decide the number it shows and whether to show a prompt.
 * When member P is not reachable free on the first check and is on the second (the relay became ready, or
 * P's Nostr key was learned in between), no prompt is shown, the plan runs with P still in the frozen
 * paid set, and when P's free delivery then fails P is paid on-chain under a permission the user never saw.
 * `createGroup` has the same second check.
 *
 * Here P's Nostr key is unknown on the first lookup and known after it; the relay is up but acks nothing, so
 * the free delivery fails.
 *
 * Wanted: the prompt is built from the one frozen quote. It shows that quote's paid count and is skipped only
 * when that quote has nobody to pay; nothing is paid before Confirm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupQuoteSamplesReachabilityOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        // The relay is ready (so a known Nostr key counts as free) but no relay acks, so a free delivery fails.
        NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }
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

    /** P is unknown to Nostr on the first lookup and known on every later one. */
    private fun keyLearnedAfterFirstLookup(): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            nostrKeyForP = { n -> if (n == 0) null else GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY },
        ).also { it.scheduler = scheduler }

    @Test
    fun `rotating the key shows the frozen quote's paid count and pays nothing before Confirm`() =
        runBlocking {
            val f = keyLearnedAfterFirstLookup()
            val vm = f.groupViewModel().also { vms += it }

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }

            assertEquals(
                "P was quoted as an on-chain message, but the plan ran with no prompt and paid for P",
                0,
                f.submits(),
            )
            assertNotNull("no prompt was shown for a quote that may pay P on-chain", vm.paidActionConfirmation.value)
            assertEquals(
                "the prompt must show the frozen quote's paid count",
                1,
                vm.paidActionConfirmation.value?.onChainMessages,
            )
        }

    @Test
    fun `creating a group shows the frozen quote's paid count and pays nothing before Confirm`() =
        runBlocking {
            val f = keyLearnedAfterFirstLookup()
            val vm = f.groupViewModel().also { vms += it }
            vm.setGroupName("Quoted once")
            vm.toggleMemberSelection(GroupPaidOnceFixture.MEMBER_P)

            f.awaitNewJobs(vm) { vm.createGroup() }

            assertEquals("P was quoted as an on-chain invite, but the create ran with no prompt", 0, f.submits())
            assertNotNull("no prompt was shown for a quote that may pay P on-chain", vm.paidActionConfirmation.value)
            assertEquals(
                "the prompt must show the frozen quote's paid count",
                1,
                vm.paidActionConfirmation.value?.onChainMessages,
            )
            vm.dismissPaidAction()
        }

    /** Control, GREEN before and after: when P is reachable free on every check, nothing is quoted or asked. */
    @Test
    fun `control - a member free on every check is not quoted and no prompt is shown`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY })
                    .also { it.scheduler = scheduler }
            val vm = f.groupViewModel().also { vms += it }

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }

            assertEquals(null, vm.paidActionConfirmation.value)
            assertEquals("a member quoted as free is never paid when the free delivery fails", 0, f.submits())
        }
}
