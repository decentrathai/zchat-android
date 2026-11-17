package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M1
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M2
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-22 — two screens changing a group at once fork its key.
 *
 * `GroupViewModel` is `viewModelOf`: the group screen and the group settings screen each have their own instance, and
 * at 565fb3c14 the D5 rotation lock is a field of the instance. The settings screen's kick of M1 reads epoch 1 and,
 * while its key memos wait for a block, the group screen's rotation (or a second kick) reads epoch 1 too. Both mint an
 * epoch-2 key, two different ones; members keep the first and reject the second as a replay: a permanent fork. The
 * second change also read the roster before the kick was saved, so it sends its new key to the member being removed.
 *
 * Wanted: one change lock per group for the whole app. The second change waits, then reads epoch 2 and the saved
 * roster: epochs 2 then 3, two distinct keys stored in that order, M1 LEFT and never sent the epoch-3 key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoGroupScreensRotateSerializeTest {
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
    fun `a kick in settings and a rotation on the group screen advance the epoch twice and never fork`() =
        runBlocking {
            val f = GroupChangeRaceFixture("zgrp_ki22_serialize", listOf(admin(), member(M1), member(M2)), setOf(M1, M2))
            val settings = f.groupViewModel().also { vms += it }
            val groupScreen = f.groupViewModel().also { vms += it }

            // The settings screen removes M1: its epoch-2 key memo to M2 is on its way (held, as a block wait holds it).
            val kick = f.startRotation(settings, M1)
            f.awaitFirstSubmit()
            val memosBeforeRotation = f.memos.size

            // Meanwhile the group screen rotates the key.
            val rotation = f.startRotation(groupScreen, null)
            f.waitUntil(timeoutMs = 1_500) { rotation.isCompleted }

            f.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                kick.join()
                rotation.join()
            }

            assertEquals("fixture: only synthetic memos", 0, f.memosTo("usyntheticgroupchangeunknown"))
            assertEquals(
                "two screens both read epoch 1 and both minted an epoch-2 key (keys stored: ${f.keyWrites.map { it.first }})",
                listOf(2, 3),
                f.keyWrites.map { it.first },
            )
            assertNotEquals("the two changes must store two different keys", f.keyWrites[0].second, f.keyWrites[1].second)
            assertEquals("the group ends at epoch 3", 3, f.epoch)
            assertEquals("M1 stays removed", MemberStatus.LEFT, f.row(M1).status)
            // The kick itself tells M1 it was removed (one memo, no key); the rotation that follows must not key M1.
            assertTrue(
                "the rotation read the roster before the kick was saved and sent its new key to the removed M1",
                f.memosTo(M1, from = memosBeforeRotation) <= 1,
            )
            assertEquals("M2 holds the latest key", null, f.row(M2).pendingKeyEpoch)
        }
}
