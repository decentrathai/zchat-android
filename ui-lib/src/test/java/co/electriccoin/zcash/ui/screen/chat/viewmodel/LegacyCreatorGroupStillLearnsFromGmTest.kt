package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA2, design test 11 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.2, §7) — CHARACTERIZATION, green at the MA1 tip on
 * purpose: a member's phone in a group whose creator never announced anyone (a creator on 2.12.4, or a 2.13 build
 * before GP) keeps learning members from their group messages exactly as today (W9, lazy learning). Only a verified
 * GP closes that door; without one, the member-announce change must not cut these groups off.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyCreatorGroupStillLearnsFromGmTest {
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

    @Test
    fun `with no GP ever, a GM adds its sender and activates an invitee, as today`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(
                phone,
                extraRows = listOf(GroupMember(address = Y, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.INVITED)),
            )
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, Z, "hello from Z"), Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1))
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, Y, "hello from Y", seq = 2),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(2)
            )
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, Y, "relay hello", seq = 3),
                Instant.ofEpochMilli(T + 3_000),
                sender = Y,
                eventId = "e-y"
            )

            assertEquals("lazy learning stopped in a group whose creator never announced", MemberStatus.ACTIVE, phone.status(GID, Z))
            assertEquals("an invitee's GM no longer activates it in a legacy group", MemberStatus.ACTIVE, phone.status(GID, Y))
            assertFalse(phone.state(GID).rosterAnnounced)
            assertEquals("every message is stored", 3, phone.storedMessageIds(GID).size)
            assertEquals(0, seam.proposalRequests.get())
        }
}
