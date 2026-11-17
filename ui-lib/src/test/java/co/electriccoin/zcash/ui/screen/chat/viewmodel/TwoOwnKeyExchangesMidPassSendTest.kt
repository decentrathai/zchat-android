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
 * ZC-1608 (Fable review of 6f7b69319) — the history pass walks the transactions oldest first and writes the own
 * KEX/KEXACK scalar at each own key exchange row; since ZC-1605 each write that changes the stored value drops the cached
 * processor at once. For a peer with two own key exchanges, the older one MINED (in both phones' sets) and the newer one
 * PENDING (the stored scalar), every pass moves the scalar to the older txid and back: in between, a send finds no cached
 * processor and builds one from {KH, U1}. Once U2 mines the HONOR holds {KH, U1, U2}, and that message never decrypts.
 *
 * Same harness as InterruptedPassOwnScalarChangeTest (HandshakePassFixture, the real convertToConversations, the real
 * getOrCreateMessageProcessor with forSend = true), on the Fold (responder: the HONOR's KEX KH and the Fold's KEXACK KA
 * mined). The seam is the prefs stand-in: during one watched pass, at EVERY getPeerByConversationId call (each is a
 * point where a send may run beside the pass), a send builds its processor; each processor the ViewModel serves there
 * that was not checked yet seals one message, which must decrypt on the HONOR holding the material it has once the
 * pending own key exchange mines. The check does not depend on a scalar write happening during the pass.
 * The cancelled case (cross-family review): a pass cut off (CancellationException) at the first such call after an
 * own-scalar write that changed the stored value must leave the stored scalar on U2, and a send right then (no further
 * pass) must still read on the HONOR; at HEAD the prefs are left on U1.
 *
 * Every address, key, id and txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoOwnKeyExchangesMidPassSendTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 16081)
    private val fold = contact(seed = 16082)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1608XA"
    private val b = "ZC1608XB"

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
    fun `KEX - own KEX U1 mined and U2 pending - a send during the pass is sealed under material the contact holds once U2 mines`() =
        runBlocking {
            val p =
                foldPhone {
                    it.ownKex(U1, to = honor, convId = b, minedAt = NEW + 9)
                    it.ownKex(U2, to = honor, convId = b, minedAt = null)
                    it.unmined(U2, expired = false)
                }
            p.f.pass(times = 3)
            assertEquals("setup: the KEX scalar is the pending own KEX U2", U2, label(p.stored().kexScalar))
            assertEquals("setup: the KEX set holds KH and the mined own KEX U1", setOf(KH, U1), labels(p.stored().kexSet))

            p.sendAtEveryPoint(contact = Stored(setOf(id(KH), id(U1), id(U2)), null, setOf(id(KA)), null))
            assertEquals("after the pass: the KEX scalar is U2 again", U2, label(p.stored().kexScalar))
        }

    @Test
    fun `KEX cancelled - pass cut off after the U1 write - the stored scalar is still U2 and a send now reads on the contact`() =
        runBlocking {
            val p =
                foldPhone {
                    it.ownKex(U1, to = honor, convId = b, minedAt = NEW + 9)
                    it.ownKex(U2, to = honor, convId = b, minedAt = null)
                    it.unmined(U2, expired = false)
                }
            p.f.pass(times = 3)
            assertEquals("setup: the KEX scalar is the pending own KEX U2", U2, label(p.stored().kexScalar))
            assertEquals("setup: the KEX set holds KH and the mined own KEX U1", setOf(KH, U1), labels(p.stored().kexSet))

            val cancelled = p.cancelAfterScalarChange()
            val context = if (cancelled) "pass cancelled after an own-scalar write" else "pass completed, no own-scalar change"
            assertEquals(
                "after the pass ($context), no further pass: the stored KEX scalar is still the pending own KEX U2",
                U2,
                label(p.stored().kexScalar),
            )
            p.assertSendNowReadsOn(contact = Stored(setOf(id(KH), id(U1), id(U2)), null, setOf(id(KA)), null), context = context)
        }

    @Test
    fun `KEXACK - own KEXACK A1 mined and A2 pending - a send during the pass is sealed under material the contact holds once A2 mines`() =
        runBlocking {
            val p =
                foldPhone {
                    it.sent(A2, ackMemo(a, SELF, foldKeys), to = honor, minedAt = null)
                    it.unmined(A2, expired = false)
                }
            p.f.pass(times = 3)
            assertEquals("setup: the KEXACK scalar is the pending own KEXACK A2", A2, label(p.stored().ackScalar))
            assertEquals("setup: the KEXACK set holds the mined own KEXACK KA (A1)", setOf(KA), labels(p.stored().ackSet))

            p.sendAtEveryPoint(contact = Stored(setOf(id(KH)), null, setOf(id(KA), id(A2)), null))
            assertEquals("after the pass: the KEXACK scalar is A2 again", A2, label(p.stored().ackScalar))
        }

    @Test
    fun `control - a single own KEX pending - a send during the pass is sealed under material the contact holds once it mines`() =
        runBlocking {
            val p =
                foldPhone {
                    it.ownKex(U2, to = honor, convId = b, minedAt = null)
                    it.unmined(U2, expired = false)
                }
            p.f.pass(times = 3)
            assertEquals("setup: the KEX scalar is the pending own KEX U2", U2, label(p.stored().kexScalar))

            p.sendAtEveryPoint(contact = Stored(setOf(id(KH), id(U2)), null, setOf(id(KA)), null))
        }

    // ---- the phone ---------------------------------------------------------------------------------------------------

    private inner class Phone(
        val f: HandshakePassFixture,
        val root: Root,
    ) {
        fun stored(): Stored = root.storedFor(honor)

        /**
         * One watched pass; at each getPeerByConversationId a send builds its processor, and each processor not checked
         * yet seals one message that must decrypt on the HONOR holding [contact].
         */
        suspend fun sendAtEveryPoint(contact: Stored) {
            var points = 0
            var built = 0
            var lastChecked: E2EMessageProcessor? = null
            val unreadable = mutableListOf<String>()
            root.atPoint = {
                points++
                val now = stored()
                runBlocking {
                    val next = processor()
                    if (next != null && next !== lastChecked) {
                        lastChecked = next
                        built++
                        if (!readsOn(next.encryptOutgoing(PLAINTEXT), contact)) {
                            unreadable += "point $points (Fold stored ${state(now)})"
                        }
                    }
                }
            }
            root.watch = true
            f.pass(times = 1)
            root.watch = false
            root.atPoint = null
            assertTrue("control: the pass made prefs calls where a send could run (got $points)", points > 0)
            assertTrue("control: a send during the pass got a processor (got $built)", built > 0)
            assertTrue(
                "a Shielded message the Fold sends during the history pass must decrypt on the HONOR holding " +
                    "${state(contact)}; unreadable at: $unreadable",
                unreadable.isEmpty(),
            )
        }

        /**
         * One pass cancelled (CancellationException) at the first getPeerByConversationId after an own-scalar write that
         * changed the stored value, as InterruptedPassOwnScalarChangeTest A1; a pass that changes nothing runs to its end.
         * Returns whether it was cancelled. No other pass and no ViewModel jobs run after it.
         */
        suspend fun cancelAfterScalarChange(): Boolean {
            root.atPoint = {
                if (root.changed) throw CancellationException("synthetic: the history pass is cancelled after the own-scalar write")
            }
            root.changed = false
            root.watch = true
            val thrown = runCatching { f.pass(times = 1, settle = false) }.exceptionOrNull()
            root.watch = false
            root.atPoint = null
            assertTrue(
                "control: the pass ended or was cancelled, nothing else (got $thrown)",
                thrown == null || thrown is CancellationException,
            )
            return thrown is CancellationException
        }

        /** The processor a send builds now must seal a message the HONOR holding [contact] decrypts. */
        suspend fun assertSendNowReadsOn(
            contact: Stored,
            context: String,
        ) {
            val next = processor()
            assertNotNull("a send now gets a processor ($context)", next)
            assertTrue(
                "a Shielded message the Fold sends now ($context; Fold stored ${state(stored())}) must decrypt on the HONOR " +
                    "holding ${state(contact)}",
                readsOn(next!!.encryptOutgoing(PLAINTEXT), contact),
            )
        }

        suspend fun processor(): E2EMessageProcessor? =
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
            when {
                method.name == "getE2EKeyVersion" -> E2EKeyVersion.V2.value
                method.name == "getPeerByConversationId" && root.watch && !root.busy -> {
                    root.busy = true
                    try {
                        root.atPoint?.invoke()
                    } finally {
                        root.busy = false
                    }
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

    /** The stored sets and scalars; while [watch] is on, each getPeerByConversationId runs [atPoint] (not re-entered). */
    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        @Volatile var watch = false

        @Volatile var busy = false

        @Volatile var changed = false

        @Volatile var atPoint: (() -> Unit)? = null

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

    private val labelsByTxid = listOf(KH, KA, U1, U2, A2).associateBy { id(it) }

    private fun labels(txids: Set<String>): Set<String> = txids.mapTo(HashSet()) { label(it) }

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
        const val KH = "zc1608x-honor-kex"
        const val KA = "zc1608x-fold-ack"
        const val U1 = "zc1608x-fold-kex-mined"
        const val U2 = "zc1608x-fold-kex-pending"
        const val A2 = "zc1608x-fold-ack-pending"

        const val PLAINTEXT = "zc1608 mid-pass shielded"
    }
}
