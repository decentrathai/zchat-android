package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * ZC-534 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §2, part 2) — a Tunnel chat's per-block ZBOOT retry asks the
 * standing "Allow handshake transactions" question for a ZBOOT a confirmed question already quoted.
 *
 * A confirmed key-change reconnect quotes 2 on-chain transactions and writes the ZBOOT's allowance. When the ZBOOT's
 * attempt provably sends nothing (here: refused before anything is stored), its marker is re-armed and the allowance is
 * restored, and the per-block retry is what sends it next. That retry called the standing-consent rule, so with no
 * standing consent the user was asked again for a transaction the reconnect's question had already quoted.
 *
 * Must hold: the block retry spends a standing allowance as a quote: the ZBOOT is paid once, no standing-consent
 * question is raised and no consent is written. Control (green before and after): with no allowance the retry still
 * asks the standing consent and pays nothing.
 *
 * Drives the real ViewModel through the real use case and repository ([KeyChangeReconnectChat]). Every address and key
 * is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelBlockRetryPaysAQuotedZbootWithoutAskingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc534tunnelretry1"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `a Tunnel chat's block retry pays the ZBOOT a confirmed reconnect quoted without the standing consent question`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, rejectFirstZbootBeforeStore = true)
            chat.owed.tunnelPeers += PEER
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the reconnect's KEX went", 1, chat.seam.submittedOf("KEX"))
            // The contact answers; our ZBOOT's first attempt is refused before anything is stored.
            chat.owed.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc534-kexack-21")
            awaitIdle(vm)
            assertEquals("fixture sanity: the first ZBOOT attempt was made", 1, chat.seam.zbootProposals.get())
            assertEquals("fixture sanity: it sent nothing", 0, chat.seam.submittedOf("ZBOOT"))
            assertNull("fixture sanity: the refused ZBOOT re-armed its marker", chat.store.getSentNostrBootPubkey(PEER))
            assertEquals("fixture sanity: the refused attempt restored the allowance", true, chat.owed.zbootQuoted[PEER])

            chat.owed.tick(vm)
            awaitIdle(vm)

            recorder.assertNoHandshakeConsentQuestion(
                "the block retry asked the standing consent for the ZBOOT the confirmed reconnect already quoted",
            )
            assertEquals("the block retry did not pay the quoted ZBOOT", 1, chat.seam.submittedOf("ZBOOT"))
            assertTrue("the ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            assertEquals("the one-off reconnect was turned into the standing consent", 0, chat.owed.consentWrites.get())
            assertFalse("the standing consent was given by the reconnect", chat.owed.consent)

            chat.owed.tick(vm)
            awaitIdle(vm)
            assertEquals("the quoted ZBOOT was paid a second time", 1, chat.seam.submittedOf("ZBOOT"))
            recorder.stop()
        }

    /** Control, green before and after: a Tunnel ZBOOT no question quoted still needs the standing consent. */
    @Test
    fun `control - without an allowance the block retry still asks the standing consent`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false)
            chat.owed.tunnelPeers += PEER
            // A Tunnel chat with a finished key exchange whose ZBOOT is due: no banner, no relay key of the contact, no
            // delivered-identity marker, and no allowance.
            chat.keyChanged = false
            chat.relayKey = null
            chat.owed.peerKeys[PEER] = E2EEncryption.generateKeyPair().publicKey
            chat.store.setSentNostrBootPubkey(PEER, null)
            // M3 (ZC-110): on a wallet that can pay one handshake. The fixture's default wallet holds only a confirming
            // deposit, and a wallet that cannot pay one is no longer asked (the block asks once its funds are spendable).
            val vm = chat.viewModel(SelectedAccountFeed.create())
            val recorder = SendStateRecorder(vm)

            chat.owed.tick(vm)
            awaitIdle(vm)

            assertTrue(
                "the block retry must ask the standing consent for a ZBOOT no question quoted: ${recorder.states}",
                recorder.states.toList().any { it is SendMessageState.NeedsHandshakeSpendConsent },
            )
            assertEquals("nothing may be paid before the consent", 0, chat.seam.submitted.size)
            assertEquals("the consent was written without being given", 0, chat.owed.consentWrites.get())
            recorder.stop()
        }
}
