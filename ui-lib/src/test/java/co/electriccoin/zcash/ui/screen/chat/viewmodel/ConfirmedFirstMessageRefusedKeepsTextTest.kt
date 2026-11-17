package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PoolType
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R4-4 (2), round-3 report section 4 item 7: what the user gave the chat survives a first message that is
 * refused after the cost prompt, and a cancelled photo does not vanish while the screen says "sent".
 *
 * The first on-chain message to a new contact asks before paying (R2-2 b). The send reports ACCEPTED while the
 * prompt waits, so the chat screen clears its input and the chat's draft, and the photo path removes its
 * optimistic bubble and reports the upload sent. R3-2 (2) gives the typed text back when the user CANCELS.
 * Two holes are left:
 *
 *  - the user CONFIRMS and the send is then refused before anything is queued (all the spendable value has
 *    moved into sealed Orchard, the peer's key changed): the send returns false, nothing is stored and the
 *    typed text is gone from the input and the draft;
 *  - a PHOTO sent as the first message: the bubble is removed the moment the send is accepted behind the
 *    prompt, so cancelling (or a refusal after Confirm) leaves the chat with no bubble at all, no file and a
 *    progress indicator that already said "sent".
 *
 * Must hold: a first message refused after Confirm puts the typed text back exactly as Cancel does, and a file
 * first message that is cancelled or refused leaves its bubble FAILED (retryable), never nothing. Nothing here
 * is paid. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmedFirstMessageRefusedKeepsTextTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticconfirmedrefused01"
        const val TYPED = "synthetic words the user typed"
        const val PHOTO_FAILED = "📷 Photo (failed)"
        const val OPTIMISTIC_ID = "img-pending-synthetic-1"
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
    fun `a first message refused after Confirm keeps the typed text as the chat's draft`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.typeAndSend(vm)
            // Round 10b R10B-1: our KEX, the message and our on-chain ZBOOT (the contact has no relay key).
            chat.fixture.assertAskedFirst(vm, messages = 3)

            // While the prompt was on screen the last spendable value moved into sealed Orchard: the send
            // the user just paid for is refused before anything is queued.
            chat.strandTheWallet(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "a refused send paid something")
            assertEquals(TYPED, chat.drafts[PEER], "the typed text was lost when the confirmed send was refused")
        }

    @Test
    fun `a first message refused after Confirm hands the typed text back to the open chat's input`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.typeAndSend(vm)
            chat.strandTheWallet(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            val restored = restoredTypedText(vm)
            assertNotNull(restored, "nothing was handed back to the input after the refusal")
            assertEquals(PEER, restored.javaClass.getMethod("getPeerAddress").invoke(restored))
            assertEquals(TYPED, restored.javaClass.getMethod("getText").invoke(restored))
        }

    /** Control, green before and after: a confirmed send that IS queued keeps nothing back. */
    @Test
    fun `control - a confirmed first message that goes out gives no text back`() =
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

    @Test
    fun `a cancelled photo first message leaves its bubble failed, not nothing`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.showPhotoBubble(vm)
            val accepted = chat.sendPhoto(vm)
            assertTrue(accepted, "fixture sanity: the photo send waits behind the cost prompt")
            // Round 10b R10B-1: our KEX, the message and our on-chain ZBOOT (the contact has no relay key).
            chat.fixture.assertAskedFirst(vm, messages = 3)

            vm.dismissPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "cancelling paid something")
            val bubble = chat.bubble(vm)
            assertNotNull(bubble, "the cancelled photo left no bubble at all — the chat had already said 'sent'")
            assertEquals(MessageStatus.FAILED, bubble.status, "the cancelled photo is not shown as failed")
            assertEquals(PHOTO_FAILED, bubble.text)
        }

    @Test
    fun `a photo first message refused after Confirm leaves its bubble failed, not nothing`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.showPhotoBubble(vm)
            assertTrue(chat.sendPhoto(vm), "fixture sanity: the photo send waits behind the cost prompt")

            chat.strandTheWallet(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "a refused send paid something")
            val bubble = chat.bubble(vm)
            assertNotNull(bubble, "the refused photo left no bubble at all — the chat had already said 'sent'")
            assertEquals(MessageStatus.FAILED, bubble.status, "the refused photo is not shown as failed")
        }

    /** Control: a photo the send refuses at once (the peer's key changed) is already shown as failed today. */
    @Test
    fun `control - a photo refused before the prompt leaves its bubble failed`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            chat.fixture.keyChanged = true

            chat.showPhotoBubble(vm)
            assertEquals(false, chat.sendPhoto(vm), "fixture sanity: a key-changed peer is refused at once")
            awaitIdle(vm)

            val bubble = chat.bubble(vm)
            assertNotNull(bubble, "a refused photo left no bubble at all")
            assertEquals(MessageStatus.FAILED, bubble.status)
        }

    /** Control: a photo the user confirms is handed off — its optimistic bubble makes way for the real row. */
    @Test
    fun `control - a confirmed photo hands its bubble off to the send`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            chat.showPhotoBubble(vm)
            assertTrue(chat.sendPhoto(vm), "fixture sanity: the photo send waits behind the cost prompt")
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(2, chat.proposals(), "confirming pays the KEX and the photo")
            assertNull(chat.bubble(vm), "the optimistic bubble outlived the send it was handed to")
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

    /** A new contact in a Shielded chat; the draft store is a map and the wallet can be stranded mid-prompt. */
    private class Chat {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT, haveOurKey = false)
        val drafts = ConcurrentHashMap<String, String>()

        /** How many proposals were built (each one a payment). */
        fun proposals(): Int = fixture.seam.proposalRequests.get()

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            PaymentHarness.setField(vm, "zchatPreferences", withDrafts(fixture.proxy))
            // A constructor-free allocation skips field initialisers; give this one its initial value.
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "_restoredTypedText" }?.let {
                it.isAccessible = true
                it.set(vm, MutableStateFlow<Any?>(null))
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

        /** Every spendable satoshi is in sealed Orchard: the next send is refused before it queues anything. */
        fun strandTheWallet(vm: ChatViewModel) {
            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_chatListState")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<ChatListState>
            state.value =
                ChatListState.Success(
                    conversations = emptyList(),
                    currentUserAddress = HandshakeHarness.SELF,
                    privacyStatus =
                        PrivacyStatus(
                            poolType = PoolType.ORCHARD,
                            orchardBalance = Zatoshi(100_000),
                            ironwoodBalance = Zatoshi(0),
                        ),
                )
        }

        /** The optimistic bubble the photo path shows while it compresses, encrypts and uploads. */
        fun showPhotoBubble(vm: ChatViewModel) {
            @Suppress("UNCHECKED_CAST")
            val pending =
                ChatViewModel::class.java
                    .getDeclaredField("pendingMessages")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<List<ChatMessage>>
            pending.value =
                listOf(
                    ChatMessage(
                        id = OPTIMISTIC_ID,
                        txId = null,
                        text = "📷 Photo",
                        timestamp = Instant.now(),
                        isOutgoing = true,
                        peerAddress = PEER,
                        isPending = true,
                        status = MessageStatus.SENDING,
                    ),
                )
        }

        /**
         * The upload is done and the ZFILE memo is handed to the send, exactly as handlePickedImage does it.
         * Reached by name so this file compiles before the ViewModel has one place for it.
         */
        fun sendPhoto(vm: ChatViewModel): Boolean {
            val zfile =
                ZFILEMessage(
                    hash = "synthetic0photo0hash000000000001",
                    type = ZFILEType.JPEG,
                    size = 4096L,
                    url = "https://synthetic.invalid/blob",
                    wrappedKey = "c3ludGhldGlj",
                    blurhash = "",
                ).serialize()
            val method =
                try {
                    ChatViewModel::class.java.getDeclaredMethod(
                        "sendFileWithOptimisticBubble",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                    )
                } catch (e: NoSuchMethodException) {
                    throw AssertionError(
                        "a file send has no single place that keeps its bubble in step with the cost prompt: " +
                            "the photo path drops the bubble the moment the send is accepted behind the prompt, " +
                            "so cancelling or a refusal leaves the chat with nothing and a progress bar that said 'sent'",
                        e,
                    )
                }
            method.isAccessible = true
            return try {
                method.invoke(vm, PEER, zfile, OPTIMISTIC_ID, PHOTO_FAILED) as Boolean
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun bubble(vm: ChatViewModel): ChatMessage? {
            @Suppress("UNCHECKED_CAST")
            val pending =
                ChatViewModel::class.java
                    .getDeclaredField("pendingMessages")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<List<ChatMessage>>
            return pending.value.firstOrNull { it.id == OPTIMISTIC_ID }
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
