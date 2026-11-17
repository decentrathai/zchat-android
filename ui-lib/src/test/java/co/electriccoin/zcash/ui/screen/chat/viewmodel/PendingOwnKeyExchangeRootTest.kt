package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1571 (third RED; Fable review of 242b2c113, finding 1) — a Shielded message sent while this phone's OWN key exchange
 * is still unmined must read on the other phone once that key exchange mines.
 *
 * 242b2c113 writes the legacy KEX/KEXACK scalar from a MINED own key exchange only. In the ordinary responder flow the
 * Fold pays its KEXACK KA for the HONOR's KEX KH and the user replies within a block or two: the Fold's send processor is
 * built from KEXACK material = the stored set (empty: KA is unmined) + no scalar, while the HONOR, once KA mines, holds
 * {KA}. The two roots differ and the reply never decrypts. Before 242b2c113 the pending KA was the scalar, so both held {KA}.
 *
 * 1. responder: the Fold pays KA (unmined), builds its processor after its pass in that state, sends M1; the HONOR reads
 *    KA mined (passes N, N+1, N+2) and must decrypt M1.
 * 2. the same shape for an own KEX sent while both keys are held: the Fold (KH and KA mined) sends its own KEX KF under its
 *    id B, then a Shielded message before KF mines; the HONOR reads KF mined and must decrypt it.
 *
 * Same harness as the first two REDs (SplitHistoryRatchetRootConvergenceTest, SplitHistoryDualAckRootConvergenceTest): the
 * real convertToConversations over each phone's view of the txids; the processor's root is the production derivation over
 * what the pass stored (getKexTxIds/getE2EKexTxId and getKexAckTxIds/getE2EKexAckTxId, as getOrCreateProcessor reads them).
 * Every address, key, id and txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingOwnKeyExchangeRootTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 15731)
    private val fold = contact(seed = 15732)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1571PA"
    private val b = "ZC1571PB"

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
    fun `1 - responder replies before its KEXACK mines - the reply decrypts on the initiator once the KEXACK mines`() =
        runBlocking {
            // The Fold: the HONOR's KH mined, its own KA paid and not mined yet; the processor is built now.
            val onFold =
                phone(peer = honor, ours = foldKeys, theirs = honorKeys, ownId = b, filed = listOf(a)) { f ->
                    f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
                    f.sent(KA, ackMemo(a, SELF, foldKeys), to = honor, minedAt = null)
                }
            freshProcess()
            // The HONOR: its KH and the Fold's KA, both mined.
            val onHonor =
                phone(peer = fold, ours = honorKeys, theirs = foldKeys, ownId = a, filed = emptyList()) { f ->
                    f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
                    f.received(KA, ackMemo(a, fold, foldKeys), minedAt = NEW + 6)
                }

            assertReads(onFold, onHonor, convId = b)
        }

    @Test
    fun `2 - own KEX sent while both keys are held - a message before it mines decrypts on the peer once it mines`() =
        runBlocking {
            // The Fold: KH and its KA mined (established), then its own KF under its id B, not mined yet.
            val onFold =
                phone(peer = honor, ours = foldKeys, theirs = honorKeys, ownId = b, filed = listOf(a)) { f ->
                    f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
                    f.sent(KA, ackMemo(a, SELF, foldKeys), to = honor, minedAt = NEW + 7)
                    f.ownKex(KF, to = honor, convId = b, minedAt = null)
                }
            freshProcess()
            // The HONOR: KH, KA and the Fold's KF, all mined.
            val onHonor =
                phone(peer = fold, ours = honorKeys, theirs = foldKeys, ownId = a, filed = emptyList()) { f ->
                    f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
                    f.received(KA, ackMemo(a, fold, foldKeys), minedAt = NEW + 7)
                    f.received(KF, kexMemo(b, fold, foldKeys), minedAt = NEW + 9)
                }

            assertReads(onFold, onHonor, convId = b)
        }

    /**
     * ZC-1792 / ZC-1632 (QA2 k1; Fable root cause 12 Nov, section 1) — the free proof of the cause, a pin that passes at RED.
     *
     * Case 1 above pays the answer before the Fold seals, so both phones end on the same root. Here the Fold has stored the
     * HONOR's KEX and made its key pair (the responder's reply part does both) but has NOT paid its answer when it seals:
     * its KEXACK material is empty. Once the answer is paid and mined the HONOR derives its root over {KA}, and the two
     * roots differ for good: nothing tries the earlier root again. The same message opens on the HONOR with the KEXACK
     * material empty (what the HONOR still holds), which is why the fix is F1 (never seal before the answer is paid), not a
     * change to the key.
     */
    @Test
    fun `3 - ZC-1792 pin - a reply sealed before the answer is paid does not read once the answer is mined`() =
        runBlocking {
            // The Fold: the HONOR's KH mined; its answer neither paid nor mined; the processor is built now.
            val onFold =
                phone(peer = honor, ours = foldKeys, theirs = honorKeys, ownId = b, filed = listOf(a)) { f ->
                    f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
                }
            freshProcess()
            // The HONOR: its KH and, later, the Fold's KA, both mined.
            val onHonor =
                phone(peer = fold, ours = honorKeys, theirs = foldKeys, ownId = a, filed = emptyList()) { f ->
                    f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
                    f.received(KA, ackMemo(a, fold, foldKeys), minedAt = NEW + 40)
                }

            assertTrue("fixture: the Fold sealed with no KEXACK material", onFold.ackMaterialIds().isEmpty())
            assertTrue("fixture: the HONOR holds the mined KA", onHonor.ackMaterialIds().isNotEmpty())
            val foldRoot = root(foldKeys, honorKeys, onFold)
            val honorRoot = root(honorKeys, foldKeys, onHonor)
            assertFalse(
                "the cause: a message sealed before the answer is paid does not read under the post-answer root " +
                    "(Fold KEXACK ${labels(onFold.ackMaterialIds())}, HONOR KEXACK ${labels(onHonor.ackMaterialIds())})",
                reads(foldRoot, foldKeys, honorRoot, honorKeys, b),
            )
            val honorWithoutAck =
                E2ERatchet.deriveRatchetRoot(
                    E2EEncryption.deriveSharedSecret(honorKeys.privateKey, foldKeys.publicKey),
                    null,
                    onHonor.kexMaterial(),
                    E2ERatchet.canonicalTxidMaterial(emptySet(), null),
                )
            assertTrue(
                "the HONOR holds what opens it: its root with the KEXACK material empty reads the Fold's message",
                reads(foldRoot, foldKeys, honorWithoutAck, honorKeys, b),
            )
        }

    /** The Fold's message, sealed under the root it built while its key exchange was pending, reads on the HONOR. */
    private suspend fun assertReads(
        onFold: Stored,
        onHonor: Stored,
        convId: String,
    ) {
        val foldRoot = root(foldKeys, honorKeys, onFold)
        val honorRoot = root(honorKeys, foldKeys, onHonor)
        assertTrue(
            "the Fold's Shielded message M1 (sent before its own key exchange mined) decrypts on the HONOR; " +
                "Fold KEX ${labels(onFold.kexMaterialIds())} KEXACK ${labels(onFold.ackMaterialIds())}, " +
                "HONOR KEX ${labels(onHonor.kexMaterialIds())} KEXACK ${labels(onHonor.ackMaterialIds())}",
            reads(foldRoot, foldKeys, honorRoot, honorKeys, convId),
        )
    }

    // ---- the phones -------------------------------------------------------------------------------------------------

    /**
     * One phone whose chat with [peer] is established ([ours] and [theirs] keys held): its own id [ownId], the ids [filed]
     * filed under [peer] before the pass, then the chat's transactions; three passes (N, N+1, N+2) over them.
     */
    private suspend fun phone(
        peer: String,
        ours: E2EKeyPair,
        theirs: E2EKeyPair,
        ownId: String,
        filed: List<String>,
        chat: (HandshakePassFixture) -> Unit,
    ): Stored {
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        val root = Root()
        f.extra = { method, args -> root.answer(method.name, args) }
        f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
        f.prefs.e2ePeerKey[peer] = theirs.publicKey
        f.ids.store.restoreOwnConversationId(ownId, peer)
        filed.forEach { f.ids.store.setConversationMapping(it, peer) }
        f.open()
        chat(f)
        f.pass(times = 3)
        labelsByTxid.putAll(listOf(KH, KA, KF).associateBy { f.txid(it) })
        return root.storedFor(peer)
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

    /** The production derivation on one side (no Quantum Shield on either), as getOrCreateProcessor builds it. */
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

    /** One message from the sender's ratchet under [convId] reads on the receiver's. */
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

    // ---- the stored root material (as the first two REDs) -----------------------------------------------------------

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

        fun storedFor(peer: String) = Stored(kex[peer].orEmpty(), kexScalar[peer], acks[peer].orEmpty(), ackScalar[peer])

        @Suppress("UNCHECKED_CAST")
        fun answer(
            method: String,
            a: Array<Any?>,
        ): Any? =
            when (method) {
                "getKexTxIds" -> kex[a[0] as String].orEmpty()
                "setKexTxIds" -> kex.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getKexAckTxIds" -> acks[a[0] as String].orEmpty()
                "setKexAckTxIds" -> acks.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getE2EKexTxId" -> kexScalar[a[0] as String]
                "setE2EKexTxId" -> kexScalar.put(a[0] as String, a[1] as String).let { null }
                "getE2EKexAckTxId" -> ackScalar[a[0] as String]
                "setE2EKexAckTxId" -> ackScalar.put(a[0] as String, a[1] as String).let { null }
                else -> ClaimMarkMaps.UNHANDLED
            }
    }

    private val labelsByTxid = ConcurrentHashMap<String, String>()

    private fun labels(txids: Set<String>): List<String> = txids.map { labelsByTxid[it] ?: "other" }.sorted()

    private companion object {
        const val KH = "zc1571p-honor-kex"
        const val KA = "zc1571p-fold-ack"
        const val KF = "zc1571p-fold-kex"

        val PLAINTEXT = "zc1571 pending-own shielded".toByteArray()
    }
}
