package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1583 (GPT finding 2 + Grok finding 2, cross-family review of 242b2c113) — an own KEX that was broadcast and EXPIRED
 * unmined must not stay this phone's KEX scalar.
 *
 * The pass writes the legacy own KEX/KEXACK scalar for any listed own key exchange, mined or not (9a029e2f5, so a reply
 * sealed before it mines reads once it does; PendingOwnKeyExchangeRootTest). An own KEX U that expires unmined is still
 * listed by the wallet, and its txid stays the scalar: canonicalTxidMaterial folds it in, the contact never has U, the
 * two roots differ and every Shielded message is paid for and unreadable. Nothing replaces it without a later own KEX.
 *
 * Expired vs pending, as production reads it: the repository maps the SDK's TransactionState.Expired to
 * SendTransaction.Failed (TransactionRepositoryImpl.createTransaction), and the pass already counts an own send as expired
 * unmined by `tx is SendTransaction.Failed && tx.overview.minedHeight == null` (ZC-310, expiredUnminedOwnTxIds). Here U is
 * that: SendTransaction.Failed, overview state Expired, no mined height, expiry height below the scanned tip. A pending U
 * is SendTransaction.Pending, state Pending, expiry height above the tip.
 *
 * The chat: the HONOR's KEX KH under its id A (mined), the Fold's KEXACK KA under A (mined); then the Fold's own KEX U
 * under its id B. The Fold never sends another KEX.
 * 1. upgrade: the Fold's prefs as 2.12.4 left them (sets {KH}/{KA}, own KEX scalar U, KEXACK scalar KA), U expired;
 * 2. the current build writes the same from scratch, U expired at the pass;
 * 3. control: U pending; the scalar stays U and the HONOR, once U mines, holds U too (must pass at HEAD);
 * 4. control: a legacy chat (scalars only, sets empty, mined scalars) keeps its material bytes (must pass at HEAD).
 * For each: both phones' KEX and KEXACK material equal, and a Shielded message each way decrypts.
 *
 * Same harness as the ZC-1571 REDs: the real convertToConversations (HandshakePassFixture, three passes), the root by the
 * production derivation over what the pass stored. The prefs stub also takes a clear of a scalar (a `remove…`/`clear…`
 * call naming E2EKexTxId or E2EKexAckTxId, which ZchatPreferences does not have yet) so the fix may drop it. Every address,
 * key, id and txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredOwnKeyExchangeScalarRootTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 15831)
    private val fold = contact(seed = 15832)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1583XA"
    private val b = "ZC1583XB"

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

    @Test
    fun `1 - upgrade from 2-12-4 prefs holding an expired own KEX scalar - both phones meet and Shielded reads each way`() =
        runBlocking {
            val onFold = fold(u = UState.EXPIRED, preload = Stored(setOf(id(KH)), id(U), setOf(id(KA)), id(KA)))
            freshProcess()
            val onHonor = honor(uMined = false, preload = Stored(setOf(id(KH)), id(KH), setOf(id(KA)), id(KA)))
            assertMeet(onFold, onHonor)
        }

    @Test
    fun `2 - the current build alone, own KEX expired unmined - both phones meet and Shielded reads each way`() =
        runBlocking {
            val onFold = fold(u = UState.EXPIRED, preload = null)
            freshProcess()
            val onHonor = honor(uMined = false, preload = null)
            assertMeet(onFold, onHonor)
        }

    @Test
    fun `3 - control, own KEX still pending - the scalar stays U and the contact holds U once it mines`() =
        runBlocking {
            val onFold = fold(u = UState.PENDING, preload = null)
            assertEquals("Fold: the KEX scalar is the pending own KEX U", U, label(onFold.kexScalar))
            freshProcess()
            val onHonor = honor(uMined = true, preload = null)
            assertTrue("HONOR: U mined joins its KEX material", id(U) in onHonor.kexMaterialIds())
            assertMeet(onFold, onHonor)
        }

    @Test
    fun `4 - control, a legacy scalar-only chat with mined scalars - the material bytes stay`() =
        runBlocking {
            val foldLegacy = Stored(emptySet(), id(KH), emptySet(), id(KA))
            val honorLegacy = Stored(emptySet(), id(KH), emptySet(), id(KA))
            val onFold = fold(u = UState.NONE, preload = foldLegacy)
            assertArrayEquals("Fold: KEX material bytes unchanged", foldLegacy.kexMaterial(), onFold.kexMaterial())
            assertArrayEquals("Fold: KEXACK material bytes unchanged", foldLegacy.ackMaterial(), onFold.ackMaterial())
            freshProcess()
            val onHonor = honor(uMined = false, preload = honorLegacy)
            assertArrayEquals("HONOR: KEX material bytes unchanged", honorLegacy.kexMaterial(), onHonor.kexMaterial())
            assertArrayEquals("HONOR: KEXACK material bytes unchanged", honorLegacy.ackMaterial(), onHonor.ackMaterial())
            assertMeet(onFold, onHonor)
        }

    // ---- the checks --------------------------------------------------------------------------------------------------

    private suspend fun assertMeet(
        onFold: Stored,
        onHonor: Stored,
    ) {
        val state =
            "Fold KEX ${labels(onFold.kexMaterialIds())} KEXACK ${labels(onFold.ackMaterialIds())}, " +
                "HONOR KEX ${labels(onHonor.kexMaterialIds())} KEXACK ${labels(onHonor.ackMaterialIds())}"
        assertEquals("both phones' KEX material is equal; $state", labels(onHonor.kexMaterialIds()), labels(onFold.kexMaterialIds()))
        assertEquals("both phones' KEXACK material is equal; $state", labels(onHonor.ackMaterialIds()), labels(onFold.ackMaterialIds()))
        val foldRoot = root(foldKeys, honorKeys, onFold)
        val honorRoot = root(honorKeys, foldKeys, onHonor)
        assertTrue("a Shielded message from the Fold decrypts on the HONOR; $state", reads(foldRoot, foldKeys, honorRoot, honorKeys, b))
        assertTrue("a Shielded message from the HONOR decrypts on the Fold; $state", reads(honorRoot, honorKeys, foldRoot, foldKeys, a))
    }

    // ---- the phones --------------------------------------------------------------------------------------------------

    private enum class UState { NONE, PENDING, EXPIRED }

    /** The Fold (responder): KH received and its KA, both mined; then its own KEX U under its id B, as [u] says. */
    private suspend fun fold(
        u: UState,
        preload: Stored?,
    ): Stored =
        phone(peer = honor, ours = foldKeys, theirs = honorKeys, ownId = b, filed = listOf(a), preload = preload) { f ->
            f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
            f.sent(KA, ackMemo(a, SELF, foldKeys), to = honor, minedAt = NEW + 7)
            if (u != UState.NONE) {
                f.ownKex(U, to = honor, convId = b, minedAt = null)
                f.unmined(U, expired = u == UState.EXPIRED)
            }
        }

    /** The HONOR (initiator): its KH and the Fold's KA, both mined; U only when it mined ([uMined]). */
    private suspend fun honor(
        uMined: Boolean,
        preload: Stored?,
    ): Stored =
        phone(peer = fold, ours = honorKeys, theirs = foldKeys, ownId = a, filed = emptyList(), preload = preload) { f ->
            f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
            f.received(KA, ackMemo(a, fold, foldKeys), minedAt = NEW + 7)
            if (uMined) f.received(U, kexMemo(b, fold, foldKeys), minedAt = NEW + 9)
        }

    private suspend fun phone(
        peer: String,
        ours: E2EKeyPair,
        theirs: E2EKeyPair,
        ownId: String,
        filed: List<String>,
        preload: Stored?,
        chat: (HandshakePassFixture) -> Unit,
    ): Stored {
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        val root = Root()
        preload?.let { root.load(peer, it) }
        f.extra = { method, args -> root.answer(method.name, args) }
        f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
        f.prefs.e2ePeerKey[peer] = theirs.publicKey
        f.ids.store.restoreOwnConversationId(ownId, peer)
        filed.forEach { f.ids.store.setConversationMapping(it, peer) }
        f.open()
        chat(f)
        f.pass(times = 3)
        return root.storedFor(peer)
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
        val s = txs[i] as SendTransaction.Success
        txs[i] =
            if (expired) {
                val o = s.overview.copy(expiryHeight = BlockHeight.new(NEW + 50), transactionState = TransactionState.Expired)
                SendTransaction.Failed(s.id, s.amount, s.timestamp, s.memoCount, s.fee, s.transactionOutputs, o, s.recipient)
            } else {
                val o = s.overview.copy(expiryHeight = BlockHeight.new(TIP + 40), transactionState = TransactionState.Pending)
                SendTransaction.Pending(s.id, s.amount, s.timestamp, s.memoCount, s.fee, s.transactionOutputs, o, s.recipient)
            }
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

    // ---- the root and one ratchet message ---------------------------------------------------------------------------

    private fun root(
        mine: E2EKeyPair,
        peer: E2EKeyPair,
        stored: Stored,
    ): ByteArray =
        E2ERatchet.deriveRatchetRoot(
            E2EEncryption.deriveSharedSecret(mine.privateKey, peer.publicKey),
            null,
            stored.kexMaterial(),
            stored.ackMaterial(),
        )

    private suspend fun reads(
        senderRoot: ByteArray,
        senderKeys: E2EKeyPair,
        receiverRoot: ByteArray,
        receiverKeys: E2EKeyPair,
        convId: String,
    ): Boolean {
        val senderIsLower = senderKeys.publicKey < receiverKeys.publicKey
        val sent = E2ERatchet(senderRoot, convId, senderIsLower, InMemoryRatchetStateStore()).encrypt(PLAINTEXT)
        val read = runCatching { E2ERatchet(receiverRoot, convId, !senderIsLower, InMemoryRatchetStateStore()).decrypt(sent) }
        return read.getOrNull()?.contentEquals(PLAINTEXT) == true
    }

    // ---- the stored root material ------------------------------------------------------------------------------------

    private class Stored(
        val kexSet: Set<String>,
        val kexScalar: String?,
        val ackSet: Set<String>,
        val ackScalar: String?,
    ) {
        fun kexMaterialIds(): Set<String> = kexSet + listOfNotNull(kexScalar)

        fun ackMaterialIds(): Set<String> = ackSet + listOfNotNull(ackScalar)

        fun kexMaterial(): ByteArray = E2ERatchet.canonicalTxidMaterial(kexSet, kexScalar)

        fun ackMaterial(): ByteArray = E2ERatchet.canonicalTxidMaterial(ackSet, ackScalar)
    }

    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        fun load(
            peer: String,
            s: Stored,
        ) {
            if (s.kexSet.isNotEmpty()) kex[peer] = s.kexSet
            if (s.ackSet.isNotEmpty()) acks[peer] = s.ackSet
            s.kexScalar?.let { kexScalar[peer] = it }
            s.ackScalar?.let { ackScalar[peer] = it }
        }

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

    private val labelsByTxid = listOf(KH, KA, U).associateBy { id(it) }

    private fun label(txid: String?): String = txid?.let { labelsByTxid[it] ?: "other" } ?: "none"

    private fun labels(txids: Set<String>): List<String> = txids.map { label(it) }.sorted()

    private fun id(label: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(label.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KH = "zc1583x-honor-kex"
        const val KA = "zc1583x-fold-ack"
        const val U = "zc1583x-fold-kex-unmined"

        val PLAINTEXT = "zc1583 expired-own shielded".toByteArray()
    }
}
