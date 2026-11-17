package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, design test 15 (MEMBER_ANNOUNCE_DESIGN.md v2 §3, review M6 / L1 / F16) — a member the creator cannot
 * vouch for is neither told nor announced, and its row says so.
 *
 * The creator C has A and B (secure link, joined through an authenticated step) and five more ACTIVE members:
 * N (no key exchange with C), P (C's key exchange with P still waits for Accept), K (K's key changed and the user was
 * warned), U (its row became ACTIVE through an unauthenticated step) and L (its address is too long for one GP memo).
 * Nobody is reachable over the relays. A and B are told about each other, and L is told about A and B: 4 pairs, quoted
 * and paid. L itself is never announced (its GP would not fit one memo), so no memo names it. N, P, K and U get the
 * "can't be introduced (no secure link)" note, L the "too long" note, and no memo names or reaches N, P, K or U.
 *
 * At the MA2 tip there is no introduce state and nothing to tap.
 *
 * Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberAnnounceSkipsUnverifiableTest {
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
    fun `only members with a secure link and an authenticated join are told and announced, and a too-long one is never quoted`() =
        runBlocking {
            val n = "usyntheticma3membern000000001"
            val p = "usyntheticma3memberp000000001"
            val k = "usyntheticma3memberk000000001"
            val u = "usyntheticma3memberu000000001"
            val l = "u" + "syntheticma3memberlong".repeat(15)
            val all = listOf(A, B, n, p, k, u, l)
            val admin = MA3.adminPhone(all.map { it to MemberStatus.ACTIVE })
            listOf(A, B, p, k, u, l).forEach { MA3.memberPhone(it, admin) }
            admin.base.awaitingAccept[p] = "synthetic-pending-accept"
            admin.base.keyChanged += k
            admin.base.states[GID] =
                admin.state().let { st ->
                    st.copy(members = st.members + (u to st.members.getValue(u).copy(activatedBy = ActivatedBy.UNAUTH)))
                }
            net.register()
            val seam = AnnounceSeam()
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceSkipsUnverifiableTest.scheduler }
            val vm = MA3.groupVm(admin, f, seam)

            val conversation = MA3.load(vm, f)

            assertEquals("the owed pairs are not A-B, B-A, L-A and L-B", 4, MA3.introduce(conversation, "owedOnChainPairs"))
            assertEquals(
                "the no-secure-link note is not on exactly N, P, K and U",
                setOf(n, p, k, u),
                MA3.introduce(conversation, "noSecureLink"),
            )
            assertEquals("the too-long note is not on L", setOf(l), MA3.introduce(conversation, "tooBig"))

            assertTrue("GroupViewModel has no introduceMembers", MA3.callGroupVm(vm, f, "introduceMembers"))
            assertEquals("the quote does not count exactly the 4 pairs", 4, vm.paidActionConfirmation.value?.onChainMessages)
            MA3.confirm(vm, f)
            assertEquals(4, seam.proposals.size)
            val told = seam.proposals.map { it.recipient to MA3.parseGp(it.memo)?.subject }.toSet()
            assertEquals("a memo names or reaches the wrong member: $told", setOf(A to B, B to A, l to A, l to B), told)
            assertTrue("a relay copy reached someone", net.gps().isEmpty())
        }
}
