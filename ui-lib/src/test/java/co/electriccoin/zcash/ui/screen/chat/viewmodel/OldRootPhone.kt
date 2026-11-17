package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-1792 (P-A) test support: ONE phone's chat with one contact, run through the REAL history pass (convertToConversations,
 * [HandshakePassFixture]) with the REAL ratchet code path (tryDecryptMessage -> E2EMessageProcessor over [ratchetStore]).
 * What the phone stored is set up directly, as the 12 Nov HONOR held it: the KEX/KEXACK txid sets and the legacy scalars the
 * current root reads ([state]), an empty plaintext cache, the contact's key. [textStore] is the decrypted-text store
 * (ZchatPreferences.get/put/removeDecryptedText); [controls] names the TTL and mode controls the pass applied.
 *
 * The ViewModel is built without its constructor (as every harness here), so `ratchetStateStore`, a `val` the constructor
 * sets, is set by reflection. A new ViewModel FIELD would be null here: the fix keeps its process state in the
 * `OldRootFallback` object (see OldRootFallbackApi). Every address, key, id and txid is synthetic.
 */
internal class OldRootPhone(
    scheduler: TestCoroutineScheduler,
    val peer: String,
    val ours: E2EKeyPair,
    val theirs: E2EKeyPair,
    private val ownConvId: String?,
    private val filedConvIds: List<String>,
) {
    val f = HandshakePassFixture(scheduler, peer, firstContact = false)
    val floors = ConcurrentHashMap<String, Long>()
    val ratchetStore = InMemoryRatchetStateStore(floors)
    val textStore = ConcurrentHashMap<String, String>()
    val controls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private var kexSet: Set<String> = emptySet()
    private var kexScalar: String? = null
    private var ackSet: Set<String> = emptySet()
    private var ackScalar: String? = null

    /** The disappearing-message timer the phone holds for the contact, in seconds (null: none). */
    @Volatile var ttlSeconds: Long? = null

    /** What the current root reads, by label (a txid is [txid] of its label). */
    fun stored(
        kex: Set<String>,
        kexScalar: String?,
        ack: Set<String>,
        ackScalar: String?,
    ) {
        this.kexSet = kex.mapTo(HashSet()) { txid(it) }
        this.kexScalar = kexScalar?.let { txid(it) }
        this.ackSet = ack.mapTo(HashSet()) { txid(it) }
        this.ackScalar = ackScalar?.let { txid(it) }
    }

    fun txid(label: String): String = f.txid(label)

    /** The current root's material for [kex] and [ack] (labels), as the app derives and this phone stores it. */
    fun root(
        kex: Set<String>,
        ack: Set<String>,
        kexScalar: String? = null,
        ackScalar: String? = null,
    ): ByteArray =
        E2ERatchet.deriveRatchetRoot(
            E2EEncryption.deriveSharedSecret(ours.privateKey, theirs.publicKey),
            null,
            E2ERatchet.canonicalTxidMaterial(kex.mapTo(HashSet()) { txid(it) }, kexScalar?.let { txid(it) }),
            E2ERatchet.canonicalTxidMaterial(ack.mapTo(HashSet()) { txid(it) }, ackScalar?.let { txid(it) }),
        )

    /** The ratchet of the CONTACT (it sends to us) under [root], on the contact's own store. */
    fun contactSealer(
        root: ByteArray,
        convId: String,
    ): Sealer = Sealer(E2ERatchet(root, convId, theirs.publicKey < ours.publicKey, InMemoryRatchetStateStore()))

    /** Our own ratchet under [root] on a store of its own: what an older send of ours looked like. */
    fun ownSealer(
        root: ByteArray,
        convId: String,
    ): Sealer = Sealer(E2ERatchet(root, convId, ours.publicKey < theirs.publicKey, InMemoryRatchetStateStore()))

    class Sealer(
        private val ratchet: E2ERatchet,
    ) {
        /** The `E2E1:` wire string of [text] at the next counter. */
        suspend fun seal(text: String): String = CiphertextWireFormat.serialize(ratchet.encrypt(text.toByteArray()))
    }

    suspend fun open() {
        f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
        f.prefs.e2ePeerKey[peer] = theirs.publicKey
        ownConvId?.let { f.ids.store.restoreOwnConversationId(it, peer) }
        filedConvIds.forEach { f.ids.store.setConversationMapping(it, peer) }
        f.extra = { method, args -> answer(method, args) }
        val vm = f.open()
        ChatViewModel::class.java
            .getDeclaredField("ratchetStateStore")
            .apply { isAccessible = true }
            .set(vm, ratchetStore)
    }

    fun hasDecryptFailure(): Boolean = f.vm.hasDecryptFailure(peer)

    /** A message from the contact in a ZMSG v4 memo, [wire] being its (encrypted) body. */
    fun inboundMemo(
        convId: String,
        wire: String,
    ): String = ZMSGProtocol.createV4ReplyMessage(convId, peer, wire)

    /** A message of ours in a ZMSG v4 memo. */
    fun ownMemo(
        convId: String,
        wire: String,
    ): String = ZMSGProtocol.createV4ReplyMessage(convId, HandshakePassFixture.SELF, wire)

    /** [times] passes over the fixture's list; the last one's conversations. */
    @Suppress("UNCHECKED_CAST")
    suspend fun passes(times: Int = 1): List<Conversation> {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        val field = HandshakePassFixture::class.java.getDeclaredField("txs").apply { isAccessible = true }
        var listed: List<Conversation> = emptyList()
        repeat(times) {
            val txs = (field.get(f) as List<Transaction>).sortedBy { it.timestamp ?: Instant.MIN }
            listed =
                suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    try {
                        val pending = PaymentHarness.pendingMessages(f.vm)
                        method.invoke(
                            f.vm,
                            txs,
                            HandshakePassFixture.SELF,
                            emptySet<String>(),
                            pending,
                            emptySet<String>(),
                            true,
                            continuation,
                        )
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as List<Conversation>
            f.idle()
        }
        return listed
    }

    /** The row of the transaction labelled [label] in [rows], or null. */
    fun message(
        rows: List<Conversation>,
        label: String,
    ): ChatMessage? = rows.flatMap { it.messages }.firstOrNull { it.id == txid(label) }

    /** The text of the row of [label]; fails with what the pass did list when there is none. */
    fun textOf(
        rows: List<Conversation>,
        label: String,
    ): String =
        message(rows, label)?.text
            ?: throw AssertionError("no row for $label; the pass listed ${rows.flatMap { it.messages }.map { it.text.take(24) }}")

    /** The ratchet store as a value: the chat's state under [convId] and every floor. */
    suspend fun storeSnapshot(convId: String) = ratchetStore.load(convId) to floors.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun answer(
        method: Method,
        a: Array<Any?>,
    ): Any? =
        when (method.name) {
            "getKexTxIds" -> kexSet
            "setKexTxIds" -> (a[1] as Set<String>).toSet().also { kexSet = it }.let { null }
            "getKexAckTxIds" -> ackSet
            "setKexAckTxIds" -> (a[1] as Set<String>).toSet().also { ackSet = it }.let { null }
            "getE2EKexTxId" -> kexScalar
            "setE2EKexTxId" -> (a[1] as String).also { kexScalar = it }.let { null }
            "getE2EKexAckTxId" -> ackScalar
            "setE2EKexAckTxId" -> (a[1] as String).also { ackScalar = it }.let { null }
            // The keys in these tests are derived with the version the app writes today (V2).
            "getE2EKeyVersion" -> 2
            "getDecryptedText" -> textStore[a[0] as String]
            "putDecryptedText" -> textStore.put(a[0] as String, a[1] as String).let { null }
            "removeDecryptedText" -> textStore.remove(a[0] as String).let { null }
            "getDisappearingTtl" -> ttlSeconds?.let { ZchatPreferences.DisappearingTtl(it, 0L) }
            "setDisappearingTtl" -> controls.add("ttl").let { true }
            "advancePeerModeChangeSince" -> controls.add("mode").let { true }
            "armReplyConversationId" -> controls.add("arm").let { true }
            else -> ClaimMarkMaps.UNHANDLED
        }

    companion object {
        /** A synthetic contact address. */
        fun contact(seed: Int): String = RestoredSendScan.contact(seed)
    }
}
