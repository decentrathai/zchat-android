package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M1
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.X
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
 * KI-22 — a member who leaves while the admin's rotation waits for a block comes back.
 *
 * At 565fb3c14 rotateAndNotify reads the roster when it takes the lock, sends the key memos (which can take minutes),
 * then saves `members.map { … }` built from that old copy. A GROUP_LEAVE that ChatViewModel stored meanwhile (under the
 * group lock, as processGroupLeave does) is overwritten: X is ACTIVE again, keeps being sent every future key and is
 * counted in every group message: the departed member keeps reading the group.
 *
 * Wanted: the commit re-reads the roster under the group lock and applies only this rotation's own changes, so a row
 * that went LEFT stays LEFT, and a member who left before their key memo went out is not sent the new key at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaveDuringRotationStaysLeftTest {
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
    fun `a member who leaves during the rotation stays left and gets no later key`() =
        runBlocking {
            val f = GroupChangeRaceFixture("zgrp_ki22_leave", listOf(admin(), member(M1), member(X)), setOf(M1, X))
            val vm = f.groupViewModel().also { vms += it }

            // The rotation's key memo to M1 waits for a block.
            val rotation = f.startRotation(vm, null)
            f.awaitFirstSubmit()

            // X's GROUP_LEAVE arrives and is stored, as processGroupLeave stores it.
            f.storeUnderGroupLock { rows -> rows.map { if (it.address == X) it.copy(status = MemberStatus.LEFT) else it } }
            val memosAtLeave = f.memos.size

            f.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { rotation.join() }

            assertEquals("the rotation's roster write brought X back", MemberStatus.LEFT, f.row(X).status)
            assertEquals("the rotation completed", 2, f.epoch)
            assertEquals("X had left before its key memo went out; it must not be sent the new key", 0, f.memosTo(X, from = memosAtLeave))

            // The next rotation does not key X either.
            val next = f.startRotation(vm, null)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { next.join() }
            assertEquals(3, f.epoch)
            assertEquals("a later rotation sent its key to the departed X", 0, f.memosTo(X, from = memosAtLeave))
            assertEquals(MemberStatus.LEFT, f.row(X).status)
            assertEquals("M1 still gets every key", null, f.row(M1).pendingKeyEpoch)
        }
}
