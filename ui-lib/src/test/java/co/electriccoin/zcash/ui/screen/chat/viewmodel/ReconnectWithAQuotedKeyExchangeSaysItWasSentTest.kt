package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.QuotedChatSetup
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.state
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

/**
 * ZC-1509 (B1a cross-family review grok-4, the confirmed sub-case; VERIFY_B1a.md item 6; FIX_PLAN REVISION 7;
 * money-path rule 4: the chat never says "nothing paid" while a paid send goes out).
 *
 * Reconnect in a torn Tunnel chat (our key, no key from the contact) runs startNostrBootstrap(force = true, switch = null,
 * kexEnded). When a confirmed price question still covers this chat's KEX leg (QuotedChatSetup.kex), the bootstrap takes
 * the `quotedLeg != null` arm and launches sendQuotedKex, which pays the KEX at its quoted leg. At e626e879d that arm never
 * reports: kexReported stays false, `finally` completes kexEnded with null, and Reconnect says
 * chat_note_reconnect_not_sent ("nothing was sent and nothing was paid") while the quoted KEX goes out. Wanted: that arm
 * reports through launchKexReporting (or completes kexEnded from its outcome), so the tap says the key was re-sent.
 *
 * Driven through the real tap ([RealModeStorePhone]; the quoted legs in the real store). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectWithAQuotedKeyExchangeSaysItWasSentTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1509quotedkex0001"
        val PEER_RELAY_KEY: String = "c1509".padEnd(64, 'e')
        const val QUOTED_KEX_LEG = 60_000L
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
    fun `Reconnect that pays a quoted key exchange never says that nothing was sent or paid`() =
        runBlocking {
            val phone =
                RealModeStorePhone(
                    PEER,
                    ConversationMode.TUNNEL,
                    consent = true,
                    exchangeComplete = false,
                    ourKey = true,
                    relayKey = PEER_RELAY_KEY,
                )
            phone.real.setQuotedChatSetup(PEER, QuotedChatSetup(kex = QUOTED_KEX_LEG).encode())
            phone.relaysUp()
            val vm = phone.viewModel()

            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)
            PaymentHarness.eventually { phone.seam.submits.get() > 0 }
            awaitIdle(vm)

            assertEquals("fixture: Reconnect paid the quoted key exchange once", 1, phone.seam.submits.get())
            assertEquals(
                "Reconnect paid a quoted key exchange but did not say the key was re-sent (money-path rule 4); notes " +
                    "${phone.notes().map { it.text }}",
                SendMessageState.Success(ChatSendCopy.KEY_RESENT),
                state(vm),
            )
        }
}
