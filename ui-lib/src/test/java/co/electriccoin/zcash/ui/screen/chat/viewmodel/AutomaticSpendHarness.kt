package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared fixture for the P2b automatic-spend tests (KEX / ZBOOT / GROUP_ACCEPT / Reconnect).
 *
 * [AutoSpendPrefs] is a map-backed preferences store that answers the handshake, bootstrap and group-join
 * calls from real state and every other call with the type's empty value. Every call is recorded in
 * [AutoSpendPrefs.calls]. Because it is lenient, every test that asserts "nothing was paid" is paired with
 * a control that pays through the same fixture, so a path that ends early for a fixture reason fails the
 * control instead of passing the RED assertion.
 *
 * All keys are generated per run and every address is synthetic.
 */
internal class AutoSpendPrefs(
    consent: Boolean = true,
) {
    @Volatile var consent: Boolean = consent

    val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
    val peerKeys = ConcurrentHashMap<String, String>()
    val complete: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val ownBootSent = ConcurrentHashMap<String, Boolean>()
    val sentNostrBoot = ConcurrentHashMap<String, String>()
    val modes = ConcurrentHashMap<String, ConversationMode>()
    val paidRecords = ConcurrentHashMap<String, String>()
    val groupInfo = ConcurrentHashMap<String, String>()

    /** R3-4: the durable held GROUP_ACCEPT records, by group id (they survive a simulated app kill). */
    val heldGroupAccepts = ConcurrentHashMap<String, String>()

    /**
     * Round 8 P1: the durable needs-join records (an invite whose paid "I joined" reply waits for Join), by group id.
     * With [needsJoinWritesFail] a write reports that it did not reach the disk.
     */
    val needsJoin = ConcurrentHashMap<String, String>()
    val needsJoinWritesFail = AtomicBoolean(false)

    /**
     * [test-change-ok: W-137 ZC-922 ZC-633 review follow-up] A known contact's fresh waiting record shows no price, so its
     * first Join only tries the relays and puts the reply back (KnownContactJoinShowsItsPriceBeforePayingTest). The tests
     * that use this fixture are about what a PAID Join keeps, claims, retries and pays, so they start from the record such a
     * return leaves: the relay-failed flag set (the paid banner with its price on screen). Set false for a fresh record.
     */
    @Volatile var needsJoinAfterRelayTry: Boolean = true
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Gives [peer] a completed-or-pending E2E session with us. */
    fun session(
        peer: String,
        complete: Boolean,
        mode: ConversationMode = ConversationMode.VAULT,
    ): E2EKeyPair {
        val ours = E2EEncryption.generateKeyPair()
        ourKeys[peer] = ours
        if (complete) {
            peerKeys[peer] = E2EEncryption.generateKeyPair().publicKey
            this.complete += peer
        }
        modes[peer] = mode
        return ours
    }

    /** A valid 8-character conversation id per peer (the KEX builders validate it). */
    fun convIdFor(peer: String): String = "%08X".format(peer.hashCode())

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            calls += method.name
            when (method.name) {
                "hasConsentedToHandshakeSpend" -> this@AutoSpendPrefs.consent
                "setConsentedToHandshakeSpend" -> {
                    this@AutoSpendPrefs.consent = true
                    // R5-2: a working store reports its write.
                    true
                }
                "clearConsentedToHandshakeSpend" -> {
                    this@AutoSpendPrefs.consent = false
                    // R4-1: a working store reports its write; ignored while the setter still returns Unit.
                    true
                }
                "getE2EOurPublicKey" -> ourKeys[arg(0) as String]?.publicKey
                "getE2EPrivateKey" -> ourKeys[arg(0) as String]?.privateKey
                "getE2EPeerPublicKey" -> peerKeys[arg(0) as String]
                "setE2EPeerPublicKey" -> {
                    peerKeys[arg(0) as String] = arg(1) as String
                    null
                }
                // ZC-634: the one write of a contact key (no claim marks are modelled here); this store always saves it.
                "storePeerKey" -> {
                    peerKeys[arg(0) as String] = arg(1) as String
                    true
                }
                "isE2EKeyExchangeComplete" -> (arg(0) as String) in complete
                "isE2EEnabled" -> true
                "getOrCreateConversationId" -> convIdFor(arg(0) as String) to false
                // R2-6 (c): the per-message call (an existing conversation whose INIT went out).
                "getOrCreateConversationIdForMessage" -> convIdFor(arg(0) as String) to false
                // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                "ownConversationId", "ownConversationIdForStart" -> convIdFor(arg(0) as String)
                "getPeerByConversationId" -> ourKeys.keys.firstOrNull { convIdFor(it) == arg(0) }
                "getConversationMode" -> modes[arg(0) as String] ?: ConversationMode.VAULT
                "getTunnelModePeers" -> modes.filterValues { it == ConversationMode.TUNNEL }.keys.toList()
                "isOwnBootSent" -> ownBootSent[arg(0) as String] ?: false
                "setOwnBootSent" -> {
                    ownBootSent[arg(0) as String] = arg(1) as Boolean
                    // R5-2: a working store reports its write.
                    true
                }
                "getSentNostrBootPubkey" -> sentNostrBoot[arg(0) as String]
                "setSentNostrBootPubkey" -> {
                    val value = arg(1) as String?
                    if (value == null) sentNostrBoot.remove(arg(0) as String) else sentNostrBoot[arg(0) as String] = value
                    // R5-2: a working store reports its write.
                    true
                }
                "getHandshakePaidRecord" -> paidRecords[arg(0) as String]
                "setHandshakePaidRecord" -> {
                    val value = arg(1) as String?
                    if (value == null) paidRecords.remove(arg(0) as String) else paidRecords[arg(0) as String] = value
                    // R4-1: a working store reports its write; ignored while the setter still returns Unit.
                    true
                }
                "getNostrRotationIndex" -> 0
                "getGroupInfo" -> groupInfo[arg(0) as String]
                "saveGroupInfo" -> {
                    groupInfo[arg(0) as String] = arg(1) as String
                    null
                }
                "deleteGroup" -> {
                    groupInfo.remove(arg(0) as String)
                    // Round 8 P1: as ZchatPreferences.deleteGroup, its waiting invite goes too.
                    needsJoin.remove(arg(0) as String)
                    null
                }
                // Round 8 P1: named here so the fixture keeps them (the paid group reply waits for Join).
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
                // R3-4: named here so the fixture keeps them before the store has these calls.
                "getHeldGroupAccepts" -> HashMap(heldGroupAccepts)
                "setHeldGroupAccept" -> {
                    val value = arg(1) as String?
                    if (value == null) heldGroupAccepts.remove(arg(0) as String) else heldGroupAccepts[arg(0) as String] = value
                    // R4-1: a working store reports its write; ignored while the setter still returns Unit.
                    true
                }
                "resolvePeerAddress" -> arg(0)
                "toString" -> "AutoSpendPrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> emptyValueFor(method.returnType)
            }
        } as ZchatPreferences

    private fun emptyValueFor(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }
}

