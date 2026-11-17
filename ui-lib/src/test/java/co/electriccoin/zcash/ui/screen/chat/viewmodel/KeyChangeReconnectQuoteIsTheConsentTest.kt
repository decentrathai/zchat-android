package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
 * ZC-300 (fix-all 2.13.0, train T5b; r8-findings SPECS §16.1, NEW-KCR) — in a Tunnel or Open chat the × on the "Security
 * key changed" banner asks the reconnect's price (KEY_CHANGE_RECONNECT: our KEX, then our ZBOOT, 2 on-chain). On Pay,
 * ChatViewModel.dismissE2EKeyChanged clears the flag, drops the contact's relay key and calls sendKEXMessage with no
 * confirmedByQuote, so the KEX goes through the standing "Allow handshake transactions" gate: without that consent a
 * second question follows for the same KEX, and "Not now" leaves the chat with no relay key and no KEX. Our ZBOOT after
 * the contact's KEXACK has no allowance either.
 *
 * Must hold: the confirmed reconnect is the consent for exactly what it quoted. No consent question at any point, the
 * standing consent is never written, the KEX is paid once and our ZBOOT after the contact's KEXACK once, against the
 * reconnect's allowance, which it uses up: 2 on-chain transactions in all, never more. With a Keystone account selected
 * when Pay is tapped, nothing changes (the flag, the relay key) and the Keystone text is shown.
 *
 * Controls (green before and after): with the standing consent the reconnect stays within its quote; Cancel changes
 * nothing; a Shielded chat's × clears the flag for free, asks nothing and owes nothing.
 *
 * Drives the real ViewModel through the real use case and repository ([KeyChangeReconnectChat]). Every address and key is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChangeReconnectQuoteIsTheConsentTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc300reconnquote01"
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
    fun `without the standing consent a confirmed reconnect pays its KEX and its ZBOOT and asks nothing more`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            assertTrue("the banner stays until the user decides", chat.keyChanged)
            vm.confirmPaidAction()
            awaitIdle(vm)

            recorder.assertNoHandshakeConsentQuestion("the reconnect's confirm already quoted this KEX; it was asked about again")
            assertFalse("Pay accepts the new key", chat.keyChanged)
            assertNull("Pay drops the old relay key", chat.relayKey)
            assertEquals("the reconnect's KEX was not paid on its own confirm", 1, chat.seam.kexAnswered.get())
            assertFalse("the paid KEX is still marked owed", PEER in chat.owed.kexOwed)
            assertEquals("the reconnect quoted our ZBOOT: its allowance is written", true, chat.owed.zbootQuoted[PEER])

            // The contact answers our KEX; its KEXACK carries no relay key, so our ZBOOT goes on-chain.
            val contactKeys = E2EEncryption.generateKeyPair()
            chat.owed.kexAckFromContact(vm, contactKeys, txId = "synthetic-zc300-kexack-01")
            awaitIdle(vm)

            assertEquals("fixture sanity: the KEXACK was taken", contactKeys.publicKey, chat.owed.peerKeys[PEER])
            recorder.assertNoHandshakeConsentQuestion("the ZBOOT the reconnect quoted was asked about again")
            assertEquals("the ZBOOT the reconnect quoted was not paid", 1, chat.seam.zbootAnswered.get())
            assertTrue("the ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)

            // Something re-arms our ZBOOT (a failed delivery, a repeated KEXACK): nothing more is paid on that one quote.
            chat.store.setSentNostrBootPubkey(PEER, null)
            chat.owed.paidRecords.clear()
            chat.owed.kexAckFromContact(vm, contactKeys, txId = "synthetic-zc300-kexack-02")
            awaitIdle(vm)

            assertEquals("a reconnect quoted as 2 transactions paid more", 2, chat.seam.submitted.size)
            assertEquals("the one-off reconnect was turned into the standing handshake consent", 0, chat.owed.consentWrites.get())
            assertFalse("the standing consent was given by the reconnect", chat.owed.consent)
            recorder.stop()
        }

    // T7BH-N2 (W-57): an Open chat pays no reconnect (AnOpenChatNeverSpendsZecTest); the quote-is-the-consent rule is a Tunnel chat's.
    @Test
    fun `in a Tunnel chat without the standing consent the confirmed reconnect pays its KEX and asks nothing more`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            recorder.assertNoHandshakeConsentQuestion("the reconnect's confirm already quoted this KEX; it was asked about again")
            assertFalse("Pay accepts the new key", chat.keyChanged)
            assertNull("Pay drops the old relay key", chat.relayKey)
            assertEquals("the reconnect's KEX was not paid on its own confirm", 1, chat.seam.kexAnswered.get())
            assertEquals(1, chat.seam.submittedOf("KEX"))
            assertEquals("the one-off reconnect was turned into the standing handshake consent", 0, chat.owed.consentWrites.get())
            recorder.stop()
        }

    @Test
    fun `with a Keystone account selected when Pay is tapped nothing changes`() =
        runBlocking {
            val accounts = SelectedAccountFeed.create()
            val chat = KeyChangeReconnectChat(PEER, consent = true)
            val vm = chat.viewModel(accounts)

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            accounts.keystoneSelected()
            vm.confirmPaidAction()
            awaitIdle(vm)

            chat.assertNothingChanged("with Keystone selected")
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP), HandshakeHarness.sendMessageState(vm))
        }

    /** Control, green before and after: with the standing consent given, the reconnect still pays within its quote. */
    @Test
    fun `control - with the standing consent given the reconnect's KEX and ZBOOT stay within its quote`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = true)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            chat.owed.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc300-kexack-03")
            awaitIdle(vm)

            assertEquals("one KEX", 1, chat.seam.submittedOf("KEX"))
            assertEquals("one ZBOOT", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            assertTrue("the ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
        }

    /** Control, green before and after: Cancel changes nothing and pays nothing. */
    @Test
    fun `control - Cancel keeps the flag and the relay key and owes nothing`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            vm.dismissPaidAction()
            awaitIdle(vm)

            chat.assertNothingChanged("after Cancel")
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(vm))
        }

    /** Control, green before and after: a Shielded chat's × accepts the key for free; nothing is asked, dropped or owed. */
    @Test
    fun `control - a Shielded chat's dismiss clears the flag for free and owes nothing`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, mode = ConversationMode.VAULT)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse("a free dismiss must not ask: $state", state.javaClass.simpleName == "NeedsPaidActionConfirmation")
            assertFalse("a Shielded chat's × accepts the new key", chat.keyChanged)
            assertEquals("a Shielded chat keeps the relay key", KeyChangeReconnectChat.OLD_RELAY, chat.relayKey)
            assertEquals("a Shielded chat's × pays nothing", 0, chat.seam.proposals())
            chat.assertNothingOwed("after a Shielded chat's ×")
        }
}
