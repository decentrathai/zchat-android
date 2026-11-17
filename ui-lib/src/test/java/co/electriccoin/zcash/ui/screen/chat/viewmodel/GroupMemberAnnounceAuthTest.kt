package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA2, design test 6 (MEMBER_ANNOUNCE_DESIGN.md v2 §3, §5.1) — a member's phone takes a member announce (GP)
 * only from the group's creator.
 *
 * At the MA1 tip nothing handles GP: the dispatch logs it as unhandled, so even a valid announce from the creator adds
 * nobody, and a member's roster keeps only the inviter and itself (PHONE_TRIAGE F4).
 *
 * Wanted: a GP whose signature verifies against the creator's key (the one captured at join; since KI-25 pair 3, D1,
 * ZC-598, no Tier-1 snapshot of a key at the creator's address) adds the member ACTIVE, on-chain and over the relays,
 * and the member row says "Shared by the group creator". Nothing changes for an unsigned GP, a GP signed with a
 * member's key, a GP the creator signed for another member and someone replays here, a relay GP sealed by someone
 * who is not the creator, or a group with no creator key.
 *
 * Every case runs with the handshake-spend consent GRANTED and asserts that nothing was proposed. All addresses, keys
 * and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMemberAnnounceAuthTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun assertNothingChanged(
        phone: AnnouncePhone,
        rosterBefore: String?,
        seam: HandshakeSeam,
        what: String,
    ) {
        assertEquals("$what changed the roster", rosterBefore, phone.rosters[GID])
        assertFalse("$what marked the roster as announced", phone.state(GID).rosterAnnounced)
        assertEquals("$what: something was proposed for payment", 0, seam.proposalRequests.get())
    }

    @Test
    fun `a GP the creator signed adds the member ACTIVE on-chain, shared by the group creator`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(1)
            )

            assertEquals("a verified announce from the creator did not add the member", MemberStatus.ACTIVE, phone.status(GID, X))
            assertTrue("a verified announce did not mark the roster as announced", phone.state(GID).rosterAnnounced)
            assertEquals(0, seam.proposalRequests.get())

            val f = GroupPaidOnceFixture().apply { scheduler = this@GroupMemberAnnounceAuthTest.scheduler }
            val gvm = AnnounceDriver.groupVm(phone, f)
            val shared = AnnounceDriver.conversationSet(AnnounceDriver.loadedConversation(gvm, f), "sharedByCreator")
            assertTrue("the member row does not say it was shared by the group creator: $shared", shared?.contains(X) == true)
            assertEquals("Shared by the group creator", ChatResourceText.strings("values")["group_member_shared_by_creator"])
            assertEquals("Compartido por el creador del grupo", ChatResourceText.strings("values-es")["group_member_shared_by_creator"])
            assertEquals(0, f.seam.proposalRequests.get())
        }

    @Test
    fun `a GP the creator signed and sealed adds the member over the relays`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 2_000),
                sender = ADMIN,
                eventId = "synthetic-event-gp-1",
            )

            assertEquals("a verified relay announce from the creator did not add the member", MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a GP from a group with no creator key is refused although a key sits at the creator's address (D1)`() =
        runBlocking {
            // Joined before the creator key was captured. KI-25 pair 3 (D1, ZC-598): the key the KEX stored for the
            // creator's address is never the creator's by itself any more, and no announce writes a snapshot of it.
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, withCreatorKey = false)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.rosters[GID]

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(1)
            )

            assertNull(phone.status(GID, X))
            assertNothingChanged(phone, before, seam, "a GP checked against a key at the creator's address")
            assertNull("an announce wrote a creator key", phone.info(GID).creatorPublicKey)
        }

    @Test
    fun `an unsigned GP changes nothing`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(phone)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.rosters[GID]

            AnnounceDriver.receive(vm, AnnounceDriver.unsignedGp(X, T + 1_000, 1), Instant.ofEpochMilli(T + 2_000), AnnounceDriver.txId(1))

            assertNull(phone.status(GID, X))
            assertNothingChanged(phone, before, seam, "an unsigned GP")
        }

    @Test
    fun `a GP signed with a member's key changes nothing`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(phone)
            val member = phone.link(Y)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.rosters[GID]

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, member),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(1)
            )
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_001, 1, member),
                Instant.ofEpochMilli(T + 2_000),
                sender = Y,
                eventId = "synthetic-event-member",
            )

            assertNothingChanged(phone, before, seam, "a GP signed by a member")
        }

    @Test
    fun `a GP the creator signed for another member, replayed here, changes nothing`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(phone)
            // The creator's pairwise key with some other member C: a valid signature, but not for this phone.
            val adminForC = E2EEncryption.generateKeyPair()
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.rosters[GID]

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, adminForC),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(1)
            )

            assertNothingChanged(phone, before, seam, "a GP signed for another member")
        }

    @Test
    fun `a relay GP sealed by someone who is not the creator changes nothing, even with a valid signature`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            phone.link(Y)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.rosters[GID]

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 2_000),
                sender = Y,
                eventId = "synthetic-event-forwarded",
            )

            assertNothingChanged(phone, before, seam, "a relay GP sealed by a member")
        }

    @Test
    fun `a GP in a group with no creator key changes nothing`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, withCreatorKey = false)
            // No key for the creator's address either (a legacy join with no key exchange).
            phone.peerKeys.remove(ADMIN)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.rosters[GID]

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(1)
            )

            assertNothingChanged(phone, before, seam, "a GP in a group with no creator key")
        }
}
