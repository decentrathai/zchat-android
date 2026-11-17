package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.D
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, design test 18 (MEMBER_ANNOUNCE_DESIGN.md v2 §4 T5, §2.3, §6.1, review F11) — an authorized key request
 * (GR) or a roster query (GQ) from an ACTIVE member gets the current announces again, over the relays only.
 *
 * The creator C has A, B and D, all told about each other (the ledger records it). A asks:
 * - GQ over the relays: C re-sends A the current announce of B and of D over the relays; the ledger is unchanged; a
 *   second GQ within 10 minutes gets nothing; a GQ from someone not in the group, or one on-chain (a GQ is never
 *   on-chain), gets nothing;
 * - no relay route to A: nothing is sent and nothing is paid;
 * - an authorized GR over the relays: besides the key, the same relay re-send.
 *
 * At the MA2 tip a GQ is only logged and a GR sends only the key.
 *
 * Consent GRANTED; nothing may be proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyRequestAndRosterQueryResendRelayOnlyTest {
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

    private fun admin(relayToA: Boolean = true): Pair<AnnouncerPhone, AnnouncerPhone> {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, D to MemberStatus.ACTIVE))
        val phoneA = MA3.memberPhone(A, admin)
        listOf(B, D).forEach { MA3.memberPhone(it, admin) }
        if (relayToA) admin.base.nostrKeys[A] = NPUB.getValue(A)
        MA3.alreadyAnnounced(admin, listOf(A, B, D), AnnounceLedgerState.RELAY_FINAL)
        return admin to phoneA
    }

    private fun resent(npub: String) = net.gps(npub).map { it.op to it.subject }.toSet()

    @Test
    fun `a GQ from an active member gets a relay re-send, leaves the ledger alone, and a second one within 10 minutes gets nothing`() =
        runBlocking {
            val (admin, _) = admin()
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(admin, seam)
            val ledger = admin.ledger()

            MA3.relay(vm, ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), sender = A)

            assertEquals(
                "A was not re-sent the current announces over the relays",
                setOf(AnnounceOp.A to B, AnnounceOp.A to D),
                resent(NPUB.getValue(A)),
            )
            assertEquals("answering a GQ changed the ledger", ledger, admin.ledger())
            val count = net.gps().size
            MA3.relay(vm, ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), sender = A)
            assertEquals("a second GQ within 10 minutes was answered", count, net.gps().size)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a GQ from a non-member, or one found on-chain, gets nothing`() =
        runBlocking {
            val (admin, _) = admin()
            MA3.linkPhones(admin, AnnouncerPhone(X))
            admin.base.nostrKeys[X] = NPUB.getValue(X)
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(admin, seam)

            MA3.relay(vm, ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), sender = X)
            MA3.chain(vm, ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), tx = 40)

            assertTrue("a GQ that is not from an active member over the relays was answered", net.gps().isEmpty())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `with no relay route to the member nothing is sent and nothing is paid`() =
        runBlocking {
            val (admin, _) = admin(relayToA = false)
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(admin, seam)

            MA3.relay(vm, ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), sender = A)

            assertTrue(net.gps().isEmpty())
            assertEquals("answering a GQ paid on-chain", 0, seam.proposalRequests.get())
        }

    @Test
    fun `an authorized GR gets the key and a relay re-send of the current announces, with the ledger unchanged`() =
        runBlocking {
            val (admin, phoneA) = admin()
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(admin, seam)
            val ledger = admin.ledger()
            val sig = E2EEncryption.sign(MA3.keyFor(phoneA, C).privateKey, ZMSGGroupProtocol.groupKeyRequestSignedData(GID, A, 1))

            MA3.relay(vm, ZMSGGroupProtocol.createGroupKeyRequestMessage(GID, A, 1, sig), sender = A)

            assertEquals("the key was not granted over the relays", 1, net.ofType(GroupMessageType.GROUP_KEY, NPUB.getValue(A)).size)
            assertEquals(
                "a key request did not also re-send the current announces",
                setOf(AnnounceOp.A to B, AnnounceOp.A to D),
                resent(NPUB.getValue(A)),
            )
            assertEquals("answering a GR changed the announce ledger", ledger, admin.ledger())
            assertEquals(0, seam.proposalRequests.get())
        }
}
