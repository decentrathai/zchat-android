package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.advancedsettings.AutomaticSetupPayments
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupQueue
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-1096 (register, high, money; C2 merge review finding 2) — the ZC-880 stop of an automatic setup in an Open chat
 * (ChatViewModel, sendAutomaticHandshakeMemoOutcome) calls AutomaticSetupQueue.done, which also deletes the stored
 * "Not now" mark (ZC1013-X2). The user declines a key reply, turns "Automatic setup payments" on (the declined setup
 * keeps asking, ZC1013-X1), the chat is Open so an attempt stops there and the mark goes. The chat later becomes Tunnel
 * (an Open → Tunnel switch asks no price of its own: modeChangeGoesOnChain is true only when leaving Shielded), and the
 * next retry pays the declined key reply under the standing consent, with no question.
 *
 * Must hold: an Open-chat stop removes the setup from the queue only, the stored mark stays, and the declined setup
 * still asks (the same question it was declined in, in a Tunnel chat) once the chat is Tunnel again, paying nothing. And while its chat is Open a kept mark does not
 * count as a setup asking under the consent (no Open chat ever asks or pays).
 *
 * Drives the real ChatViewModel through HandshakeHarness; every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeclinedSetupStaysDeclinedAfterAnOpenChatStopTest {
    private val sendScopes = SendHarnessScopes()
    private val peer = "usyntheticzc1096openstop0001"

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetQuestionState()
    }

    @After
    fun tearDown() {
        resetQuestionState()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    /** What the question keeps in memory, in this process, is emptied; the store is not touched. */
    private fun resetQuestionState() {
        for (name in listOf("queuedAutomaticSetups", "dismissedAutomaticSetups")) {
            val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableSet<*>)?.clear()
        }
        val instance = AutomaticSetupQueue
        for (field in AutomaticSetupQueue::class.java.declaredFields) {
            field.isAccessible = true
            when (val value = runCatching { field.get(instance) }.getOrNull()) {
                is MutableSet<*> -> value.clear()
                is AtomicBoolean -> value.set(false)
            }
        }
    }

    /** One automatic attempt of [kind] towards [destination] through the one door of every automatic setup. */
    private suspend fun automaticAttempt(
        vm: ChatViewModel,
        destination: String,
        kind: AutomaticSetupKind,
    ): Any? {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "sendAutomaticHandshakeMemoOutcome" && it.parameterCount == 11 }
                .apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(
                    vm,
                    destination,
                    HandshakeHarness.SELF,
                    "ZMSG|v4|synthetic-zc1096",
                    kind,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    continuation,
                )
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /**
     * In a Tunnel chat the key reply to [peer] asks; the user answers "Not now" and then turns the standing consent on.
     * Tunnel, so the question asked again in Tunnel later counts the same transactions (the key reply and the ZBOOT).
     */
    private suspend fun declinedThenConsentOn(
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
    ): Pair<ChatViewModel, SendMessageState.NeedsHandshakeSpendConsent> {
        prefs.mode = ConversationMode.TUNNEL
        val vm = HandshakeHarness.handshakeViewModel(prefs, seam)
        val keys = E2EEncryption.generateKeyPair()
        prefs.e2ePeerKey[peer] = keys.publicKey
        HandshakeHarness.handleKexMemo(vm, HandshakeHarness.kexMemoFrom(peer, keys), "synthetic-zc1096")
        HandshakeHarness.awaitIdle(vm)
        val ask = HandshakeHarness.sendMessageState(vm)
        assertTrue("fixture: the key reply asks first: $ask", ask is SendMessageState.NeedsHandshakeSpendConsent)
        vm.dismissOrchardShieldingWarning()
        assertEquals("fixture: Not now stored one mark", 1, prefs.declinedSetups.size)
        assertEquals(true, AutomaticSetupPayments(prefs.proxy, Dispatchers.Unconfined).turnOn())
        assertEquals("fixture: nothing paid before the Open-chat stop", 0, seam.proposalRequests.get())
        return vm to (ask as SendMessageState.NeedsHandshakeSpendConsent)
    }

    @Test
    fun `an Open-chat stop keeps the Not now mark and the setup asks again once the chat is Tunnel`() =
        runBlocking {
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            val (vm, ask) = declinedThenConsentOn(prefs, seam)
            val marks = prefs.declinedSetups.toSet()

            prefs.mode = ConversationMode.OPEN
            automaticAttempt(vm, peer, AutomaticSetupKind.KEXACK)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("an Open chat paid an automatic setup", 0, seam.proposalRequests.get())
            assertEquals("the Open-chat stop deleted the stored Not now mark", marks, prefs.declinedSetups.toSet())

            prefs.mode = ConversationMode.TUNNEL
            vm.askAgainForHandshakeConsent()
            vm.retryKexAckIfResponder(peer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals(
                "a key reply the user declined was paid under the standing consent, with no question",
                0,
                seam.proposalRequests.get(),
            )
            val again = HandshakeHarness.sendMessageState(vm)
            assertTrue("the declined key reply did not ask again: $again", again is SendMessageState.NeedsHandshakeSpendConsent)
            again as SendMessageState.NeedsHandshakeSpendConsent
            assertEquals(ask.pendingPeers, again.pendingPeers)
            assertEquals(ask.estimatedCost, again.estimatedCost)
            assertEquals("unexpected store calls: ${prefs.unexpectedCalls}", emptyList<String>(), prefs.unexpectedCalls.toList())
        }

    @Test
    fun `a kept mark whose chat is Open does not count as asking under the consent`() =
        runBlocking {
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            declinedThenConsentOn(prefs, seam)

            assertTrue("fixture: the declined setup asks under the consent", AutomaticSetupQueue.anyAskingUnderConsent(prefs.proxy))
            prefs.mode = ConversationMode.OPEN
            assertFalse(
                "a declined setup of an Open chat counts as asking under the standing consent",
                AutomaticSetupQueue.anyAskingUnderConsent(prefs.proxy),
            )
            prefs.mode = ConversationMode.TUNNEL
            assertTrue("the declined setup no longer asks once the chat is Tunnel", AutomaticSetupQueue.anyAskingUnderConsent(prefs.proxy))
            assertEquals("the kept mark was lost", 1, prefs.declinedSetups.size)
        }
}
