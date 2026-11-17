package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * W-72 (register ZC-852), SPEC_DECISIONS D2, independent review of 3426ade7d finding 1 (P1): the history pass runs off the
 * main thread and handleKEXMessage only queues its work on the main thread (viewModelScope), while the pass records this key
 * store's first complete history read (setLegacyHistoryRead) at its own end. So in the first complete pass after a restore
 * the read is recorded before the queued key offers ask whether it was, and a restored key from a memo above the upgrade
 * height is stored without its pre-update mark. Wanted: whether the offer came before the complete read is decided when the
 * pass hands the offer over, not when the queue runs it.
 *
 * Main here is a real queue (StandardTestDispatcher): nothing queued runs until the scheduler is advanced, as on a phone
 * while the pass is still running. Today the key is stored unmarked. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyKeyMarkSurvivesTheQueuedOfferTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        ChainTiming.current = ChainTiming.MAINNET
    }

    @After
    fun tearDown() {
        ChainTiming.current = ChainTiming.MAINNET
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(4) a key offer the pass handed over before it recorded the complete read is marked when the queue runs it`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72401), firstContact = true)
            chat.maps.legacyHistoryRead = false
            val vm = chat.viewModel()
            scheduler.advanceUntilIdle()
            val contact = E2EEncryption.generateKeyPair()

            // The pass hands the restored key exchange over (queued on the main thread) ...
            handleKex(vm, kexFrom(chat.peer, contact, "ZC72Q401"), "synthetic-w72-queued", ChainTiming.MAINNET_UPGRADE_HEIGHT + 500)
            assertNull("fixture: the offer ran at once, so Main is not a queue here", chat.prefs.e2ePeerKey[chat.peer])
            // ... and records the complete read at its end, before the main thread runs the queue.
            chat.store.setLegacyHistoryRead()
            scheduler.advanceUntilIdle()
            awaitIdle(vm)

            assertEquals("fixture: the queued offer did not store the key", contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertEquals(
                "a restored key the pass handed over before the read went unmarked",
                contact.publicKey,
                chat.maps.legacy[chat.peer]
            )
        }

    @Test
    fun `(4) control - an offer handed over after the complete read is not marked`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72402), firstContact = true)
            chat.maps.legacyHistoryRead = true
            val vm = chat.viewModel()
            scheduler.advanceUntilIdle()
            val contact = E2EEncryption.generateKeyPair()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72Q402"), "synthetic-w72-after", ChainTiming.MAINNET_UPGRADE_HEIGHT + 500)
            scheduler.advanceUntilIdle()
            awaitIdle(vm)

            assertEquals("fixture: the key is stored", contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertNull("a key handed over after the complete read got a mark", chat.maps.legacy[chat.peer])
        }

    /** handleKEXMessage as the history scan calls it: the memo's mined height, the scan allowed to act. */
    private fun handleKex(
        vm: ChatViewModel,
        memo: String,
        txId: String,
        minedHeight: Long,
    ) {
        val method =
            ChatViewModel::class.java.getDeclaredMethod(
                "handleKEXMessage",
                String::class.java,
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                Long::class.javaObjectType,
            )
        method.isAccessible = true
        try {
            method.invoke(vm, memo, HandshakeHarness.SELF, txId, true, minedHeight)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
