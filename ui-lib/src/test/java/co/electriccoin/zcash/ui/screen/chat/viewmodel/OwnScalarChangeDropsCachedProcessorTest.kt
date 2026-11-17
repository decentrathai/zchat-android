package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.SELF
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.TIP
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1586 (Fable review of 406e733aa..0e24213d7, finding 1; GPT review, KEXACK case) — changing or removing this
 * phone's own KEX/KEXACK scalar must drop the cached message processor.
 *
 * The processor is cached per "$peer:$convId" (ChatViewModel.messageProcessors) and its root is read from the stored
 * KEX/KEXACK sets and scalars only when it is built (getOrCreateMessageProcessorUngated). The pass drops the cache only
 * when a stored SET changes or a key is recovered. The ZC-1583 clear (an own KEX/KEXACK that expired unmined is removed
 * from the scalar) and the scalar writes themselves never drop it: a processor built while the own KEX was pending
 * (the user opened the chat, a send ran) keeps the old root, and every later Shielded send and decrypt is sealed under
 * material the contact never holds, until the process dies.
 *
 * Each case runs on the Fold (responder) of a chat where the HONOR's KEX KH and the Fold's KEXACK KA are mined:
 * 1. an own KEX U pending at pass 1 (scalar U), expired unmined at pass 2 (scalar cleared); the contact holds {KH}/{KA};
 * 2. an own KEX U1 pending at pass 1 (scalar U1), a newer own KEX U2 listed at pass 2 (scalar U2); the contact holds
 *    {KH, U2}/{KA} once U2 mines;
 * 3. an own KEXACK A2 (a second answer) pending at pass 1 (KEXACK scalar A2), expired unmined at pass 2; the contact
 *    holds {KH}/{KA}.
 * Pass 1 builds the processor through the ViewModel's real getOrCreateMessageProcessor (forSend = true, what a send
 * uses) and a control checks it seals under what was stored then. Pass 2 checks the prefs moved (ZC-1583's part, which
 * holds at HEAD), then the processor the ViewModel serves next must seal a message the contact decrypts.
 *
 * Same harness as ExpiredOwnKeyExchangeScalarRootTest (HandshakePassFixture: the real convertToConversations). Every
 * address, key, id and txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnScalarChangeDropsCachedProcessorTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 15861)
    private val fold = contact(seed = 15862)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1586XA"
    private val b = "ZC1586XB"

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `1 - own KEX pending then expired - the cleared scalar reaches the processor a send uses next`() =
        runBlocking {
            val p = foldPhone { it.ownKex(U, to = honor, convId = b, minedAt = null) }
            p.f.unmined(U, expired = false)
            p.f.pass(times = 3)
            assertEquals("pass 1: the KEX scalar is the pending own KEX U", U, label(p.stored().kexScalar))
            p.buildAndControl()

            p.f.unmined(U, expired = true)
            p.f.pass(times = 3)
            assertEquals("pass 2: the expired own KEX U was cleared from the KEX scalar (ZC-1583)", "none", label(p.stored().kexScalar))
            p.assertNextProcessorReadsOn(contact = Stored(setOf(id(KH)), id(KH), setOf(id(KA)), id(KA)))
        }

    @Test
    fun `2 - own KEX scalar moves from one listed own KEX to a newer one - the processor follows`() =
        runBlocking {
            val p = foldPhone { it.ownKex(U, to = honor, convId = b, minedAt = null) }
            p.f.unmined(U, expired = false)
            p.f.pass(times = 3)
            assertEquals("pass 1: the KEX scalar is the pending own KEX U1", U, label(p.stored().kexScalar))
            p.buildAndControl()

            p.f.ownKex(U2, to = honor, convId = b, minedAt = null)
            p.f.unmined(U2, expired = false)
            p.f.pass(times = 3)
            assertEquals("pass 2: the KEX scalar is the newer own KEX U2", U2, label(p.stored().kexScalar))
            p.assertNextProcessorReadsOn(contact = Stored(setOf(id(KH), id(U2)), id(KH), setOf(id(KA)), id(KA)))
        }

    @Test
    fun `3 - own KEXACK pending then expired - the cleared KEXACK scalar reaches the processor a send uses next`() =
        runBlocking {
            val p =
                foldPhone {
                    it.sent(A2, ackMemo(a, SELF, foldKeys), to = honor, minedAt = null)
                }
            p.f.unmined(A2, expired = false)
            p.f.pass(times = 3)
            assertEquals("pass 1: the KEXACK scalar is the pending own KEXACK A2", A2, label(p.stored().ackScalar))
            p.buildAndControl()

            p.f.unmined(A2, expired = true)
            p.f.pass(times = 3)
            assertTrue(
                "pass 2: the expired own KEXACK A2 is no longer the KEXACK scalar (ZC-1583); it is ${label(p.stored().ackScalar)}",
                label(p.stored().ackScalar) != A2,
            )
            p.assertNextProcessorReadsOn(contact = Stored(setOf(id(KH)), id(KH), setOf(id(KA)), id(KA)))
        }

    // ---- the phone ---------------------------------------------------------------------------------------------------

    private inner class Phone(
        val f: HandshakePassFixture,
        val root: Root,
    ) {
        private var built: E2EMessageProcessor? = null

        fun stored(): Stored = root.storedFor(honor)

        /** Pass 1: the processor a send builds now, and a control that it seals under what is stored now. */
        suspend fun buildAndControl() {
            val now = stored()
            built = processor()
            assertNotNull("pass 1: getOrCreateMessageProcessor built a processor for the send", built)
            assertTrue(
                "control: the pass-1 processor seals under the material stored at pass 1 (${state(now)})",
                readsOn(built!!.encryptOutgoing(PLAINTEXT), now),
            )
        }

        /** Pass 2: the processor the ViewModel serves next must seal a message [contact] decrypts. */
        suspend fun assertNextProcessorReadsOn(contact: Stored) {
            val next = processor()
            assertNotNull("pass 2: getOrCreateMessageProcessor serves a processor for the send", next)
            val now = stored()
            val wire = next!!.encryptOutgoing(PLAINTEXT)
            val same = if (next === built) "the SAME cached processor as pass 1" else "a new processor"
            assertTrue(
                "a Shielded message the Fold sends after the scalar changed decrypts on the HONOR ($same; " +
                    "Fold stored ${state(now)}, HONOR holds ${state(contact)})",
                readsOn(wire, contact),
            )
        }

        private suspend fun processor(): E2EMessageProcessor? =
            KeyAcceptApi.callSuspend(f.vm, "getOrCreateMessageProcessor", honor, b, true) as E2EMessageProcessor?

        /** Whether the HONOR, holding [material], decrypts [wire] the Fold sent under [b]. */
        private suspend fun readsOn(
            wire: String,
            material: Stored,
        ): Boolean {
            val ct = CiphertextWireFormat.parse(wire) ?: return false
            val honorRoot =
                E2ERatchet.deriveRatchetRoot(
                    E2EEncryption.deriveSharedSecret(honorKeys.privateKey, foldKeys.publicKey, E2EKeyVersion.V2),
                    null,
                    material.kexMaterial(),
                    material.ackMaterial(),
                )
            val honorIsLower = honorKeys.publicKey < foldKeys.publicKey
            val read = runCatching { E2ERatchet(honorRoot, b, honorIsLower, InMemoryRatchetStateStore()).decrypt(ct) }
            return read.getOrNull()?.contentEquals(PLAINTEXT.toByteArray()) == true
        }
    }

    /** The Fold: KH received and its KA sent, both mined; then [chat]; the processor's store and key version set. */
    private suspend fun foldPhone(chat: (HandshakePassFixture) -> Unit): Phone {
        val f = HandshakePassFixture(scheduler, honor, firstContact = false)
        val root = Root()
        f.extra = { method, args ->
            if (method.name == "getE2EKeyVersion") E2EKeyVersion.V2.value else root.answer(method.name, args)
        }
        f.prefs.ourKeyStore[honor] = foldKeys.publicKey to foldKeys.privateKey
        f.prefs.e2ePeerKey[honor] = honorKeys.publicKey
        f.ids.store.restoreOwnConversationId(b, honor)
        f.ids.store.setConversationMapping(a, honor)
        f.open()
        PaymentHarness.setField(f.vm, "ratchetStateStore", InMemoryRatchetStateStore())
        // The send gate (R5) reads the recovery marker through the seed provider: over the routed prefs, no seed.
        val routed =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(f.vm) as ZchatPreferences
        PaymentHarness.setField(f.vm, "seedKeyProvider", SeedDerivedE2EKeyProvider(routed) { null })
        f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
        f.sent(KA, ackMemo(a, SELF, foldKeys), to = honor, minedAt = NEW + 7)
        chat(f)
        return Phone(f, root)
    }

    /**
     * The own send [label] as the repository lists it unmined: [expired] -> SendTransaction.Failed (SDK state Expired,
     * expiry height below the scanned tip); else SendTransaction.Pending (state Pending, expiry height above the tip).
     */
    @Suppress("UNCHECKED_CAST")
    private fun HandshakePassFixture.unmined(
        label: String,
        expired: Boolean,
    ) {
        val txs =
            HandshakePassFixture::class.java
                .getDeclaredField("txs")
                .apply { isAccessible = true }
                .get(this) as MutableList<Transaction>
        val i = txs.indexOfFirst { it.id.txIdString() == txid(label) }
        val s = txs[i] as SendTransaction
        txs[i] =
            if (expired) {
                val o = s.overview.copy(expiryHeight = BlockHeight.new(NEW + 50), transactionState = TransactionState.Expired)
                SendTransaction.Failed(s.id, s.amount, s.timestamp, s.memoCount, s.fee, s.transactionOutputs, o, s.recipient)
            } else {
                val o = s.overview.copy(expiryHeight = BlockHeight.new(TIP + 40), transactionState = TransactionState.Pending)
                SendTransaction.Pending(s.id, s.amount, s.timestamp, s.memoCount, s.fee, s.transactionOutputs, o, s.recipient)
            }
    }

    private fun kexMemo(
        convId: String,
        sender: String,
        keys: E2EKeyPair,
    ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))

    private fun ackMemo(
        convId: String,
        sender: String,
        keys: E2EKeyPair,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(convId, sender, E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey))

    // ---- the stored root material ------------------------------------------------------------------------------------

    private class Stored(
        val kexSet: Set<String>,
        val kexScalar: String?,
        val ackSet: Set<String>,
        val ackScalar: String?,
    ) {
        fun kexMaterial(): ByteArray = E2ERatchet.canonicalTxidMaterial(kexSet, kexScalar)

        fun ackMaterial(): ByteArray = E2ERatchet.canonicalTxidMaterial(ackSet, ackScalar)
    }

    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        fun storedFor(peer: String) = Stored(kex[peer].orEmpty(), kexScalar[peer], acks[peer].orEmpty(), ackScalar[peer])

        @Suppress("UNCHECKED_CAST")
        fun answer(
            method: String,
            a: Array<Any?>,
        ): Any? =
            when {
                method == "getKexTxIds" -> kex[a[0] as String].orEmpty()
                method == "setKexTxIds" -> kex.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                method == "getKexAckTxIds" -> acks[a[0] as String].orEmpty()
                method == "setKexAckTxIds" -> acks.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                method == "getE2EKexTxId" -> kexScalar[a[0] as String]
                method == "setE2EKexTxId" -> scalar(kexScalar, a)
                method == "getE2EKexAckTxId" -> ackScalar[a[0] as String]
                method == "setE2EKexAckTxId" -> scalar(ackScalar, a)
                isClear(method, "E2EKexAckTxId") -> ackScalar.remove(a[0] as String).let { null }
                isClear(method, "E2EKexTxId") -> kexScalar.remove(a[0] as String).let { null }
                else -> ClaimMarkMaps.UNHANDLED
            }

        private fun scalar(
            map: ConcurrentHashMap<String, String>,
            a: Array<Any?>,
        ): Any? {
            val value = a.getOrNull(1) as String?
            if (value == null) map.remove(a[0] as String) else map[a[0] as String] = value
            return null
        }

        private fun isClear(
            method: String,
            what: String,
        ): Boolean = method.contains(what) && (method.startsWith("remove") || method.startsWith("clear"))
    }

    private val labelsByTxid = listOf(KH, KA, U, U2, A2).associateBy { id(it) }

    private fun label(txid: String?): String = txid?.let { labelsByTxid[it] ?: "other" } ?: "none"

    private fun state(s: Stored): String =
        "KEX ${(s.kexSet + listOfNotNull(s.kexScalar)).map { label(it) }.sorted()} " +
            "KEXACK ${(s.ackSet + listOfNotNull(s.ackScalar)).map { label(it) }.sorted()}"

    private fun id(label: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(label.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KH = "zc1586x-honor-kex"
        const val KA = "zc1586x-fold-ack"
        const val U = "zc1586x-fold-kex-unmined"
        const val U2 = "zc1586x-fold-kex-newer"
        const val A2 = "zc1586x-fold-ack-unmined"

        const val PLAINTEXT = "zc1586 cached-processor shielded"
    }
}
