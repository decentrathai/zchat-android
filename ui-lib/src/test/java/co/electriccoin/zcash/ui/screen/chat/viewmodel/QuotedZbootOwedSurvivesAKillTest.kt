package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
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
import java.util.Collections

/**
 * ZC-534 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §2, part 3) — the ZBOOT a confirmed Reset or key-change
 * reconnect quoted is never sent when the process ends while it waits for a block.
 *
 * During that wait the durable state is: the delivered-identity marker set (it means "delivered or in flight"), the
 * allowance standing, no paid record. A kill runs no code, so the next process finds a marker that says "delivered" and
 * no trigger sends the ZBOOT: a Shielded chat has no automatic trigger at all, and a Tunnel chat's per-block retry only
 * lists chats whose marker is empty.
 *
 * Must hold: the attempt that provably sent nothing records the ZBOOT as owed (a durable mark that never outlives its
 * allowance); the next process pays it on a later block, exactly once, on the confirmed quote, with no question, in a
 * Shielded chat and in a Tunnel chat. A Reconnect tap that pays it first leaves nothing for the block retry; a free
 * delivery over the relays withdraws the mark and the allowance and pays nothing.
 *
 * Drives the real ViewModel through the real use case and repository ([ResetOwedChat], [KeyChangeReconnectChat]). Every
 * address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotedZbootOwedSurvivesAKillTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc534zbootkilled1"
        const val RELAY_KEY = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
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
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `a quoted ZBOOT interrupted by the end of the process is paid once by the next process in a Shielded chat`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val first = chat.viewModel()
            confirmResetUntilItsZbootWaits(chat, first)

            ResetOwedChat.kill(first)
            assertEquals("fixture sanity: the kill came before any ZBOOT went", 0, chat.seam.submittedOf("ZBOOT"))

            val next = chat.viewModel()
            val recorder = SendStateRecorder(next)
            chat.tick(next)
            awaitIdle(next)

            assertEquals(
                "the ZBOOT a confirmed Reset quoted was never sent after the process ended while it waited for a block",
                1,
                chat.seam.submittedOf("ZBOOT"),
            )
            assertTrue("the ZBOOT did not use up the Reset's allowance", chat.zbootQuoted[PEER] != true)
            assertFalse("the paid ZBOOT is still marked owed", PEER in chat.zbootOwed)

            chat.tick(next)
            awaitIdle(next)
            chat.openChat(next)
            awaitIdle(next)
            chat.tick(next)
            awaitIdle(next)

            assertEquals("the quoted ZBOOT was paid a second time", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("the ZBOOT the Reset quoted was asked about again after the restart")
            assertEquals("the one-off Reset was turned into the standing handshake consent", 0, chat.consentWrites.get())
            recorder.stop()
        }

    @Test
    fun `a quoted ZBOOT interrupted by the end of the process is paid once by the next process in a Tunnel chat`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            chat.owed.tunnelPeers += PEER
            val first = chat.viewModel()
            first.dismissE2EKeyChanged(PEER)
            awaitIdle(first)
            chat.assertAskedFirst(first)
            first.confirmPaidAction()
            awaitIdle(first)
            assertEquals("fixture sanity: the reconnect's KEX went", 1, chat.seam.submittedOf("KEX"))
            chat.owed.kexAckFromContact(first, E2EEncryption.generateKeyPair(), txId = "synthetic-zc534-kexack-11")
            awaitTrue { chat.owed.heights.isWaiting() }
            assertEquals("fixture sanity: no ZBOOT went yet", 0, chat.seam.submittedOf("ZBOOT"))

            ResetOwedChat.kill(first)

            val next = chat.viewModel()
            val recorder = SendStateRecorder(next)
            chat.owed.tick(next)
            awaitIdle(next)

            assertEquals(
                "the ZBOOT a confirmed reconnect quoted was never sent after the process ended while it waited for a block",
                1,
                chat.seam.submittedOf("ZBOOT"),
            )
            assertTrue("the ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
            assertFalse("the paid ZBOOT is still marked owed", PEER in chat.owed.zbootOwed)

            chat.owed.tick(next)
            awaitIdle(next)
            chat.owed.tick(next)
            awaitIdle(next)

            assertEquals("the quoted ZBOOT was paid a second time", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("the ZBOOT the reconnect quoted was asked about again after the restart")
            assertEquals("the one-off reconnect was turned into the standing consent", 0, chat.owed.consentWrites.get())
            recorder.stop()
        }

    /** Never twice: the tap's ZBOOT uses up the allowance, and the owed mark goes with it. Green before and after. */
    @Test
    fun `a Reconnect tap that pays the owed ZBOOT first leaves nothing for the block retry`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = true, failFirstZbootWithStillConfirming = true)
            val first = chat.viewModel()
            confirmResetUntilItsZbootWaits(chat, first)
            ResetOwedChat.kill(first)

            val next = chat.viewModel()
            next.reSendOurIdentity(PEER)
            awaitIdle(next)
            assertEquals("fixture sanity: the Reconnect tap paid the ZBOOT", 1, chat.seam.submittedOf("ZBOOT"))

            chat.tick(next)
            awaitIdle(next)
            chat.tick(next)
            awaitIdle(next)

            assertEquals("the block retry paid the ZBOOT the Reconnect tap had already paid", 1, chat.seam.submittedOf("ZBOOT"))
            assertTrue("the allowance outlived the ZBOOT that used it", chat.zbootQuoted[PEER] != true)
            assertFalse("the owed mark outlived its allowance", PEER in chat.zbootOwed)
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
        }

    @Test
    fun `a ZBOOT delivered over the relays while owed withdraws the owed mark and the allowance`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val first = chat.viewModel()
            confirmResetUntilItsZbootWaits(chat, first)
            ResetOwedChat.kill(first)

            // Meanwhile the contact's relay key became known and the relays answer: the ZBOOT can go for free.
            chat.reset.peerRelayKey = RELAY_KEY
            val published = Collections.synchronizedList(mutableListOf<String>())
            NostrChatBridge.registerPublisher { plaintext, _ ->
                published += plaintext
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-zc534-rumor-${published.size}")
            }
            val next = chat.viewModel()
            chat.tick(next)
            awaitIdle(next)

            assertEquals(
                "the owed ZBOOT was not delivered over the relays by the next block",
                1,
                published.count { it.startsWith("ZBOOT|") },
            )
            assertEquals("a ZBOOT the relays took was also paid on-chain", 0, chat.seam.submittedOf("ZBOOT"))
            assertTrue("the allowance outlived the ZBOOT the relays delivered", chat.zbootQuoted[PEER] != true)
            assertFalse("the owed mark outlived the delivery", PEER in chat.zbootOwed)

            chat.tick(next)
            awaitIdle(next)
            assertEquals("the delivered ZBOOT was published again", 1, published.count { it.startsWith("ZBOOT|") })
            assertEquals("only the Reset's KEX was paid on-chain", 1, chat.seam.submitted.size)
        }

    private suspend fun confirmResetUntilItsZbootWaits(
        chat: ResetOwedChat,
        vm: ChatViewModel,
    ) {
        vm.resetSecureSession(PEER)
        awaitIdle(vm)
        chat.reset.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitIdle(vm)
        assertEquals("fixture sanity: the Reset's KEX went", 1, chat.seam.submittedOf("KEX"))
        chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc534-kexack-10")
        awaitTrue { chat.heights.isWaiting() }
        assertEquals("fixture sanity: the ZBOOT met the still-confirming wait", 1, chat.seam.zbootProposals.get())
        assertEquals("fixture sanity: no ZBOOT went yet", 0, chat.seam.submittedOf("ZBOOT"))
        assertEquals("fixture sanity: the allowance stands while the ZBOOT waits", true, chat.zbootQuoted[PEER])
    }
}
