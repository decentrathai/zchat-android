package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.STATUS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.question
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.runToEnd
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1054-R2 (review of T7bH round 2, 20 Oct 2026, finding 1, P2) — ZC-1054-R1 reads each status recipient's
 * key-change flag once, before its copy; a copy that then waits for a block (funds still confirming) goes through the
 * user-memo sender, whose per-attempt gate always proceeds. If that contact's new key arrives while the copy waits,
 * the next block pays it (a Shielded status memo) although the banner says nothing is paid. The scheduled and
 * time-lock senders use the same gate.
 *
 * Must hold: the user-memo sender re-reads the flag before every attempt; a copy whose contact's new key waits pays
 * nothing. Three Shielded contacts A, B and C; B's copy meets "still confirming" and waits; B's flag rises while it
 * waits: A and C are paid, B is not. [StatusBroadcastChat]; every address and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AUserMemoWaitingForABlockIsNotPaidOnceANewKeyWaitsTest {
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

    @Test
    fun `a status copy waiting for a block is not paid once its contact's new key waits`() =
        runBlocking {
            // The second proposal (B's copy) meets the "still confirming" wait: the first memo locked the only note.
            val chat = StatusBroadcastChat(failProposal = { _, n -> if (n == 2) stillConfirming() else null })
            val accounts = OwnChangeAccounts.create().apply { ownChangeConfirmingNoOrchard() }
            val vm = chat.viewModel(accounts)
            val flagged = ConcurrentHashMap.newKeySet<String>()
            val store =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    if (method.name == "isE2EKeyChanged") {
                        args!![0] in flagged
                    } else {
                        try {
                            method.invoke(chat.store, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                } as ZchatPreferences
            AutoSpendVm.setField(vm, "zchatPreferences", store)

            vm.setUserStatus(STATUS, broadcast = true)
            assertEquals("fixture: the question counts the three Shielded contacts", "STATUS_BROADCAST" to 3, question(vm))
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture: A is paid, B waits for a block", listOf(CONTACT_A), chat.seam.submittedTo.toList())

            flagged += CONTACT_B
            runToEnd(vm, chat.heights)

            assertEquals(
                "the waiting copy went to a contact whose new key waits",
                listOf(CONTACT_A, CONTACT_C),
                chat.seam.submittedTo.toList(),
            )
        }
}
