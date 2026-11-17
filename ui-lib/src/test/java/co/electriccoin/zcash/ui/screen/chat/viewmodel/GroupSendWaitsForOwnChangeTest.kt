package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 D7 (sweep #8) — a group message to several members from a wallet that still holds Orchard.
 *
 * The first member's copy spends the wallet's only Ironwood note. Until its change confirms, the wallet has no
 * spendable Ironwood, and because some Orchard value is left (not moved yet, or dust too small to move), the send
 * chokepoint refuses the next member with `OrchardSealedException(ironwoodPending > 0)`: "wait for the
 * confirmation". That is the same wait a wallet with no Orchard meets as the "…confirm on-chain…" shortfall, which
 * `sendGroupMemoWithRetry` waits out block by block. The sealed-pool refusal is not waited out: at 1b98dd127 the
 * second member is NOTHING_SENT at once, and every member after the first gets nothing (a partial delivery).
 *
 * Wanted: the refusal that says "wait" is waited out like the shortfall (next block, then retry, with the same
 * cap), so the second member gets the message once. A refusal that says "move" (`ironwoodPending == 0`) cannot
 * be helped by waiting and is still NOTHING_SENT at once (control).
 *
 * Both refusals come before the submit, so nothing was stored and a retry cannot pay twice. All addresses, keys,
 * group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendWaitsForOwnChangeTest {
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

    private class Setup(
        val vm: GroupViewModel,
        val seam: ScriptedSeam,
        val heights: HeightFeed,
        val fixture: GroupPaidOnceFixture,
    )

    /** Two ACTIVE members, P then Q, both paid on-chain; the second proposal request meets [refusal]. */
    private suspend fun setup(refusal: Exception): Setup {
        val f = GroupPaidOnceFixture(consent = true, extraActiveMembers = listOf(MEMBER_Q))
        f.scheduler = scheduler
        val vm = f.groupViewModel().also { vms += it }
        vm.consumeGroupSendEvent()
        val seam = ScriptedSeam(failRequest = { n -> if (n == 2) refusal else null })
        val heights = HeightFeed()
        setField(vm, "createChunkedMessageProposal", seam.useCase(OwnChangeAccounts.create()))
        setField(vm, "synchronizerProvider", heights.provider)
        return Setup(vm, seam, heights, f)
    }

    private fun setField(
        vm: GroupViewModel,
        name: String,
        value: Any,
    ) {
        GroupViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    /**
     * Run [block] and drive the jobs it starts to the end: virtual time moves in small steps (the 500 ms pause
     * between members), and each time a send waits for the next block, one block is mined. Returns how many
     * blocks were mined. The block-wait timeout is minutes of virtual time, so the small steps never reach it.
     */
    private suspend fun runToEnd(
        s: Setup,
        block: suspend () -> Unit,
    ): Int {
        val before: Set<Job> =
            s.vm.viewModelScope.coroutineContext.job.children
                .toSet()
        block()
        var mined = 0
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            while (s.vm.viewModelScope.coroutineContext.job.children
                    .any { it !in before && it.isActive }
            ) {
                if (s.heights.isWaiting()) {
                    s.heights.advance()
                    mined++
                }
                scheduler.advanceTimeBy(STEP_MS)
                scheduler.runCurrent()
                delay(5)
            }
        }
        return mined
    }

    private suspend fun sendToBoth(s: Setup): Int {
        s.fixture.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "hello group")
        runToEnd(s) { s.vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
        assertEquals(
            "fixture: the message quotes two on-chain messages",
            2,
            s.vm.paidActionConfirmation.value
                ?.onChainMessages,
        )
        return runToEnd(s) { s.vm.confirmPaidAction() }
    }

    @Test
    fun `a member refused while our own change confirms gets the group message after the next block`() =
        runBlocking {
            val s = setup(OrchardSealedException(OwnChangeAccounts.ORCHARD, ironwoodPending = OwnChangeAccounts.PENDING))

            val mined = sendToBoth(s)

            assertEquals(
                "Q was refused with \"wait for the confirmation\" and was never tried again after a block " +
                    "(event ${s.vm.groupSendEvent.value})",
                listOf(GroupPaidOnceFixture.MEMBER_P, MEMBER_Q),
                s.seam.submittedTo.toList(),
            )
            assertTrue("the second member must wait for a block, not be given up at once", mined >= 1)
            assertEquals("each member is paid once", 2, s.seam.submits.get())
            assertNull(
                "every member got the message, so there is no partial-delivery or failure banner",
                s.vm.groupSendEvent.value,
            )
            assertEquals("the draft of a delivered message is cleared", null, s.fixture.draft())
        }

    @Test
    fun `control - a member refused because the funds must be moved is not sent at once and nothing waits`() =
        runBlocking {
            val s = setup(OrchardSealedException(OwnChangeAccounts.ORCHARD, ironwoodPending = Zatoshi(0)))

            val mined = sendToBoth(s)

            assertEquals("no block can fix a \"move your old balance\" refusal", 0, mined)
            assertEquals("only P was paid", listOf(GroupPaidOnceFixture.MEMBER_P), s.seam.submittedTo.toList())
            assertEquals("Q is not tried again", 2, s.seam.proposalRequests.get())
            val event = s.vm.groupSendEvent.value
            assertTrue("Q's failure is shown as a partial delivery: $event", event is GroupSendResult.PartialDelivery)
            assertEquals(1, (event as GroupSendResult.PartialDelivery).sent)
            assertEquals(2, event.total)
        }

    private companion object {
        const val MEMBER_Q = "usyntheticd7groupmemberq0001"
        const val STEP_MS = 100L
    }
}
