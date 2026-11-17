package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.QsClaim
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield
import co.electriccoin.zcash.ui.screen.chat.datasource.PrefsQsClaimStore
import co.electriccoin.zcash.ui.screen.chat.datasource.QsPeerClaim
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * D3-3 (fix-all 2.13.0, train T5; t5-specs SPECS §2.5): one phone's side of a Quantum Shield setup, for the scan-rule,
 * rescan and alert tests (S1-S3).
 *
 * The ViewModel is the handshake harness allocation (no constructor, no init) with the production claim store
 * ([PrefsQsClaimStore]) set in its `qsClaims` field, over [QsPhonePrefs]: an in-memory ZchatPreferences that answers the
 * Quantum Shield and claim methods, the E2E keys, the key-changed flag and the chat mode, and hands the rest to a
 * [RelayKeyStore]. Every Quantum Shield and claim write is listed in [QsPhonePrefs.writes], in order.
 *
 * What D3-3 adds is reached by name (the problem's name, `quantumShieldAlerts`, `recomputeQsAlerts`), so this file and
 * the tests compile at the RED commit. Every address and key is synthetic.
 */
internal class QsPhonePrefs(
    val peer: String,
    ourPub: String,
    peerPub: String,
    relayKey: String,
    private val ourPrivate: String?,
) {
    val relay = RelayKeyStore()
    val keyChanged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "psk", "secret", "bound" of this chat. */
    val qs = ConcurrentHashMap<String, Any>()

    /** "peer", "our", "sent", "setup" of this chat → the raw stored value. */
    val claimRaw = ConcurrentHashMap<String, Any>()

    /** Every Quantum Shield and claim write, in order: "psk", "bound", "secret", "clear", "claim:<kind>". */
    val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        relay.pubkeys[peer] = relayKey
        relay.modes[peer] = ConversationMode.TUNNEL
        relay.e2eOurKeys[peer] = ourPub
        relay.e2ePeerKeys[peer] = peerPub
    }

    private fun claimKind(name: String): String? =
        when (name.removePrefix("get").removePrefix("set")) {
            "QsClaimPeer" -> "peer"
            "QsClaimOurSeq" -> "our"
            "QsClaimSentSeq" -> "sent"
            "QsClaimSetup" -> "setup"
            else -> null
        }

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun str(i: Int) = args!![i] as String

            fun mine(i: Int) = str(i) == peer
            val kind = claimKind(method.name)
            when {
                method.name == "toString" -> "QsPhonePrefs"
                method.name == "hashCode" -> System.identityHashCode(p)
                method.name == "equals" -> p === args?.get(0)
                kind != null && method.name.startsWith("get") ->
                    (if (mine(0)) claimRaw[kind] else null) ?: if (method.returnType == java.lang.Long.TYPE) 0L else null
                kind != null && method.name.startsWith("set") -> {
                    check(mine(0)) { "a claim write for another chat" }
                    writes += "claim:$kind"
                    claimRaw[kind] = args!![1]
                    true
                }
                method.name == "getRelayModePeers" ->
                    synchronized(relay.modes) {
                        relay.modes
                            .filterValues { it == ConversationMode.TUNNEL || it == ConversationMode.OPEN }
                            .keys
                            .toList()
                    }
                method.name == "getE2EPrivateKey" -> if (mine(0)) ourPrivate else null
                method.name == "isE2EKeyChanged" -> str(0) in keyChanged
                method.name == "getQuantumShieldPSK" -> if (mine(0)) qs["psk"] else null
                method.name == "setQuantumShieldPSK" -> {
                    writes += "psk"
                    qs["psk"] = str(1)
                    Unit
                }
                method.name == "getQuantumShieldOurSecret" -> if (mine(0)) qs["secret"] else null
                method.name == "setQuantumShieldOurSecret" -> {
                    writes += "secret"
                    qs["secret"] = str(1)
                    Unit
                }
                method.name == "getQuantumShieldBound" -> if (mine(0)) qs["bound"] else null
                method.name == "setQuantumShieldBound" -> {
                    writes += "bound"
                    qs["bound"] = args!![1] as Boolean
                    Unit
                }
                method.name == "clearQuantumShieldPSK" -> {
                    // As ZchatPreferencesImpl: the key, its bound record and the setup record, in one edit.
                    writes += "clear"
                    qs.remove("psk")
                    qs.remove("bound")
                    claimRaw.remove("setup")
                    Unit
                }
                else ->
                    try {
                        method.invoke(relay.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}

/** One phone: its ViewModel over [prefs], with the production claim store installed. */
internal class QsPhone private constructor(
    val prefs: QsPhonePrefs,
    val vm: ChatViewModel,
) {
    val peer: String get() = prefs.peer

    /** This chat's relay key and E2E public keys, as this phone stores them. */
    val relayKey: String get() = prefs.relay.pubkeys.getValue(peer)
    val ourPub: String get() = prefs.relay.e2eOurKeys.getValue(peer)
    val peerPub: String get() = prefs.relay.e2ePeerKeys.getValue(peer)

    fun withOwnSecret(
        secret: ByteArray,
        padded: Boolean = false,
    ) = apply { prefs.qs["secret"] = QuantumShield.encodeSecret(secret, boundCapable = !padded) }

    fun initiate(): String = vm.initiateQuantumShield(peer)

    fun complete(qr: String): Boolean = vm.completeQuantumShield(peer, qr)

    fun reset() = vm.resetQuantumShield(peer)

    fun dismiss() = vm.clearQuantumShieldSas()

    suspend fun idle() = HandshakeHarness.awaitIdle(vm)

    val pskHex: String?
        get() = (prefs.qs["psk"] as String?)?.let { Base64.getDecoder().decode(it).joinToString("") { b -> "%02x".format(b) } }

    val confirmation: Any?
        get() = (field("_quantumShieldSas") as MutableStateFlow<*>).value

    val code: String?
        get() = confirmation?.let { Regex("\\d{6}").find(it.toString())?.value }

    /** The confirmation's problem by name, or null. */
    val problem: String?
        get() =
            confirmation?.let { c ->
                runCatching {
                    c.javaClass
                        .getDeclaredField("problem")
                        .apply { isAccessible = true }
                        .get(c)
                        ?.toString()
                }.getOrNull()
            }

    /** The relay reader's claim gate (what the read-receipt branch calls for a DM from this chat's peer). */
    fun receive(line: String) {
        val method =
            ChatViewModel::class.java.declaredMethods.first {
                it.name == "recordQsPeerClaim" && it.parameterCount == 2
            }
        call(method, vm, peer, line)
    }

    /**
     * The fake carrier: stores [claim] as the peer's record exactly as the claim gate would after verifying it (for
     * phones whose E2E keys are the pinned synthetic values, which have no private key to sign with).
     */
    fun carry(
        claim: QsClaim,
        rxAt: Long = System.currentTimeMillis(),
    ) {
        val line = claim.copy(sig = DUMMY_SIG).encode()
        prefs.claimRaw["peer"] = QsPeerClaim(line = line, signerPub = peerPub, otherPub = ourPub, rxAt = rxAt).encode()
    }

    /** This chat's alert by name ("NONE" when the map has no entry), or a marker when the build has no alert holder. */
    fun alert(): String {
        val getter =
            ChatViewModel::class.java.methods.firstOrNull { it.name == "getQuantumShieldAlerts" && it.parameterCount == 0 }
                ?: return NO_ALERT_HOLDER
        val flow = call(getter, vm) as StateFlow<*>
        return ((flow.value as Map<*, *>)[peer])?.toString() ?: "NONE"
    }

    /** The refresh tick's alert pass, by name. */
    fun tick() {
        val method =
            ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == "recomputeQsAlerts" && it.parameterCount == 0 }
                ?: error("the refresh tick's alert pass (recomputeQsAlerts) is missing")
        call(method, vm)
    }

    /** Everything this phone stores for the chat's shield and claims. */
    fun snapshot(): Map<String, Any?> = HashMap<String, Any?>(prefs.qs).apply { prefs.claimRaw.forEach { (k, v) -> put("claim:$k", v) } }

    private fun field(name: String): Any? =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)

    companion object {
        const val NO_ALERT_HOLDER = "<no quantumShieldAlerts holder>"
        private const val DUMMY_SIG = "AAAA"

        suspend fun open(prefs: QsPhonePrefs): QsPhone {
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, HandshakeSeam(prefs.peer))
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
            AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
            AutoSpendVm.setField(vm, "_quantumShieldSas", MutableStateFlow<Any?>(null))
            AutoSpendVm.setField(vm, "qsClaims", PrefsQsClaimStore(prefs.proxy))
            return QsPhone(prefs, vm)
        }

        /** Two phones of one chat: A's peer is B and B's peer is A, with real E2E key pairs (claims are signed). */
        suspend fun pair(
            a: E2EKeyPair,
            b: E2EKeyPair,
            tag: Int,
        ): Pair<QsPhone, QsPhone> {
            val relayOfB = RelayKeyBinding.relayKey(tag * 2 + 1)
            val relayOfA = RelayKeyBinding.relayKey(tag * 2 + 2)
            val phoneA = open(QsPhonePrefs("usyntheticd33peerb$tag", a.publicKey, b.publicKey, relayOfB, a.privateKey))
            val phoneB = open(QsPhonePrefs("usyntheticd33peera$tag", b.publicKey, a.publicKey, relayOfA, b.privateKey))
            return phoneA to phoneB
        }

        fun call(
            method: java.lang.reflect.Method,
            target: Any,
            vararg args: Any?,
        ): Any? =
            try {
                method.isAccessible = true
                method.invoke(target, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }
}

/** A fake relay publisher: records every claim line with its recipient and acks it once. */
internal class QsClaimRelay {
    class Sent(
        val line: String,
        val recipient: String,
    )

    val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

    fun register() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            sent += Sent(plaintext, recipient)
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-d33-rumor-${sent.size}")
        }
    }

    fun unregister() = NostrChatBridge.unregisterPublisher()

    /** The newest claim [from] published to its peer, as that peer receives it. */
    fun newestFrom(from: QsPhone): String {
        val key = from.relayKey
        return synchronized(sent) { sent.last { it.recipient == key && it.line.startsWith(QsClaim.PREFIX) } }.line
    }
}
