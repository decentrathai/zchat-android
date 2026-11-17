package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-634 part B (FIRST-CONTACT-SPEC 5.5 items 2 to 9) test support: the store calls of the claim mark, the relay binding,
 * the processed ZBOOTs, the verified and key-changed flags and the chat mode, answered from maps ([ClaimMarkMaps.handle]),
 * over any other store ([ClaimMarkChat] puts them over a [HandshakePrefs]; a pass test hands [ClaimMarkMaps.handle] to
 * [HandshakePassFixture.extra]). The claim calls are named as the fix adds them (storePeerKey, getKeyClaim,
 * getHeldClaimBoot, holdClaimedBoot, releaseKeyClaim); a build without them never makes them. Every address, key and id
 * is synthetic.
 */
internal class ClaimMarkMaps(
    /** The contact keys, shared with the store underneath (HandshakePrefs.e2ePeerKey). */
    private val peerKeys: MutableMap<String, String>,
    /** The asked records, shared with the store underneath (HandshakePrefs.askedKey). */
    private val askedKeys: MutableMap<String, String>,
) {
    val nostr = ConcurrentHashMap<String, String>()

    /** The relay URL stored per address (setPeerNostrRelay). */
    val relayUrls = ConcurrentHashMap<String, String>()
    val binds: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
    val bootSigs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val epochs = ConcurrentHashMap<String, Long>()
    val claims = ConcurrentHashMap<String, String>()
    val held = ConcurrentHashMap<String, String>()
    val verified: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val keyChanged: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val modes = ConcurrentHashMap<String, ConversationMode>()
    val sentNostrBoot = ConcurrentHashMap<String, String>()
    val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every saved row id (system notes: the "rotated" note, the "tunnel established" note). */
    val rows: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * W-72 (ZC-847, ZC-852, ZC-855) — the pre-update marks ("legacykey:<address>", naming a key; a mark stands only while
     * it names the stored key) and the ZBOOT each one held back. Answered by the calls the fix adds (getLegacyKeyMark,
     * holdLegacyBoot, getHeldLegacyBoot, releaseLegacyKey, and storePeerKey's fifth argument); a build without them never
     * makes them.
     */
    val legacy = ConcurrentHashMap<String, String>()
    val legacyHeld = ConcurrentHashMap<String, String>()

    /** W-72 (SPEC_F R4, R5): the probe id kept with a mark (getLegacyProbe) and the probe's sent txids (getLegacyProbeTxIds). */
    val legacyProbe = ConcurrentHashMap<String, String>()
    val legacyProbeTxIds = ConcurrentHashMap<String, MutableList<String>>()

    /** W-72 (review finding 2): the pre-update mark goes at the hold (as "Mark as verified" on another thread), which is refused. */
    @Volatile var legacyMarkGoesAtHold = false

    /** W-72 (SPEC_DECISIONS D2): whether this key store finished one complete history read (isLegacyHistoryRead). */
    @Volatile var legacyHistoryRead = true

    /** How often the pass recorded that read (setLegacyHistoryRead). */
    val legacyHistoryReadWrites = AtomicInteger(0)

    /** One line per storePeerKey commit: "peer key claim asked", with " legacy" when the fifth argument asked a mark. */
    val keyCommits: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * Runs once, at the next releaseKeyClaim, before it is answered: a test puts the user's "Mark as verified" there (the
     * tap racing a proof). Null: none.
     */
    @Volatile var beforeRelease: (() -> Unit)? = null

    /** Runs once, at the next holdClaimedBoot, before it is answered (the tap racing a ZBOOT being held). Null: none. */
    @Volatile var beforeHold: (() -> Unit)? = null

    /** The key store refuses every commit of a contact key (case t). */
    @Volatile var refuseKeyCommits = false

    /** Answers [method] with [a] when it is one of these calls; [UNHANDLED] otherwise. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun handle(
        method: Method,
        a: Array<Any?>,
    ): Any? {
        fun s(i: Int) = a[i] as String
        return when (method.name) {
            "getPeerNostrPubkey" -> nostr[s(0)]
            "setPeerNostrPubkey" -> {
                binds += s(0) to (a[1] as String?)
                if (a[1] == null) nostr.remove(s(0)) else nostr[s(0)] = s(1)
                typed(method)
            }
            "setPeerNostrRelay" -> {
                if (a[1] == null) relayUrls.remove(s(0)) else relayUrls[s(0)] = s(1)
                typed(method)
            }
            "getPeerNostrRelay" -> relayUrls[s(0)]
            "findPeerByNostrPubkey" -> nostr.entries.firstOrNull { it.value.equals(s(0), ignoreCase = true) }?.key
            "isOwnNostrPubkey" -> false
            "hasProcessedBootSig" -> s(0) in bootSigs
            "markBootSigProcessed" -> {
                bootSigs += s(0)
                typed(method)
            }
            "getPeerBootEpoch" -> epochs[s(0)] ?: 0L
            "setPeerBootEpoch" -> {
                epochs[s(0)] = a[1] as Long
                typed(method)
            }
            "isE2EVerified" -> s(0) in verified
            "setE2EVerified" -> {
                if (a[1] as Boolean) verified += s(0) else verified -= s(0)
                typed(method)
            }
            "isE2EKeyChanged" -> s(0) in keyChanged
            "setE2EKeyChanged" -> {
                if (a[1] as Boolean) keyChanged += s(0) else keyChanged -= s(0)
                typed(method, true)
            }
            "getConversationMode" -> modes[s(0)] ?: ConversationMode.VAULT
            "getConversationModeOrNull" -> modes[s(0)]
            "setConversationMode" -> {
                modes[s(0)] = a[1] as ConversationMode
                typed(method, true)
            }
            "isConversationModeExplicit" -> false
            "getTunnelModePeers" -> modes.filterValues { it == ConversationMode.TUNNEL }.keys.toList()
            "getSentNostrBootPubkey" -> sentNostrBoot[s(0)]
            "setSentNostrBootPubkey" -> {
                if (a[1] == null) sentNostrBoot.remove(s(0)) else sentNostrBoot[s(0)] = s(1)
                typed(method, true)
            }
            "isZbootQuoted" -> s(0) in zbootQuoted
            "setZbootQuoted" -> {
                if (a[1] as Boolean) zbootQuoted += s(0) else zbootQuoted -= s(0)
                typed(method, true)
            }
            "addPendingMessage" -> {
                rows += (a[0] as ZchatPreferences.PendingMessageData).id
                typed(method)
            }
            // The claim mark, in the key store with the key it qualifies (5.5 item 2).
            "storePeerKey" ->
                if (refuseKeyCommits) {
                    false
                } else {
                    peerKeys[s(0)] = s(1)
                    if (a[2] as Boolean) {
                        claims[s(0)] = s(1)
                        verified -= s(0)
                    } else {
                        claims.remove(s(0))
                        held.remove(s(0))
                    }
                    if (a[3] as Boolean) askedKeys[s(0)] = s(1)
                    // W-72: a mark is written when asked; another key takes the mark (and its held ZBOOT) away.
                    val legacyAsked = a.getOrNull(4) as Boolean? ?: false
                    if (legacyAsked) {
                        legacy[s(0)] = s(1)
                    } else if (legacy[s(0)] != s(1)) {
                        legacy.remove(s(0))
                        legacyHeld.remove(s(0))
                    }
                    keyCommits += "${s(0)} ${s(1)} ${a[2]} ${a[3]}" + if (legacyAsked) " legacy" else ""
                    true
                }
            // The write before the fix: a plain write the store saves whatever it reports (no commit result).
            "setE2EPeerPublicKey" -> {
                peerKeys[s(0)] = s(1)
                typed(method)
            }
            "getKeyClaim" -> claims[s(0)]
            "markKeyClaim" ->
                if (peerKeys[s(0)] == s(1)) {
                    claims[s(0)] = s(1)
                    verified -= s(0)
                    true
                } else {
                    false
                }
            "getHeldClaimBoot" -> held[s(0)]
            "holdClaimedBoot" -> {
                beforeHold?.let { hook ->
                    beforeHold = null
                    hook()
                }
                if (claims.containsKey(s(0))) {
                    held[s(0)] = s(1)
                    true
                } else {
                    false
                }
            }
            "releaseKeyClaim" -> {
                beforeRelease?.let { hook ->
                    beforeRelease = null
                    hook()
                }
                if (claims.remove(s(0)) != null) {
                    if (a[1] as Boolean) verified += s(0)
                    held.remove(s(0)).orEmpty()
                } else {
                    null
                }
            }
            // W-72 (D2): the key store's first complete history read.
            "isLegacyHistoryRead" -> legacyHistoryRead
            "setLegacyHistoryRead" -> {
                legacyHistoryRead = true
                legacyHistoryReadWrites.incrementAndGet()
                typed(method)
            }
            // W-72: the pre-update mark, beside the key it names.
            "getLegacyKeyMark" -> legacy[s(0)]?.takeIf { it == peerKeys[s(0)] }
            "getHeldLegacyBoot" -> legacyHeld[s(0)]?.takeIf { legacy[s(0)] != null && legacy[s(0)] == peerKeys[s(0)] }
            "holdLegacyBoot" ->
                if (legacyMarkGoesAtHold) {
                    legacy.remove(s(0))
                    legacyHeld.remove(s(0))
                    false
                } else if (legacy[s(0)] != null && legacy[s(0)] == peerKeys[s(0)]) {
                    legacyHeld[s(0)] = s(1)
                    true
                } else {
                    false
                }
            // The release that hands back the held ZBOOT from the same section ("" none held, null no mark).
            "releaseLegacyKeyWithBoot" ->
                if (legacy.remove(s(0)) != null) {
                    val boot = legacyHeld.remove(s(0)).orEmpty()
                    legacyProbe.remove(s(0))
                    legacyProbeTxIds.remove(s(0))
                    if (a[1] as Boolean) verified += s(0)
                    boot
                } else {
                    null
                }
            "getLegacyProbe" -> legacyProbe[s(0)]?.takeIf { legacy[s(0)] != null && legacy[s(0)] == peerKeys[s(0)] }
            "getLegacyProbeTxIds" ->
                legacyProbeTxIds[s(0)]
                    ?.takeIf { it.isNotEmpty() && legacy[s(0)] != null && legacy[s(0)] == peerKeys[s(0)] }
                    ?.joinToString("\n")
            "releaseLegacyKey" ->
                if (legacy.remove(s(0)) != null) {
                    legacyHeld.remove(s(0))
                    legacyProbe.remove(s(0))
                    legacyProbeTxIds.remove(s(0))
                    if (a[1] as Boolean) verified += s(0)
                    true
                } else {
                    false
                }
            else -> UNHANDLED
        }
    }

    /** What a write returns: [ok] for a Boolean, nothing for Unit. */
    private fun typed(
        method: Method,
        ok: Boolean = true,
    ): Any? = if (method.returnType == java.lang.Boolean.TYPE) ok else null

    companion object {
        val UNHANDLED = Any()
    }
}

