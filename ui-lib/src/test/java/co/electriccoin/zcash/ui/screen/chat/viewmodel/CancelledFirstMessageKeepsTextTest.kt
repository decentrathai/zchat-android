package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R3-2 (2), round-2 report section 5.4: cancelling the cost prompt for a first message keeps the typed text.
 *
 * The first on-chain message to a new contact asks first (R2-2 b): our KEX and the message are quoted, and
 * nothing is paid until the user confirms. The send reports "accepted" while the prompt waits, so the chat
 * screen clears its input and the chat's draft (as it does for a sent message). At 1836c4de3 cancelling the
 * prompt only drops the waiting send: the text the user typed is gone, from the input and from the draft.
 *
 * Must hold: after Cancel, the typed text is the chat's draft again and is handed back to the open chat's
 * input (the ViewModel's restoredTypedText, which the screen puts back into the box); nothing is paid.
 * Confirming sends it and hands nothing back (no second copy to send by mistake).
 *
 * The screen's side (clearDraft on an accepted send) is done here exactly as AndroidChat does it. The new
 * API is reached by name so this file compiles before it exists. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelledFirstMessageKeepsTextTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticcancelledfirstmsg1"
        const val TYPED = "synthetic words the user typed"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `cancelling the first-message cost prompt keeps the typed text as the chat's draft`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.typeAndSend(vm)
            // Round 10b R10B-1: our KEX, the message and our on-chain ZBOOT (the contact has no relay key).
            chat.fixture.assertAskedFirst(vm, messages = 3)

            vm.dismissPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "cancelling paid something")
            assertEquals(TYPED, chat.drafts[PEER], "the typed text was lost when the cost prompt was cancelled")
        }

    @Test
    fun `cancelling the first-message cost prompt hands the typed text back to the open chat's input`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.typeAndSend(vm)
            vm.dismissPaidAction()
            awaitIdle(vm)

            val restored = restoredTypedText(vm)
            assertNotNull(restored, "nothing was handed back to the input after Cancel")
            assertEquals(PEER, restored.javaClass.getMethod("getPeerAddress").invoke(restored))
            assertEquals(TYPED, restored.javaClass.getMethod("getText").invoke(restored))

            // The screen took it: it is not handed back a second time.
            ChatViewModel::class.java.getMethod("consumeRestoredTypedText").invoke(vm)
            assertNull(restoredTypedText(vm), "the restored text is offered again after the input took it")
        }

    /** Confirming pays the KEX and the message and gives nothing back (the text is sent, not restored). */
    @Test
    fun `confirming the first-message cost prompt sends it and gives no text back`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.typeAndSend(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(2, chat.proposals(), "confirming pays the KEX and the message")
            assertNull(chat.drafts[PEER], "a sent message was put back as the draft")
            assertNull(restoredTypedText(vm), "a sent message was handed back to the input")
        }

    /** Control, green before and after: a message to a contact we have keys for is sent at once, no prompt. */
    @Test
    fun `control - a message to a keyed contact is sent at once and its draft stays cleared`() =
        runBlocking {
            val chat = Chat(haveOurKey = true)
            val vm = chat.viewModel()

            chat.typeAndSend(vm)

            assertEquals(1, chat.proposals())
            assertNull(chat.drafts[PEER], "a sent message was put back as the draft")
        }

    // --------------------------------------------------------------- harness

    private fun restoredTypedText(vm: ChatViewModel): Any? {
        val flow =
            try {
                ChatViewModel::class.java.getMethod("getRestoredTypedText").invoke(vm) as StateFlow<*>
            } catch (e: NoSuchMethodException) {
                throw AssertionError("the ViewModel does not hand typed text back to the chat input", e)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        return flow.value
    }

    /** A new contact (unless [haveOurKey]) in a Shielded chat; the draft store is a map. */
    private class Chat(
        haveOurKey: Boolean = false,
    ) {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT, haveOurKey = haveOurKey)
        val drafts = ConcurrentHashMap<String, String>()

        /** How many proposals were built (each one a payment). */
        fun proposals(): Int = fixture.seam.proposalRequests.get()

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            PaymentHarness.setField(vm, "zchatPreferences", withDrafts(fixture.proxy))
            // A constructor-free allocation skips field initialisers; give this one its initial value.
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "_restoredTypedText" }?.let {
                it.isAccessible = true
                it.set(vm, kotlinx.coroutines.flow.MutableStateFlow<Any?>(null))
            }
            return vm
        }

        /** What the chat screen does on Send: the typed text is the draft; an accepted send clears it. */
        suspend fun typeAndSend(vm: ChatViewModel) {
            vm.saveDraft(PEER, TYPED)
            val accepted = vm.sendMessage(PEER, TYPED)
            if (accepted) vm.clearDraft(PEER)
            awaitIdle(vm)
        }

        private fun withDrafts(base: ZchatPreferences): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "setDraft" -> {
                        drafts[args!![0] as String] = args[1] as String
                        null
                    }
                    "clearDraft" -> {
                        drafts.remove(args!![0] as String)
                        null
                    }
                    "getDraft" -> drafts[args!![0] as String]
                    "getAllDrafts" -> HashMap(drafts)
                    "toString" -> "Drafts($base)"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        try {
                            method.invoke(base, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
    }
}
