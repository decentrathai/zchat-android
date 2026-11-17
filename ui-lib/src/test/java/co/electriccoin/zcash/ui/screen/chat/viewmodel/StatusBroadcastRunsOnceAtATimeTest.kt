package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACTS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.OTHER_STATUS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.STATUS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.question
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.runToEnd
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-283 (fix-all 2.13.0, train T5b; r8-findings SPECS §2, S2) — one status broadcast at a time.
 *
 * At 998956906 nothing stops a second broadcast while one runs: the first one's memo locks the wallet's only Ironwood
 * note, the second contact waits for a block, and a status set again during that wait (the user saw no result yet) asks
 * again and starts a second loop, which pays A again (and B once the block lands).
 *
 * Wanted (SPECS §2.3 (3)): a status set with "tell my contacts" while a broadcast runs saves the text, asks nothing,
 * sends and pays nothing, and says so (ChatSendCopy.STATUS_BROADCAST_BUSY); the running broadcast ends with each contact
 * paid once. Control: once it ended, a status is asked about as before.
 *
 * All addresses and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBroadcastRunsOnceAtATimeTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        StatusBroadcastChat.resetStatusBroadcastFlag()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        StatusBroadcastChat.resetStatusBroadcastFlag()
    }

    private fun busyText(): String =
        ChatSendCopy::class.java.declaredFields
            .firstOrNull { it.name == "STATUS_BROADCAST_BUSY" }
            ?.apply { isAccessible = true }
            ?.get(null) as? String
            ?: throw AssertionError("ChatSendCopy has no STATUS_BROADCAST_BUSY notice")

    @Test
    fun `a status set while a broadcast waits for a block starts nothing and says a broadcast is running`() =
        runBlocking {
            // The second proposal meets the "still confirming" wait (the first memo locked the only Ironwood note).
            val chat = StatusBroadcastChat(failProposal = { _, n -> if (n == 2) stillConfirming() else null })
            val accounts = OwnChangeAccounts.create().apply { ownChangeConfirmingNoOrchard() }
            val vm = chat.viewModel(accounts)
            vm.setUserStatus(STATUS, broadcast = true)
            assertEquals("fixture: the first broadcast asks for 3", "STATUS_BROADCAST" to 3, question(vm))
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture: A is paid, B waits for a block", 1, chat.seam.submits.get())

            vm.setUserStatus(STATUS, broadcast = true)
            val during = state(vm)
            // Pay on whatever is on screen (a second question, if one was raised).
            vm.confirmPaidAction()

            assertTrue(
                "a status set while a broadcast runs must say so and ask nothing, was $during",
                during is SendMessageState.Error && during.message == busyText(),
            )
            runToEnd(vm, chat.heights)
            assertEquals("each contact is paid once (submitted to ${chat.seam.submittedTo})", 3, chat.seam.submits.get())
            assertEquals(CONTACTS, chat.seam.submittedTo.toList())
            assertEquals("the text is saved all the same", STATUS, chat.savedStatus)
        }

    @Test
    fun `once a broadcast ended a status is asked about as before`() =
        runBlocking {
            val chat = StatusBroadcastChat()
            val vm = chat.viewModel(OwnChangeAccounts.create())
            vm.setUserStatus(STATUS, broadcast = true)
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)
            assertEquals("fixture: each contact paid once", 3, chat.seam.submits.get())

            vm.setUserStatus(OTHER_STATUS, broadcast = true)

            assertEquals("STATUS_BROADCAST" to 3, question(vm))
        }
}
