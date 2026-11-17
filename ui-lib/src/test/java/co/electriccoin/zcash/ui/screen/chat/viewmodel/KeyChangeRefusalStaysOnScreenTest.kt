package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-250 (fix-all 2.13.0, train T5b; t5b SPECS §6; diagnosis train-T4b2-2026/zc250/DIAGNOSIS.md) — a send refused by the
 * key-change gate leaves nothing on screen.
 *
 * At 3ff26bc9a, when the contact's "security key changed" flag is set, the TOFU/MITM gate (blockedByKeyChange) refuses
 * every user send, as it must: nothing is sent, stored or paid. But the only sign of it is SendMessageState.Error, which
 * the chat screen shows as a ~4 s toast and then resets (resetSendState); the HONOR phones read it as "Send does
 * nothing". There is no lasting state the screen can show (no ChatViewModel.keyChangeRefusal, no
 * acknowledgeKeyChangeRefusal), and sendPayment returns nothing, so its dialog cannot tell a refusal from a send.
 *
 * Wanted, in an Open, a Tunnel and a Shielded chat with the flag set:
 * - sendMessage, sendPaymentRequest, sendPayment and fulfillPaymentRequest are refused exactly as today (false where
 *   they answer, 0 proposals, 0 fee probes, 0 relay publishes, no row), the short Error(KEY_CHANGED_VERIFY) is set, and
 *   the new keyChangeRefusal holds the contact; it is still set after the toast's resetSendState();
 * - sendPayment answers false (refused before anything was built);
 * - acknowledgeKeyChangeRefusal() clears the refusal only: the flag stays and the next send is refused again;
 * - a Shielded chat's × (dismissE2EKeyChanged) clears the flag and the refusal; an Open chat's × first asks
 *   KEY_CHANGE_RECONNECT for 2 on-chain messages and the refusal stays until Pay, which clears both.
 *
 * The new names are reached by reflection so this file compiles before they exist. Synthetic addresses only; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChangeRefusalStaysOnScreenTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc250keychangepeer1"
        const val PEER_NOSTR = "synthetic-zc250-peer-nostr-pubkey"
        const val AMOUNT = 10_000L
        const val REASON = "synthetic zc250 reason"
        const val REQUEST_ID = "synthetic-zc250-request-id"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        NostrChatBridge.unregisterPublisher()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `an Open chat refuses every send and the refusal stays on screen`() = refusesEverySend(ConversationMode.OPEN)

    @Test
    fun `a Tunnel chat refuses every send and the refusal stays on screen`() = refusesEverySend(ConversationMode.TUNNEL)

    @Test
    fun `a Shielded chat refuses every send and the refusal stays on screen`() = refusesEverySend(ConversationMode.VAULT)

    private fun refusesEverySend(mode: ConversationMode) =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = mode, peerNostrPubkey = PEER_NOSTR).apply { keyChanged = true }
            val seam = PaymentSeam(PEER)
            val publishes = AtomicInteger(0)
            NostrChatBridge.registerPublisher { _, _ ->
                publishes.incrementAndGet()
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-zc250-rumor")
            }
            val vm = paymentViewModel(prefs, seam)

            assertFalse("the message was not refused", vm.sendMessage(PEER, "synthetic zc250 text"))
            awaitIdle(vm)
            assertShortCue(vm)
            assertEquals("the refused message is not on screen ($mode)", PEER, refusal(vm))
            // The chat screen resets the state as soon as its toast is shown; the refusal must outlive that.
            vm.resetSendState()
            assertEquals("the refusal vanished with the toast ($mode)", PEER, refusal(vm))

            assertFalse("the payment request was not refused", vm.sendPaymentRequest(PEER, AMOUNT, REASON))
            awaitIdle(vm)
            assertShortCue(vm)
            vm.resetSendState()

            assertEquals("sendPayment must answer false when refused before anything was built ($mode)", false, sendPayment(vm))
            awaitIdle(vm)
            assertShortCue(vm)
            vm.resetSendState()

            vm.fulfillPaymentRequest(PEER, AMOUNT, REQUEST_ID)
            awaitIdle(vm)
            assertShortCue(vm)
            vm.resetSendState()

            assertEquals("the refusal is still the contact after every refused send ($mode)", PEER, refusal(vm))
            assertTrue("the gate must not be weakened: the flag stays", prefs.keyChanged)
            assertEquals("nothing may be built ($mode)", 0, seam.proposalUris.size)
            assertEquals("nothing may be priced ($mode)", 0, seam.probes.get())
            assertEquals("nothing may be broadcast ($mode)", 0, seam.submits.get())
            assertEquals("nothing may go over the relays ($mode)", 0, publishes.get())
            assertTrue("no row may be stored ($mode): ${prefs.pending.keys}", prefs.pending.isEmpty())
            assertTrue("no bubble may show ($mode)", PaymentHarness.pendingMessages(vm).isEmpty())
            assertFalse("no paid marker ($mode)", prefs.paid.contains(REQUEST_ID))
            assertEquals("fixture sanity ($mode)", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `acknowledging the refusal clears only the notice, never the gate`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR).apply { keyChanged = true }
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)

            assertFalse(vm.sendMessage(PEER, "synthetic zc250 text"))
            awaitIdle(vm)
            assertEquals(PEER, refusal(vm))

            invoke(vm, "acknowledgeKeyChangeRefusal")
            assertNull("collapsing the notice must clear the refusal", refusal(vm))
            assertTrue("acknowledging must not accept the new key", prefs.keyChanged)

            assertFalse("the next send must be refused again", vm.sendMessage(PEER, "synthetic zc250 text again"))
            awaitIdle(vm)
            assertEquals("the next refusal shows again", PEER, refusal(vm))
            assertEquals(0, seam.proposalUris.size)
            assertTrue(prefs.pending.isEmpty())
        }

    @Test
    fun `a Shielded chat's dismiss clears the flag and the refusal`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT).apply { keyChanged = true }
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)

            assertFalse(vm.sendMessage(PEER, "synthetic zc250 text"))
            awaitIdle(vm)
            assertEquals(PEER, refusal(vm))

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)

            assertFalse("a Shielded chat's × accepts the new key, free", prefs.keyChanged)
            assertNull("the refusal must go with the flag", refusal(vm))
            assertEquals("accepting the key in a Shielded chat pays nothing", 0, seam.proposalUris.size)
        }

    // T7BH-N2 (W-57): an Open chat pays no reconnect (AnOpenChatNeverSpendsZecTest); the paid reconnect is a Tunnel chat's.
    @Test
    fun `a Tunnel chat's dismiss first asks to pay the reconnect and the refusal stays until Pay`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticzc250openreconn01", ConversationMode.TUNNEL).apply { keyChanged = true }
            val vm = f.viewModel()

            assertFalse(vm.sendMessage(f.peer, "synthetic zc250 text"))
            awaitIdle(vm)
            assertEquals(f.peer, refusal(vm))
            vm.resetSendState()

            vm.dismissE2EKeyChanged(f.peer)
            awaitIdle(vm)

            f.assertAskedFirst(vm, messages = 2)
            val state = HandshakeHarness.sendMessageState(vm) as SendMessageState.NeedsPaidActionConfirmation
            assertEquals(PaidAction.KEY_CHANGE_RECONNECT, state.confirmation.action)
            assertTrue("the banner stays until the user decides", f.keyChanged)
            assertEquals("the refusal stays while the price question is open", f.peer, refusal(vm))

            vm.confirmPaidAction()
            awaitIdle(vm)

            assertFalse("Pay accepts the new key", f.keyChanged)
            assertNull("the refusal must go with the flag", refusal(vm))
        }

    private fun assertShortCue(vm: ChatViewModel) {
        assertEquals(
            "the short cue is the key-changed notice",
            SendMessageState.Error(ChatSendCopy.KEY_CHANGED_VERIFY),
            HandshakeHarness.sendMessageState(vm),
        )
    }

    /** ChatViewModel.keyChangeRefusal (ZC-250): the contact whose send was refused, or null. */
    private fun refusal(vm: ChatViewModel): String? {
        val getter =
            try {
                ChatViewModel::class.java.getMethod("getKeyChangeRefusal")
            } catch (e: NoSuchMethodException) {
                throw AssertionError("ChatViewModel has no keyChangeRefusal: a refused send leaves only a toast", e)
            }
        return (getter.invoke(vm) as StateFlow<*>).value as String?
    }

    /** sendPayment's answer, read by reflection (it answered nothing before ZC-250). */
    private fun sendPayment(vm: ChatViewModel): Any? =
        try {
            ChatViewModel::class.java
                .getMethod(
                    "sendPayment",
                    String::class.java,
                    java.lang.Double.TYPE,
                    String::class.java,
                    ChatSendQuote::class.java,
                ).invoke(vm, PEER, 0.0001, "synthetic zc250 memo", null)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun invoke(
        vm: ChatViewModel,
        name: String,
    ) {
        val method =
            try {
                ChatViewModel::class.java.getMethod(name)
            } catch (e: NoSuchMethodException) {
                throw AssertionError("ChatViewModel has no $name()", e)
            }
        try {
            method.invoke(vm)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
