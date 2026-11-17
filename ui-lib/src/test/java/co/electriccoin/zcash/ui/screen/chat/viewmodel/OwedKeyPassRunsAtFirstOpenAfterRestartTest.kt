package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-1045 (high; T7aF phone run 19 Oct, S-C C3 r1 and r2 on the Fold) — after an interrupted key rotation the unkeyed
 * member gets no key until Group Settings is opened.
 *
 * On the phone: the creator's app was stopped mid-rotation; at the next start the group open completed the rotation
 * ("an interrupted rotation ... was completed (epoch 1)") and marked the members owed, but no owed-key pass followed for
 * 6 minutes on the group screen, nor after leaving and reopening it from the chat list. It ran ("D3 retry: re-sending
 * epoch-1 key to 2 owed member(s)") only when the user came back to the group screen from Group Settings.
 *
 * What the code shows: every group screen gets a new GroupViewModel, whose own address is read asynchronously
 * (loadCurrentUserAddress, which waits for the wallet after a start). The group open (loadGroupDetail) runs the owed-key
 * pass right after the screen's first read, and the pass returns at once, silently, while that address is not known yet;
 * nothing runs it again on that screen. Coming back from Group Settings reloads the SAME screen, whose address is known
 * by then. The test opens the group before the address is known and lets it arrive just after.
 *
 * Wanted: the group open's owed-key pass runs once the address is known (here, with the standing consent, the owed key
 * is sent once and the member is no longer owed). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwedKeyPassRunsAtFirstOpenAfterRestartTest {
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
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Suppress("UNCHECKED_CAST")
    private fun address(vm: GroupViewModel): MutableStateFlow<String?> =
        GroupViewModel::class.java
            .getDeclaredField("_currentUserAddress")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<String?>

    @Test
    fun `a group opened before the screen knows its own address still sends the owed key once the address arrives`() =
        runBlocking {
            val fixture = GroupRetryFixture(member = "usyntheticzc1045owedmember1", consent = true)
            val seam = HandshakeSeam(fixture.member)
            val vm = fixture.groupViewModel(seam).also { vms += it }
            // A new screen right after a start: its address is not read yet.
            address(vm).value = null

            fixture.awaitNewJobs(vm) {
                vm.loadGroupDetail(GroupRetryFixture.GROUP_ID)
                address(vm).value = GroupRetryFixture.SELF
            }

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "the group open's owed-key pass did nothing because the screen did not know its own address yet; the " +
                    "unkeyed member gets the key only after another reload of the same screen",
                1,
                seam.proposalRequests.get(),
            )
            assertNull("the member is still owed the key after the group open", fixture.memberRow().pendingKeyEpoch)
        }
}
