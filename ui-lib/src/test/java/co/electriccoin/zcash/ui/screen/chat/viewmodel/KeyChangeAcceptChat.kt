package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetConversationState
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-545 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3A, owner decision W-32), also register ZC-564 — a chat whose
 * contact's key exchange is complete (our key pair and the contact's OLD key stored, verified, a live ratchet) and
 * whose contact then offers a NEW key.
 *
 * Built on [KeyChangeReconnectChat] (over [ResetOwedChat] and [ResetFixture]): the owed-KEX marks, the ZBOOT allowance,
 * the paid records, the standing consent, the chain tip, the settlement seam and the recorded `wipes` and
 * `ratchetDeletes` are theirs. Everything the accept decides on is kept by the REAL [ZchatPreferencesImpl] over
 * in-memory files ([realPreferences]): both key pairs' entries, the key-changed flag, the verified flag, the
 * remembered refused key, the key-exchange txids and the conversation-id mapping. So the record's rules (one slot, the
 * flag in the same commit, compare-and-remove) are the production store's, not a fake's.
 *
 * - [writes] lists every write the ViewModel made through the store, in order; [killAfterWrites] and [killAtWrite] end
 *   "the process" at a write: that write and every later one throw [SyntheticKill] until [revive].
 * - The new API (ZC-545) is reached by name ([KeyAcceptApi]), so this file compiles before it exists.
 *
 * Every address and key is synthetic.
 */
