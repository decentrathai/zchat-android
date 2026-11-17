package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, design test 19 (MEMBER_ANNOUNCE_DESIGN.md v2 §6.4) — a member the creator announces is a new paid
 * recipient, so the member's next group message asks the message cost again before paying for it.
 *
 * CHARACTERIZATION: expected to pass already at the MA2 tip (MA2 made announced members recipients, and R5-1's
 * per-group consent asks again whenever the paying set grows). It pins that an announce never widens a spend the user
 * agreed to.
 *
 * B's phone pays C on-chain for each message (no relay route), and B already agreed to that. C announces A (no relay
 * route either). B's next message asks again, quoting 2 paid members, and nothing is paid before B answers.
 *
 * All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewlyAnnouncedMemberAsksMessageConsentAgainTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

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
    fun `a newly announced paid member makes the next message ask the cost again`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE))
            val phoneB = MA3.memberPhone(B, admin)
            phoneB.base.nostrKeys.remove(C)
            phoneB.base.spendConsents[GID] = setOf(C)
            val vmB = MA3.chatVm(phoneB)
            val f = GroupPaidOnceFixture().apply { scheduler = this@NewlyAnnouncedMemberAsksMessageConsentAgainTest.scheduler }
            val seam = AnnounceSeam()
            val gvmB = MA3.groupVm(phoneB, f, seam)
            assertEquals("setup: B does not pay for exactly C", 1, gvmB.groupMessageCost(GID).onChainMessages)

            MA3.chain(vmB, MA3.gpFrom(admin, B, AnnounceOp.A, A, MA3.now()), tx = 3)

            f.awaitNewJobs(gvmB) { gvmB.sendGroupMessage(GID, "after the announce") }
            val quote = gvmB.paidActionConfirmation.value
            assertEquals("the message to the newly announced member did not ask again", PaidAction.GROUP_MESSAGE, quote?.action)
            assertEquals(2, quote?.onChainMessages)
            assertEquals("something was paid before B answered", 0, seam.proposals.size)
            f.awaitNewJobs(gvmB) { gvmB.dismissPaidAction() }
            assertNull(gvmB.paidActionConfirmation.value)
            assertEquals(0, seam.proposals.size)
        }
}
