package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * M3 (ZC-110) — the drivers of the "Allow handshake transactions?" funds-gate tests: a contact whose KEX we answer (the
 * KEXACK shape of ConsentGrantReleasesQueuedKexAckTest), our own KEX and a Tunnel ZBOOT, each on a selected account a
 * test switches between unfunded and funded ([SelectedAccountFeed]); the block tick and the process-wide queue of the
 * question, reached by name (a tick that does not exist yet is simply what the RED tests find missing).
 *
 * All keys are generated per run and every address is synthetic.
 */
internal object FundsGateDriver {
    /** We received [peer]'s KEX (we are the responder, a known contact) and have not allowed automatic chat setups. */
    fun responderPrefs(peer: String): HandshakePrefs {
        val peerKeys = E2EEncryption.generateKeyPair()
        return HandshakePrefs(peer, consent = false).apply {
            e2ePeerKey[peer] = peerKeys.publicKey
            receivedKex[peer] = peerKeys.publicKey
        }
    }

    /** A screen of the responder phone over [accounts] (the block tick may run on it). */
    suspend fun responderScreen(
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
        accounts: SelectedAccountFeed?,
    ): ChatViewModel = chatViewModelWith(tickable(prefs.proxy), seam, accountSource = accounts)

    /** A screen of the responder phone over [accounts]; opening the chat with [peer] retries the owed KEXACK. */
    suspend fun openResponderChat(
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
        accounts: SelectedAccountFeed?,
        peer: String,
    ): ChatViewModel {
        val vm = responderScreen(prefs, seam, accounts)
        vm.retryKexAckIfResponder(peer)
        awaitIdle(vm)
        return vm
    }

    /**
     * [delegate] as the block tick reads it: [HandshakePrefs] answers null for the stores the KEXACK path never reads (a
     * Tunnel ZBOOT owed list, a held group reply), which the tick's other work does read. A missing answer reads as the
     * empty store (no list, false, zero); every other answer is the delegate's own.
     */
    fun tickable(delegate: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            val answer =
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            answer ?: when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                Set::class.java -> emptySet<Any>()
                List::class.java -> emptyList<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }
        } as ZchatPreferences

    /** A screen of the KEX / ZBOOT fixture ([AutoSpendPrefs]) over [accounts]. */
    suspend fun autoSpendScreen(
        prefs: AutoSpendPrefs,
        seam: HandshakeSeam,
        accounts: SelectedAccountFeed?,
    ): ChatViewModel = chatViewModelWith(prefs.proxy, seam, accountSource = accounts).also(AutoSpendVm::wireBootstrapFields)

    /** Our bootstrap KEX to [peer] (the automatic one, under the standing consent). */
    suspend fun automaticKex(
        vm: ChatViewModel,
        prefs: AutoSpendPrefs,
        peer: String,
    ) {
        prefs.ownBootSent[peer] = true
        vm.sendKEXMessage(peer, HandshakeHarness.SELF)
        awaitIdle(vm)
    }

    /** Our Tunnel ZBOOT to [peer], whose key exchange is complete. */
    suspend fun tunnelZboot(
        vm: ChatViewModel,
        peer: String,
    ) {
        AutoSpendVm.setHistoryRead(true)
        AutoSpendVm.sendNostrBoot(vm, peer)
        awaitIdle(vm)
    }

    /** One chain tip, as observeBlockHeight drives it, and everything it started. */
    suspend fun chainTip(vm: ChatViewModel) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, HandshakeHarness.START_HEIGHT + 1)
        awaitIdle(vm)
    }

    fun state(vm: ChatViewModel): SendMessageState = HandshakeHarness.sendMessageState(vm)

    fun setState(
        vm: ChatViewModel,
        state: SendMessageState,
    ) {
        stateFlow(vm).value = state
    }

    /** Every state [vm]'s send state passes through from now on, until [StateRecorder.stop]. */
    fun record(vm: ChatViewModel): StateRecorder = StateRecorder(stateFlow(vm))

    class StateRecorder(
        flow: MutableStateFlow<SendMessageState>,
    ) {
        val states: MutableList<SendMessageState> = Collections.synchronizedList(mutableListOf())
        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { flow.collect { states += it } }

        fun asked(): Boolean = states.toList().any { it is SendMessageState.NeedsHandshakeSpendConsent }

        fun stop() = job.cancel()
    }

    fun queued(): Set<QueuedAutomaticSetup> = processSet("queuedAutomaticSetups")

    fun dismissed(): Set<QueuedAutomaticSetup> = processSet("dismissedAutomaticSetups")

    fun setup(
        kind: AutomaticSetupKind,
        peer: String,
    ) = QueuedAutomaticSetup(kind, peer)

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(vm: ChatViewModel): MutableStateFlow<SendMessageState> =
        ChatViewModel::class.java
            .getDeclaredField("_sendMessageState")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<SendMessageState>

    @Suppress("UNCHECKED_CAST")
    private fun processSet(name: String): Set<QueuedAutomaticSetup> =
        (
            ChatViewModel::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(null) as Set<QueuedAutomaticSetup>
        ).toSet()
}