/**
 * A chat with [peer] over [HandshakePrefs] (its keys, holds, ids, budget and answers paid) with [ClaimMarkMaps] on top;
 * a call neither answers gets the type's empty value. [viewModel] is the handshake harness's ChatViewModel over it, with
 * the ZBOOT paths' fields wired and the English texts. Relay publishes go to [publishes] through [relayAcks]
 * acknowledgements (none registered: the relays are not ready).
 */
internal class ClaimMarkChat(
    val peer: String,
    firstContact: Boolean = true,
    consent: Boolean = true,
) {
    val prefs = HandshakePrefs(peer, consent = consent, firstContact = firstContact)
    val maps = ClaimMarkMaps(prefs.e2ePeerKey, prefs.askedKey)
    val seam = HandshakeSeam(peer)
    val publishes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val store: ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "ClaimMarkStore"
                "resolvePeerAddress" -> a[0]
                "getOrPutPendingTxFirstSeenMillis", "getOrPutMessageExpiryAnchorMillis" -> a[1]
                "clearE2EPeerState" -> {
                    maps.legacy.remove(a[0] as String)
                    maps.legacyHeld.remove(a[0] as String)
                    maps.claims.remove(a[0] as String)
                    maps.held.remove(a[0] as String)
                    maps.verified.remove(a[0] as String)
                    delegate(method, a)
                }
                else -> {
                    val mine = maps.handle(method, a)
                    if (mine !== ClaimMarkMaps.UNHANDLED) mine else delegate(method, a)
                }
            }
        } as ZchatPreferences

    private fun delegate(
        method: Method,
        a: Array<Any?>,
    ): Any? {
        val value =
            try {
                method.invoke(prefs.proxy, *a)
            } catch (e: InvocationTargetException) {
                val unknownPrimitive = e.targetException is NullPointerException && method.returnType.isPrimitive
                if (unknownPrimitive) null else throw e.targetException
            }
        return value ?: emptyOf(method.returnType)
    }

    /** Relays that acknowledge every publish with [acks] readable copies (0: they take nothing). */
    fun relays(acks: Int) {
        val constructor = NostrChatBridge.PublishResult::class.java.constructors.first { it.parameterCount == 3 }
        NostrChatBridge.registerPublisher { content, recipient ->
            publishes += "$recipient $content"
            constructor.newInstance(acks, "synthetic-claim-event", acks) as NostrChatBridge.PublishResult
        }
    }

    suspend fun viewModel(): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(store, seam)
        AutoSpendVm.wireBootstrapFields(vm)
        AutoSpendVm.setField(vm, "noteCopy", EnglishCopy)
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
        AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
        AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        // A flushed Tunnel message is published through the reply-tag helpers (their constructor values).
        AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
        AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
        AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
        return vm
    }

    /** routeIncomingBoot([peer], [raw]) as the relay reader's attributed call ([sealed]) or as attributeBoot calls it. */
    fun route(
        vm: ChatViewModel,
        raw: String,
        sealed: Boolean = false,
        to: String = peer,
    ): Boolean {
        val method =
            ChatViewModel::class.java.declaredMethods.first {
                it.name == "routeIncomingBoot" && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE
            }
        method.isAccessible = true
        return try {
            method.invoke(vm, to, raw, sealed) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** attributeBoot([raw]): an on-chain ZBOOT, or one that came over the relays unsealed by a bound key. */
    fun attribute(
        vm: ChatViewModel,
        raw: String,
    ): String? {
        val method = ChatViewModel::class.java.getDeclaredMethod("attributeBoot", String::class.java)
        method.isAccessible = true
        return try {
            method.invoke(vm, raw) as String?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A Tunnel message of ours that waits for the contact's relay key (the process-wide queue). */
    fun queueTunnelMessage(text: String) {
        val type =
            ChatViewModel::class.java.declaredClasses
                .flatMap { listOf(it) + it.declaredClasses }
                .first { it.simpleName == "QueuedTunnelPayload" }
        val constructor = type.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val queue = tunnelQueue() as MutableMap<String, MutableList<Any>>
        queue[peer] = mutableListOf(constructor.newInstance("tunnel-wait-zc634", text))
    }

    /** Whether our waiting Tunnel message is still queued. */
    fun tunnelMessageWaits(): Boolean = (tunnelQueue()[peer] as? List<*>)?.isNotEmpty() == true

    fun proposals(): Int = seam.proposalRequests.get()

    companion object {
        const val RELAY = "wss://relay.zsend.xyz"
        const val BOOT_CONV = "ZC634BT2"

        fun nostr(seed: Int): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64) { (it + seed).toByte() })

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun relayKey(seed: Int): String = hex(nostr(seed).publicKey)

        /** A v3 ZBOOT signed with [signer], announcing [relayKey] at [epoch]. */
        fun v3Boot(
            signer: E2EKeyPair,
            relayKey: String,
            epoch: Long = 1L,
            convId: String = BOOT_CONV,
        ): String {
            val signed = ZBootMessage.signedDataFor(convId, relayKey, RELAY, epoch)
            val sig = E2EEncryption.sign(signer.privateKey, signed)
            return ZBootMessage(convId, relayKey, RELAY, sig, epoch = epoch, version = 3).serialize()
        }

        /** A v4 ZBOOT signed with the relay identity [signer], announcing [relayKey]. */
        fun v4Boot(
            signer: NOSTRIdentity,
            relayKey: String,
            epoch: Long = 2L,
        ): String {
            val signed = ZBootMessage.signedDataFor(BOOT_CONV, relayKey, RELAY, epoch)
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(signed.toByteArray(Charsets.UTF_8))
            return ZBootMessage(BOOT_CONV, relayKey, RELAY, hex(signer.signHashSchnorr(digest)), epoch = epoch, version = 4).serialize()
        }

        /** A signed key exchange claiming [sender] under [convId]. */
        fun kexFrom(
            sender: String,
            keys: E2EKeyPair,
            convId: String,
        ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))

        /** The same, forged to carry relay fields beside the address (2.2 step 2: the app's builder never writes both). */
        fun kexWithRelayFields(
            sender: String,
            keys: E2EKeyPair,
            convId: String,
            relayKey: String,
        ): String {
            val signed = E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey)
            val relayB64 =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(RELAY.toByteArray(Charsets.UTF_8))
            val payload = signed.substringBeforeLast(":") + ":" + relayKey + ":" + relayB64 + ":" + sender
            return ZMSGProtocol.createV4KEXMessage(convId, sender, payload)
        }

        private fun tunnelQueue(): MutableMap<*, *> {
            val field =
                (listOf(ChatViewModel::class.java) + ChatViewModel::class.java.declaredClasses)
                    .flatMap { it.declaredFields.toList() }
                    .first { it.name == "tunnelPendingPayloads" }
            field.isAccessible = true
            return field.get(null) as MutableMap<*, *>
        }

        fun clearTunnelQueue() = tunnelQueue().clear()

        private fun emptyOf(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                List::class.java -> emptyList<Any>()
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }

        private val EnglishCopy =
            ChatNoteCopy { id, args ->
                val text = ChatResourceText.english(id)
                if (args.isEmpty()) text else String.format(text, *args)
            }
    }
}
