package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * ZC-535 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3, owner decision W-31 option A) — the × on "Security key
 * changed" in a Tunnel or Open chat quotes a reconnect of 2 on-chain messages (our key exchange, then our relay
 * identity). In the usual state (our key for the contact and the contact's OLD key both stored, so the exchange reads as
 * complete) Pay cleared the flag, dropped the contact's relay key and then sent and paid nothing:
 * ChatViewModel.payOwedKex settled the KEX as "already delivered" because the exchange was complete, no trigger existed
 * for our ZBOOT without a KEXACK, the question was the constant 2 and the ZBOOT allowance was left standing. ZChat 2.12.4
 * re-sent the key exchange.
 *
 * Must hold: the confirmed reconnect really sends its key exchange and then its relay identity, once, exactly as
 * quoted; the question counts exactly the legs that will be paid (a leg paid in the last hour is neither quoted nor
 * paid again; with both in the hour the × is a free accept); Pay reads the plan again and asks again when it now costs
 * more; a ZBOOT that can go over the relays goes free and its allowance is withdrawn. What is paid never exceeds what
 * was quoted (asserted against the seam's submitted transactions).
 *
 * Controls (green before and after): an incomplete exchange still quotes 2 and pays the KEX, then the ZBOOT after the
 * contact's KEXACK (ZC-300); the contact's own signed ZBOOT after a reconnect binds its relay key again and a queued
 * Tunnel message goes over the relay.
 *
 * Drives the real ViewModel through the real use case and repository ([KeyChangeReconnectChat]). Every address and key
 * is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChangeReconnectSendsItsKeyExchangeTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc535reconnsends01"
        val KEX_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.KEX, PEER)
        val ZBOOT_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)
        const val WAIT_MS = 3_000L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
        queuedTunnelPayloads().clear()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        queuedTunnelPayloads().clear()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `in the usual key-changed state a confirmed reconnect pays its key exchange and its relay identity, two transactions as quoted`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            assertTrue("the banner stays until the user decides", chat.keyChanged)
            vm.confirmPaidAction()
            awaitSettled(vm, chat)

            assertEquals("the reconnect quoted its key exchange and sent none", 1, chat.seam.submittedOf("KEX"))
            assertEquals("the reconnect quoted its relay identity and sent none", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("a reconnect quoted as 2 transactions must pay exactly 2", 2, chat.seam.submitted.size)
            assertFalse("Pay accepts the new key", chat.keyChanged)
            assertNull("Pay drops the old relay key", chat.relayKey)
            assertEquals("the flag cleared before the reconnect was recorded", true, chat.owedWhenTheFlagCleared)
            assertFalse("the paid KEX is still marked owed", PEER in chat.owed.kexOwed)
            assertTrue("the paid ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
            assertFalse("an owed ZBOOT was left behind a paid one", PEER in chat.owed.zbootOwed)
            recorder.assertNoHandshakeConsentQuestion("the reconnect's confirm quoted both messages; it was asked about again")
            assertEquals("the one-off reconnect was turned into the standing handshake consent", 0, chat.owed.consentWrites.get())
            assertFalse("the standing consent was given by the reconnect", chat.owed.consent)
            recorder.stop()
        }

    @Test
    fun `the relay identity is re-sent although our identity is already recorded as delivered`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            reconnect(vm, chat, expectedCount = 2)
            assertEquals("the first reconnect must pay its two messages", 2, chat.seam.submitted.size)
            val delivered = chat.store.getSentNostrBootPubkey(PEER)
            assertNotNull("fixture sanity: our identity is recorded as delivered", delivered)

            // More than an hour later the contact's key changes again: our identity is still recorded as delivered.
            chat.owed.paidRecords.clear()
            keyChangesAgain(chat)
            reconnect(vm, chat, expectedCount = 2)

            assertEquals("fixture sanity: the same identity", delivered, chat.store.getSentNostrBootPubkey(PEER))
            assertEquals("the second reconnect's key exchange was not sent", 2, chat.seam.submittedOf("KEX"))
            assertEquals(
                "the second reconnect quoted our relay identity and did not send it (recorded as delivered)",
                2,
                chat.seam.submittedOf("ZBOOT"),
            )
            assertEquals("two reconnects quoted 2 transactions each", 4, chat.seam.submitted.size)
            assertTrue("an allowance was left", chat.owed.zbootQuoted[PEER] != true)
        }

    @Test
    fun `a second confirm of the same reconnect pays nothing more and leaves no allowance`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            reconnect(vm, chat, expectedCount = 2)
            assertEquals("the reconnect must pay its two messages", 2, chat.seam.submitted.size)

            vm.dismissE2EKeyChanged(PEER, costConfirmed = true)
            awaitSettled(vm, chat)
            vm.dismissE2EKeyChanged(PEER, costConfirmed = true)
            awaitSettled(vm, chat)

            assertEquals("a reconnect quoted as 2 transactions paid more on a repeated confirm", 2, chat.seam.submitted.size)
            assertTrue("a repeated confirm left a ZBOOT allowance nothing will use", chat.owed.zbootQuoted[PEER] != true)
            assertFalse("a repeated confirm left an owed KEX", PEER in chat.owed.kexOwed)
        }

    @Test
    fun `a KEX paid within the hour is neither quoted nor paid again`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            reconnect(vm, chat, expectedCount = 2)
            assertEquals("the first reconnect must pay its two messages", 2, chat.seam.submitted.size)

            // Only the relay identity is older than an hour; the key exchange of this key was paid just now.
            assertNotNull("fixture sanity: the ZBOOT's paid record", chat.owed.paidRecords.remove(ZBOOT_SLOT))
            keyChangesAgain(chat)
            reconnect(vm, chat, expectedCount = 1)

            assertEquals("a KEX paid within the hour was paid again", 1, chat.seam.submittedOf("KEX"))
            assertEquals("the one message quoted, our relay identity, was not sent", 2, chat.seam.submittedOf("ZBOOT"))
            assertEquals("quoted 2, then 1", 3, chat.seam.submitted.size)
            assertFalse("no KEX leg: nothing may be owed", PEER in chat.owed.kexOwed)
            assertTrue("the paid ZBOOT did not use up its allowance", chat.owed.zbootQuoted[PEER] != true)
            assertFalse("Pay accepts the new key", chat.keyChanged)
            assertNull("Pay drops the old relay key", chat.relayKey)
        }

    @Test
    fun `a ZBOOT paid within the hour is neither quoted nor paid again`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            reconnect(vm, chat, expectedCount = 2)
            assertEquals("the first reconnect must pay its two messages", 2, chat.seam.submitted.size)

            assertNotNull("fixture sanity: the KEX's paid record", chat.owed.paidRecords.remove(KEX_SLOT))
            keyChangesAgain(chat)
            reconnect(vm, chat, expectedCount = 1)

            assertEquals("the one message quoted, our key exchange, was not sent", 2, chat.seam.submittedOf("KEX"))
            assertEquals("a ZBOOT paid within the hour was paid again", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("quoted 2, then 1", 3, chat.seam.submitted.size)
            assertTrue("no ZBOOT leg: no allowance may be written", chat.owed.zbootQuoted[PEER] != true)
            assertFalse("the paid KEX is still marked owed", PEER in chat.owed.kexOwed)
        }

    @Test
    fun `with both paid within the hour no question is asked and nothing is paid`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            reconnect(vm, chat, expectedCount = 2)
            assertEquals("the first reconnect must pay its two messages", 2, chat.seam.submitted.size)

            keyChangesAgain(chat)
            vm.resetSendState()
            vm.dismissE2EKeyChanged(PEER)
            awaitSettled(vm, chat)

            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse(
                "nothing will be paid (both went out in the last hour), yet a price was asked: $state",
                state is SendMessageState.NeedsPaidActionConfirmation,
            )
            assertFalse("the free × accepts the new key", chat.keyChanged)
            assertNull("the free × drops the old relay key as the paid one does", chat.relayKey)
            assertEquals("nothing more may be paid", 2, chat.seam.submitted.size)
            chat.assertNothingOwed("after a free accept")
        }

    @Test
    fun `Pay reads the plan again and asks once more when it now costs more than it quoted`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            reconnect(vm, chat, expectedCount = 2)
            assertEquals("the first reconnect must pay its two messages", 2, chat.seam.submitted.size)

            // The question is asked while the KEX is inside its hour (1 message); the hour ends before Pay is tapped.
            assertNotNull("fixture sanity: the ZBOOT's paid record", chat.owed.paidRecords.remove(ZBOOT_SLOT))
            keyChangesAgain(chat)
            vm.resetSendState()
            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAsked(vm, 1)
            chat.owed.paidRecords.clear()
            vm.confirmPaidAction()
            awaitSettled(vm, chat)

            assertEquals("Pay paid more than the 1 message it quoted", 2, chat.seam.submitted.size)
            chat.assertAsked(vm, 2)
            assertTrue("nothing changes until the new price is answered", chat.keyChanged)
            assertEquals("nothing changes until the new price is answered", KeyChangeReconnectChat.OLD_RELAY, chat.relayKey)
            chat.assertNothingOwed("before the new price is answered")

            vm.confirmPaidAction()
            awaitSettled(vm, chat)

            assertEquals("the second question quoted 2 transactions", 4, chat.seam.submitted.size)
            assertEquals(2, chat.seam.submittedOf("KEX"))
            assertEquals(2, chat.seam.submittedOf("ZBOOT"))
            assertFalse("Pay accepts the new key", chat.keyChanged)
        }

    @Test
    fun `with the contact's relay key bound again before our ZBOOT runs it goes free and the allowance is withdrawn`() =
        runBlocking {
            val chat =
                KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()
            val published = Collections.synchronizedList(mutableListOf<String>())

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAsked(vm, 2)
            vm.confirmPaidAction()
            // The KEX's first proposal meets "funds still confirming" and waits for the next block.
            val kexWaits = withTimeoutOrNull(WAIT_MS) { awaitTrue { chat.owed.heights.isWaiting() } } != null
            assertTrue("the reconnect quoted its key exchange and never built it", kexWaits)
            assertEquals("fixture sanity: the KEX waits for a block", 0, chat.seam.submitted.size)
            assertNull("fixture sanity: the old relay key was dropped", chat.relayKey)

            // While the KEX waits, the contact's relay key is bound again and the relays answer.
            chat.relayKey = relayKeyOfContact()
            NostrChatBridge.registerPublisher { text, _ ->
                published += text
                NostrChatBridge.PublishResult(1, "synthetic-zc535-${published.size}")
            }
            chat.owed.heights.advance()
            awaitTrue { chat.seam.submittedOf("KEX") == 1 }
            awaitSettled(vm, chat)

            assertEquals("the reconnect's key exchange was not sent", 1, chat.seam.submittedOf("KEX"))
            assertEquals("our relay identity did not go over the relays", 1, published.count { it.startsWith("ZBOOT|") })
            assertEquals("a ZBOOT the relays delivered was paid on-chain too", 0, chat.seam.submittedOf("ZBOOT"))
            assertEquals("paid below the quote of 2", 1, chat.seam.submitted.size)
            assertTrue("the allowance of a ZBOOT that went free was not withdrawn", chat.owed.zbootQuoted[PEER] != true)
        }

    /** Control, green before and after: ZC-300's path. With an incomplete exchange the KEXACK stays the ZBOOT's trigger. */
    @Test
    fun `control - an incomplete exchange still quotes 2 and pays the KEX, then the ZBOOT after the KEXACK`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitSettled(vm, chat)
            assertEquals("the KEX goes on the confirm", 1, chat.seam.submittedOf("KEX"))
            assertEquals("no ZBOOT before the contact's key is here", 0, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the allowance waits for the KEXACK", true, chat.owed.zbootQuoted[PEER])

            chat.owed.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc535-kexack-01")
            awaitSettled(vm, chat)

            assertEquals("one ZBOOT after the KEXACK", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            assertTrue("the ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
        }

    /**
     * Control, green before and after: the receiving side. The contact's own ZBOOT, signed with the key we hold for it,
     * binds its relay key again after the reconnect dropped it, and a Tunnel message that waited goes over the relay.
     */
    @Test
    fun `control - the contact's signed ZBOOT after the reconnect binds its relay key and the queued Tunnel message goes over the relay`() =
        runBlocking {
            val contactKeys = E2EEncryption.generateKeyPair()
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true, contactKeys = contactKeys)
            val vm = chat.viewModel()
            val publishedTo = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitSettled(vm, chat)
            assertNull("the reconnect drops the old relay key", chat.relayKey)

            // The harness allocates the ViewModel without its constructor; the relay bubble's reply marker is read here.
            AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
            // A Tunnel message typed meanwhile waits for the contact's relay key ("waiting for secure connection").
            queueTunnelPayload(PEER, "synthetic zc535 queued tunnel text")
            NostrChatBridge.registerPublisher { text, to ->
                publishedTo += to to text
                NostrChatBridge.PublishResult(1, "synthetic-zc535-ctl-${publishedTo.size}")
            }
            val announced = relayKeyOfContact()
            val taken = chat.owed.zbootFromContact(vm, contactKeys, announced)
            assertTrue("the contact's ZBOOT, signed with the key we hold, must verify", taken)
            awaitSettled(vm, chat)

            assertEquals("the contact's relay key was not bound again", announced, chat.relayKey)
            assertFalse("the key-change flag is back", chat.keyChanged)
            assertTrue("the queue was not taken", queuedTunnelPayloads()[PEER].isNullOrEmpty())
            assertTrue(
                "the queued Tunnel message did not go over the relay to the contact's key: $publishedTo",
                publishedTo.any { (to, text) -> to == announced && !text.startsWith("ZBOOT|") },
            )
            assertEquals("a queued Tunnel message is never paid on-chain", 0, chat.seam.otherAnswered.get())
        }

    /** Tap ×, read the question ([expectedCount] on-chain messages), Pay, and wait until everything it started is done. */
    private suspend fun reconnect(
        vm: ChatViewModel,
        chat: KeyChangeReconnectChat,
        expectedCount: Int,
    ) {
        vm.resetSendState()
        vm.dismissE2EKeyChanged(PEER)
        awaitIdle(vm)
        chat.assertAsked(vm, expectedCount)
        vm.confirmPaidAction()
        awaitSettled(vm, chat)
    }

    /** The banner is up again: the flag set and a relay key stored, as when the contact's key changes once more. */
    private fun keyChangesAgain(chat: KeyChangeReconnectChat) {
        chat.keyChanged = true
        chat.relayKey = KeyChangeReconnectChat.OLD_RELAY
    }

    /** Everything the last tap started has finished: the screen's jobs, the process-wide sends and their follow-ups. */
    private suspend fun awaitSettled(
        vm: ChatViewModel,
        chat: KeyChangeReconnectChat,
    ) {
        var seen = -1
        while (seen != chat.seam.proposals()) {
            seen = chat.seam.proposals()
            awaitIdle(vm)
            awaitIdle(vm)
        }
    }

    private fun relayKeyOfContact(): String =
        NOSTRIdentity
            .fromSeed(ByteArray(64) { (it + 11).toByte() })
            .publicKey
            .joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun queuedTunnelPayloads(): MutableMap<String, MutableList<Any>> =
        ChatViewModel::class.java
            .getDeclaredField("tunnelPendingPayloads")
            .apply { isAccessible = true }
            .get(null) as MutableMap<String, MutableList<Any>>

    private fun queueTunnelPayload(
        peer: String,
        text: String,
    ) {
        val type = Class.forName(ChatViewModel::class.java.name + "\$Companion\$QueuedTunnelPayload")
        val made =
            type
                .getDeclaredConstructor(String::class.java, String::class.java)
                .apply { isAccessible = true }
                .newInstance("tunnel-wait-synthetic-zc535", text)
        queuedTunnelPayloads().getOrPut(peer) { mutableListOf() }.add(made)
    }
}
