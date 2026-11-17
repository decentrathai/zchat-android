package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
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
 * Round 8 MA2, design test 7 (MEMBER_ANNOUNCE_DESIGN.md v2 §0, §5.1) — an old member announce, replayed, never brings a
 * member back.
 *
 * The chain scan replays every group memo on every pass. `A X@5`, then `R X@6`: replaying `A@5` must leave X out. After
 * the creator removed X (a GROUP_KICK to epoch 2), an `A X` signed at the old epoch 1 leaves X out; a newer `A X` at
 * the new epoch brings X back.
 *
 * At the MA1 tip nothing handles GP, so X never appears at all. Consent GRANTED; nothing is proposed. All addresses,
 * keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnounceReplayCannotResurrectTest {
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
    fun `A X at 5, R X at 6, then the old A at 5 replayed - X stays out, over three passes`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val a5 = AnnounceDriver.gp(AnnounceOp.A, X, T + 5_000, 1, admin)
            val r6 = AnnounceDriver.gp(AnnounceOp.R, X, T + 6_000, 1, admin)

            AnnounceDriver.receive(vm, a5, Instant.ofEpochMilli(T + 5_000), AnnounceDriver.txId(5))
            assertEquals("the announce A X@5 did not add X", MemberStatus.ACTIVE, phone.status(GID, X))
            AnnounceDriver.receive(vm, r6, Instant.ofEpochMilli(T + 6_000), AnnounceDriver.txId(6))
            assertEquals("the announce R X@6 did not take X out", MemberStatus.LEFT, phone.status(GID, X))

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, a5, Instant.ofEpochMilli(T + 5_000), AnnounceDriver.txId(5))
                AnnounceDriver.receive(vm, r6, Instant.ofEpochMilli(T + 6_000), AnnounceDriver.txId(6))
                // And the relays deliver the old A again, last.
                AnnounceDriver.receive(vm, a5, Instant.ofEpochMilli(T + 5_000), sender = ADMIN, eventId = "synthetic-event-a5")
                assertEquals("pass ${pass + 1}: the replayed A X@5 brought X back", MemberStatus.LEFT, phone.status(GID, X))
            }
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `after a kick, an A signed at the old epoch leaves X out, and a newer A at the new epoch brings X back`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 5_000, 1, admin),
                Instant.ofEpochMilli(T + 5_000),
                AnnounceDriver.txId(5)
            )
            assertEquals("the announce A X did not add X", MemberStatus.ACTIVE, phone.status(GID, X))

            val kick = AnnounceDriver.kick(phone, admin, X, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
            AnnounceDriver.receive(vm, kick, Instant.ofEpochMilli(T + 6_000), AnnounceDriver.txId(6))
            assertEquals("fixture: the kick removed X", MemberStatus.LEFT, phone.status(GID, X))
            assertEquals("fixture: the kick moved the key epoch", 2, phone.epochs[GID])

            // Newer than the kick, but signed at the epoch X was removed from.
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 7_000, 1, admin),
                Instant.ofEpochMilli(T + 7_000),
                AnnounceDriver.txId(7)
            )
            assertEquals("an A signed at the epoch X was removed from brought X back", MemberStatus.LEFT, phone.status(GID, X))

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X, T + 8_000, 2, admin),
                Instant.ofEpochMilli(T + 8_000),
                AnnounceDriver.txId(8)
            )
            assertEquals("a newer A at the new epoch did not bring X back", MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(0, seam.proposalRequests.get())
        }
}
