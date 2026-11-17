package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1462 (OT213b owner finding, Fold, 2 Nov 2026; lane L3 hypothesis H1, plan critique S4): after the first scan of a
 * friend's ZChat code, New Chat showed the ZEC cost of a paid send although the code carries the friend's relay key and
 * the chat could start free in Open.
 *
 * `ZchatComposeVM.syncModeForRecipient` at 669cf19e1 resolves `stored ?: newChatDefault`: any mode already stored for the
 * address wins over "the key is now known, so Open", even one the user never chose (not explicit). The free Open card
 * and the cost card switch on `isFreeOpenSend = selectedMode == OPEN && key known`, so a stale stored Tunnel shows the
 * cost.
 *
 * What it must be: when a scan or a paste has just bound the friend's relay key and the stored mode is not the user's
 * explicit choice, New Chat selects Open and the send is the free Open send. An explicit choice is kept (control).
 * Harness: the M1 [RelayKeyStore] and a real New Chat screen. Synthetic addresses and keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannedCodeMakesNewChatOpenOverStaleModeTest {
    private val composeVms = mutableListOf<ZchatComposeVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        composeVms.forEach(RelayKeyBinding::close)
        composeVms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a scanned code makes New Chat Open over a stale non-explicit stored Tunnel`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(14_621)
            val store = RelayKeyStore().apply { modes[peer] = ConversationMode.TUNNEL }
            val prefill = PrefillZchatUseCase()
            val vm = composeVm(store, prefillZchat = prefill).also { composeVms += it }

            scan(store, prefill, peer, relayKey(14_621))
            awaitRecipient(vm, peer)

            val ready = vm.ready()
            assertEquals(peer, ready.recipientAddress, "fixture sanity: the scan set the recipient")
            assertEquals(
                ConversationMode.OPEN,
                ready.selectedMode,
                "after the scan bound the relay key, a stale non-explicit stored mode still beats Open (cost shown for a free chat)",
            )
            assertTrue(ready.isFreeOpenSend, "after the scan the send is not the free Open send")
        }

    @Test
    fun `a pasted code makes New Chat Open over a stale non-explicit stored Tunnel`() {
        val peer = SyntheticUnifiedAddress.mainnet(14_622)
        val store = RelayKeyStore().apply { modes[peer] = ConversationMode.TUNNEL }
        val vm = composeVm(store).also { composeVms += it }

        vm.ready().onRecipientChange(code(peer, relayKey(14_622)))

        val ready = vm.ready()
        assertEquals(peer, ready.recipientAddress, "fixture sanity: the paste set the recipient")
        assertEquals(relayKey(14_622), store.pubkeys[peer], "fixture sanity: the paste bound the relay key")
        assertEquals(
            ConversationMode.OPEN,
            ready.selectedMode,
            "after the paste bound the relay key, a stale non-explicit stored mode still beats Open",
        )
        assertTrue(ready.isFreeOpenSend, "after the paste the send is not the free Open send")
    }

    /** Control, green before and after: with nothing stored a scanned code gives Open. */
    @Test
    fun `control - a scanned code with no stored mode gives Open`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(14_623)
            val store = RelayKeyStore()
            val prefill = PrefillZchatUseCase()
            val vm = composeVm(store, prefillZchat = prefill).also { composeVms += it }

            scan(store, prefill, peer, relayKey(14_623))
            awaitRecipient(vm, peer)

            assertEquals(ConversationMode.OPEN, vm.ready().selectedMode)
            assertTrue(vm.ready().isFreeOpenSend)
        }

    /** Control, green before and after: the user's explicit choice is kept. */
    @Test
    fun `control - an explicit stored Tunnel is kept after a scan`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(14_624)
            val store =
                RelayKeyStore().apply {
                    modes[peer] = ConversationMode.TUNNEL
                    explicitModes += peer
                }
            val prefill = PrefillZchatUseCase()
            val vm = composeVm(store, prefillZchat = prefill).also { composeVms += it }

            scan(store, prefill, peer, relayKey(14_624))
            awaitRecipient(vm, peer)

            assertEquals(ConversationMode.TUNNEL, vm.ready().selectedMode)
        }

    /** What ScanZashiAddressVM does for a contact code: bind the relay key first, then hand the address to New Chat. */
    private fun scan(
        store: RelayKeyStore,
        prefill: PrefillZchatUseCase,
        peer: String,
        key: String,
    ) {
        store.pubkeys[peer] = key
        store.relays[peer] = RelayKeyBinding.RELAY
        prefill.request(peer)
    }

    private suspend fun awaitRecipient(
        vm: ZchatComposeVM,
        peer: String,
    ) {
        withTimeoutOrNull(TIMEOUT_MS) { while (vm.ready().recipientAddress != peer) delay(5) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
