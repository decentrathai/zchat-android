package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, design test 16 (the task's "removal" case; MEMBER_ANNOUNCE_DESIGN.md v2 §4 T3, §6.3, review F5) — the
 * members are told that a removed member is out, inside the removal's own quote.
 *
 * The creator C has A (no relay key), B (relay key) and X (no relay key), all already told about each other. C removes
 * X. The removal's quote counts, besides the new key to A and the removal notice to X (P6), the on-chain `R X` to A:
 * 3 memos. Accepting pays exactly those 3. B is told `R X` over the relays, free. X is told of its removal once, by
 * P6's signed notice inside the same quote; C sends X no paid `R X` on top of it (a relay copy only, when X has a
 * relay key). A's and B's phones then leave X out of their group messages.
 *
 * At the MA2 tip the removal sends no `R X` at all, so A and B keep writing (and paying) to X.
 *
 * Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KickedMemberAnnouncedRemovedTest {
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
    fun `the removal's quote includes the on-chain R X pairs, pays exactly them, and the members drop X`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE))
            val phoneA = MA3.memberPhone(A, admin)
            val phoneB = MA3.memberPhone(B, admin)
            MA3.memberPhone(X, admin)
            admin.base.nostrKeys[B] = NPUB.getValue(B)
            MA3.alreadyAnnounced(admin, listOf(A, B, X))
            val announcedAt =
                admin
                    .state()
                    .members
                    .getValue(X)
                    .n
            val vmA = MA3.chatVm(phoneA)
            val vmB = MA3.chatVm(phoneB)
            // Both members learned X (and each other) from C before the removal.
            var tx = 10
            for ((phone, vm) in listOf(phoneA to vmA, phoneB to vmB)) {
                for (s in listOf(A, B, X).filter { it != phone.self }) {
                    MA3.chain(
                        vm,
                        MA3.gpFrom(
                            admin,
                            phone.self,
                            AnnounceOp.A,
                            s,
                            admin
                                .state()
                                .members
                                .getValue(s)
                                .n
                        ),
                        tx = tx++
                    )
                }
            }
            assertTrue(
                "setup: A does not write to X",
                X in AnnounceDriver.messageRecipients(MA3.groupVm(phoneA, GroupPaidOnceFixture(), AnnounceSeam()), GID)
            )
            net.register()
            val seam = AnnounceSeam()
            val f = GroupPaidOnceFixture().apply { scheduler = this@KickedMemberAnnouncedRemovedTest.scheduler }
            val vm = MA3.groupVm(admin, f, seam)
            MA3.load(vm, f)
            net.clear()

            f.awaitNewJobs(vm) { vm.kickMember(GID, X) }
            val quote = vm.paidActionConfirmation.value
            assertNotNull("the removal asked nothing", quote)
            assertEquals(PaidAction.GROUP_REMOVE_MEMBER, quote!!.action)
            assertEquals(
                "the removal's quote is not the key to A + the notice to X + R X to A",
                3,
                quote.onChainMessages,
            )
            MA3.confirm(vm, f)

            val paid = seam.proposals
            assertEquals("the removal did not pay exactly its 3 quoted memos: ${paid.map { it.recipient }}", 3, paid.size)
            val rxToA =
                paid.filter {
                    it.recipient == A &&
                        MA3.parseGp(it.memo)?.let { g ->
                            g.op == AnnounceOp.R && g.subject == X
                        } == true
                }
            assertEquals("A was not paid the on-chain R X", 1, rxToA.size)
            val toX = paid.filter { it.recipient == X }
            assertEquals("X got a second paid memo on top of the removal notice", 1, toX.size)
            assertEquals(GroupMessageType.GROUP_KICK, ZMSGGroupProtocol.parseMessageType(toX.single().memo))
            val rxToB = net.gps(NPUB.getValue(B)).filter { it.op == AnnounceOp.R && it.subject == X }
            // (The group screen's reload after the removal re-sends every current R free, so there may be more copies.)
            assertTrue("B was not told R X over the relays", rxToB.isNotEmpty())
            assertTrue("R X is not newer than X's announce", rxToB.all { it.n > announcedAt })

            // The members take C's R X: their messages no longer go to X.
            MA3.chain(vmA, rxToA.single().memo, tx = 90)
            net.to(NPUB.getValue(B)).filter { MA3.parseGp(it) != null }.forEach { MA3.relay(vmB, it, sender = C) }
            for (phone in listOf(phoneA, phoneB)) {
                val recipients = AnnounceDriver.messageRecipients(MA3.groupVm(phone, GroupPaidOnceFixture(), AnnounceSeam()), GID)
                assertFalse("${phone.self.take(20)} still writes to X after R X: $recipients", X in recipients)
            }
        }
}
