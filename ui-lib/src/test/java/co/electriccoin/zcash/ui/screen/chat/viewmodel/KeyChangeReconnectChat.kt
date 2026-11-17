package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Assert.assertEquals
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-300 (fix-all 2.13.0, train T5b; r8-findings SPECS §16.1, NEW-KCR) — a Tunnel or Open chat whose "Security key changed"
 * banner is up: the contact's relay key changed ([keyChanged] set, the old relay key [relayKey] still stored), our own key
 * for the contact is stored and the contact's key has not reached us yet, so the exchange is not complete and the
 * reconnect's KEX goes on-chain. (With a complete exchange the KEX settles as already delivered and nothing is paid,
 * before and after ZC-300.)
 *
 * Built on [ResetOwedChat]: the owed-KEX marks, the quoted-ZBOOT allowance, the paid-setup records, the contact's key, the
 * standing consent, the chain tip and the settlement seam are its maps, shared by every ViewModel of a test. This store
 * adds the key-change flag and the relay key, and notes whether the owed KEX ("reconnect"), and the ZBOOT allowance were
 * already recorded when the flag was cleared ([owedWhenTheFlagCleared]) and when the relay key was dropped
 * ([owedWhenTheRelayKeyDropped]). Every address and key is synthetic.
 *
 * ZC-535: [exchangeComplete] is the usual key-changed state: the contact's OLD key is still stored, so the exchange reads
 * as complete ([contactKeys] is that old key pair when a test lets the contact sign with it; a synthetic key otherwise).
 * [caps] answers getPeerIronwoodCap (no record unless a test adds one).
 */
internal class KeyChangeReconnectChat(
    val peer: String,
    consent: Boolean = true,
    failFirstKexWithStillConfirming: Boolean = false,
    mode: ConversationMode = ConversationMode.TUNNEL,
    // ZC-534: the ZBOOT seams of [ResetKexSeam].
    failFirstZbootWithStillConfirming: Boolean = false,
    rejectFirstZbootBeforeStore: Boolean = false,
    // ZC-535
    exchangeComplete: Boolean = false,
    contactKeys: E2EKeyPair? = null,
) {
    val owed =
        ResetOwedChat(
            peer,
            consent,
            failFirstKexWithStillConfirming,
            mode,
            failFirstZbootWithStillConfirming = failFirstZbootWithStillConfirming,
            rejectFirstZbootBeforeStore = rejectFirstZbootBeforeStore,
        )
    val seam: ResetKexSeam get() = owed.seam

    @Volatile var keyChanged: Boolean = true

    @Volatile var relayKey: String? = OLD_RELAY

    @Volatile var owedWhenTheFlagCleared: Boolean? = null

    @Volatile var owedWhenTheRelayKeyDropped: Boolean? = null

    val caps = ConcurrentHashMap<String, ZchatPreferences.PeerIronwoodCap>()

    init {
        if (exchangeComplete) {
            // ZC-535, the usual state: the contact's old key is still stored, so the exchange reads as complete.
            contactKeys?.let { owed.peerKeys[peer] = it.publicKey }
        } else {
            // The contact's key has not reached us: the exchange is not complete, so the reconnect's KEX is paid on-chain.
            owed.peerKeys.remove(peer)
        }
    }

    private fun reconnectOwedRecorded(): Boolean =
        peer in owed.kexOwed && owed.kexOwedReason[peer] == "reconnect" && owed.zbootQuoted[peer] == true

    val store: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun forPeer() = args?.getOrNull(0) == peer
            when (method.name) {
                "isE2EKeyChanged" -> forPeer() && keyChanged
                "setE2EKeyChanged" -> {
                    if (forPeer()) {
                        val value = args!![1] as Boolean
                        if (!value && keyChanged && owedWhenTheFlagCleared == null) {
                            owedWhenTheFlagCleared = reconnectOwedRecorded()
                        }
                        keyChanged = value
                    }
                    null
                }
                "getPeerNostrPubkey" -> if (forPeer()) relayKey else null
                "setPeerNostrPubkey" -> {
                    if (forPeer()) {
                        val value = args!![1] as String?
                        if (value == null && relayKey != null && owedWhenTheRelayKeyDropped == null) {
                            owedWhenTheRelayKeyDropped = reconnectOwedRecorded()
                        }
                        relayKey = value
                    }
                    null
                }
                "getPeerIronwoodCap" -> caps[args!![0] as String]
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "KeyChangeReconnectStore"
                else ->
                    try {
                        method.invoke(owed.store, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    /** A chat screen's ViewModel over this chat's store (see [ResetOwedChat.viewModel]). */
    suspend fun viewModel(
        accounts: AccountDataSource? = null,
        planCheck: FirstMessagePlanApi.ScriptedCheck? = null,
    ): ChatViewModel {
        val vm = owed.viewModel(accounts, planCheck)
        AutoSpendVm.setField(vm, "zchatPreferences", store)
        AutoSpendVm.setField(vm, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
        return vm
    }

    /** The reconnect's price question is on screen: KEY_CHANGE_RECONNECT, 2 on-chain, nothing built or paid yet. */
    fun assertAskedFirst(vm: ChatViewModel) {
        assertEquals("the reconnect paid on-chain with no question on screen", 0, seam.proposals())
        assertAsked(vm, 2)
    }

    /** ZC-535 — the reconnect's price question is on screen with [expected] on-chain messages (the legs that are due). */
    fun assertAsked(
        vm: ChatViewModel,
        expected: Int,
    ) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "the reconnect must ask with the on-chain count and the total first, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val action = confirmation.javaClass.getMethod("getAction").invoke(confirmation)
        val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
        assertEquals("the question names the reconnect", "KEY_CHANGE_RECONNECT", (action as Enum<*>).name)
        assertEquals("the question must count exactly the on-chain messages the reconnect will pay", expected, count)
    }

    /** Nothing changed: the banner's flag and the old relay key stay, nothing is owed, built or paid. */
    fun assertNothingChanged(whenText: String) {
        assertEquals("the key-change flag must stay $whenText", true, keyChanged)
        assertEquals("the contact's relay key must stay $whenText", OLD_RELAY, relayKey)
        assertEquals("nothing may be built $whenText", 0, seam.proposals())
        assertEquals("nothing may be submitted $whenText", 0, seam.submitted.size)
        assertNothingOwed(whenText)
    }

    fun assertNothingOwed(whenText: String) {
        assertEquals("an owed KEX was left $whenText", false, peer in owed.kexOwed)
        assertEquals("an owed reason was left $whenText", null, owed.kexOwedReason[peer])
        assertEquals("an owed account was left $whenText", null, owed.kexOwedAccount[peer])
        assertEquals("a ZBOOT allowance was left $whenText", false, owed.zbootQuoted[peer] == true)
    }

    companion object {
        const val OLD_RELAY = "old relay of the contact"
    }
}
