package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-785 (d) (review of 12 Oct, finding 3d) — a withdrawal of a quoted-ZBOOT allowance that the store did not save is
 * written again by every later look until it is saved, also when the look goes through the older withdraw function.
 *
 * A failed commit can still change the live value (Android applies an edit to memory before it reports the write).
 * At 9dc8d5fbf only the sender's own withdraw (withdrawOwnedZbootQuote) writes again in that state. The older
 * withdrawZbootQuote, reached from the chat-request count, the reconnect and the first-message take, reads "not quoted"
 * from the live value, drops the reminder and reports the withdrawal as saved. The allowance is then still on disk: the
 * next process reads it back and a later ZBOOT is paid on it with no question that covers it (17,000 zat; ZC-642).
 *
 * The quote and owed marks are held here in two layers (live and disk); every other call goes to [ResetOwedChat].
 * Money: the cases count proposals against submits; a withdrawn allowance pays nothing, before and after the restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnUnsavedZbootWithdrawalIsWrittenAgainByEveryLookTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
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
    fun `an unsaved withdrawal is written again by the next look although the live flag already reads withdrawn`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val quotes = TwoLayerQuotes(chat.store).apply { quote(PEER) }
            val vm = quotes.viewModel(chat)
            withdrawWithAFailedCommit(chat, quotes, vm)

            // The next look at this allowance: the chat-request banner counts what Accept can pay.
            vm.chatRequestAcceptTransactions(PEER)

            assertFalse("the unsaved withdrawal was forgotten: the allowance is still on disk", PEER in quotes.disk)
            assertEquals("the look must write the withdrawal exactly once more", 2, quotes.withdrawals.get())

            vm.chatRequestAcceptTransactions(PEER)
            assertEquals("a saved withdrawal is not written again", 2, quotes.withdrawals.get())
            ZbootSetup.kill(vm)
        }

    @Test
    fun `after a restart the withdrawn allowance is not back and pays no ZBOOT`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val contact = E2EEncryption.generateKeyPair()
            chat.peerKeys[PEER] = contact.publicKey
            val quotes = TwoLayerQuotes(chat.store).apply { quote(PEER) }
            val first = quotes.viewModel(chat)
            withdrawWithAFailedCommit(chat, quotes, first)
            first.chatRequestAcceptTransactions(PEER)
            ZbootSetup.kill(first)

            // The next process reads what is on disk and has read its history. Our identity was re-armed since (nothing is
            // recorded as delivered), and the contact's own ZBOOT arrives: in a Shielded chat our reply goes on-chain only
            // against an allowance.
            quotes.restart()
            AutoSpendVm.setHistoryRead(true)
            chat.store.setSentNostrBootPubkey(PEER, null)
            val next = quotes.viewModel(chat)
            val recorder = SendStateRecorder(next)
            assertTrue(
                "fixture sanity: the contact's ZBOOT verifies",
                chat.zbootFromContact(next, contact, announcedRelayKey = "ab".repeat(32)),
            )
            awaitIdle(next)

            assertEquals("a ZBOOT was built on an allowance that had been withdrawn", 0, chat.seam.zbootProposals.get())
            assertEquals("a ZBOOT was paid on an allowance that had been withdrawn", 0, chat.seam.submitted.size)
            assertFalse("the withdrawn allowance is back on disk after the restart", PEER in quotes.disk)
            assertEquals(
                "fixture sanity: the contact's ZBOOT was taken, and our unquoted reply was put off (the marker stays re-armed)",
                null,
                chat.store.getSentNostrBootPubkey(PEER),
            )
            recorder.assertNoHandshakeConsentQuestion("a Shielded chat's unquoted reply ZBOOT must not ask either")
            recorder.stop()
            ZbootSetup.kill(next)
        }

    @Test
    fun `control - an allowance that stands is counted and left alone by a look`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val quotes = TwoLayerQuotes(chat.store).apply { quote(PEER) }
            val vm = quotes.viewModel(chat)

            assertEquals("Accept can pay the key reply and the quoted ZBOOT", 2, vm.chatRequestAcceptTransactions(PEER))
            assertTrue("a look withdrew an allowance that stands", PEER in quotes.disk)
            assertTrue(quotes.store.isZbootQuoted(PEER))
            assertEquals(0, quotes.withdrawals.get())
            ZbootSetup.kill(vm)
        }

    @Test
    fun `while the store keeps refusing, every look tries again and the allowance is never counted as payable`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val quotes = TwoLayerQuotes(chat.store).apply { quote(PEER) }
            val vm = quotes.viewModel(chat)
            withdrawWithAFailedCommit(chat, quotes, vm)
            quotes.commitsFail = true

            assertEquals("an allowance whose withdrawal is not saved yet was counted as payable", 1, vm.chatRequestAcceptTransactions(PEER))
            assertTrue("fixture sanity: the store still refuses, so the allowance is still on disk", PEER in quotes.disk)
            quotes.commitsFail = false
            assertEquals(1, vm.chatRequestAcceptTransactions(PEER))
            assertFalse("the withdrawal must be saved once the store works again", PEER in quotes.disk)
            assertEquals(0, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    /**
     * Our current relay identity is recorded as delivered, so the ZBOOT the allowance counted needs no payment and the
     * sender withdraws the allowance (ZC-536); that one commit fails after it changed the live value.
     */
    private suspend fun withdrawWithAFailedCommit(
        chat: ResetOwedChat,
        quotes: TwoLayerQuotes,
        vm: ChatViewModel,
    ) {
        chat.store.setSentNostrBootPubkey(PEER, ZbootSetup.currentIdentity(vm))
        quotes.commitsFail = true
        AutoSpendVm.sendNostrBoot(vm, PEER)
        awaitIdle(vm)
        quotes.commitsFail = false
        assertEquals("fixture sanity: the sender tried to withdraw the allowance once", 1, quotes.withdrawals.get())
        assertFalse("fixture sanity: the failed commit still changed the live flag", quotes.store.isZbootQuoted(PEER))
        assertTrue("fixture sanity: the failed commit left the allowance on disk", PEER in quotes.disk)
        assertEquals("fixture sanity: a delivered identity is not paid again", 0, chat.seam.zbootProposals.get())
    }

    /**
     * The quoted-ZBOOT allowance and its owed mark as a store keeps them: [live] is what this process reads, [disk] what
     * the next one will. While [commitsFail], a write changes the live values and reports "not saved"; the disk keeps
     * what it had. [withdrawals] counts every write that removes the allowance.
     */
    private class TwoLayerQuotes(
        delegate: ZchatPreferences,
    ) {
        val live: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val disk: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val liveOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val diskOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val withdrawals = AtomicInteger(0)

        @Volatile var commitsFail = false

        fun quote(peer: String) {
            live += peer
            disk += peer
        }

        /** The next process: it knows only what reached the disk. */
        fun restart() {
            live.clear()
            live += disk
            liveOwed.clear()
            liveOwed += diskOwed
        }

        private fun commit(change: (quoted: MutableSet<String>, owed: MutableSet<String>) -> Unit): Boolean {
            change(live, liveOwed)
            if (commitsFail) return false
            change(disk, diskOwed)
            return true
        }

        val store: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                fun peer() = args!![0] as String
                when (method.name) {
                    "isZbootQuoted" -> peer() in live
                    "setZbootQuoted" ->
                        if (args!![1] as Boolean) {
                            commit { quoted, _ -> quoted += peer() }
                        } else {
                            withdrawals.incrementAndGet()
                            commit { quoted, owed ->
                                quoted -= peer()
                                owed -= peer()
                            }
                        }
                    "restoreQuotedZbootAsOwed" ->
                        commit { quoted, owed ->
                            quoted += peer()
                            owed += peer()
                        }
                    "isZbootOwed" -> peer() in liveOwed
                    "setZbootOwed" -> commit { _, owed -> if (args!![1] as Boolean) owed += peer() else owed -= peer() }
                    "getZbootOwedPeers" -> liveOwed.toSet()
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "Zc785TwoLayerQuoteStore"
                    else ->
                        try {
                            method.invoke(delegate, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        suspend fun viewModel(chat: ResetOwedChat): ChatViewModel =
            chat.viewModel().also {
                AutoSpendVm.setField(it, "zchatPreferences", store)
                AutoSpendVm.setField(it, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
            }
    }

    private companion object {
        const val PEER = "usyntheticzc785withdrawal001"
    }
}