/** A wallet provider holding the SDK's public test seed, so our NOSTR identity derives on the JVM. */
internal object FixtureSeedWalletProvider : PersistableWalletProvider {
    private val wallet =
        PersistableWallet(
            network = ZcashNetwork.Mainnet,
            endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
            birthday = BlockHeight.new(3_000_000L),
            seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
            walletInitMode = WalletInitMode.ExistingWallet,
        )

    override val persistableWallet: Flow<PersistableWallet?> = flowOf(wallet)

    override suspend fun store(persistableWallet: PersistableWallet) = Unit

    override suspend fun getPersistableWallet(): PersistableWallet = wallet

    override suspend fun requirePersistableWallet(): PersistableWallet = wallet
}

internal object EmptyContacts : ContactBook {
    override fun addContact(contact: Contact) = Unit

    override fun removeContact(address: String) = Unit

    override fun getContact(address: String): Contact? = null

    override fun getAllContacts(): List<Contact> = emptyList()

    override fun hasContact(address: String): Boolean = false

    override fun updateContactName(
        address: String,
        newName: String
    ) = Unit

    override fun clearAll() = Unit
}

/** Reflection helpers for the private ChatViewModel members these tests drive. */
internal object AutoSpendVm {
    fun setField(
        vm: ChatViewModel,
        name: String,
        value: Any
    ) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    /** Wires the fields the ZBOOT and group-invite paths read that the handshake harness leaves unset. */
    fun wireBootstrapFields(vm: ChatViewModel) {
        setField(vm, "persistableWalletProvider", FixtureSeedWalletProvider)
        setField(vm, "contactBook", EmptyContacts)
        val fields = ChatViewModel::class.java.declaredFields
        val pending = fields.firstOrNull { it.name == "pendingGroupInvites" && !Modifier.isStatic(it.modifiers) }
        pending?.isAccessible = true
        pending?.set(vm, ConcurrentHashMap<String, Any>())
    }

    fun sendNostrBoot(
        vm: ChatViewModel,
        peer: String
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("sendNostrBootHandshake", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, peer)
    }

    /**
     * Sets the process-wide "the history scan has been read" flag when this build has one. Builds before
     * P2b have no such gate, so the call is a no-op there.
     */
    fun setHistoryRead(read: Boolean) {
        val fields = ChatViewModel::class.java.declaredFields
        val holder = fields.firstOrNull { it.name == "processHistoryRead" && Modifier.isStatic(it.modifiers) } ?: return
        holder.isAccessible = true
        (holder.get(null) as AtomicBoolean).set(read)
    }

    /** Drops every process-wide automatic-spend bookkeeping entry a previous test may have left. */
    fun resetProcessState() {
        setHistoryRead(false)
        // C5: also the held GROUP_ACCEPTs claimed by a send in flight, and those whose wait for the wallet was logged.
        for (name in listOf(
            "queuedAutomaticSetups",
            "dismissedAutomaticSetups",
            "claimedGroupAccepts",
            "heldGroupAcceptsWaitLogged",
            // ZC-952: the kept admin changes a refused write left in memory, process-wide.
            "keptChangesNotWritten",
            // ZC-634 part B: the legs the history pass judged answers by, and its verdicts on this wallet's own answers.
            "answerLegByTx",
            "ownAnswerVerdicts",
            // ZC-599 (round 3, recheck finding 3): the old answers no own key exchange backs, as the last pass judged them.
            "unbackedOldAnswerTx",
            "unbackedOldAnswerKeys",
        )) {
            val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableCollection<*>)?.clear()
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
        // R2-2 (d): GROUP_ACCEPTs held for consent are process-wide too.
        ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "heldGroupAccepts" }?.let { field ->
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
        // R3-4: the once-per-process restore of the durable held GROUP_ACCEPTs, when this build has it.
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == "heldGroupAcceptsRestored" && Modifier.isStatic(it.modifiers) }
            ?.let { field ->
                field.isAccessible = true
                (field.get(null) as? AtomicBoolean)?.set(false)
            }
    }
}

/**
 * The waiting record of an invite whose first Join came back unpaid: [record] with the relay-failed flag set, the way
 * ChatViewModel.returnGroupAcceptToJoin writes it. A record that is not a JSON object is returned as it was.
 */
internal fun withRelayTryFailed(record: String): String =
    try {
        org.json
            .JSONObject(record)
            .put(co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord.RELAY_FAILED, true)
            .toString()
    } catch (e: org.json.JSONException) {
        record
    }
