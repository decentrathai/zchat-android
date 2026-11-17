package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.SELF
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1571 (owner W-133, option A) guard: the per-peer union of KEX/KEXACK txids keeps the ratchet root of every chat that
 * works today byte-for-byte. The 2.12.4 phone is modelled by [Frozen2124], copied from `git show v2.12.4:ui-lib/src/main/
 * java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt` (a1fb4136f): the mined-set collection (lines
 * 3341-3352), the own KEX/KEXACK legacy scalar (3380-3390) and the per-id replace loop (4060-4072). The received-side scalar
 * its handlers write (4982 and 5102, on a first or repeated key, once per txid by the processed mark at 4899) is modelled as
 * the last received mined one of each kind; that handler code is not touched by the fix, and the fixture re-runs it (it
 * holds no processed marks), so for that side the test asserts the scalar adds nothing to the set and the root bytes stay.
 *
 * Each history (a single sender, a Reset by the initiator under its id, a phone with more chats) runs through the REAL pass
 * (convertToConversations) of both phones:
 * - upgraded: the prefs hold what 2.12.4 wrote ([Frozen2124]); the pass must leave the sets and the own-side scalar as they
 *   are, write no set of the contact that differs, and derive the same root bytes (no re-key);
 * - fresh: the pass must store the sets 2.12.4 stores and fold the same material;
 * - legacy rows (a build before the sets: scalars only, empty sets): the pass fills the sets and the root bytes stay.
 * Both phones' roots must also be equal (the chat works). The last case pins the own-side scalar of a pending own key
 * exchange: it becomes the scalar as in 2.12.4 (Fable finding 1, RED 1e8e8d3f0), so a reply sealed before it mines reads on
 * the contact once it mines. Every address, key, id and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexRootUnion2124GuardTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 15731)
    private val fold = contact(seed = 15732)
    private val honorOld = E2EEncryption.generateKeyPair()
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1571GA"

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        freshProcess()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- histories ---------------------------------------------------------------------------------------------------

    /** One entry of a chat history as one phone sees it. */
    private class Entry(
        val label: String,
        val memo: String,
        val own: Boolean,
        val minedAt: Long?,
    )

    /** Single sender: the HONOR's KEX KH under its id A, the Fold's answer KA under A. */
    private fun singleSender(onHonor: Boolean): List<Entry> {
        val kh = kexMemo(a, if (onHonor) SELF else honor, honorKeys)
        val ka = ackMemo(a, if (onHonor) fold else SELF, foldKeys)
        return listOf(Entry(KH, kh, own = onHonor, minedAt = NEW + 5), Entry(KA, ka, own = !onHonor, minedAt = NEW + 7))
    }

    /** Reset by the initiator: KH1 (old key) and KA1 under A, then the Reset's KH2 (new key) and its answer KA2 under A. */
    private fun resetByInitiator(onHonor: Boolean): List<Entry> {
        val h = if (onHonor) SELF else honor
        val f = if (onHonor) fold else SELF
        return listOf(
            Entry(KH1, kexMemo(a, h, honorOld), own = onHonor, minedAt = NEW + 5),
            Entry(KA1, ackMemo(a, f, foldKeys), own = !onHonor, minedAt = NEW + 7),
            Entry(KH2, kexMemo(a, h, honorKeys), own = onHonor, minedAt = NEW + 20),
            Entry(KA2, ackMemo(a, f, foldKeys), own = !onHonor, minedAt = NEW + 22),
        )
    }

    // ---- the cases ---------------------------------------------------------------------------------------------------

    @Test
    fun `single sender - upgraded phones keep sets, scalars and root bytes`() = runBlocking { upgraded(::singleSender, otherChats = 0) }

    @Test
    fun `single sender, the HONOR with more chats - upgraded phones keep sets, scalars and root bytes`() =
        runBlocking { upgraded(::singleSender, otherChats = OTHER_CHATS) }

    @Test
    fun `reset by the initiator - upgraded phones keep sets, scalars and root bytes`() =
        runBlocking { upgraded(::resetByInitiator, otherChats = 0) }

    @Test
    fun `single sender - a fresh pass stores what 2-12-4 stores`() = runBlocking { fresh(::singleSender, otherChats = OTHER_CHATS) }

    @Test
    fun `reset by the initiator - a fresh pass stores what 2-12-4 stores`() = runBlocking { fresh(::resetByInitiator, otherChats = 0) }

    @Test
    fun `legacy rows (scalars only, no sets) - the pass fills the sets and the root bytes stay`() =
        runBlocking {
            val roots =
                Side.entries.map { side ->
                    freshProcess()
                    val history = singleSender(side == Side.HONOR)
                    val legacy = Frozen2124.legacyScalarsOnly(history.toFrozen(side), side.peerByConv())
                    val before = material(legacy.storedFor(side.peerFor()))
                    val root = runPhone(side, history, otherChats = 0, preload = legacy)
                    val after = root.storedFor(side.peerFor())
                    assertEquals("$side: the KEX set is filled from the chain", setOf(KH), labels(after.kexSet))
                    assertEquals("$side: the KEXACK set is filled from the chain", setOf(KA), labels(after.ackSet))
                    assertArrayEquals("$side: KEX material bytes unchanged", before.first, material(after).first)
                    assertArrayEquals("$side: KEXACK material bytes unchanged", before.second, material(after).second)
                    rootOf(side, after)
                }
            assertArrayEquals("both phones derive the same root", roots[0], roots[1])
        }

    @Test
    fun `an own key exchange not mined yet - the KEX scalar is the pending one as in 2-12-4, both roots meet once it mines`() =
        runBlocking {
            val history = singleSender(onHonor = true) + Entry(KU, kexMemo(a, SELF, honorKeys), own = true, minedAt = null)
            val frozen = Frozen2124.passes(history.toFrozen(Side.HONOR), Side.HONOR.peerByConv()).storedFor(fold)
            val onHonor = runPhone(Side.HONOR, history, otherChats = 0, preload = null).storedFor(fold)
            assertEquals("HONOR: the KEX scalar is the pending own KEX", KU, label(onHonor.kexScalar))
            assertEquals("HONOR: the KEX set holds the mined one only", setOf(KH), labels(onHonor.kexSet))
            assertArrayEquals("HONOR: KEX material as 2.12.4 folds it", material(frozen).first, material(onHonor).first)
            freshProcess()
            val onFold =
                runPhone(
                    Side.FOLD,
                    singleSender(onHonor = false) + Entry(KU, kexMemo(a, honor, honorKeys), own = false, minedAt = NEW + 9),
                    otherChats = 0,
                    preload = null,
                ).storedFor(honor)
            assertEquals("FOLD: KU mined joins the KEX material", setOf(KH, KU), labels(onFold.kexSet + listOfNotNull(onFold.kexScalar)))
            assertArrayEquals("both phones derive the same root", rootOf(Side.HONOR, onHonor), rootOf(Side.FOLD, onFold))
        }

    // ---- the checks --------------------------------------------------------------------------------------------------

    private suspend fun upgraded(
        history: (Boolean) -> List<Entry>,
        otherChats: Int,
    ) {
        val roots =
            Side.entries.map { side ->
                freshProcess()
                val peer = side.peerFor()
                val entries = history(side == Side.HONOR)
                val written2124 = Frozen2124.passes(entries.toFrozen(side), side.peerByConv())
                val stored2124 = written2124.storedFor(peer)
                val root = runPhone(side, entries, otherChats, preload = written2124)
                val after = root.storedFor(peer)
                assertEquals("$side: KEX set as 2.12.4 left it", labels(stored2124.kexSet), labels(after.kexSet))
                assertEquals("$side: KEXACK set as 2.12.4 left it", labels(stored2124.ackSet), labels(after.ackSet))
                if (side == Side.HONOR) {
                    // The HONOR sent the key exchanges: its KEX scalar is the own-side one the fix gates on mining.
                    assertEquals("$side: own KEX scalar as 2.12.4 left it", label(stored2124.kexScalar), label(after.kexScalar))
                    assertTrue("$side: the received KEXACK scalar adds nothing to the set", after.ackScalar in after.ackSet)
                } else {
                    assertEquals("$side: own KEXACK scalar as 2.12.4 left it", label(stored2124.ackScalar), label(after.ackScalar))
                    assertTrue("$side: the received KEX scalar adds nothing to the set", after.kexScalar in after.kexSet)
                }
                assertEquals("$side: no write that changes the contact's stored set", 0, root.changedSetWrites(peer))
                val derived = rootOf(side, after)
                assertArrayEquals("$side: the same root bytes as under 2.12.4", rootOf(side, stored2124), derived)
                derived
            }
        assertArrayEquals("both phones derive the same root (the chat works)", roots[0], roots[1])
    }

    private suspend fun fresh(
        history: (Boolean) -> List<Entry>,
        otherChats: Int,
    ) {
        val roots =
            Side.entries.map { side ->
                freshProcess()
                val entries = history(side == Side.HONOR)
                val frozen = Frozen2124.passes(entries.toFrozen(side), side.peerByConv()).storedFor(side.peerFor())
                val root = runPhone(side, entries, otherChats, preload = null)
                val after = root.storedFor(side.peerFor())
                assertEquals("$side: KEX set as 2.12.4 stores it", labels(frozen.kexSet), labels(after.kexSet))
                assertEquals("$side: KEXACK set as 2.12.4 stores it", labels(frozen.ackSet), labels(after.ackSet))
                assertArrayEquals("$side: KEX material as 2.12.4 folds it", material(frozen).first, material(after).first)
                assertArrayEquals("$side: KEXACK material as 2.12.4 folds it", material(frozen).second, material(after).second)
                rootOf(side, after)
            }
        assertArrayEquals("both phones derive the same root (the chat works)", roots[0], roots[1])
    }

    // ---- the phones --------------------------------------------------------------------------------------------------

    private enum class Side { HONOR, FOLD }

    private fun Side.peerFor(): String = if (this == Side.HONOR) fold else honor

    /** The ids each phone filed: id A under the contact (the HONOR's own id, filed by the Fold when it answered). */
    private fun Side.peerByConv(): Map<String, String> = mapOf(a to peerFor())

    private fun List<Entry>.toFrozen(side: Side): List<Frozen2124.Tx> =
        map { Frozen2124.Tx(txidOf(it.label), it.memo, own = it.own, mined = it.minedAt != null, from = side.peerFor()) }

    private suspend fun runPhone(
        side: Side,
        history: List<Entry>,
        otherChats: Int,
        preload: Frozen2124.Prefs?,
    ): Root {
        val peer = side.peerFor()
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        val root = Root()
        preload?.let { root.load(it) }
        f.extra = { method, args -> root.answer(method.name, args) }
        val (ours, theirs) = if (side == Side.HONOR) honorKeys to foldKeys else foldKeys to honorKeys
        f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
        f.prefs.e2ePeerKey[peer] = theirs.publicKey
        if (side == Side.HONOR) f.ids.store.restoreOwnConversationId(a, peer) else f.ids.store.setConversationMapping(a, peer)
        f.open()
        repeat(otherChats) { n ->
            val other = contact(seed = 15950 + n)
            f.received("other-$n", kexMemo("OTHG%04d".format(n), other, E2EEncryption.generateKeyPair()), minedAt = NEW + 1)
        }
        for (e in history) {
            if (e.own) f.sent(e.label, e.memo, to = peer, minedAt = e.minedAt) else f.received(e.label, e.memo, minedAt = e.minedAt)
        }
        f.pass(times = 3)
        labelsByTxid.putAll(LABELS.associateBy { f.txid(it) })
        return root
    }

    private fun freshProcess() {
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
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

    // ---- material and root -------------------------------------------------------------------------------------------

    private class Stored(
        val kexSet: Set<String>,
        val kexScalar: String?,
        val ackSet: Set<String>,
        val ackScalar: String?,
    )

    private fun material(s: Stored): Pair<ByteArray, ByteArray> =
        E2ERatchet.canonicalTxidMaterial(s.kexSet, s.kexScalar) to E2ERatchet.canonicalTxidMaterial(s.ackSet, s.ackScalar)

    private fun rootOf(
        side: Side,
        s: Stored,
    ): ByteArray {
        val (ours, theirs) = if (side == Side.HONOR) honorKeys to foldKeys else foldKeys to honorKeys
        val (kex, ack) = material(s)
        return E2ERatchet.deriveRatchetRoot(E2EEncryption.deriveSharedSecret(ours.privateKey, theirs.publicKey), null, kex, ack)
    }

    private fun Frozen2124.Prefs.storedFor(peer: String) =
        Stored(kex[peer].orEmpty(), kexScalar[peer], acks[peer].orEmpty(), ackScalar[peer])

    /** The KEX/KEXACK sets and scalars by address; counts, per address, the set writes that change a stored set. */
    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()
        private val changed = ConcurrentHashMap<String, Int>()

        fun changedSetWrites(peer: String): Int = changed[peer] ?: 0

        fun load(p: Frozen2124.Prefs) {
            kex.putAll(p.kex)
            acks.putAll(p.acks)
            kexScalar.putAll(p.kexScalar)
            ackScalar.putAll(p.ackScalar)
        }

        fun storedFor(peer: String) = Stored(kex[peer].orEmpty(), kexScalar[peer], acks[peer].orEmpty(), ackScalar[peer])

        @Suppress("UNCHECKED_CAST")
        fun answer(
            method: String,
            a: Array<Any?>,
        ): Any? =
            when (method) {
                "getKexTxIds" -> kex[a[0] as String].orEmpty()
                "setKexTxIds" -> write(kex, a[0] as String, (a[1] as Set<String>).toSet())
                "getKexAckTxIds" -> acks[a[0] as String].orEmpty()
                "setKexAckTxIds" -> write(acks, a[0] as String, (a[1] as Set<String>).toSet())
                "getE2EKexTxId" -> kexScalar[a[0] as String]
                "setE2EKexTxId" -> kexScalar.put(a[0] as String, a[1] as String).let { null }
                "getE2EKexAckTxId" -> ackScalar[a[0] as String]
                "setE2EKexAckTxId" -> ackScalar.put(a[0] as String, a[1] as String).let { null }
                else -> ClaimMarkMaps.UNHANDLED
            }

        private fun write(
            map: ConcurrentHashMap<String, Set<String>>,
            peer: String,
            value: Set<String>,
        ): Any? {
            if (map[peer] != value) changed.merge(peer, 1, Int::plus)
            map[peer] = value
            return null
        }
    }

    private val labelsByTxid = ConcurrentHashMap<String, String>()

    private fun label(txid: String?): String = txid?.let { labelsByTxid[it] ?: "other" } ?: "none"

    private fun labels(txids: Set<String>): Set<String> = txids.mapTo(sortedSetOf()) { label(it) }

    private fun txidOf(label: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(label.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KH = "zc1571g-honor-kex"
        const val KA = "zc1571g-fold-ack"
        const val KH1 = "zc1571g-honor-kex-1"
        const val KA1 = "zc1571g-fold-ack-1"
        const val KH2 = "zc1571g-honor-kex-reset"
        const val KA2 = "zc1571g-fold-ack-reset"
        const val KU = "zc1571g-honor-kex-unmined"
        val LABELS = listOf(KH, KA, KH1, KA1, KH2, KA2, KU)

        /** More ids than a 16-bucket HashMap holds before it grows (12). */
        const val OTHER_CHATS = 12
    }
}

