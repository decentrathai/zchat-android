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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-286 (fix-all 2.13.0, train T5b; r8-findings SPECS §1, scenario B) — "Reset encryption" asks for the price of 2
 * on-chain transactions (our KEX, then our ZBOOT), and then, without the standing "Allow handshake transactions" consent,
 * wipes the keys and asks that consent question for the same KEX. "Not now" leaves the chat with no keys and no KEX;
 * "Allow" turns a one-off Reset into a standing consent for every later handshake. Our ZBOOT after the contact's KEXACK
 * is not covered by the Reset's quote either (no allowance is written for it).
 *
 * Must hold: the confirmed Reset is the consent for exactly what it quoted. No consent question at any point, the
 * standing consent is never written, the KEX is paid once, and after the contact's KEXACK (no relay key) our ZBOOT goes
 * on-chain once against the Reset's allowance, which it uses up: 2 on-chain transactions in all, never 3.
 *
 * Controls (green before and after): Cancel writes and wipes nothing; an Open chat whose contact's relay key is known
 * resets for free, is not asked and owes nothing; with a Keystone account selected when the question is answered nothing
 * is written, wiped or sent.
 *
 * Drives the real ViewModel through the real use case and repository ([ResetOwedChat]). Every address and key is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetQuoteIsTheHandshakeConsentTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc286resetquote001"
        const val RELAY_KEY = "6d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e"
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
    fun `without the standing consent a confirmed Reset pays its KEX and its ZBOOT and asks nothing more`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            recorder.assertNoHandshakeConsentQuestion("the Reset's confirm already quoted this KEX; it was asked about again")
            chat.reset.assertWiped()
            assertEquals("the Reset's KEX was not paid on its own confirm", 1, chat.seam.kexAnswered.get())
            assertEquals("the Reset quoted our ZBOOT: its allowance is written", true, chat.zbootQuoted[PEER])

            // The contact answers our new key; its first-contact KEXACK carries no relay key, so our ZBOOT goes on-chain.
            val contactKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, contactKeys, txId = "synthetic-zc286-kexack-01")
            awaitIdle(vm)

            assertEquals("fixture sanity: the KEXACK was taken", contactKeys.publicKey, chat.peerKeys[PEER])
            assertEquals("the ZBOOT the Reset quoted was not paid", 1, chat.seam.zbootAnswered.get())
            assertTrue("the ZBOOT did not use up the Reset's allowance", chat.zbootQuoted[PEER] != true)
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)

            // Something re-arms our ZBOOT (a failed delivery, a repeated KEXACK): nothing more is paid on that one quote.
            chat.store.setSentNostrBootPubkey(PEER, null)
            chat.paidRecords.clear()
            chat.kexAckFromContact(vm, contactKeys, txId = "synthetic-zc286-kexack-02")
            awaitIdle(vm)

            assertEquals("a Reset quoted as 2 transactions paid more", 2, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("the ZBOOT the Reset quoted was asked about again")
            assertEquals("the one-off Reset was turned into the standing handshake consent", 0, chat.consentWrites.get())
            assertFalse("the standing consent was given by the Reset", chat.consent)
            recorder.stop()
        }

    @Test
    fun `with the standing consent given the Reset's KEX and ZBOOT still stay within its quote`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = true)
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc286-kexack-03")
            awaitIdle(vm)

            assertEquals("one KEX", 1, chat.seam.submittedOf("KEX"))
            assertEquals("one ZBOOT", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            assertTrue("the ZBOOT did not use up the Reset's allowance", chat.zbootQuoted[PEER] != true)
        }

    /** Control, green before and after: Cancel writes nothing, wipes nothing and pays nothing. */
    @Test
    fun `control - Cancel writes no owed mark and wipes nothing`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.dismissPaidAction()
            awaitIdle(vm)

            chat.reset.assertNothingWiped("after Cancel")
            assertNothingOwed(chat)
            assertEquals("Cancel must pay nothing", 0, chat.seam.proposals())
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(vm))
        }

    /** Control, green before and after: an Open chat with the relay key known resets for free, unasked, owing nothing. */
    @Test
    fun `control - an Open chat whose contact's relay key is known resets for free and owes nothing`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, mode = ConversationMode.OPEN)
            chat.reset.peerRelayKey = RELAY_KEY
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse("a reset that pays nothing must not ask: $state", state.javaClass.simpleName == "NeedsPaidActionConfirmation")
            chat.reset.assertWiped()
            assertNothingOwed(chat)
            assertEquals("an Open chat never pays a KEX (#224)", 0, chat.seam.proposals())
        }

    /** Control, green before and after: Keystone selected when the question is answered: nothing written, wiped or sent. */
    @Test
    fun `control - with Keystone selected when Pay is tapped nothing is written, wiped or sent`() =
        runBlocking {
            val accounts = SelectedAccountFeed.create()
            val chat = ResetOwedChat(PEER, consent = true)
            val vm = chat.viewModel(accounts)

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            accounts.keystoneSelected()
            vm.confirmPaidAction()
            awaitIdle(vm)

            chat.reset.assertNothingWiped("with Keystone selected")
            assertNothingOwed(chat)
            assertEquals("nothing may be built with Keystone selected", 0, chat.seam.proposals())
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP), HandshakeHarness.sendMessageState(vm))
        }

    private fun assertNothingOwed(chat: ResetOwedChat) {
        assertFalse("an owed KEX was written", PEER in chat.kexOwed)
        assertFalse("an owed reason was written", chat.kexOwedReason.containsKey(PEER))
        assertFalse("an owed account was written", chat.kexOwedAccount.containsKey(PEER))
        assertTrue("a ZBOOT allowance was written", chat.zbootQuoted[PEER] != true)
    }
}
