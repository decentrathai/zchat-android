package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
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
 * Round 8 MA3, design test 23 (MEMBER_ANNOUNCE_DESIGN.md v2 §4.1, review compat 4 / 5) — a restored creator rebuilds its
 * announce ledger from its own sent GPs, never pays for a pair already on-chain, and announces only on a tap.
 *
 * C's phone was restored from the recovery phrase: it holds no group. The chain scan replays C's own sent invites to A
 * and B (they rebuild the group, A and B INVITED), A's and B's signed accepts (ACTIVE), and C's own sent on-chain GP
 * telling B about A. That GP is recorded as CHAIN_SENT for (B, A) and raises the announce counter, over three passes.
 * Opening the group announces nothing by itself (the screen offers "Introduce members" instead); the tap quotes only
 * the pair not already on-chain: A told about B.
 *
 * At the MA2 tip the scan drops our own GP (we are its announcer), so the ledger stays empty and a tap would not exist.
 *
 * Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltAdminLedgerTest {
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
    fun `a restored admin rebuilds its ledger from its own sent GPs, announces nothing by itself, and quotes only what is not on-chain`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            val phoneA = MA3.memberPhone(A, admin)
            val phoneB = MA3.memberPhone(B, admin)
            admin.base.nostrKeys[B] = NPUB.getValue(B)
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(admin, seam)
            val t = MA3.now() - 3_600_000L
            val giToA = AnnounceDriver.gi(C, admin.base.sessionWith(A), MA3.groupKey, keyEpoch = 1, groupId = GID)
            val giToB = AnnounceDriver.gi(C, admin.base.sessionWith(B), MA3.groupKey, keyEpoch = 1, groupId = GID)
            val sentN = t + 5_000
            val ownGp = MA3.gpFrom(admin, B, AnnounceOp.A, A, sentN)

            repeat(3) {
                MA3.chain(vm, giToA, tx = 1, timeMs = t, ownSend = true, ownRecipient = A)
                MA3.chain(vm, giToB, tx = 2, timeMs = t + 1, ownSend = true, ownRecipient = B)
                MA3.chain(vm, MA3.signedAccept(phoneA), tx = 3, timeMs = t + 2_000)
                MA3.chain(vm, MA3.signedAccept(phoneB), tx = 4, timeMs = t + 3_000)
                MA3.chain(vm, ownGp, tx = 5, timeMs = t + 6_000, ownSend = true, ownRecipient = B)
            }

            assertTrue("setup: the group was not rebuilt from our own invites", admin.base.info(GID).announcerRebuilt)
            assertEquals(MemberStatus.ACTIVE, admin.status(A))
            assertEquals(MemberStatus.ACTIVE, admin.status(B))
            val onChain = admin.ledger().filter { it.rAddr == B && it.sAddr == A }
            assertEquals("our own sent GP was not recorded once as CHAIN_SENT for (B, A)", 1, onChain.size)
            assertEquals(AnnounceLedgerState.CHAIN_SENT, onChain.single().state)
            assertEquals(AnnounceOp.A, onChain.single().op)
            assertTrue("the announce counter was not raised to our own sent GP's n", (admin.counters[GID] ?: 0L) >= sentN)
            assertTrue("a rebuilt group announced by itself", net.gps().isEmpty())

            val f = GroupPaidOnceFixture().apply { scheduler = this@RebuiltAdminLedgerTest.scheduler }
            val gseam = AnnounceSeam()
            val gvm = MA3.groupVm(admin, f, gseam)
            val conversation = MA3.load(gvm, f)
            assertTrue("opening a rebuilt group announced by itself", net.gps().isEmpty())
            assertEquals("the screen does not offer Introduce members on a rebuilt group", true, MA3.introduce(conversation, "rebuilt"))
            assertEquals(0, gseam.proposals.size)

            assertTrue("GroupViewModel has no introduceMembers", MA3.callGroupVm(gvm, f, "introduceMembers"))
            assertEquals("the tap quotes a pair already on-chain", 1, gvm.paidActionConfirmation.value?.onChainMessages)
            MA3.confirm(gvm, f)
            assertEquals(listOf(A), gseam.proposals.map { it.recipient })
            assertEquals(B, MA3.parseGp(gseam.proposals.single().memo)?.subject)
            assertEquals(0, seam.proposalRequests.get())
        }
}
