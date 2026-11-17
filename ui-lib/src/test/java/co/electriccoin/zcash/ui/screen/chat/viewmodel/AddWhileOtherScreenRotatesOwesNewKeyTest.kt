package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M1
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.N
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * KI-22 — a member added while another screen rotates the key is left holding a stale key.
 *
 * At 565fb3c14 addMemberToGroup reads epoch 1 and its key, writes N as INVITED and sends the invite with the epoch-1
 * key, without the group's change lock. A rotation from the group screen in between reads N as a pending invitee (not a
 * key holder), moves the group to epoch 2 without N, and the add then marks N's invite as sent: N holds epoch 1, is not
 * owed epoch 2, and nothing ever sends it.
 *
 * Wanted: the add holds the group's change lock from its roster read to its final invite-status write, so the rotation
 * waits and then keys N (a delivered invitee holds the key). And, as a safety net, when the invite is written and the
 * epoch is above the invite's, N is marked owed the current key (pendingKeyEpoch), so the owed-key pass delivers it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddWhileOtherScreenRotatesOwesNewKeyTest {
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

    private fun startAdd(
        f: GroupChangeRaceFixture,
        vm: GroupViewModel,
    ) {
        vm.addMemberToGroup(f.groupId, N)
        // R2-2 (b): the add asks with its cost first; the user confirms.
        GroupViewModel::class.java.getMethod("confirmPaidAction").invoke(vm)
    }

    @Test
    fun `a member added while the other screen rotates ends up with the new key or owed it`() =
        runBlocking {
            val f = GroupChangeRaceFixture("zgrp_ki22_add", listOf(admin(), member(M1)), setOf(M1, N))
            val settings = f.groupViewModel().also { vms += it }
            val groupScreen = f.groupViewModel().also { vms += it }

            // Settings adds N: the epoch-1 invite is on its way (held).
            startAdd(f, settings)
            f.awaitFirstSubmit()
            assertEquals("fixture: the held memo is N's invite", 1, f.memosTo(N))
            val memosAtRotation = f.memos.size

            // The group screen rotates meanwhile.
            val rotation = f.startRotation(groupScreen, null)
            f.waitUntil(timeoutMs = 1_500) { rotation.isCompleted }

            f.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { rotation.join() }
            f.waitUntil { f.row(N).inviteStatus != InviteStatus.INVITE_PENDING }

            assertEquals("fixture: the invite went out", InviteStatus.SENT, f.row(N).inviteStatus)
            assertEquals(2, f.epoch)
            assertTrue(
                "N holds the epoch-1 key from its invite, was not sent the epoch-2 key and is not owed it " +
                    "(pendingKeyEpoch ${f.row(N).pendingKeyEpoch}, memos to N after the rotation began " +
                    "${f.memosTo(N, from = memosAtRotation)})",
                f.memosTo(N, from = memosAtRotation) >= 1 || f.row(N).pendingKeyEpoch == 2,
            )
        }

    @Test
    fun `safety net - when the epoch moved under the invite the new member is owed the current key`() =
        runBlocking {
            val f = GroupChangeRaceFixture("zgrp_ki22_add_epoch", listOf(admin(), member(M1)), setOf(M1, N))
            val settings = f.groupViewModel().also { vms += it }

            startAdd(f, settings)
            f.awaitFirstSubmit()
            // The group's key moves on while the epoch-1 invite is on its way (the store, not this add, changed it).
            f.keys[2] = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            f.epoch = 2

            f.release.complete(Unit)
            f.waitUntil { f.row(N).inviteStatus != InviteStatus.INVITE_PENDING }

            assertEquals("fixture: the invite went out", InviteStatus.SENT, f.row(N).inviteStatus)
            assertEquals("N's invite carried epoch 1; N must be owed epoch 2", 2, f.row(N).pendingKeyEpoch)
        }

    @Test
    fun `control - an add with no rotation leaves the new member up to date`() =
        runBlocking {
            val f = GroupChangeRaceFixture("zgrp_ki22_add_control", listOf(admin(), member(M1)), setOf(M1, N))
            val settings = f.groupViewModel().also { vms += it }
            f.release.complete(Unit)

            startAdd(f, settings)
            f.waitUntil { f.row(N).inviteStatus != InviteStatus.INVITE_PENDING }

            assertEquals(InviteStatus.SENT, f.row(N).inviteStatus)
            assertEquals(null, f.row(N).pendingKeyEpoch)
            assertEquals(1, f.memosTo(N))
        }
}