internal class KeyChangeAcceptChat(
    val peer: String,
    mode: ConversationMode = ConversationMode.VAULT,
    consent: Boolean = false,
    // ZC-660: opt-in; true lets the automatic answers' rolling budget (tryConsumeKexAckBudget) grant a unit and counts it
    // in [budgetUsed]. Off, the lenient store answers false, as every earlier test of this fixture ran.
    private val kexAckBudget: Boolean = false,
) {
    val budgetUsed = AtomicInteger(0)

    val our: E2EKeyPair = E2EEncryption.generateKeyPair()
    val oldContact: E2EKeyPair = E2EEncryption.generateKeyPair()
    val reconnect =
        KeyChangeReconnectChat(peer, consent = consent, mode = mode, exchangeComplete = true, contactKeys = oldContact)
    val owed: ResetOwedChat get() = reconnect.owed
    val reset: ResetFixture get() = reconnect.owed.reset
    val seam: ResetKexSeam get() = reconnect.seam

    val impl: ZchatPreferencesImpl
    val files: Map<String, MemorySharedPreferences>

    val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val writeCount = AtomicInteger(0)

    @Volatile var killAfterWrites: Int? = null

    @Volatile var killAtWrite: String? = null

    @Volatile var killed = false

    class SyntheticKill(
        at: String,
    ) : RuntimeException("synthetic kill at $at")

    init {
        val (real, realFiles) = realPreferences()
        impl = real
        files = realFiles
        impl.setE2EOurKeys(peer, our.publicKey, our.privateKey)
        impl.setE2EKeyVersion(peer, KEY_VERSION)
        impl.setE2EKeyProvenance(peer, PROVENANCE)
        impl.setE2EPeerPublicKey(peer, oldContact.publicKey)
        impl.setE2EEnabled(peer, true)
        impl.setE2EVerified(peer, true)
        impl.setConversationId(peer, CONV_ID)
    }

    /** Ends "the process" after [n] more writes. */
    fun killAfter(n: Int) {
        writeCount.set(0)
        killed = false
        killAfterWrites = n
    }

    fun revive() {
        killAfterWrites = null
        killAtWrite = null
        killed = false
    }

    val store: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val name = method.name

            fun on(target: Any): Any? =
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            if (WRITE_PREFIXES.any { name.startsWith(it) }) {
                val n = writeCount.incrementAndGet()
                val limit = killAfterWrites
                // ZC-634: a kill at the contact key's write ("setE2EPeerPublicKey") is a kill at its one write since part B.
                val atKill = name == killAtWrite || (killAtWrite == "setE2EPeerPublicKey" && name == "storePeerKey")
                if (killed || (limit != null && n > limit) || atKill) {
                    killed = true
                    throw SyntheticKill(name)
                }
                writes += name
            }
            when {
                name == "hashCode" -> System.identityHashCode(p)
                name == "equals" -> p === args?.get(0)
                name == "toString" -> "KeyChangeAcceptStore"
                name == "tryConsumeKexAckBudget" && kexAckBudget -> {
                    budgetUsed.incrementAndGet()
                    true
                }
                name == "clearE2EKeys" -> {
                    on(impl)
                    on(reconnect.store)
                }
                name == "clearE2EPeerState" -> {
                    if (args?.getOrNull(0) == peer) reset.wipes += name
                    on(impl)
                }
                name in REAL -> on(impl)
                else -> on(reconnect.store)
            }
        } as ZchatPreferences

    /** A chat screen's ViewModel over this chat's store; its processor cache and ratchet store are [ResetFixture]'s. */
    suspend fun viewModel(accounts: AccountDataSource? = null): ChatViewModel {
        val vm = reconnect.viewModel(accounts)
        AutoSpendVm.setField(vm, "zchatPreferences", store)
        AutoSpendVm.setField(vm, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == "decryptFailedPeers" && !Modifier.isStatic(it.modifiers) }
            ?.let {
                it.isAccessible = true
                it.set(vm, ConcurrentHashMap.newKeySet<String>())
            }
        return vm
    }

    fun kexMemo(
        keys: E2EKeyPair,
        convId: String = CONV_ID,
    ): String =
        ZMSGProtocol.createV4KEXMessage(convId, peer, E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey))

    fun kexAckMemo(
        keys: E2EKeyPair,
        convId: String = CONV_ID,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            peer,
            E2EEncryption.createKEXAckPayload(peer, keys.publicKey, keys.privateKey),
        )

    /** The contact's signed KEX carrying [keys]'s public key reaches [vm]'s inbound handler. @return the memo. */
    suspend fun kexFromContact(
        vm: ChatViewModel,
        keys: E2EKeyPair,
        txId: String,
        convId: String = CONV_ID,
    ): String = deliver(vm, kexMemo(keys, convId), txId)

    /** The contact's signed KEXACK carrying [keys]'s public key reaches [vm]'s inbound handler. @return the memo. */
    suspend fun kexAckFromContact(
        vm: ChatViewModel,
        keys: E2EKeyPair,
        txId: String,
        convId: String = CONV_ID,
    ): String = deliver(vm, kexAckMemo(keys, convId), txId)

    suspend fun deliver(
        vm: ChatViewModel,
        memo: String,
        txId: String,
    ): String {
        HandshakeHarness.handleKexMemo(vm, memo, txId)
        awaitIdle(vm)
        return memo
    }

    fun flag(): Boolean = impl.isE2EKeyChanged(peer)

    fun storedKey(): String? = impl.getE2EPeerPublicKey(peer)

    /** The remembered refused key's record, or null. Fails when the store has no such API (before ZC-545). */
    fun record(): Any? = KeyAcceptApi.record(impl, peer)

    fun recordKey(): String? = record()?.let { KeyAcceptApi.get(it, "publicKey") as String }

    /** Whether a user send to the contact is refused by the key-change gate now. */
    fun sendsRefused(vm: ChatViewModel): Boolean =
        ChatViewModel::class.java
            .getDeclaredMethod("blockedByKeyChange", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, peer) as Boolean

    /** The paid-action question on screen as (action name, on-chain count), or null when none is shown. */
    fun question(vm: ChatViewModel): Pair<String, Int>? {
        val state = HandshakeHarness.sendMessageState(vm)
        if (state.javaClass.simpleName != "NeedsPaidActionConfirmation") return null
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val action = confirmation.javaClass.getMethod("getAction").invoke(confirmation) as Enum<*>
        val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
        return action.name to count
    }

    fun error(vm: ChatViewModel): String? = (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message

    /** Waits until nothing of an accept, a reconnect or a reset runs any more and no new proposal appears. */
    suspend fun settle(vm: ChatViewModel) {
        var seen = -1
        while (seen != seam.proposals()) {
            seen = seam.proposals()
            awaitIdle(vm)
            awaitTrue { peer !in KeyAcceptApi.reconnectsInFlight() }
            awaitIdle(vm)
        }
    }

    /**
     * Taps "Accept this key" for ([refusedKey], [ourKey]) and, when a price question appears, answers it ([pay]: Pay,
     * else nothing). @return the question that was shown, or null.
     */
    suspend fun accept(
        vm: ChatViewModel,
        refusedKey: String,
        ourKey: String = our.publicKey,
        pay: Boolean = true,
    ): Pair<String, Int>? {
        vm.resetSendState()
        KeyAcceptApi.accept(vm, peer, refusedKey, ourKey)
        settle(vm)
        val asked = question(vm)
        if (asked != null && pay) {
            vm.confirmPaidAction()
            settle(vm)
        }
        return asked
    }

    /** The banner goes up: the contact's KEX with [keys] is refused. @return the memo. */
    suspend fun raiseBanner(
        vm: ChatViewModel,
        keys: E2EKeyPair,
        txId: String = "synthetic-zc545-kex-${keys.publicKey.takeLast(6)}",
    ): String = kexFromContact(vm, keys, txId)

    fun assertAdopted(
        keys: E2EKeyPair,
        whenText: String,
    ) {
        assertEquals("the contact's stored key must be the key the user accepted $whenText", keys.publicKey, storedKey())
        assertEquals("our own public key must stay $whenText", our.publicKey, impl.getE2EOurPublicKey(peer))
        assertEquals("our own private key must stay $whenText", our.privateKey, impl.getE2EPrivateKey(peer))
        assertEquals("the flag must be down $whenText", false, flag())
        assertEquals("the record must be gone $whenText", null, record())
    }

    fun assertNothingAdopted(whenText: String) {
        assertEquals("the contact's OLD key must stay stored $whenText", oldContact.publicKey, storedKey())
        assertEquals("our own key must stay $whenText", our.publicKey, impl.getE2EOurPublicKey(peer))
        assertEquals("no ratchet state may be deleted $whenText", emptyList<String>(), reset.ratchetDeletes.toList())
        assertEquals("nothing may be wiped $whenText", emptyList<String>(), reset.wipes.toList())
        assertEquals("nothing may be built $whenText", 0, seam.proposals())
        assertEquals("nothing may be submitted $whenText", 0, seam.submitted.size)
        assertEquals("nothing may be owed $whenText", false, peer in owed.kexOwed)
        assertEquals("no allowance may be written $whenText", false, owed.zbootQuoted[peer] == true)
    }

    companion object {
        const val CONV_ID = ResetFixture.CONV_ID
        const val KEY_VERSION = 2
        const val PROVENANCE = "synthetic-provenance"
        val WRITE_PREFIXES = listOf("set", "store", "put", "mark", "clear", "remember", "take", "dismiss", "remove", "add", "bind")

        /** The calls the real store answers: keys, trust flags, the record, key-exchange txids, conversation ids. */
        val REAL =
            setOf(
                "getE2EPeerPublicKey",
                "setE2EPeerPublicKey",
                "getE2EOurPublicKey",
                "getE2EPrivateKey",
                "setE2EOurKeys",
                "getE2EKeyVersion",
                "setE2EKeyVersion",
                "getE2EKeyProvenance",
                "setE2EKeyProvenance",
                "isE2EKeyChanged",
                "setE2EKeyChanged",
                "isE2EVerified",
                "setE2EVerified",
                "isE2EKeyExchangeComplete",
                "isE2EEnabled",
                "setE2EEnabled",
                "setE2EKexTxId",
                "getE2EKexTxId",
                "setE2EKexAckTxId",
                "getE2EKexAckTxId",
                "getKexTxIds",
                "getKexAckTxIds",
                "getRefusedKeyOffer",
                "hasRefusedKeyOffer",
                "rememberRefusedKeyOffer",
                "takeRefusedKeyOffer",
                "dismissRefusedKeyOffer",
                "getConversationId",
                "setConversationId",
                "getPeerByConversationId",
                "getAllConversationMappings",
                "getOrCreateConversationId",
                "getOrCreateConversationIdForMessage",
                // FC-ID (ZC-892): the own-id calls, answered by the real store too.
                "ownConversationId",
                "ownConversationIdForStart",
                "isOwnConversationId",
                "restoreOwnConversationId",
                // ZC-599, ZC-893 (FC-2B): the asked record lives in the key store beside the contact key.
                "getKexAskedKey",
                "setKexAskedKey",
                // ZC-634: the key's one write with its claim mark, and the mark's calls, in the same real key store.
                "storePeerKey",
                "getKeyClaim",
                "markKeyClaim",
                "getHeldClaimBoot",
                "holdClaimedBoot",
                "releaseKeyClaim",
                // ZC-660: the key an accept adopted (reached by name; the real store keeps it beside the keys).
                "getAcceptedKey",
                "setAcceptedKey",
                // ZC-739-R3: the refused key an accept wrote its owed key exchange for.
                "getAcceptOwedKey",
                "setAcceptOwedKey",
            )
    }
}

