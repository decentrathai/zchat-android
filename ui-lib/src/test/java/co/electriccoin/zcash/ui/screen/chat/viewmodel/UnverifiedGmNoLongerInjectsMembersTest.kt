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
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA2, design test 9 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.2, review H4) — once the creator announces the roster,
 * a group message no longer adds members on a member's phone.
 *
 * Today every GROUP_MSG adds its sender to the roster (W9, lazy learning), and the phone then pays to write to it. The
 * on-chain sender field is self-asserted, a first-contact key exchange stores the stranger's key before the user
 * accepts it, and a relay contact can be given the group key: all three put a stranger in the fan-out.
 *
 * Wanted: after one verified GP, none of these adds a row: an unsigned on-chain GM claiming Z, a GM signed with a key
 * planted by a first-contact key exchange for Z (still waiting for Accept), and a relay-sealed GM from a contact who is
 * not a member. Each message is still stored, and the bubble says "Not in your member list yet: your messages don't
 * reach them." / "Aún no está en tu lista de miembros: tus mensajes no le llegan."
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnverifiedGmNoLongerInjectsMembersTest {
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

    /** A member's phone whose roster the creator announced (one verified `A X`). */
    private suspend fun announcedPhone(): Triple<AnnouncePhone, ChatViewModel, HandshakeSeam> {
        val phone = AnnouncePhone()
        val admin = AnnounceDriver.memberPhone(phone)
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.receive(
            vm,
            AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
            Instant.ofEpochMilli(T + 1_000),
            AnnounceDriver.txId(1)
        )
        assertEquals("fixture: the verified announce did not add X", MemberStatus.ACTIVE, phone.status(GID, X))
        return Triple(phone, vm, seam)
    }

    private suspend fun assertStoredWithNote(
        phone: AnnouncePhone,
        sender: String,
        storedBefore: Int,
    ) {
        assertEquals("the message from an unlisted sender was not stored", storedBefore + 1, phone.storedMessageIds(GID).size)
        val f = GroupPaidOnceFixture().apply { scheduler = this@UnverifiedGmNoLongerInjectsMembersTest.scheduler }
        val gvm = AnnounceDriver.groupVm(phone, f)
        val unlisted = AnnounceDriver.conversationSet(AnnounceDriver.loadedConversation(gvm, f), "sendersNotInList")
        assertTrue("the bubble of an unlisted sender carries no note: $unlisted", unlisted?.contains(sender) == true)
        assertEquals(
            "Not in your member list yet: your messages don't reach them.",
            ChatResourceText.strings("values")["group_sender_not_in_list"],
        )
        assertEquals(
            "Aún no está en tu lista de miembros: tus mensajes no le llegan.",
            ChatResourceText.strings("values-es")["group_sender_not_in_list"],
        )
        assertEquals(0, f.seam.proposalRequests.get())
    }

    @Test
    fun `an unsigned on-chain GM claiming Z adds no row`() =
        runBlocking {
            val (phone, vm, seam) = announcedPhone()
            val stored = phone.storedMessageIds(GID).size

            AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, Z, "hello from Z"), Instant.ofEpochMilli(T + 2_000), AnnounceDriver.txId(2))

            assertNull("an unsigned GM claiming Z put Z in the roster", phone.status(GID, Z))
            assertStoredWithNote(phone, Z, stored)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a GM signed with a first-contact key planted for Z adds no row`() =
        runBlocking {
            val (phone, vm, seam) = announcedPhone()
            // Z's first key exchange stored Z's key before the user accepted the chat.
            val planted = E2EEncryption.generateKeyPair()
            phone.peerKeys[Z] = planted.publicKey
            phone.awaitingAccept[Z] = planted.publicKey
            val stored = phone.storedMessageIds(GID).size

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, Z, "signed by Z", signer = planted),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(2)
            )

            assertNull("a GM signed with a planted first-contact key put Z in the roster", phone.status(GID, Z))
            assertStoredWithNote(phone, Z, stored)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a relay-sealed GM from a contact who is not a member adds no row`() =
        runBlocking {
            val (phone, vm, seam) = announcedPhone()
            phone.link(Y)
            val stored = phone.storedMessageIds(GID).size

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, Y, "sealed by Y"),
                Instant.ofEpochMilli(T + 2_000),
                sender = Y,
                eventId = "synthetic-event-gm-y"
            )

            assertNull("a relay GM from a non-member contact put it in the roster", phone.status(GID, Y))
            assertStoredWithNote(phone, Y, stored)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `control - a GM from an announced member is stored with no note and changes nothing`() =
        runBlocking {
            val (phone, vm, seam) = announcedPhone()
            val before = phone.rosters[GID]

            AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, X, "hi from X"), Instant.ofEpochMilli(T + 2_000), AnnounceDriver.txId(2))

            assertEquals(before, phone.rosters[GID])
            assertEquals(1, phone.storedMessageIds(GID).size)
            val f = GroupPaidOnceFixture().apply { scheduler = this@UnverifiedGmNoLongerInjectsMembersTest.scheduler }
            val unlisted =
                AnnounceDriver.conversationSet(
                    AnnounceDriver.loadedConversation(AnnounceDriver.groupVm(phone, f), f),
                    "sendersNotInList"
                )
            assertTrue("an announced member's bubble carries the note: $unlisted", unlisted != null && X !in unlisted)
            assertEquals(0, seam.proposalRequests.get())
        }
}
