package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * ZC-773, follow-up (found in this lane's own review of its fix aa6e55cb2) — a history pass whose wallet was switched
 * under it never opens the "history was read" gate of the automatic ZBOOTs.
 *
 * A pass belongs to the wallet whose stores were live when it began (the wallet-scope generation). Until aa6e55cb2 a
 * pass that enrolled no contact answered "ready" only when that generation was still the same. Since aa6e55cb2 a pass
 * that read a mined own ZBOOT for a contact with nothing owed no longer takes that early answer, and when none of the
 * identities it read is our current one it touches no contact at all: it then ended "ready" although the wallet had
 * been switched after the row was read, and opened the gate for a wallet whose history no pass of this process has
 * read. An automatic ZBOOT of that wallet could then be paid before its history is known (17,000 zat). Wanted: such a
 * pass never ends ready.
 *
 * The wallet switch is the generation the store reports, raised while the pass is parked between two rows. Money:
 * proposals are counted against submits on the real use case and repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AHistoryPassAcrossAWalletSwitchNeverOpensTheZbootGateTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        // No pass of this process has read the history yet.
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
    fun `a wallet switch after the pass read an earlier identity for a contact with nothing owed leaves the gate closed`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            passOverTwoEarlierRows(this, vm, switchBeforeRow = 1, chat)

            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)

            assertEquals(
                "an automatic ZBOOT was paid although no pass has read the history of the wallet now live",
                0,
                chat.seam.zbootProposals.get(),
            )
            assertEquals(0, chat.seam.submitted.size)
            assertFalse("a pass whose wallet was switched under it opened the gate", ZbootSetup.historyRead())
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - a wallet switch before the pass read any row leaves the gate closed`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            passOverTwoEarlierRows(this, vm, switchBeforeRow = 0, chat)

            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)

            assertEquals(0, chat.seam.zbootProposals.get())
            assertFalse("a pass whose wallet was switched under it opened the gate", ZbootSetup.historyRead())
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - the same pass with no wallet switch opens the gate and our current identity is paid once`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            passOverTwoEarlierRows(this, vm, switchBeforeRow = null, chat)
            assertTrue("a complete pass of the live wallet must open the gate", ZbootSetup.historyRead())

            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)
            assertEquals("our current identity was never delivered and must be paid", 1, chat.seam.submittedOf("ZBOOT"))

            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)
            assertEquals(1, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    /**
     * One complete pass over two mined own ZBOOTs of an EARLIER identity to [PEER]. The wallet-scoped stores are handed
     * to another wallet (the generation goes up) right before row [switchBeforeRow] is read; null: no switch.
     */
    private suspend fun passOverTwoEarlierRows(
        scope: CoroutineScope,
        vm: ChatViewModel,
        switchBeforeRow: Int?,
        chat: Chat,
    ) {
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val earlier = ZbootSetup.boot(ZbootSetup.EARLIER_IDENTITY)
        val job =
            scope.launch {
                ZbootSetup.historyPass(vm, PEER, listOf(earlier, earlier)) { index ->
                    if (index == switchBeforeRow) {
                        parked.complete(Unit)
                        release.await()
                    }
                }
            }
        try {
            if (switchBeforeRow != null) {
                withTimeout(HandshakeHarness.TIMEOUT_MS) { parked.await() }
                chat.generation.incrementAndGet()
            }
        } finally {
            release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { job.join() }
        }
        AutoSpendVm.setField(vm, "synchronizerProvider", chat.owed.heights.provider)
    }

    /**
     * A Shielded chat after a restore (keys both ways, no relay key, nothing recorded as delivered, nothing quoted, the
     * standing consent given) over a store that reports the wallet-scope [generation] a test can raise.
     */
    private class Chat {
        val owed =
            ResetOwedChat(PEER, consent = true).also { chat ->
                chat.store.setSentNostrBootPubkey(PEER, null)
            }
        val seam: ResetKexSeam get() = owed.seam
        val generation = AtomicLong(0L)

        private val store: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "walletScopeGeneration" -> generation.get()
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "Zc773GenerationStore"
                    else ->
                        try {
                            method.invoke(owed.store, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        suspend fun viewModel(): ChatViewModel =
            owed.viewModel().also {
                AutoSpendVm.setField(it, "zchatPreferences", store)
                AutoSpendVm.setField(it, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
            }
    }

    private companion object {
        const val PEER = "usyntheticzc773walletswitch1"
    }
}