/** ZC-545 — the new API, reached by name so the RED tests compile before it exists; a missing member fails the test. */
internal object KeyAcceptApi {
    private fun prefsMethod(name: String): Method {
        val m = ZchatPreferences::class.java.methods.firstOrNull { it.name == name }
        assertNotNull("ZC-545: ZchatPreferences.$name does not exist (nothing remembers a refused key)", m)
        return m!!
    }

    private fun vmMethod(name: String): Method {
        val m = ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == name }
        assertNotNull("ZC-545: ChatViewModel.$name does not exist (the accept never adopts the new key)", m)
        return m!!.apply { isAccessible = true }
    }

    private fun call(
        method: Method,
        target: Any,
        vararg args: Any?,
    ): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    fun record(
        store: ZchatPreferences,
        peer: String,
    ): Any? = call(prefsMethod("getRefusedKeyOffer"), store, peer)

    fun has(
        store: ZchatPreferences,
        peer: String,
    ): Boolean = call(prefsMethod("hasRefusedKeyOffer"), store, peer) as Boolean

    fun remember(
        store: ZchatPreferences,
        peer: String,
        key: String,
        memo: String,
        txId: String?,
        nowMs: Long,
    ): Boolean = call(prefsMethod("rememberRefusedKeyOffer"), store, peer, key, memo, txId, nowMs) as Boolean

    fun take(
        store: ZchatPreferences,
        peer: String,
        key: String,
    ): Any? = call(prefsMethod("takeRefusedKeyOffer"), store, peer, key)

    fun dismissInStore(
        store: ZchatPreferences,
        peer: String,
        refusedKey: String? = null,
    ): Boolean {
        val method =
            ZchatPreferences::class.java.methods.firstOrNull { it.name == "dismissRefusedKeyOffer" && it.parameterCount == 2 }
                ?: prefsMethod("dismissRefusedKeyOffer")
        return when (method.parameterCount) {
            2 -> {
                val key = refusedKey ?: record(store, peer)?.let { get(it, "publicKey") as String }.orEmpty()
                call(method, store, peer, key) as Boolean
            }
            1 -> call(method, store, peer) as Boolean
            else -> error("dismissRefusedKeyOffer has an unsupported arity")
        }
    }

    fun clearPeerState(
        store: ZchatPreferences,
        peer: String,
    ) {
        call(prefsMethod("clearE2EPeerState"), store, peer)
    }

    /** A property of a record or of the view object, by its Kotlin name. */
    fun get(
        target: Any,
        property: String,
    ): Any? {
        val getter =
            target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "get" + property.replaceFirstChar(Char::uppercase) || it.name == property)
            }
        assertNotNull("ZC-545: ${target.javaClass.simpleName} has no $property", getter)
        return call(getter!!, target)
    }

    fun accept(
        vm: ChatViewModel,
        peer: String,
        refusedKey: String,
        ourKey: String,
    ) {
        call(vmMethod("acceptRefusedKey"), vm, peer, refusedKey, ourKey)
    }

    /** "Not their key". */
    fun dismiss(
        vm: ChatViewModel,
        peer: String,
        refusedKey: String? = null,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .firstOrNull { it.name == "dismissRefusedKeyOffer" && it.parameterCount == 2 }
                ?.apply { isAccessible = true }
                ?: vmMethod("dismissRefusedKeyOffer")
        when (method.parameterCount) {
            2 -> {
                val key = refusedKey ?: view(vm, peer)?.let { get(it, "refusedKey") as String }.orEmpty()
                call(method, vm, peer, key)
            }
            1 -> call(method, vm, peer)
            else -> error("dismissRefusedKeyOffer has an unsupported arity")
        }
    }

    /** What the dialog shows for the remembered key (ChatViewModel.refusedKeyOffer), or null. */
    fun view(
        vm: ChatViewModel,
        peer: String,
    ): Any? = call(vmMethod("refusedKeyOffer"), vm, peer)

    fun changedAgainText(): String = copyConstant("KEY_CHANGE_CHANGED_AGAIN")

    fun copyConstant(name: String): String {
        val field = ChatSendCopy::class.java.declaredFields.firstOrNull { it.name == name }
        assertNotNull("ZC-545: ChatSendCopy.$name does not exist", field)
        field!!.isAccessible = true
        return field.get(null) as String
    }

    @Suppress("UNCHECKED_CAST")
    fun reconnectsInFlight(): Set<String> =
        ChatViewModel::class.java
            .getDeclaredField("reconnectsInFlight")
            .apply { isAccessible = true }
            .get(null) as Set<String>

    /** The per-contact session lock of ZC-545 (companion `peerSessionLocks`), created when absent. */
    @Suppress("UNCHECKED_CAST")
    fun peerSessionLock(peer: String): kotlinx.coroutines.sync.Mutex {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "peerSessionLocks" }
        assertNotNull("ZC-545, ZC-564: no per-contact session lock exists (peerSessionLocks)", field)
        field!!.isAccessible = true
        val locks = field.get(null) as ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>
        return locks.computeIfAbsent(peer) { kotlinx.coroutines.sync.Mutex() }
    }

    /** Calls a private suspend member of the ViewModel by name with [args]. */
    suspend fun callSuspend(
        vm: ChatViewModel,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
        assertNotNull("ChatViewModel.$name with ${args.size} arguments not found", method)
        method!!.isAccessible = true
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(vm, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /** The ViewModel's own processor for ([peer], [convId]), through its real lookup (never a seeded one). */
    suspend fun processor(
        vm: ChatViewModel,
        peer: String,
        convId: String,
    ): E2EMessageProcessor? = callSuspend(vm, "getOrCreateMessageProcessorUngated", peer, convId, false) as E2EMessageProcessor?

    /** Reset encryption's wipe as the confirmed paid reset runs it (its KEX owed: no bootstrap is started). */
    suspend fun resetWipe(
        vm: ChatViewModel,
        peer: String,
    ) {
        callSuspend(vm, "wipeAndReestablishSecureSession", peer, true)
    }

    suspend fun decrypt(
        vm: ChatViewModel,
        content: String,
        peer: String,
        convId: String,
    ): String = callSuspend(vm, "tryDecryptMessage", content, peer, convId, null) as String
}

/**
 * ZC-545, ZC-564 — a ratchet state store whose [load], [save] and [delete] can each be held, and which records what
 * reached the store, in order ([events]: "load:<id>", "save:<id>", "delete:<id>"; a held call is recorded when it is
 * let through). [parked] completes when a call reaches its gate.
 */
internal class GatedRatchetStore(
    private val inner: InMemoryRatchetStateStore = InMemoryRatchetStateStore(),
) : RatchetStateStore by inner {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var holdLoad: CompletableDeferred<Unit>? = null

    @Volatile var holdSave: CompletableDeferred<Unit>? = null

    @Volatile var holdDelete: CompletableDeferred<Unit>? = null

    @Volatile var parked = CompletableDeferred<String>()

    private suspend fun gate(
        what: String,
        hold: CompletableDeferred<Unit>?,
    ) {
        if (hold != null && !hold.isCompleted) {
            parked.complete(what)
            hold.await()
        }
    }

    override suspend fun load(convId: String): RatchetConversationState? {
        gate("load", holdLoad)
        events += "load:$convId"
        return inner.load(convId)
    }

    override suspend fun save(state: RatchetConversationState) {
        gate("save", holdSave)
        events += "save:${state.convId}"
        inner.save(state)
    }

    override suspend fun delete(convId: String) {
        gate("delete", holdDelete)
        events += "delete:$convId"
        inner.delete(convId)
    }

    suspend fun stateOf(convId: String): RatchetConversationState? = inner.load(convId)

    fun deletes(): List<String> = events.toList().filter { it.startsWith("delete:") }

    /** Whether a save reached the store after a delete of the same conversation id (old-root state resurrected). */
    fun saveAfterDelete(convId: String): Boolean {
        val all = events.toList()
        val firstDelete = all.indexOf("delete:$convId")
        return firstDelete >= 0 && all.drop(firstDelete).any { it == "save:$convId" }
    }
}

/**
 * ZC-545 — the harness every accept test runs in: the process-wide send scopes, an unconfined Main, the history read,
 * and the process-wide handshake state cleared before and after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class KeyChangeAcceptTestBase {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun openAcceptHarness() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetProcessWide()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun closeAcceptHarness() {
        sendScopes.close()
        Dispatchers.resetMain()
        resetProcessWide()
    }

    private fun resetProcessWide() {
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        co.electriccoin.zcash.ui.nostr.NostrChatBridge
            .unregisterPublisher()
        for (name in listOf("reconnectsInFlight", "peerSessionLocks", "peerSessionEpochs", "kexAckedKeys")) {
            val field =
                ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
                    ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableCollection<*>)?.clear()
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }
}
