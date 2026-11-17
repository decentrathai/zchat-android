package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-130 (fix-all 2.13.0, train T5b; high-items SPECS §2.2 cases 2 and 3, §2.4 cut A) — the group-message spend consent
 * follows the members a message really pays for.
 *
 * R5-1 says the consent is asked for again "as soon as the paying set grows (a member joined, or a member who used to
 * be reachable free no longer is)". At 878158714 nothing ever leaves the stored set: a member once agreed to stays
 * covered while reachable free and when paid again later, a Pay stores the old set plus the new one, and a member who
 * is not in the group any more is kept.
 *
 * Wanted: a message that pays without a question first cuts the stored consent to the members it pays for (and asks
 * instead when that cut cannot be saved); a question that is answered with Pay stores exactly the members it covered.
 * An unchanged paid set still asks only once per group.
 *
 * Runs the real GroupViewModel over the [PaidGroupFixture] fakes. Every address and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageConsentFollowsThePaidSetTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    // sendGroupMessage pauses 500 ms between members; virtual time is advanced while a test waits.
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

    private suspend fun viewModel(f: PaidGroupFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vms += it }
    }

    private fun consent(f: PaidGroupFixture): Set<String> = f.proxy.getGroupMessageSpendConsent(PaidGroupFixture.GROUP_ID)

    @Test
    fun `a member who was free and needs an on-chain message again is asked about again`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            f.proxy.setGroupMessageSpendConsent(PaidGroupFixture.GROUP_ID, setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B))
            // B is reachable free over the relays (the publish is not acked, so B's delivery fails and is never paid).
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 B free") }
            assertNull("fixture: only A is paid and A is covered, so nothing is asked", vm.paidActionConfirmation.value)
            assertEquals("fixture: only A is paid", 1, f.seam.proposalRequests.get())
            assertEquals(
                "the message paid only A, so the stored consent must hold only A",
                setOf(PaidGroupFixture.MEMBER_A),
                consent(f),
            )

            // The relays go away: B needs an on-chain message again.
            NostrChatBridge.unregisterPublisher()
            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 B paid again") }

            assertEquals("B was paid for again with no question", 1, f.seam.proposalRequests.get())
            val question = vm.paidActionConfirmation.value
            assertNotNull("a member who was free and is paid again must be asked about", question)
            assertEquals("the question covers A and B", 2, question!!.onChainMessages)
        }

    @Test
    fun `Pay stores exactly the members the question covered`() =
        runBlocking {
            val f = PaidGroupFixture()
            f.proxy.setGroupMessageSpendConsent(
                PaidGroupFixture.GROUP_ID,
                setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B, NOT_A_MEMBER),
            )
            f.addActiveMember(PaidGroupFixture.MEMBER_C)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 C joined") }
            assertEquals("fixture: C joined, so the message is asked about", 3, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("fixture: Pay paid the three members", 3, f.seam.proposalRequests.get())

            assertEquals(
                "the stored consent must be exactly the members the user agreed to pay for",
                setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B, PaidGroupFixture.MEMBER_C),
                consent(f),
            )
        }

    @Test
    fun `a cut that cannot be saved asks instead of paying`() =
        runBlocking {
            val f = PaidGroupFixture()
            f.proxy.setGroupMessageSpendConsent(
                PaidGroupFixture.GROUP_ID,
                setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B, NOT_A_MEMBER),
            )
            val vm = viewModel(f)
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(vm, consentWritesFail(f.proxy))

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 unsaved cut") }

            assertEquals(
                "the message paid on a consent the disk may still hold larger than the paid set",
                0,
                f.seam.proposalRequests.get(),
            )
            assertNotNull("a cut that did not reach the disk must ask instead", vm.paidActionConfirmation.value)
            assertEquals(2, vm.paidActionConfirmation.value?.onChainMessages)
        }

    @Test
    fun `control - an unchanged paid set asks once for two messages`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)
            var questions = 0

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 one") }
            if (vm.paidActionConfirmation.value != null) questions++
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 two") }
            if (vm.paidActionConfirmation.value != null) questions++

            assertEquals("one question per group while the paid set does not change", 1, questions)
            assertEquals("both messages paid both members", 4, f.seam.proposalRequests.get())
            assertEquals(setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B), consent(f))
        }

    /** [rest], except that every consent write fails to reach the disk. */
    private fun consentWritesFail(rest: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setGroupMessageSpendConsent") {
                false
            } else {
                try {
                    method.invoke(rest, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    private companion object {
        const val NOT_A_MEMBER = "usyntheticzc130notamember001"
    }
}