/**
 * The 2.12.4 history pass's KEX/KEXACK material code, copied from v2.12.4 (a1fb4136f) ChatViewModel.kt: the set collection
 * (3341-3352), the own legacy scalar (3380-3390) and the per-id replace (4060-4072), over plain maps in place of
 * ZchatPreferences; the two `parseKEXMessage ?: parseKEXAckMessage` expressions are only re-wrapped for ktlint. [Tx.from]
 * is the address a received memo's handler stores under (4982/5102).
 */
private object Frozen2124 {
    class Tx(
        val txid: String,
        val memo: String,
        val own: Boolean,
        val mined: Boolean,
        val from: String,
    )

    class Prefs(
        val peerByConv: Map<String, String>,
    ) {
        val kex = HashMap<String, Set<String>>()
        val acks = HashMap<String, Set<String>>()
        val kexScalar = HashMap<String, String>()
        val ackScalar = HashMap<String, String>()
        val handledReceived = HashSet<String>()

        fun getPeerByConversationId(convId: String): String? = peerByConv[convId]
    }

    /** What 2.12.4 stored after its passes over [txs] (three, as the real pass runs here). */
    fun passes(
        txs: List<Tx>,
        peerByConv: Map<String, String>,
    ): Prefs = Prefs(peerByConv).also { p -> repeat(3) { pass(txs, p) } }

