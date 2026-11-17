package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 D8 (REVIEW_7C F33) — a 1:1 message or reply refused before the submit, because the encrypted session is
 * not ready or the history settlement holds it, never tells the user why.
 *
 * doSendMessage and sendReply throw the refusal as an IllegalStateException whose message is the sentence written
 * for the user: "Secure session isn't ready yet — message not sent (it won't be sent unencrypted). It'll send once
 * the encrypted connection re-establishes; try again in a moment." (English only, and it promises an automatic send
 * that nothing makes), or RestoreKexPolicy.STILL_READING_HISTORY. The catch runs every failure through
 * toZchatUserMessage, which never shows an exception's own message: the "connection" in the first sentence matches
 * the network rule, so the user reads "Network hiccup — check your connection and try again.", and the second
 * matches nothing, so the user reads "Failed to send message". The failed row keeps that text above its Retry.
 *
 * Wanted: the toast and the row show the refusal itself: ChatSendCopy.SECURE_SESSION_NOT_READY_MESSAGE /
 * SECURE_SESSION_NOT_READY_REPLY (no promise of an automatic send; English and Spanish), or STILL_READING_HISTORY.
 * Nothing is paid either way.
 *
 * All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendRefusalReachesTheUserTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /**
     * A Shielded chat with E2E on whose session cannot be rebuilt right now (mid Reset encryption: the contact's
     * new key has not arrived), optionally held by the history settlement as well.
     */
    private suspend fun chat(heldBySettlement: Boolean): Pair<ChatViewModel, PaidChatFixture> {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT)
        val vm = fixture.viewModel()
        AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
        AutoSpendVm.setField(vm, "zchatPreferences", sessionNotReady(fixture.proxy))
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        if (heldBySettlement) {
            ChatViewModel::class.java
                .getDeclaredField("peersAwaitingLegacySettlement")
                .apply { isAccessible = true }
                .set(vm, setOf(PEER))
        }
        return vm to fixture
    }

    private suspend fun refusedText(
        vm: ChatViewModel,
        fixture: PaidChatFixture,
        send: () -> Unit,
    ): String {
        send()
        awaitTrue { HandshakeHarness.sendMessageState(vm) is SendMessageState.Error }
        assertEquals("nothing may be paid without a secure session", 0, fixture.seam.submits.get())
        val text = (HandshakeHarness.sendMessageState(vm) as SendMessageState.Error).message
        val row = PaymentHarness.pendingMessages(vm).single()
        assertEquals("the row is kept as failed, with Retry", MessageStatus.FAILED, row.status)
        assertEquals("the row shows the same reason above its Retry", text, row.failureReason)
        return text
    }

    @Test
    fun `a message refused because the secure session is not ready says so`() =
        runBlocking {
            val (vm, fixture) = chat(heldBySettlement = false)

            val text = refusedText(vm, fixture) { vm.sendMessage(PEER, "hello") }

            assertFalse("the refusal must not promise an automatic send: $text", text.contains("It'll send"))
            assertEquals(SECURE_SESSION_NOT_READY_MESSAGE, text)
        }

    @Test
    fun `a reply refused because the secure session is not ready says so`() =
        runBlocking {
            val (vm, fixture) = chat(heldBySettlement = false)

            val text = refusedText(vm, fixture) { vm.sendReply(PEER, "hello back", replyToId = "synthetic-target-id") }

            assertFalse("the refusal must not promise an automatic send: $text", text.contains("It'll send"))
            assertEquals(SECURE_SESSION_NOT_READY_REPLY, text)
        }

    @Test
    fun `a message the history settlement holds says the history is being read`() =
        runBlocking {
            val (vm, fixture) = chat(heldBySettlement = true)

            val text = refusedText(vm, fixture) { vm.sendMessage(PEER, "hello") }

            assertEquals(RestoreKexPolicy.STILL_READING_HISTORY, text)
        }

    @Test
    fun `a reply the history settlement holds says the history is being read`() =
        runBlocking {
            val (vm, fixture) = chat(heldBySettlement = true)

            val text = refusedText(vm, fixture) { vm.sendReply(PEER, "hello back", replyToId = "synthetic-target-id") }

            assertEquals(RestoreKexPolicy.STILL_READING_HISTORY, text)
        }

    /** E2E is on for the contact, but its public key is not readable, so no ratchet can be built for the send. */
    private fun sessionNotReady(base: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getE2EPeerPublicKey" -> null
                "isE2EEnabled" -> true
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private companion object {
        const val PEER = "usyntheticd8sessionpeer000001"

        /** The new ChatSendCopy constants (the parity test pins them and their resources to these texts). */
        const val SECURE_SESSION_NOT_READY_MESSAGE =
            "Secure session isn't ready yet — message not sent (it won't be sent unencrypted). " +
                "Tap Retry once the secure connection is back."
        const val SECURE_SESSION_NOT_READY_REPLY =
            "Secure session isn't ready yet — reply not sent (it won't be sent unencrypted). " +
                "Tap Retry once the secure connection is back."
    }
}
