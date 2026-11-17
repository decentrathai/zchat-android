package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1038..ZC-1041 — the receiver of a paid key exchange held in an Open chat (ZC-1017's Tunnel offer): [LedgerWorld]'s
 * phone with a store overlay that keeps the chat mode, the Accept hold and the offer record. The same shape as
 * OpenChatPaidKexOfferSwitchesToTunnelBeforePayingTest's phone. Every address, key and transaction id is synthetic.
 */
internal class OpenChatTunnelOfferPhone(
    val world: LedgerWorld,
) {
    val modes = ConcurrentHashMap<String, ConversationMode>()
    val explicit: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val hold = ConcurrentHashMap<String, String>()
    val offer = ConcurrentHashMap<String, String>()

    /** ZC-1230: the account of an owed KEX; it and the reason go with the mark, as in the real store. */
    val owedAccount = ConcurrentHashMap<String, String>()

    /** A contact whose new key for an already stored key waits for the user (setE2EKeyChanged): stored key and hold stay. */
    val keyChanged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Once: runs right after the next read of the hold, which still answers what it read. */
    @Volatile var afterHoldRead: (() -> Unit)? = null

    /** ZC-1038-R1: while true, a write of the offer record does not reach the store (setOpenChatKexOffer answers false). */
    @Volatile var refuseOfferWrites = false

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
            fun arg(i: Int) = args!![i]
            when (method.name) {
                "getConversationMode" -> modes[arg(0) as String] ?: ConversationMode.VAULT
                "getConversationModeOrNull" -> modes[arg(0) as String]
                "setConversationMode" -> {
                    val mode = arg(1) as ConversationMode?
                    if (mode == null) modes.remove(arg(0) as String) else modes[arg(0) as String] = mode
                    world.timeline.add("prefs:setConversationMode:$mode")
                    null
                }
                "isConversationModeExplicit" -> arg(0) in explicit
                "setConversationModeExplicit" -> {
                    explicit += arg(0) as String
                    null
                }
                "clearConversationModeExplicit" -> {
                    explicit -= arg(0) as String
                    null
                }
                "getKexOwedAccount" -> owedAccount[arg(0) as String]
                "setKexOwedAccount" -> {
                    val value = arg(1) as String?
                    if (value == null) owedAccount.remove(arg(0) as String) else owedAccount[arg(0) as String] = value
                    true
                }
                "setKexOwedReason" -> {
                    val value = arg(1) as String?
                    val reasons = world.prefs.kexOwedReason
                    if (value == null) reasons.remove(arg(0) as String) else reasons[arg(0) as String] = value
                    true
                }
                "setKexOwed" -> {
                    if (arg(1) == false) {
                        owedAccount.remove(arg(0) as String)
                        world.prefs.kexOwedReason.remove(arg(0) as String)
                    }
                    method.invoke(world.prefs.proxy, *args!!)
                }
                "isE2EKeyChanged" -> (arg(0) as String) in keyChanged
                "getInboundKexAwaitingAccept" -> {
                    val read = hold[arg(0) as String]
                    afterHoldRead?.let {
                        afterHoldRead = null
                        it()
                    }
                    read
                }
                "setInboundKexAwaitingAccept" -> {
                    val value = arg(1) as String?
                    if (value == null) hold.remove(arg(0) as String) else hold[arg(0) as String] = value
                    world.timeline.add("prefs:setInboundKexAwaitingAccept")
                    true
                }
                "getOpenChatKexOffer" -> offer[arg(0) as String]
                "setOpenChatKexOffer" -> {
                    if (refuseOfferWrites) return@newProxyInstance false
                    val value = arg(1) as String?
                    if (value == null) offer.remove(arg(0) as String) else offer[arg(0) as String] = value
                    true
                }
                else ->
                    try {
                        method.invoke(world.prefs.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    suspend fun screen(): ChatViewModel = world.openVm().also { PaymentHarness.setField(it, "zchatPreferences", proxy) }

    /** The contact's paid key exchange held for Accept in a chat of [mode]; returns the contact's key. */
    fun heldKex(mode: ConversationMode = ConversationMode.OPEN): String {
        val contactKey = E2EEncryption.generateKeyPair().publicKey
        world.base.peerKeys[world.peer] = contactKey
        world.prefs.receivedKex[world.peer] = contactKey
        modes[world.peer] = mode
        hold[world.peer] = contactKey
        return contactKey
    }

    fun call(
        vm: ChatViewModel,
        name: String,
    ): Any? {
        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
                ?: throw AssertionError("ChatViewModel.$name(peer) does not exist")
        return try {
            method.invoke(vm, world.peer)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun stage(vm: ChatViewModel): String = call(vm, "openChatTunnelOffer").toString()

    companion object {
        fun funded() =
            Zc101FakeAccountDataSource(
                kotlinx.coroutines.runBlocking {
                    syntheticZashiAccount().let {
                        it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))))
                    }
                },
            )
    }
}
