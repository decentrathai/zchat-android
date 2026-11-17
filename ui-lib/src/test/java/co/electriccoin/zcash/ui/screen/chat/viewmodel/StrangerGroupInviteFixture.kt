package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 8 D4 — the preferences of one phone that can tell a stranger from a known address, for the group-invite
 * tests. It is [AutoSpendPrefs] (the handshake, group and held-accept state) with four more answers:
 *
 * - `isE2EEnabled` is true only for [e2eEnabled] (AutoSpendPrefs answers true for everybody, so nobody would be a
 *   first contact);
 * - `isSelfAddress` is true for [selfAddresses];
 * - the needs-join records (`getGroupInviteNeedsJoin` / `setGroupInviteNeedsJoin`) live in [needsJoin]; with
 *   [needsJoinWritesFail] a write reports that it did not reach the disk;
 * - every `getPeerNostrPubkey` call is counted in [nostrLookups]: the free NOSTR reply starts with that lookup, so a
 *   count of 0 proves no NOSTR send was even tried. Every write is logged in [writes].
 *
 * The needs-join calls are answered by name, so this file compiles before the store has them. All addresses, keys
 * and group ids are synthetic.
 */
internal class StrangerInvitePrefs(
    consent: Boolean,
) {
    val base = AutoSpendPrefs(consent = consent)
    val e2eEnabled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val selfAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val needsJoin = ConcurrentHashMap<String, String>()
    val needsJoinWritesFail = AtomicBoolean(false)

    /** When true, a waiting record is stored as a returned Join leaves it (the relay-failed flag set); see [withRelayTryFailed]. */
    @Volatile var needsJoinAfterRelayTry: Boolean = false
    val nostrLookups = AtomicInteger(0)
    val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            if (method.name.startsWith("set") || method.name.startsWith("save") || method.name.startsWith("delete")) {
                writes += method.name
            }
            when (method.name) {
                "isE2EEnabled" -> arg(0) in e2eEnabled
                "isSelfAddress" -> arg(0) in selfAddresses
                "getPeerNostrPubkey" -> {
                    nostrLookups.incrementAndGet()
                    null
                }
                "getGroupInviteNeedsJoin" -> needsJoin[arg(0) as String]
                "setGroupInviteNeedsJoin" ->
                    if (needsJoinWritesFail.get()) {
                        false
                    } else {
                        val value = arg(1) as String?
                        if (value == null) {
                            needsJoin.remove(arg(0) as String)
                        } else {
                            needsJoin[arg(0) as String] = if (needsJoinAfterRelayTry) withRelayTryFailed(value) else value
                        }
                        true
                    }
                "deleteGroup" -> {
                    // As ZchatPreferences.deleteGroup: the group, its held reply and its waiting invite go.
                    base.groupInfo.remove(arg(0) as String)
                    base.heldGroupAccepts.remove(arg(0) as String)
                    needsJoin.remove(arg(0) as String)
                    null
                }
                "toString" -> "StrangerInvitePrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(base.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}

/** An address book holding exactly [saved] (the R2-2 (a) "saved contact" test). */
internal class SavedContacts(
    private val saved: Set<String>,
) : ContactBook {
    override fun addContact(contact: Contact) = Unit

    override fun removeContact(address: String) = Unit

    override fun getContact(address: String): Contact? = null

    override fun getAllContacts(): List<Contact> = emptyList()

    override fun hasContact(address: String): Boolean = address in saved

    override fun updateContactName(
        address: String,
        newName: String
    ) = Unit

    override fun clearAll() = Unit
}

/** The group-invite paths of ChatViewModel, driven as the history scan and the group screen drive them. */
internal object GroupInviteDriver {
    val GROUP_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7).toByte() })

    /** A legacy invite carrying the group key in plaintext (what 2.13.0 sends to someone it has no KEX with). */
    fun plaintextInvite(inviter: String): String =
        JSONObject()
            .put("name", "Synthetic invite group")
            .put("inviter", inviter)
            .put("key_epoch", 0)
            .put("group_key", GROUP_KEY)
            .put("members", JSONArray().put(inviter).put(HandshakeHarness.SELF))
            .toString()

    /** A compact invite whose key is wrapped under the finished key exchange ([sharedKey]) with [inviter]. */
    fun sessionInvite(
        inviter: String,
        sharedKey: ByteArray,
    ): String =
        JSONObject()
            .put("name", "Synthetic session group")
            .put("inviter", inviter)
            .put("key_epoch", 0)
            .put("k2", E2EEncryption.encrypt(GROUP_KEY, sharedKey))
            .toString()

    /**
     * Gives [prefs] a finished key exchange with [peer] whose session unwraps a k2 invite, as ChatViewModel reads
     * it (our key pair, the peer's public key, key version 0 = V1). @return the session key.
     */
    fun finishedKeyExchange(
        prefs: StrangerInvitePrefs,
        peer: String,
    ): ByteArray {
        val ours = prefs.base.session(peer, complete = true)
        val theirs = E2EEncryption.generateKeyPair()
        prefs.base.peerKeys[peer] = theirs.publicKey
        prefs.e2eEnabled += peer
        return E2EEncryption.deriveSharedSecret(ours.privateKey, theirs.publicKey, E2EKeyVersion.fromValue(0))
    }

    /** The real invite handler, as the history scan calls it for an on-chain GROUP_INVITE (no NOSTR sender). */
    fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        payload: String,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "processGroupInvite",
                String::class.java,
                String::class.java,
                TransactionId::class.java,
                Instant::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(vm, groupId, payload, null, Instant.EPOCH, null, null)
    }

    /** A new chain tip, as observeBlockHeight delivers it. */
    suspend fun chainTip(
        vm: ChatViewModel,
        height: Long,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
        HandshakeHarness.awaitIdle(vm)
    }

    /**
     * The group screen's Join (called by name: it does not exist before round 8 D4; round 8 P1 renamed it
     * joinGroupInvite, keeping joinStrangerGroupInvite as a deprecated alias). @return what Join returned.
     */
    suspend fun join(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean {
        val method =
            ChatViewModel::class.java.methods.firstOrNull {
                it.name == "joinGroupInvite" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
                ?: ChatViewModel::class.java.getMethod("joinStrangerGroupInvite", String::class.java)
        val result = method.invoke(vm, groupId) as Boolean
        HandshakeHarness.awaitIdle(vm)
        return result
    }

    /** The group screen's Ignore (called by name, like [join]). */
    suspend fun ignore(
        vm: ChatViewModel,
        groupId: String,
    ) {
        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == "ignoreGroupInvite" }
                ?: ChatViewModel::class.java.getMethod("ignoreStrangerGroupInvite", String::class.java)
        method.invoke(vm, groupId)
        HandshakeHarness.awaitIdle(vm)
    }

    /** The process-wide automatic setups the consent question would quote (queuedAutomaticSetups). */
    fun queuedSetups(): Set<Any?> =
        ChatViewModel::class.java
            .getDeclaredField("queuedAutomaticSetups")
            .apply { isAccessible = true }
            .let { (it.get(null) as Collection<*>).toSet() }

    /** Whether the process holds a GROUP_ACCEPT for [groupId] in memory (heldGroupAccepts). */
    fun heldInMemory(groupId: String): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAccepts")
            .apply { isAccessible = true }
            .let { (it.get(null) as Map<*, *>).containsKey(groupId) }

    /** Round 8 D4: the once-per-process Keystone explanation flag, when this build has it. */
    fun resetKeystoneExplanation() {
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == "keystoneAutomaticSetupExplained" && Modifier.isStatic(it.modifiers) }
            ?.let { field ->
                field.isAccessible = true
                (field.get(null) as? AtomicBoolean)?.set(false)
            }
    }
}
