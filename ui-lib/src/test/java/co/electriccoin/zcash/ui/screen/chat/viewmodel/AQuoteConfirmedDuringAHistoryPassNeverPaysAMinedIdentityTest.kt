package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-789 (verification of 12 Oct, section 2; a regression of 7d1d183ab) — a price question confirmed while a history
 * pass runs never ends in a payment for a relay identity that pass then reads as mined.
 *
 * After a restore the delivered-identity markers are empty and the first pass has not read the history yet. A pass
 * snapshots the order of the confirmed quotes when it begins. A mode switch confirmed meanwhile installs a newer quote
 * for the contact; at aa6e55cb2 the pass then skips that contact's mined row whole, also the restore of the empty
 * marker, still ends as settled and opens the gate. The quoted sender finds an empty marker and pays 17,000 zat for an
 * identity that is already on chain (inside the question the user accepted, so redundant, not unquoted). Wanted: the
 * pass skips such a row only for a confirmed Reset or key-change reconnect (their ZBOOT is sent again on purpose). For
 * any other quote newer than the pass the marker is restored, the end of the pass leaves our current identity as the
 * delivered one, and the allowance of the ZBOOT that needs no payment is withdrawn.
 *
 * Money: every case counts proposals against submits on the real use case and repository. The switch pays its mode
 * change (1 of the 2 transactions quoted) and nothing more; the Reset and the reconnect pay exactly their 2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AQuoteConfirmedDuringAHistoryPassNeverPaysAMinedIdentityTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        // After a restore: no pass of this process has read the history yet.
        AutoSpendVm.setHistoryRead(false)
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
    fun `a mode switch confirmed before the pass reads the contact's mined row pays no ZBOOT and its allowance is withdrawn`() =
        runBlocking { switchConfirmedDuringThePass(this, earlierIdentityFirst = false) }

    @Test
    fun `the same with an earlier identity mined before our current one - the marker ends as the current identity`() =
        runBlocking { switchConfirmedDuringThePass(this, earlierIdentityFirst = true) }

    private suspend fun switchConfirmedDuringThePass(
        scope: CoroutineScope,
        earlierIdentityFirst: Boolean,
    ) {
        val chat = restoredChat()
        val store = switchable(chat)
        val vm = viewModel(chat, store)
        val current = ZbootSetup.currentIdentity(vm)
        val rows = listOfNotNull(ZbootSetup.EARLIER_IDENTITY.takeIf { earlierIdentityFirst }, current).map(ZbootSetup::boot)
        val pass = ParkedPass()
        try {
            pass.start(scope, vm, rows)
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

            vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertEquals("nothing may be paid before the question", 0, chat.seam.proposals())
            assertModeQuestion(vm, transactions = 2)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the confirmed switch changed the mode", ConversationMode.TUNNEL, chat.reset.mode)
            assertEquals("fixture sanity: the confirmed switch paid its mode change", 1, chat.seam.submitted.size)
            assertTrue("fixture sanity: the confirmed switch wrote the allowance of its quoted ZBOOT", store.isZbootQuoted(PEER))
            assertEquals("fixture sanity: no ZBOOT is built before the history is read", 0, chat.seam.zbootProposals.get())
        } finally {
            pass.finish()
        }
        AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
        assertTrue("fixture sanity: the complete pass opened the gate", ZbootSetup.historyRead())

        repeat(2) {
            chat.tick(vm)
            awaitIdle(vm)
        }
        vm.ensureNostrBootstrapSent(PEER)
        awaitIdle(vm)

        assertEquals("a ZBOOT was built for an identity that is already on chain", 0, chat.seam.zbootProposals.get())
        assertEquals("17,000 zat were paid for an identity that is already on chain", 0, chat.seam.submittedOf("ZBOOT"))
        assertEquals("only the mode change of the two quoted transactions may be paid", 1, chat.seam.submitted.size)
        assertTrue("our mined current identity must end as the delivered one", store.getSentNostrBootPubkey(PEER) == current)
        assertFalse("the allowance of a ZBOOT that needs no payment was left standing", store.isZbootQuoted(PEER))
        assertFalse(PEER in chat.zbootOwed)
        ZbootSetup.kill(vm)
    }

    @Test
    fun `control - a Reset confirmed during the pass still pays its key exchange and its ZBOOT exactly once`() =
        runBlocking {
            val chat = restoredChat()
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)
            val pass = ParkedPass()
            try {
                pass.start(this, vm, listOf(ZbootSetup.boot(current)))
                AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

                vm.resetSecureSession(PEER)
                awaitIdle(vm)
                chat.reset.assertAskedFirst(vm)
                vm.confirmPaidAction()
                awaitIdle(vm)
                assertEquals("fixture sanity: the confirmed Reset paid its KEX", 1, chat.seam.submittedOf("KEX"))
            } finally {
                pass.finish()
            }
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            assertTrue("a pass restored the marker a confirmed Reset had cleared", chat.store.getSentNostrBootPubkey(PEER) == null)
            assertTrue("a pass withdrew the allowance of a confirmed Reset", chat.store.isZbootQuoted(PEER))

            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc789-reset-key-reply")
            awaitSettled(vm, chat.seam)
            repeat(2) {
                chat.tick(vm)
                awaitIdle(vm)
            }
            assertEquals("the confirmed Reset's ZBOOT must still be paid, once", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the confirmed Reset pays exactly the two transactions it quoted", 2, chat.seam.submitted.size)
            assertFalse(chat.store.isZbootQuoted(PEER))
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - a key-change reconnect confirmed during the pass still pays its key exchange and its ZBOOT exactly once`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.owed.store.setSentNostrBootPubkey(PEER, null)
            chat.owed.tunnelPeers += PEER
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)
            val pass = ParkedPass()
            try {
                pass.start(this, vm, listOf(ZbootSetup.boot(current)))
                AutoSpendVm.setField(vm, "synchronizerProvider", chat.owed.heights.provider)

                vm.dismissE2EKeyChanged(PEER)
                awaitIdle(vm)
                chat.assertAskedFirst(vm)
                vm.confirmPaidAction()
                awaitSettled(vm, chat.seam)
                assertEquals("fixture sanity: the confirmed reconnect paid its KEX", 1, chat.seam.submittedOf("KEX"))
            } finally {
                pass.finish()
            }
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.owed.heights.provider)
            assertTrue("a pass withdrew the allowance of a confirmed reconnect", chat.owed.store.isZbootQuoted(PEER))

            repeat(3) {
                chat.owed.tick(vm)
                awaitIdle(vm)
            }
            assertEquals("the confirmed reconnect's ZBOOT must still be paid, once", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the confirmed reconnect pays exactly the two transactions it quoted", 2, chat.seam.submitted.size)
            assertFalse(chat.owed.store.isZbootQuoted(PEER))
            ZbootSetup.kill(vm)
        }

    /** A complete history pass of one screen, parked before it reads its first memo until [finish]. */
    private class ParkedPass {
        private val parked = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()
        private var job: Job? = null

        suspend fun start(
            scope: CoroutineScope,
            vm: ChatViewModel,
            memos: List<String>,
        ) {
            job =
                scope.launch {
                    ZbootSetup.historyPass(vm, PEER, memos) { index ->
                        if (index == 0) {
                            parked.complete(Unit)
                            release.await()
                        }
                    }
                }
            withTimeout(HandshakeHarness.TIMEOUT_MS) { parked.await() }
        }

        suspend fun finish() {
            release.complete(Unit)
            job?.let { withTimeout(HandshakeHarness.TIMEOUT_MS) { it.join() } }
        }
    }

    private fun assertModeQuestion(
        vm: ChatViewModel,
        transactions: Int,
    ) {
        val state =
            HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
                ?: throw AssertionError("the switch must ask its price first, was ${HandshakeHarness.sendMessageState(vm)}")
        assertEquals("MODE_CHANGE", state.confirmation.action.name)
        assertEquals("the mode change and our ZBOOT", transactions, state.confirmation.onChainMessages)
        assertTrue("the question must say it also pays for the chat setup", state.confirmation.includesChatSetup)
    }

    /** Join the sends a confirm or a key reply started until no new proposal appears. */
    private suspend fun awaitSettled(
        vm: ChatViewModel,
        seam: ResetKexSeam,
    ) {
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            var seen = -1
            while (seen != seam.proposals()) {
                seen = seam.proposals()
                awaitIdle(vm)
                awaitIdle(vm)
            }
        }
    }

    /** A Shielded chat after a restore: keys both ways, no relay key, nothing recorded as delivered, nothing quoted. */
    private fun restoredChat(): ResetOwedChat =
        ResetOwedChat(PEER, consent = false).also { chat ->
            chat.store.setSentNostrBootPubkey(PEER, null)
            assertEquals(null, chat.reset.peerRelayKey)
        }

    /** [chat]'s store with a mode the switch can really change: a Tunnel chat is listed for the per-block retry. */
    private fun switchable(chat: ResetOwedChat): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "setConversationMode" -> {
                    (args!![1] as ConversationMode?)?.let { mode ->
                        chat.reset.mode = mode
                        if (mode == ConversationMode.TUNNEL) chat.tunnelPeers += PEER else chat.tunnelPeers -= PEER
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "Zc789SwitchableStore"
                else ->
                    try {
                        method.invoke(chat.store, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private suspend fun viewModel(
        chat: ResetOwedChat,
        store: ZchatPreferences,
    ): ChatViewModel =
        chat.viewModel().also {
            AutoSpendVm.setField(it, "zchatPreferences", store)
            AutoSpendVm.setField(it, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
        }

    private companion object {
        const val PEER = "usyntheticzc789quoteinpass01"
    }
}
