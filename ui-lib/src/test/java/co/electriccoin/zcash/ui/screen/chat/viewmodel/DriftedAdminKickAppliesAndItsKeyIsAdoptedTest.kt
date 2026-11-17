package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R1 and R2; section 4.1 class 2) — on a phone whose group record holds the
 * admin's key but whose stored admin address holds none (shape B), the admin's removal and key change are applied, and
 * the new group key is adopted from the chat that holds the admin's key.
 *
 * The phone: a chat with the admin under Y; the group's creator address is X_OLD with no key under it; the stored
 * creator key is the admin's. The admin's phone writes X_OLD into its changes and wraps the new group key under its
 * session with this phone, which this phone holds under Y.
 *
 * At `00f1a349b` the gate refuses each such change ("No E2E key for group-control signer"): the removed member stays
 * ACTIVE in this phone's list and keeps receiving what this phone writes; the new key is never adopted; a removal of
 * this phone itself is never noticed. And had the gate let the change through, the new key would be unwrapped with a
 * session looked up under X_OLD, which does not exist (R2).
 *
 * Nothing is sent or paid by any case. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriftedAdminKickAppliesAndItsKeyIsAdoptedTest {
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

    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)

    private fun onChain(
        vm: ChatViewModel,
        memo: String,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(6), minedHeight = HEIGHT + 6)

    private fun assertRemovalApplied(
        p: AdminCheckPhone,
        newKey: String,
    ) {
        assertEquals("the member the admin removed is still in this phone's list", MemberStatus.LEFT, p.base.status(GID, M))
        assertEquals("the removal did not move the key epoch", 2, p.base.epochs[GID])
        assertEquals("the new group key was not adopted from the admin's chat", newKey, p.groupKey(2))
        assertEquals("the removal raised no removal floor", 2, p.base.floors[GID])
    }

    @Test
    fun `a - an on-chain kick naming the keyless creator address removes the member and its key is adopted`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()

            onChain(vm, AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = newKey))

            assertRemovalApplied(p, newKey)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `b - the same kick over the relays removes the member and its key is adopted`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()

            AnnounceDriver.receive(
                vm,
                AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = newKey),
                kickTime,
                sender = Y,
                eventId = "synthetic-event-ki25-kick",
            )

            assertRemovalApplied(p, newKey)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `c - a rotation naming the keyless creator address is adopted`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()

            onChain(vm, AdminCheck.keyNaming(p, naming = X_OLD, newEpoch = 2, newKey = newKey))

            assertEquals("the admin's key change did not move the key epoch", 2, p.base.epochs[GID])
            assertEquals("the admin's new group key was not adopted", newKey, p.groupKey(2))
            assertEquals("a key change removed a member", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `d - a kick naming this phone closes the group and marks it removed`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)

            onChain(vm, AdminCheck.kickNaming(p, naming = X_OLD, kicked = p.self, newEpoch = 2, newKey = null))

            val info = p.base.info(GID)
            assertFalse("the admin removed this phone and the group is still open here", info.isActive)
            assertTrue("the group is not marked as one this phone was removed from", info.removedByAdmin)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `e - control - a kick naming the admin's chat is applied`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()

            onChain(vm, AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey))

            assertRemovalApplied(p, newKey)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `f - control - a kick naming the creator address signed with a member's key changes nothing`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val member = p.base.link(M)
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val before = p.snapshot()

            onChain(
                vm,
                AdminCheck.kickNaming(
                    p,
                    naming = X_OLD,
                    kicked = p.self,
                    newEpoch = 2,
                    newKey = AnnounceDriver.newGroupKey(),
                    signer = member,
                    wrapFor = M,
                ),
            )

            assertEquals("a kick a member's key signed changed this phone's group", before, p.snapshot())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `g - the on-chain kick of case a on three passes leaves the state of the first pass`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val kick = AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())

            onChain(vm, kick)
            val afterFirst = p.snapshot()
            onChain(vm, kick)
            onChain(vm, kick)

            assertEquals("the same kick read again on later passes changed the group again", afterFirst, p.snapshot())
            assertEquals(0, seam.proposalRequests.get())
        }
}
