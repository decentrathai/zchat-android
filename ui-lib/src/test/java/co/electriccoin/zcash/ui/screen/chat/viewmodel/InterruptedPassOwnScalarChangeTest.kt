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
import kotlinx.coroutines.CancellationException
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
 * ZC-1605 (Fable review of 5318c7fee, finding 1; Grok review of f9753d06a, P1) — the ZC-1586 fix drops the cached
 * message processor for an own KEX/KEXACK scalar change only at the END of the history pass (the compare against the
 * scalars it snapshotted when it first met the peer). Between the scalar write and that compare:
 *
 * A. a pass that is cancelled or throws there never reaches the compare, and the next pass snapshots the already
 *    changed value: the processor built under the old root is served for good, and every Shielded send is sealed under
 *    material the contact never holds;
 * B. a send that runs there (the pass is still going) gets the processor built under the old root.
 *
 * Each case runs on the Fold (responder) of a chat where the HONOR's KEX KH and the Fold's KEXACK KA are mined, as in
 * OwnScalarChangeDropsCachedProcessorTest (same harness: HandshakePassFixture, the real convertToConversations, the
 * real getOrCreateMessageProcessor with forSend = true). Pass 1 builds the processor while an own KEX U is pending
 * (scalar U) and a control checks it. Pass 2 changes the scalar (U expires unmined: cleared; or a newer own KEX U2:
 * moved). The seam is the prefs stand-in: once the scalar change has landed, the pass's next getPeerByConversationId
 * call (the first is after the KEX row; the post-loop per-peer union calls it uncaught, before the compare)
 * - A: throws CancellationException (the pass is cancelled); then a clean pass runs, and the processor the ViewModel
 *   serves next must seal a message the HONOR decrypts;
 * - B: records which processor the ViewModel caches for the chat at that moment; it must no longer be the pass-1 one.
 *
 * Every address, key, id and txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptedPassOwnScalarChangeTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 16051)
    private val fold = contact(seed = 16052)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1605XA"
    private val b = "ZC1605XB"

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
    fun `A1 - pass cancelled after the expired own KEX left the scalar - the next clean pass serves a processor the contact reads`() =
        runBlocking {
            val p = pendingOwnKexBuilt()
            p.f.unmined(U, expired = true)
            p.interruptedPass()
            assertEquals(
                "pass 2 (cancelled): the expired own KEX U was already cleared from the KEX scalar",
                "none",
                label(p.stored().kexScalar),
            )

            p.f.pass(times = 3)
            assertEquals("pass 3 (clean): the KEX scalar stays cleared", "none", label(p.stored().kexScalar))
            p.assertNextProcessorReadsOn(contact = Stored(setOf(id(KH)), id(KH), setOf(id(KA)), id(KA)))
        }

    @Test
    fun `A2 - pass cancelled after the KEX scalar moved to a newer own KEX - the next clean pass serves a processor the contact reads`() =
        runBlocking {
            val p = pendingOwnKexBuilt()
            p.f.ownKex(U2, to = honor, convId = b, minedAt = null)
            p.f.unmined(U2, expired = false)
            p.interruptedPass()
            assertEquals("pass 2 (cancelled): the KEX scalar already moved to the newer own KEX U2", U2, label(p.stored().kexScalar))

            p.f.pass(times = 3)
            assertEquals("pass 3 (clean): the KEX scalar stays U2", U2, label(p.stored().kexScalar))
            p.assertNextProcessorReadsOn(contact = Stored(setOf(id(KH), id(U2)), id(KH), setOf(id(KA)), id(KA)))
        }

    @Test
    fun `B - right after the pass clears the expired own KEX from the scalar, a send is no longer served the old-root processor`() =
        runBlocking {
            val p = pendingOwnKexBuilt()
            p.f.unmined(U, expired = true)
            var observed = false
            var cachedThen: Any? = null
            p.root.afterChange = {
                if (!observed) {
                    observed = true
                    cachedThen = p.cached()
                }
            }
            p.root.watch = true
            p.f.pass(times = 1)
            p.root.watch = false
            p.root.afterChange = null
            assertEquals("pass 2: the expired own KEX U was cleared from the KEX scalar (ZC-1583)", "none", label(p.stored().kexScalar))
            assertTrue("control: the pass made a prefs call after the scalar write (the observation point)", observed)
            assertTrue(
                "later in the same pass, after the own KEX scalar was cleared, the ViewModel still caches the pass-1 processor " +
                    "(built under ${p.builtState}) for the chat: a send there is sealed under the old root",
                cachedThen == null || cachedThen !== p.built,
            )
        }

    // ---- the phone ---------------------------------------------------------------------------------------------------

    private inner class Phone(
        val f: HandshakePassFixture,
        val root: Root,
    ) {
        var built: E2EMessageProcessor? = null
        var builtState: String = "?"

        fun stored(): Stored = root.storedFor(honor)

        /** The processor the ViewModel caches for this chat now (what getOrCreateMessageProcessorUngated returns first). */
        @Suppress("UNCHECKED_CAST")
        fun cached(): Any? =
            (
                ChatViewModel::class.java
                    .getDeclaredField("messageProcessors")
                    .apply { isAccessible = true }
                    .get(f.vm) as Map<String, Any?>
            )["$honor:$b"]

        /** Pass 1: the processor a send builds now, and a control that it seals under what is stored now. */
        suspend fun buildAndControl() {
            val now = stored()
            built = processor()
            builtState = state(now)
            assertNotNull("pass 1: getOrCreateMessageProcessor built a processor for the send", built)
            assertTrue(
                "control: the pass-1 processor seals under the material stored at pass 1 (${state(now)})",
                readsOn(built!!.encryptOutgoing(PLAINTEXT), now),
            )
            assertTrue("control: the pass-1 processor is the one the ViewModel caches", cached() === built)
        }

        /** Pass 2, cancelled at the first getPeerByConversationId after the own scalar changed. */
        suspend fun interruptedPass() {
            root.afterChange = { throw CancellationException("synthetic: the history pass is cancelled after the own-scalar write") }
            root.watch = true
            val thrown = runCatching { f.pass(times = 1) }.exceptionOrNull()
            root.watch = false
            root.afterChange = null
            assertTrue("control: pass 2 changed the own scalar", root.changed)
            assertTrue("control: pass 2 was cancelled after the own-scalar write (got $thrown)", thrown is CancellationException)
            f.idle()
        }

        /** The processor the ViewModel serves next must seal a message [contact] decrypts. */
        suspend fun assertNextProcessorReadsOn(contact: Stored) {
            val next = processor()
            assertNotNull("getOrCreateMessageProcessor serves a processor for the send", next)
            val now = stored()
            val wire = next!!.encryptOutgoing(PLAINTEXT)
            val same = if (next === built) "the SAME cached processor as pass 1" else "a new processor"
            assertTrue(
                "a Shielded message the Fold sends after the cancelled pass and a clean one decrypts on the HONOR ($same; " +
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

    /** The Fold with an own KEX U pending (scalar U) after pass 1, and the processor a send built then (controlled). */
    private suspend fun pendingOwnKexBuilt(): Phone {
        val p = foldPhone { it.ownKex(U, to = honor, convId = b, minedAt = null) }
        p.f.unmined(U, expired = false)
        p.f.pass(times = 3)
        assertEquals("pass 1: the KEX scalar is the pending own KEX U", U, label(p.stored().kexScalar))
        p.buildAndControl()
        return p
    }

    /** The Fold: KH received and its KA sent, both mined; then [chat]; the processor's store and key version set. */
    private suspend fun foldPhone(chat: (HandshakePassFixture) -> Unit): Phone {
        val f = HandshakePassFixture(scheduler, honor, firstContact = false)
        val root = Root()
        f.extra = { method, args ->
            when {
                method.name == "getE2EKeyVersion" -> E2EKeyVersion.V2.value
                method.name == "getPeerByConversationId" && root.watch && root.changed -> {
                    root.afterChange?.invoke()
                    ClaimMarkMaps.UNHANDLED
                }
                else -> root.answer(method.name, args)
            }
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

    /**
     * The stored sets and scalars. While [watch] is on, [changed] records that an own scalar write or removal changed the
     * stored value; the fixture hook then runs [afterChange] at each later getPeerByConversationId of the pass.
     */
    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        @Volatile var watch = false

        @Volatile var changed = false

        @Volatile var afterChange: (() -> Unit)? = null

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
                method == "setE2EKexTxId" -> scalar(kexScalar, a[0] as String, a.getOrNull(1) as String?)
                method == "getE2EKexAckTxId" -> ackScalar[a[0] as String]
                method == "setE2EKexAckTxId" -> scalar(ackScalar, a[0] as String, a.getOrNull(1) as String?)
                isClear(method, "E2EKexAckTxId") -> scalar(ackScalar, a[0] as String, null)
                isClear(method, "E2EKexTxId") -> scalar(kexScalar, a[0] as String, null)
                else -> ClaimMarkMaps.UNHANDLED
            }

        private fun scalar(
            map: ConcurrentHashMap<String, String>,
            peer: String,
            value: String?,
        ): Any? {
            val before = map[peer]
            if (value == null) map.remove(peer) else map[peer] = value
            if (watch && before != value) changed = true
            return null
        }

        private fun isClear(
            method: String,
            what: String,
        ): Boolean = method.contains(what) && (method.startsWith("remove") || method.startsWith("clear"))
    }

    private val labelsByTxid = listOf(KH, KA, U, U2).associateBy { id(it) }

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
        const val KH = "zc1605x-honor-kex"
        const val KA = "zc1605x-fold-ack"
        const val U = "zc1605x-fold-kex-unmined"
        const val U2 = "zc1605x-fold-kex-newer"

        const val PLAINTEXT = "zc1605 interrupted-pass shielded"
    }
}
