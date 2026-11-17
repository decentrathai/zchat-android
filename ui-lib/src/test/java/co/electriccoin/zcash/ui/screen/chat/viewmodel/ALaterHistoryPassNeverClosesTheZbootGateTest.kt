package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-785 (a) (review of 12 Oct, finding 3a) — the "history was read" gate of the automatic relay announcements is a
 * latch: once a complete pass has read the whole history, a later pass never closes it again.
 *
 * At 9dc8d5fbf every pass that does not end "ready" switches the gate off for EVERY contact: a pass with one memo it
 * could not read, and a pass of one screen that ends while another screen's pass still holds a contact. Nothing
 * schedules another pass, so every due ZBOOT (under the standing consent or on a confirmed quote) waits for the next
 * change of the transaction list. Wanted: the gate stays open, and only the contact a pass has not settled keeps
 * waiting (the per-contact check the sender already makes). Before the first complete pass nothing changes: the gate
 * stays closed (control).
 *
 * Money: a paid ZBOOT is counted as proposals against submits on the real use case and repository; no case pays a ZBOOT
 * twice, and none is paid without the standing consent or its quote.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ALaterHistoryPassNeverClosesTheZbootGateTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        // An earlier complete pass of this process read the whole history.
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(ownHistoryReadBefore)
        Dispatchers.resetMain()
        ZbootSetup.resetProcess()
    }

    @Test
    fun `a later pass with one unreadable memo leaves the gate open and a due ZBOOT is paid on the next block`() =
        runBlocking {
            val chat = tunnelChat()
            val vm = chat.viewModel()

            ZbootSetup.historyPass(vm, PEER, listOf("synthetic memo this pass cannot read"), failingReads = setOf(0))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

            chat.tick(vm)
            awaitIdle(vm)
            assertEquals(
                "one unreadable memo in a later pass stopped the due ZBOOT of a contact the pass never touched",
                1,
                chat.seam.zbootProposals.get(),
            )
            assertEquals("the due ZBOOT of a contact the pass never touched was not paid", 1, chat.seam.submittedOf("ZBOOT"))
            assertTrue("one unreadable memo in a later pass closed the gate for every contact", ZbootSetup.historyRead())

            chat.tick(vm)
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("a later block paid the same ZBOOT again", 1, chat.seam.zbootProposals.get())
            assertEquals(1, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `a pass that ends while another screen's pass holds one contact leaves the gate open for the others`() =
        runBlocking {
            val chat = tunnelChat()
            // PEER's quoted ZBOOT is owed, so every pass holds PEER until it has read its rows; OTHER has nothing owed.
            chat.tunnelPeers -= PEER
            assertTrue(chat.store.setZbootQuoted(PEER, true))
            assertTrue(chat.store.setZbootOwed(PEER, true))
            ZbootSetup.addTunnelContact(chat, OTHER)
            val first = chat.viewModel()
            val otherScreen = chat.viewModel()
            val current = ZbootSetup.currentIdentity(first)
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scan =
                launch {
                    ZbootSetup.historyPass(first, PEER, listOf(ZbootSetup.boot(ZbootSetup.EARLIER_IDENTITY))) {
                        parked.complete(Unit)
                        release.await()
                    }
                }
            try {
                withTimeout(HandshakeHarness.TIMEOUT_MS) { parked.await() }
                ZbootSetup.historyPass(otherScreen, PEER, emptyList())
                AutoSpendVm.setField(otherScreen, "synchronizerProvider", chat.heights.provider)

                repeat(4) {
                    chat.tick(otherScreen)
                    awaitIdle(otherScreen)
                }
                assertEquals(
                    "a pass that overlapped another screen's stopped the other contact's due ZBOOT (paid exactly once)",
                    1,
                    chat.seam.submittedOf("ZBOOT"),
                )
                assertEquals(1, chat.seam.zbootProposals.get())
                assertTrue("a pass that overlapped another screen's closed the gate for every contact", ZbootSetup.historyRead())
                assertEquals("the paid ZBOOT must be the other contact's", current, chat.store.getSentNostrBootPubkey(OTHER))
                assertTrue("the contact the first pass still holds was paid before its rows were read", PEER in chat.zbootOwed)
                assertTrue(chat.store.isZbootQuoted(PEER))
            } finally {
                release.complete(Unit)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { scan.join() }
            }

            // The first pass read only an earlier identity for PEER: its quoted ZBOOT is still owed and is now paid, once.
            AutoSpendVm.setField(first, "synchronizerProvider", chat.heights.provider)
            repeat(3) {
                chat.tick(first)
                awaitIdle(first)
            }
            assertEquals("the settled contact's quoted ZBOOT was not paid exactly once", 2, chat.seam.submittedOf("ZBOOT"))
            assertEquals(2, chat.seam.submitted.size)
            assertFalse(PEER in chat.zbootOwed)
            assertFalse(chat.store.isZbootQuoted(PEER))
            ZbootSetup.kill(first, otherScreen)
        }

    @Test
    fun `control - before the first complete pass a pass with an unreadable memo opens nothing, and a complete pass does`() =
        runBlocking {
            AutoSpendVm.setHistoryRead(false)
            val chat = tunnelChat()
            val vm = chat.viewModel()

            ZbootSetup.historyPass(vm, PEER, listOf("synthetic memo this pass cannot read"), failingReads = setOf(0))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            chat.tick(vm)
            awaitIdle(vm)
            assertFalse("a pass that did not read the whole history opened the gate", ZbootSetup.historyRead())
            assertEquals("an automatic ZBOOT was paid before the history was read", 0, chat.seam.zbootProposals.get())

            ZbootSetup.historyPass(vm, PEER, emptyList())
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            assertTrue("a complete pass must open the gate", ZbootSetup.historyRead())
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals(1, chat.seam.submittedOf("ZBOOT"))
            assertEquals(1, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    /** A Tunnel chat with keys both ways, no relay key, the standing consent given, our relay identity never delivered. */
    private fun tunnelChat(): ResetOwedChat =
        ResetOwedChat(PEER, consent = true, mode = ConversationMode.TUNNEL).also { chat ->
            chat.store.setSentNostrBootPubkey(PEER, null)
            chat.tunnelPeers += PEER
        }

    private companion object {
        const val PEER = "usyntheticzc785gatelatch0001"
        const val OTHER = "usyntheticzc785gatelatch0002"
    }
}
