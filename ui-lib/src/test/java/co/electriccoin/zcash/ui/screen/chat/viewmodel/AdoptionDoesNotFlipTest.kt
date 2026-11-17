package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA2, design test 24 (MEMBER_ANNOUNCE_DESIGN.md v2 §4.2, review F4) — the address the creator sends a member's
 * messages to does not flip back and forth.
 *
 * A signed GROUP_ACCEPT makes the creator adopt the joiner's declared receive address (#218 / #219). With two signed
 * accepts from the same member in the history (it joined twice, from two address forms), each pass adopts one and then
 * the other, and the relays deliver the older accept again after the newer one: the fan-out address ends on the older
 * form (r1 -> r2 -> r1 on every pass), and with announces every flip would be a new announce to pay for.
 *
 * Wanted: an accept's address is adopted only when that accept is newer (by source time) than the last one adopted,
 * so the later accept's address is the adopted one after every pass, whatever order the copies arrive in.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdoptionDoesNotFlipTest {
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

    private val invited = "u1" + "a".repeat(110) + "invitedrep0"
    private val r1 = "u1" + "b".repeat(110) + "receiverep1"
    private val r2 = "u1" + "c".repeat(110) + "receiverep2"

    private fun accept(
        member: E2EKeyPair,
        declared: String
    ): String {
        val sig = E2EEncryption.sign(member.privateKey, ZMSGGroupProtocol.groupAcceptSignedData(GID, declared, member.publicKey))
        return ZMSGGroupProtocol.createGroupAcceptMessage(GID, declared, member.publicKey, sig)
    }

    @Test
    fun `with two signed accepts in the history, the later one's address is adopted on every pass`() =
        runBlocking {
            val phone = AnnouncePhone()
            phone.selfCreated += GID
            phone.putGroup(
                GroupInfo(groupId = GID, name = "Adoption", creatorAddress = phone.self, createdAt = Instant.ofEpochMilli(T)),
                listOf(
                    GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = invited, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.INVITED),
                ),
                1,
                AnnounceDriver.newGroupKey(),
            )
            val member = phone.link(invited)
            // The member's key, as this phone finds it under each of the member's address forms.
            phone.peerKeys[r1] = member.publicKey
            phone.peerKeys[r2] = member.publicKey
            val seam = HandshakeSeam(invited)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val first = accept(member, r1)
            val second = accept(member, r2)

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, first, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1))
                AnnounceDriver.receive(vm, second, Instant.ofEpochMilli(T + 2_000), AnnounceDriver.txId(2))
                // A relay delivers the older accept again, after the newer one.
                AnnounceDriver.receive(
                    vm,
                    first,
                    Instant.ofEpochMilli(T + 1_000),
                    sender = invited,
                    eventId = "synthetic-event-first-accept"
                )
                val rows = phone.roster(GID).filterNot { it.address == phone.self }
                assertEquals("pass ${pass + 1}: the member has more than one row: $rows", 1, rows.size)
                assertEquals("pass ${pass + 1}: the adopted address flipped back to the older accept's", r2, rows.single().address)
                assertEquals(MemberStatus.ACTIVE, rows.single().status)
            }
            assertEquals(0, seam.proposalRequests.get())
        }
}
