package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
 * ZC-1054-R1 (T7BH-N1; review of T7bH, 19 Oct 2026, finding 3, P2) — the status broadcast's recipients are frozen when
 * its question is asked. A contact whose "Security key changed" warning rises before Pay is still sent the status, and
 * paid for in a Shielded chat. Must hold: each recipient's flag is read when its copy is sent; a flagged one gets
 * nothing and pays nothing; the others are sent as before.
 *
 * [StatusBroadcastChat]: three Shielded contacts A, B and C; the flag is a per-contact wrapper over its store. Every
 * address and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AStatusIsNotSentToAContactWhoseNewKeyArrivedAfterTheQuestionTest {
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
    fun `a contact flagged after the status question is sent nothing and paid nothing at Pay`() =
        runBlocking {
            val chat = StatusBroadcastChat()
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

            flagged += CONTACT_B
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)

            assertEquals(
                "the status went to a contact whose new key waits (or a contact with no warning was skipped)",
                listOf(CONTACT_A, CONTACT_C),
                chat.seam.submittedTo.toList(),
            )
        }
}
