package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ZC-739 (T7B-HANDSHAKE-SPEC rev 2, section 2.1, item 7; text T5) — a tap that would send to a contact whose new key
 * waits for the user says why and sends nothing: Reconnect shows the key-change refusal (`chat_send_key_changed_verify`)
 * and not "Reconnected", with 0 proposals and 0 relay publishes; Accept on a chat request shows the same refusal and
 * keeps the Accept hold. Funded wallet, standing consent given, a recording publisher that answers 0 acks.
 *
 * Every address and key is synthetic.
 */
class TapsThatWouldSendSayWhyWhileANewKeyWaitsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        LedgerTests.tearDown(sendScopes)
    }

    private fun flaggedWorld(exchangeComplete: Boolean): KeyWaitWorld {
        val accounts =
            Zc101FakeAccountDataSource(
                runBlocking {
                    syntheticZashiAccount().let {
                        it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))))
                    }
                },
            )
        return KeyWaitWorld(
            LedgerWorld(mode = ConversationMode.TUNNEL, exchangeComplete = exchangeComplete, consent = true, accountSource = accounts),
        ).also { it.flagged += it.w.peer }
    }

    private fun shown(vm: ChatViewModel): SendMessageState = HandshakeHarness.sendMessageState(vm)

    @Test
    fun `Reconnect says the key changed, not Reconnected, and sends and pays nothing`() =
        runBlocking {
            val k = flaggedWorld(exchangeComplete = true)
            val relayKey = KeyWaitWorld.relayKey(7392)
            k.w.prefs.peerNostr[k.w.peer] = relayKey
            k.registerPublisher()
            val vm = k.openVm()

            vm.reSendOurIdentity(k.w.peer)
            awaitIdle(vm)

            assertEquals(
                "Reconnect did not say why nothing was sent: ${shown(vm)}",
                ChatSendCopy.KEY_CHANGED_VERIFY,
                (shown(vm) as? SendMessageState.Error)?.message,
            )
            assertEquals("Reconnect paid while the new key waits", 0, k.proposals())
            assertEquals("Reconnect published our identity while the new key waits", 0, k.publishesTo(relayKey).size)
        }

    @Test
    fun `Reconnect on a torn chat says the key changed and pays no key exchange`() =
        runBlocking {
            val k = flaggedWorld(exchangeComplete = false)
            val vm = k.openVm()

            vm.reSendOurIdentity(k.w.peer)
            awaitIdle(vm)

            assertEquals(
                "Reconnect did not say why nothing was sent: ${shown(vm)}",
                ChatSendCopy.KEY_CHANGED_VERIFY,
                (shown(vm) as? SendMessageState.Error)?.message,
            )
            assertEquals("Reconnect paid a key exchange while the new key waits", 0, k.proposals())
        }

    @Test
    fun `Accept on a chat request says the key changed and keeps the hold`() =
        runBlocking {
            val k = flaggedWorld(exchangeComplete = false)
            val theirs = k.w.asResponder()
            k.holds[k.w.peer] = theirs
            val vm = k.openVm()

            vm.acceptInboundKexRequest(k.w.peer)
            awaitIdle(vm)

            assertEquals(
                "Accept did not say why nothing was sent: ${shown(vm)}",
                ChatSendCopy.KEY_CHANGED_VERIFY,
                (shown(vm) as? SendMessageState.Error)?.message,
            )
            assertEquals("Accept paid an answer while a new key waits", 0, k.proposals())
            assertEquals("Accept dropped the hold of a request it did not answer", theirs, k.holds[k.w.peer])
        }
}
