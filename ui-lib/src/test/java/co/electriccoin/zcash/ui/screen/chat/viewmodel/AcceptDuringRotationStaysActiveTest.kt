package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M1
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.admin
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.member
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * KI-22 — an invitee who accepts while the admin's rotation waits for a block falls back to Invited.
 *
 * At 565fb3c14 the rotation saves the roster it read when it took the lock, so a GROUP_ACCEPT that ChatViewModel stored
 * meanwhile (Y: INVITED with a delivered invite → ACTIVE) is overwritten and Y shows as Invited again.
 *
 * Wanted: the commit keeps every row's status as it is now; the rotation only writes its own key-delivery fields.
 * Control: a rotation with nothing stored meanwhile leaves the invitee's status alone too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptDuringRotationStaysActiveTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `an invitee who accepts during the rotation stays active`() =
        runBlocking {
            val f =
                GroupChangeRaceFixture(
                    "zgrp_ki22_accept",
                    listOf(admin(), member(M1), member(Y, MemberStatus.INVITED, InviteStatus.SENT)),
                    setOf(M1, Y),
                )
            val vm = f.groupViewModel().also { vms += it }

            val rotation = f.startRotation(vm, null)
            f.awaitFirstSubmit()

            // Y's GROUP_ACCEPT arrives and is stored, as processGroupAccept stores it.
            f.storeUnderGroupLock { rows -> rows.map { if (it.address == Y) it.copy(status = MemberStatus.ACTIVE) else it } }

            f.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { rotation.join() }

            assertEquals("the rotation's roster write undid Y's accept", MemberStatus.ACTIVE, f.row(Y).status)
            assertEquals(2, f.epoch)
            assertEquals("Y was sent the new key", null, f.row(Y).pendingKeyEpoch)
        }

    @Test
    fun `control - with nothing stored meanwhile the invitee keeps its status and gets the key`() =
        runBlocking {
            val f =
                GroupChangeRaceFixture(
                    "zgrp_ki22_accept_control",
                    listOf(admin(), member(M1), member(Y, MemberStatus.INVITED, InviteStatus.SENT)),
                    setOf(M1, Y),
                )
            val vm = f.groupViewModel().also { vms += it }
            f.release.complete(Unit)

            withTimeout(HandshakeHarness.TIMEOUT_MS) { f.startRotation(vm, null).join() }

            assertEquals(MemberStatus.INVITED, f.row(Y).status)
            assertEquals(2, f.epoch)
            assertEquals(1, f.memosTo(Y))
        }
}
