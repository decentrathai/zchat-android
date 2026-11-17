package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA2, design test 10 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.2 "Announcer", §4.1, review M6) — on the group's
 * creator a group message adds nobody, and only an authenticated one makes an invitee "joined".
 *
 * Today W9 on the creator's phone adds any sender to the roster and flips any invitee to ACTIVE, so a forged on-chain
 * GROUP_MSG (the sender field is self-asserted) makes a stranger a paid recipient, or an invitee look joined.
 *
 * Wanted on the announcer:
 * - a forged GM adds no absent row and leaves an INVITED row INVITED;
 * - a GM authenticated by the sender's pairwise signature, from a completed key exchange, flips INVITED to ACTIVE and
 *   records the activation as authenticated (AUTH);
 * - on a group this phone rebuilt from its own sent invite (announcerRebuilt), an authenticated sender with a
 *   completed key exchange is added; one whose first key exchange still waits for Accept, or whose key changed, is not.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminW9Test {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    /** This phone created [GID] (or rebuilt it: [rebuilt]); X is INVITED with a completed key exchange. */
    private fun adminPhone(rebuilt: Boolean = false): AnnouncePhone {
        val phone = AnnouncePhone()
        if (!rebuilt) phone.selfCreated += GID
        phone.putGroup(
            GroupInfo(
                groupId = GID,
                name = "Admin W9",
                creatorAddress = phone.self,
                createdAt = Instant.ofEpochMilli(T),
                announcerRebuilt = rebuilt,
            ),
            listOf(
                GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.INVITED),
            ),
            1,
            AnnounceDriver.newGroupKey(),
        )
        return phone
    }

    @Test
    fun `a forged GM adds no absent row and leaves an invitee INVITED`() =
        runBlocking {
            val phone = adminPhone()
            phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, Z, "forged Z"), Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1))
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "forged X", seq = 2),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(2)
            )
            // Signed, but not by X's key.
            val forger = E2EEncryption.generateKeyPair()
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "forged X again", signer = forger, seq = 3),
                Instant.ofEpochMilli(
                    T + 3_000
                ),
                AnnounceDriver.txId(3)
            )

            assertNull("a forged GM added an absent sender on the creator's phone", phone.status(GID, Z))
            assertEquals("a forged GM made an invitee look joined", MemberStatus.INVITED, phone.status(GID, X))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a GM signed by an invitee with a completed key exchange makes it ACTIVE, recorded as authenticated`() =
        runBlocking {
            val phone = adminPhone()
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "hi, I joined", signer = x),
                Instant.ofEpochMilli(T + 1_000),
                AnnounceDriver.txId(1)
            )

            assertEquals("an authenticated GM did not make the invitee ACTIVE", MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(
                "the activation is not recorded as authenticated",
                ActivatedBy.AUTH,
                phone.state(GID).members[X]?.activatedBy,
            )
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a rebuilt group adds an authenticated completed-KEX sender, but not one waiting for Accept or with a changed key`() =
        runBlocking {
            val phone = adminPhone(rebuilt = true)
            val y = phone.link(Y)
            val z = phone.link(Z)
            phone.awaitingAccept[Z] = z.publicKey
            val w = "usyntheticma2memberw00000001"
            val wKeys = phone.link(w)
            phone.keyChanged += w
            val seam = HandshakeSeam(Y)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, Z, "waiting for accept", signer = z),
                Instant.ofEpochMilli(T + 1_000),
                AnnounceDriver.txId(1)
            )
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, w, "key changed", signer = wKeys),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(2)
            )
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, Y, "back after a restore", signer = y),
                Instant.ofEpochMilli(T + 3_000),
                AnnounceDriver.txId(3)
            )

            assertNull("a sender whose key exchange waits for Accept was added", phone.status(GID, Z))
            assertNull("a sender whose key changed was added", phone.status(GID, w))
            assertEquals(
                "an authenticated completed-KEX sender was not added to the rebuilt group",
                MemberStatus.ACTIVE,
                phone.status(GID, Y)
            )
            assertEquals(0, seam.proposalRequests.get())
        }
}
