package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.state
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * ZC-1473, the logic half (OT213n B1a; owner findings 2 Nov 2026 point 13; lanes/L2_mode_switch.md; FIX_PLAN
 * REVISION 1; FABLE_PLAN_CRITIQUE S2; register ZC-820, ZC-1048).
 *
 * The owner tapped "Reconnect (re-send my key)" and saw nothing useful on either phone. At 669cf19e1 reSendOurIdentity:
 *  - in an Open chat (no key exchange; Open never pays) runs a forced bootstrap that sends nothing at all, and still
 *    says "Re-sent your key" (ChatSendCopy.KEY_RESENT): a claim of a send that did not happen;
 *  - in a Tunnel chat says "Reconnected — re-sent your details" the moment the ZBOOT send is LAUNCHED, before any relay
 *    has taken it, and leaves nothing in the chat.
 *
 * Wanted: no "re-sent" claim when nothing was sent; the result only after the send ends, as a persistent note in the
 * chat; and a pure predicate the menu uses, ReconnectMenu.visible(mode), false for Open (B2 wires it; B1a touches no
 * view file). The predicate is reached by name, so this file compiles before it exists.
 *
 * Driven through the real tap ([RealModeStorePhone]); the relays hold their answer in the second case. Every address
 * and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSaysWhatItDidTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1473reconnect0001"
        const val PEER_RELAY_KEY = "6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e"
        const val RECONNECT_MENU = "co.electriccoin.zcash.ui.screen.chat.model.ReconnectMenu"
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
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `Reconnect in an Open chat does not claim that our key was re-sent, and pays nothing`() =
        runBlocking {
            val phone =
                RealModeStorePhone(PEER, ConversationMode.OPEN, consent = true, ourKey = false, relayKey = PEER_RELAY_KEY)
            phone.relaysUp()
            val vm = phone.viewModel()

            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)

            val shown = state(vm)
            assertFalse(
                "an Open chat sends no key on Reconnect, yet the tap says \"${ChatSendCopy.KEY_RESENT}\" ($shown)",
                shown == SendMessageState.Success(ChatSendCopy.KEY_RESENT),
            )
            assertFalse(
                "nor \"${ChatSendCopy.RECONNECTED_DETAILS_RESENT}\" ($shown)",
                shown == SendMessageState.Success(ChatSendCopy.RECONNECTED_DETAILS_RESENT),
            )
            assertEquals("Reconnect in an Open chat paid something", 0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `Reconnect in a Tunnel chat reports the re-send only after a relay took it, as a note that stays in the chat`() =
        runBlocking {
            val phone =
                RealModeStorePhone(
                    PEER,
                    ConversationMode.TUNNEL,
                    consent = true,
                    exchangeComplete = true,
                    relayKey = PEER_RELAY_KEY,
                )
            val relaysAnswer = CompletableDeferred<Unit>()
            phone.holdPublishes = relaysAnswer
            phone.relaysUp()
            val vm = phone.viewModel()
            val notesBefore = phone.notes().size

            vm.reSendOurIdentity(PEER)
            HandshakeHarness.awaitTrue { phone.published.isNotEmpty() }

            assertFalse(
                "the tap said \"${ChatSendCopy.RECONNECTED_DETAILS_RESENT}\" while no relay had taken the ZBOOT yet",
                state(vm) == SendMessageState.Success(ChatSendCopy.RECONNECTED_DETAILS_RESENT),
            )

            relaysAnswer.complete(Unit)
            awaitIdle(vm)
            // The ZBOOT runs in the process-wide send scope: give its end a moment to report.
            PaymentHarness.eventually { phone.notes().size > notesBefore }

            assertEquals("fixture: the identity went over the relays only", 0, phone.seam.proposalRequests.get())
            assertTrue(
                "the result of Reconnect must stay in the chat as a note once the send ended; notes ${phone.notes().map { it.text }}",
                phone.notes().size > notesBefore,
            )
        }

    @Test
    fun `the Reconnect menu item is hidden in an Open chat and shown in a Tunnel chat`() {
        val menu =
            runCatching { Class.forName(RECONNECT_MENU) }.getOrNull()
                ?: return fail("the planned pure predicate ReconnectMenu.visible(mode) (FIX_PLAN REVISION 1, ZC-1473) is missing")
        val instance = menu.getField("INSTANCE").get(null)
        val visible = menu.getMethod("visible", ConversationMode::class.java)

        assertEquals("Reconnect re-sends a key Open never uses: hidden in Open", false, visible.invoke(instance, ConversationMode.OPEN))
        assertEquals("Reconnect is the Tunnel repair: shown in Tunnel", true, visible.invoke(instance, ConversationMode.TUNNEL))
    }
}
