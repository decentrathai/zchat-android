package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1472, the logic half (OT213n B1a; owner findings 2 Nov 2026 point 12; lanes/L2_mode_switch.md; FIX_PLAN
 * REVISION 1; FABLE_PLAN_CRITIQUE S1, money-path rule 4).
 *
 * The owner switched an Open chat to Tunnel, paid, and the chat still read "Free, sent over Nostr": he could not tell
 * whether ZEC was spent or a key exchange ran. At 669cf19e1 Apply pays our key exchange (KEX) on chain and the chat gets
 * only "You switched to Tunnel"; nothing says that a paid key exchange went out, or that the setup is still running.
 *
 * Wanted (S1): a persistent "Setting up Tunnel: key exchange sent, N ZEC" note, written ONLY from the send's outcome —
 * after the KEX was handed to the network, never before (the quoted total is on the question; the actual one after).
 * The UI half (the chip and the composer line) is B2's. The wording is the implementer's (app-texts, EN and ES); this
 * test asks for a note other than "You switched to …".
 *
 * Driven through the real switch, question and KEX send ([RealModeStorePhone]); the settlement seam holds the KEX's
 * broadcast so the note's timing can be seen. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelSetupNoteComesFromTheSendTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1472setupnote0001"
        const val PEER_RELAY_KEY = "3a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071829"
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
    fun `an Open chat switched to Tunnel says the paid key exchange went out, after it went out and not before`() =
        runBlocking {
            val phone = openChat(HandshakeSeam(PEER, holdFirstSubmit = true))
            val vm = phone.viewModel()

            vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertTrue(
                "fixture: Apply asks before paying the key exchange",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation,
            )
            vm.confirmPaidAction()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { phone.seam.firstSubmitEntered.await() }

            assertEquals(
                "a setup note was written before the key exchange was handed to the network (S1: only from the outcome)",
                emptyList<String>(),
                setupNotes(phone).map { it.text },
            )

            phone.seam.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { phone.seam.firstSubmitDone.await() }
            awaitIdle(vm)

            assertEquals("fixture: one paid key exchange", 1, phone.seam.broadcasts.size)
            assertTrue(
                "after the paid key exchange went out the chat must say so (\"Setting up Tunnel: key exchange sent, N ZEC\"); " +
                    "notes: ${phone.notes().map { it.text }}",
                setupNotes(phone).isNotEmpty(),
            )
        }

    // ---------------------------------------------------------------- fixture

    /** An Open chat: the contact's relay key bound, our E2E key made, no key exchange yet; the relays not reachable now. */
    private fun openChat(seam: HandshakeSeam): RealModeStorePhone =
        RealModeStorePhone(
            PEER,
            ConversationMode.OPEN,
            consent = false,
            exchangeComplete = false,
            ourKey = true,
            relayKey = PEER_RELAY_KEY,
            seam = seam,
        )

    /** The chat's notes other than "You switched to …" (the one note 669cf19e1 writes for the switch). */
    private fun setupNotes(phone: RealModeStorePhone): List<ZchatPreferences.PendingMessageData> {
        val switched = "note#${R.string.chat_note_you_switched}("
        return phone.notes().filter { !it.text.startsWith(switched) }
    }
}
