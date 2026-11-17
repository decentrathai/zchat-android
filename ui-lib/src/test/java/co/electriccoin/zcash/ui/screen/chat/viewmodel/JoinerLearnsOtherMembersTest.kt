package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
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
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, design test 13 (MEMBER_ANNOUNCE_DESIGN.md v2 §4 T1; PHONE_TRIAGE F4 end to end) — a joiner learns the
 * other members, and they learn the joiner.
 *
 * The creator C has B in the group and invited A. When A's signed GROUP_ACCEPT reaches C, C tells B about A and A
 * about B, each in a GP signed for that member, over the relays (both are reachable there), paying nothing. B's phone
 * then counts A as a recipient of its group messages. The scan replays the same accept on every pass; a replay is not
 * a new join, so it starts no new pass and nothing more is published.
 *
 * At the MA2 tip nothing sends a GP, so B never learns A (the F4 star: B's messages reach only C).
 *
 * Consent GRANTED; nothing may be proposed on any phone. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinerLearnsOtherMembersTest {
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
    fun `A's accept makes C tell B about A and A about B over the relays, and a replayed accept tells nobody again`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.INVITED, B to MemberStatus.ACTIVE))
            val phoneA = MA3.memberPhone(A, admin)
            val phoneB = MA3.memberPhone(B, admin)
            admin.base.nostrKeys[A] = NPUB.getValue(A)
            admin.base.nostrKeys[B] = NPUB.getValue(B)
            net.register()
            val adminSeam = HandshakeSeam(C)
            val adminVm = MA3.chatVm(admin, adminSeam)
            val accept = MA3.signedAccept(phoneA)
            val acceptedAt = MA3.now() + 60_000

            MA3.relay(adminVm, accept, sender = A, timeMs = acceptedAt)

            assertEquals("A's accept did not make A ACTIVE on C's phone", MemberStatus.ACTIVE, admin.status(A))
            val toB = net.gps(NPUB.getValue(B))
            val toA = net.gps(NPUB.getValue(A))
            assertEquals(
                "C did not tell B (over the relays) that A is in the group: ${toB.map { it.op to it.subject }}",
                listOf(AnnounceOp.A to A),
                toB.map { it.op to it.subject },
            )
            assertEquals(
                "C did not tell A (over the relays) that B is in the group: ${toA.map { it.op to it.subject }}",
                listOf(AnnounceOp.A to B),
                toA.map { it.op to it.subject },
            )
            assertEquals("the announce was paid for on-chain", 0, adminSeam.proposalRequests.get())

            // B's phone takes C's GP and now writes to A too.
            val vmB = MA3.chatVm(phoneB)
            net.to(NPUB.getValue(B)).filter { MA3.parseGp(it) != null }.forEach { MA3.relay(vmB, it, sender = C) }
            val f = GroupPaidOnceFixture().apply { scheduler = this@JoinerLearnsOtherMembersTest.scheduler }
            val gvmB = MA3.groupVm(phoneB, f, AnnounceSeam())
            assertEquals("B's messages still do not reach A (and C)", 2, gvmB.groupMessageCost(GID).recipients)

            // The scan replays the same accept on every pass: no new join, no new announce.
            val before = net.gps().size
            repeat(3) {
                MA3.relay(adminVm, accept, sender = A, timeMs = acceptedAt)
                MA3.chain(adminVm, accept, tx = 900 + it, timeMs = acceptedAt)
            }
            assertEquals("a replayed accept started a new announce pass", before, net.gps().size)
            assertEquals(0, adminSeam.proposalRequests.get())
        }

    /** Design §4 / review L5: the relay sends of one pass are spaced by a random 1-20 s (the tests above set it to 0). */
    @Test
    fun `the relay sends of a pass are spaced by a random 1 to 20 seconds`() {
        val runtime =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupAnnounceRuntime") }.getOrNull()
        org.junit.Assert.assertNotNull("there is no announce pass runtime (GroupAnnounceRuntime)", runtime)
        val instance = runtime!!.getDeclaredField("INSTANCE").get(null)
        val spacing = runtime.methods.first { it.name == "defaultSpacingMs" }
        val samples = (1..500).map { spacing.invoke(instance) as Long }
        org.junit.Assert.assertTrue(
            "a spacing outside 1-20 s: ${samples.filter { it !in 1_000L..20_000L }}",
            samples.all {
                it in
                    1_000L..20_000L
            }
        )
        org.junit.Assert.assertTrue("the spacing is not random", samples.toSet().size > 10)
    }
}
