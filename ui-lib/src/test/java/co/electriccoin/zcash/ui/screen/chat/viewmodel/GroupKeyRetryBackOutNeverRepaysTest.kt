package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
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
 * NEW (A2) — the GroupViewModel key retry can pay the same owed key twice.
 *
 * retryPendingGroupKeysInternal records a successful re-send (clears the member's pendingKeyEpoch) only
 * after the whole loop, and its in-flight guard is per ViewModel. The repository broadcasts in a detached
 * scope, so:
 * - backing out of the group screen while the re-send is being broadcast cancels the loop before the
 *   roster is saved; the key still reaches the network, and the next open pays it again;
 * - a second GroupViewModel (each nav destination has its own) opening the group while the first re-send
 *   is on the wire reads the member as still owed and pays again.
 * The same shape as HS-2 (KEXACK back-out), on the group retry that is new in 2.13.0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupKeyRetryBackOutNeverRepaysTest {
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
    fun `backing out while the owed key is broadcast never pays it again on the next open`() =
        runBlocking {
            val fixture = GroupRetryFixture(member = "usyntheticgroupkeybackout01", consent = true)
            val seam = HandshakeSeam(fixture.member, holdFirstSubmit = true)
            val first = fixture.groupViewModel(seam).also { vms += it }

            first.loadGroupDetail(GroupRetryFixture.GROUP_ID)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // The user leaves the group screen: its ViewModel is cleared mid-broadcast.
            first.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            seam.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }

            val reopen = fixture.groupViewModel(seam).also { vms += it }
            fixture.openGroup(reopen)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "the key re-send reached the network, but the back-out left the member recorded as still owed, " +
                    "so reopening the group paid for it again",
                1,
                seam.broadcasts.size,
            )
        }

    @Test
    fun `a second group screen opening while the owed key is broadcast pays nothing`() =
        runBlocking {
            val fixture = GroupRetryFixture(member = "usyntheticgroupkeybackout02", consent = true)
            val seam = HandshakeSeam(fixture.member, holdFirstSubmit = true)
            val detail = fixture.groupViewModel(seam).also { vms += it }
            val settings = fixture.groupViewModel(seam).also { vms += it }

            // The detail screen's retry is on the wire while the settings screen's ViewModel loads the group.
            fixture.awaitNewJobs(detail) {
                detail.loadGroupDetail(GroupRetryFixture.GROUP_ID)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
                fixture.openGroup(settings)
                seam.release.complete(Unit)
            }

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "two GroupViewModels each paid the same owed key",
                1,
                seam.broadcasts.size,
            )
            assertEquals(null, fixture.memberRow().pendingKeyEpoch)
        }
}
