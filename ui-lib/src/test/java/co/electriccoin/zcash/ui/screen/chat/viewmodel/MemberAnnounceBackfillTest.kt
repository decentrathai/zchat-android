package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA-V1 (VERIFIED_MA_FINDINGS.md #1; MEMBER_ANNOUNCE_DESIGN.md v2 §3, §8) — a member row from before the member
 * announce is announced only when the creator can vouch for it, not merely because a secure link with it exists.
 *
 * A 2.12.4 creator added the claimed sender of ANY group message to its roster as ACTIVE: on-chain that sender is
 * self-asserted when the author signature is missing, so any holder of the group key (a member M) could put Z, a 1:1
 * contact of the creator, on the creator's roster with one unsigned group message naming `sender = Z`. After the
 * upgrade such a row has no announce record. It must not be announced to the group (which would spread M's forgery
 * with the creator's signature and make every member pay to write to Z), and it is not told about the others.
 *
 * A row from before the member announce counts as joined through an authenticated step only with a secure link AND
 * a proof: C invited it (its row's invite status, or C's own sent invite to it seen by the scan), or its address is
 * the one a verified signed accept declared (an alias). Without one it is recorded UNAUTH and keeps the "can't be
 * introduced" row note; a group message from it whose author verifies later makes it AUTH, and it is then announced.
 *
 * At the MA3 tip Z is backfilled AUTH because the secure link holds, and C tells A and B about Z over the relays.
 *
 * The creator C has A and B (invited by C, joined through an authenticated step). Every member has a completed key
 * exchange with C and a relay key. Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberAnnounceBackfillTest {
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

    private companion object {
        /** The row a member planted with an unsigned group message naming it (no invite status, no alias). */
        const val Z = "usyntheticma3memberz000000001"

        /** A row from before the member announce that C invited (its invite status is tracked). */
        const val Y = "usyntheticma3membery000000001"

        /** A row whose address a verified signed accept declared: the invited form [W_OLD] is an alias of it. */
        const val W = "usyntheticma3memberw000000001"
        const val W_OLD = "usyntheticma3memberw000000000"

        val LEGACY_NPUB = mapOf(Z to "1".repeat(64), Y to "2".repeat(64), W to "3".repeat(64))
    }

    private class World(
        val admin: AnnouncerPhone,
        val phones: Map<String, AnnouncerPhone>,
    )

    /**
     * C's phone with A and B, plus [legacy] rows (address to invite status) that are ACTIVE with no announce record, as
     * a 2.12.4 creator's roster has them after the upgrade. Each has a completed, cross-linked key exchange with C.
     */
    private fun world(legacy: List<Pair<String, InviteStatus?>>): World {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE))
        val phones = (listOf(A, B) + legacy.map { it.first }).associateWith { MA3.memberPhone(it, admin) }
        val t = MA3.now() - 3_600_000L
        admin.base.rosters[GID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                admin.base.roster(GID) +
                    legacy.map { (address, invite) ->
                        GroupMember(
                            address = address,
                            joinedAt = Instant.ofEpochMilli(t),
                            status = MemberStatus.ACTIVE,
                            inviteStatus = invite
                        )
                    },
            )
        listOf(A, B).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
        legacy.forEach { (address, _) -> admin.base.nostrKeys[address] = LEGACY_NPUB.getValue(address) }
        return World(admin, phones)
    }

    private fun npub(address: String): String = NPUB[address] ?: LEGACY_NPUB.getValue(address)

    /** The subjects [address] was told are in the group (A announces over the relays). */
    private fun toldAbout(address: String): Set<String> =
        net
            .gps(npub(address))
            .filter { it.op == AnnounceOp.A }
            .map { it.subject }
            .toSet()

    /** An on-chain group message naming [sender], signed with [signer]'s key for C when given. */
    private fun gm(
        sender: String,
        text: String,
        signer: AnnouncerPhone? = null,
        seq: Long = 1,
    ): String =
        ZMSGGroupProtocol.createGroupMsgMessage(
            GID,
            seq,
            1,
            sender,
            text,
            ZMSGGroupProtocol.decodeGroupKey(MA3.groupKey),
            signer?.let { phone -> { data: String -> E2EEncryption.sign(MA3.keyFor(phone, C).privateKey, data) } },
        )

    private fun owedPairs(admin: AnnouncerPhone) = GroupAnnouncer(admin.proxy, { _, _ -> false }, { null }).owedPairs(GID)

    @Test
    fun `a pre-upgrade ACTIVE row added by an unsigned group message is never announced, even with a secure link`() =
        runBlocking {
            val w = world(listOf(Z to null))
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(w.admin, seam)
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceBackfillTest.scheduler }
            val gseam = AnnounceSeam()
            val gvm = MA3.groupVm(w.admin, f, gseam)
            // What M sent before the upgrade: a group message naming Z, with no author signature. The scan replays it.
            val planted = gm(Z, "planted by M")

            var conversation: GroupConversation? = null
            repeat(3) {
                MA3.chain(vm, planted, tx = 700, timeMs = MA3.now() - 1_800_000L)
                conversation = MA3.load(gvm, f)
            }

            assertTrue("setup: the passes did not run (A was not told about B)", B in toldAbout(A))
            assertTrue(
                "Z (planted by an unsigned group message) was announced: ${net.gps().map { it.op to it.subject }}",
                net.gps().none { it.subject == Z },
            )
            assertTrue("Z was told about the members", net.to(npub(Z)).none { MA3.parseGp(it) != null })
            assertEquals(
                "Z is not recorded UNAUTH",
                ActivatedBy.UNAUTH,
                w.admin
                    .state()
                    .members[Z]
                    ?.activatedBy
            )
            @Suppress("UNCHECKED_CAST")
            val noSecureLink = MA3.introduce(conversation, "noSecureLink") as Set<String>?
            assertTrue("Z's row has no \"can't be introduced\" note: $noSecureLink", noSecureLink.orEmpty().contains(Z))
            val owed = owedPairs(w.admin)
            assertTrue("a pair names Z: $owed", owed.none { it.sAddr == Z || it.rAddr == Z })
            assertEquals("Z's row is gone from the admin's roster", MemberStatus.ACTIVE, w.admin.status(Z))
            assertEquals("something was paid for", 0, seam.proposalRequests.get() + gseam.proposals.size)
        }

    /** Characterization: a pre-upgrade row C invited, or one whose address a verified accept declared, is announced. */
    @Test
    fun `a pre-upgrade row with its invite status or an adopted address is backfilled AUTH and announced`() =
        runBlocking {
            val w = world(listOf(Y to InviteStatus.SENT, W to null))
            w.admin.base.aliases[W_OLD] = W
            net.register()
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceBackfillTest.scheduler }
            val gseam = AnnounceSeam()
            val gvm = MA3.groupVm(w.admin, f, gseam)

            val conversation = MA3.load(gvm, f)

            for (m in listOf(A, B)) {
                assertTrue("${m.take(22)} was not told about Y and W: ${toldAbout(m)}", toldAbout(m).containsAll(setOf(Y, W)))
            }
            assertEquals(
                "Y is not recorded AUTH",
                ActivatedBy.AUTH,
                w.admin
                    .state()
                    .members[Y]
                    ?.activatedBy
            )
            assertEquals(
                "W is not recorded AUTH",
                ActivatedBy.AUTH,
                w.admin
                    .state()
                    .members[W]
                    ?.activatedBy
            )
            @Suppress("UNCHECKED_CAST")
            val noSecureLink = (MA3.introduce(conversation, "noSecureLink") as Set<String>?).orEmpty()
            assertFalse("Y or W has the \"can't be introduced\" note: $noSecureLink", noSecureLink.contains(Y) || noSecureLink.contains(W))
            assertEquals(0, gseam.proposals.size)
        }

    @Test
    fun `a pre-upgrade row with no proof is announced once a group message from it verifies`() =
        runBlocking {
            val w = world(listOf(Z to null))
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(w.admin, seam)
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceBackfillTest.scheduler }
            val gvm = MA3.groupVm(w.admin, f, AnnounceSeam())
            MA3.load(gvm, f)
            assertTrue("Z was announced before anything vouched for it", net.gps().none { it.subject == Z })

            // Z itself posts: its group message carries its author signature, made with Z's key for C.
            MA3.chain(vm, gm(Z, "hello from Z", signer = w.phones.getValue(Z), seq = 2), tx = 701, timeMs = MA3.now() + 60_000)

            assertEquals(
                "a verified message did not make Z AUTH",
                ActivatedBy.AUTH,
                w.admin
                    .state()
                    .members[Z]
                    ?.activatedBy
            )
            for (m in listOf(A, B)) {
                assertTrue("${m.take(22)} was not told about Z after its verified message", Z in toldAbout(m))
            }
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a pre-upgrade row with no invite status is announced once the scan replays our own invite to it`() =
        runBlocking {
            val w = world(listOf(Z to null))
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(w.admin, seam)
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceBackfillTest.scheduler }
            val gvm = MA3.groupVm(w.admin, f, AnnounceSeam())
            MA3.load(gvm, f)
            assertTrue("Z was announced before anything vouched for it", net.gps().none { it.subject == Z })

            // The scan replays the invite C itself sent Z (a roster from before invite tracking has no invite status).
            val giToZ = AnnounceDriver.gi(C, w.admin.base.sessionWith(Z), MA3.groupKey, keyEpoch = 1, groupId = GID)
            repeat(2) { MA3.chain(vm, giToZ, tx = 702, timeMs = MA3.now() - 3_000_000L, ownSend = true, ownRecipient = Z) }
            MA3.load(gvm, f)

            assertEquals(
                "our own invite did not make Z AUTH",
                ActivatedBy.AUTH,
                w.admin
                    .state()
                    .members[Z]
                    ?.activatedBy
            )
            for (m in listOf(A, B)) {
                assertTrue("${m.take(22)} was not told about Z after our own invite was seen", Z in toldAbout(m))
            }
            assertEquals(MemberStatus.ACTIVE, w.admin.status(Z))
            assertEquals(0, seam.proposalRequests.get())
        }
}