    /** A build before the convergent sets: the same scalar writes, the sets empty. */
    fun legacyScalarsOnly(
        txs: List<Tx>,
        peerByConv: Map<String, String>,
    ): Prefs =
        passes(txs, peerByConv).also { p ->
            p.kex.clear()
            p.acks.clear()
        }

    private fun pass(
        txs: List<Tx>,
        zchatPreferences: Prefs,
    ) {
        val scannedKexByConv = HashMap<String, MutableSet<String>>()
        val scannedKexAckByConv = HashMap<String, MutableSet<String>>()
        for (tx in txs) {
            val memoText = tx.memo
            val txIdStr = tx.txid
            // v2.12.4 3341-3352
            if (tx.mined) {
                val convId =
                    ZMSGProtocol.parseKEXMessage(memoText)?.first
                        ?: ZMSGProtocol.parseKEXAckMessage(memoText)?.first
                if (convId != null) {
                    if (ZMSGProtocol.isKEXMessage(memoText)) {
                        scannedKexByConv.getOrPut(convId) { mutableSetOf() }.add(txIdStr)
                    } else if (ZMSGProtocol.isKEXAckMessage(memoText)) {
                        scannedKexAckByConv.getOrPut(convId) { mutableSetOf() }.add(txIdStr)
                    }
                }
            }
            if (!tx.own) {
                // 4982/5102: the handler's scalar on a first or repeated key, once per txid (hasProcessedKexTx, 4899).
                if (tx.mined && zchatPreferences.handledReceived.add(txIdStr)) {
                    if (ZMSGProtocol.isKEXMessage(memoText)) {
                        zchatPreferences.kexScalar[tx.from] = txIdStr
                    } else if (ZMSGProtocol.isKEXAckMessage(memoText)) {
                        zchatPreferences.ackScalar[tx.from] = txIdStr
                    }
                }
            } else {
                // v2.12.4 3380-3390
                // Keep the legacy scalar in sync for the derivation's backward-compat fold-in.
                val convId =
                    ZMSGProtocol.parseKEXMessage(memoText)?.first
                        ?: ZMSGProtocol.parseKEXAckMessage(memoText)?.first
                val peer = convId?.let { zchatPreferences.getPeerByConversationId(it) }
                if (peer != null) {
                    if (ZMSGProtocol.isKEXMessage(memoText)) {
                        zchatPreferences.kexScalar[peer] = txIdStr
                    } else if (ZMSGProtocol.isKEXAckMessage(memoText)) {
                        zchatPreferences.ackScalar[peer] = txIdStr
                    }
                }
            }
        }
        // v2.12.4 4060-4072 (invalidateMessageProcessors has no state here)
        val touchedConvs = scannedKexByConv.keys + scannedKexAckByConv.keys
        for (convId in touchedConvs) {
            val peer = zchatPreferences.getPeerByConversationId(convId) ?: continue
            val newKex = scannedKexByConv[convId] ?: emptySet()
            val newAck = scannedKexAckByConv[convId] ?: emptySet()
            if (newKex.isNotEmpty() && zchatPreferences.kex[peer] != newKex) {
                zchatPreferences.kex[peer] = newKex
            }
            if (newAck.isNotEmpty() && zchatPreferences.acks[peer] != newAck) {
                zchatPreferences.acks[peer] = newAck
            }
        }
    }
}
