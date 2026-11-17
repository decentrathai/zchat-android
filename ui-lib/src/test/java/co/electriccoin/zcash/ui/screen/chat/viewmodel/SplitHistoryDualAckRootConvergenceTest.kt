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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1571 (cause 1, second RED; fix option A, owner W-133) — the KEX and KEXACK txid material that feeds deriveRatchetRoot
 * is the per-peer UNION of the mined key exchanges across all of that peer's conversation ids. The first RED
 * (SplitHistoryRatchetRootConvergenceTest) has one KEXACK only, so its KEXACK assertion holds trivially; this file adds:
 *
 * 1. a simultaneous start: the HONOR sends KH under its id A, the Fold sends KF under its id B, the Fold answers KH with AF
 *    under A and the HONOR answers KF with AH under B. Two KEXACKs under two ids. Each phone files both ids under the other
 *    (each answered the other's KEX); the HONOR holds more chats than the Fold (another per-id map order). Both phones must
 *    hold KEX material {KH, KF} and KEXACK material {AF, AH}, and a message from each side reads on the other.
 * 2. the split history (KH and KA under A, the Fold's later KF under B) where the HONOR never filed B: it must still
 *    attribute KF to the Fold, as the KEX's verified payload sender is the established contact with the key it holds.
 * 3. the negative half of 2: a mined KEX under an id the HONOR never filed, claiming the Fold's address but carrying a
 *    DIFFERENT key, must not enter the HONOR's KEX or KEXACK material (nor may KF's attribution let it in).
 *
 * Same harness as the first RED (the real convertToConversations over each phone's view of the same mined txids; the root
 * material is what the pass stores through setKexTxIds/setKexAckTxIds and the legacy scalars). Every address, key, id and
 * txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitHistoryDualAckRootConvergenceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 15721)
    private val fold = contact(seed = 15722)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

    private val a = "ZC1571AA"
    private val b = "ZC1571BB"

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
    fun `1 - simultaneous start, two KEXACKs under two ids - both phones hold the union, messages read both ways`() =
        runBlocking {
            val onHonor =
                phone(
                    peer = fold,
                    ours = honorKeys,
                    theirs = foldKeys,
                    ownId = a,
                    filed = listOf(b),
                    otherChats = OTHER_CHATS,
                ) { f ->
                    f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
                    f.received(KF, kexMemo(b, fold, foldKeys), minedAt = NEW + 5)
                    f.received(AF, ackMemo(a, fold, foldKeys), minedAt = NEW + 7)
                    f.sent(AH, ackMemo(b, SELF, honorKeys), to = fold, minedAt = NEW + 7)
                }
            freshProcess()
            val onFold =
                phone(peer = honor, ours = foldKeys, theirs = honorKeys, ownId = b, filed = listOf(a), otherChats = 0) { f ->
                    f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
                    f.ownKex(KF, to = honor, convId = b, minedAt = NEW + 5)
                    f.sent(AF, ackMemo(a, SELF, foldKeys), to = honor, minedAt = NEW + 7)
                    f.received(AH, ackMemo(b, honor, honorKeys), minedAt = NEW + 7)
                }

            assertEquals(
                "KEX root material (set + scalar), Fold expected vs HONOR actual, by label",
                labels(onFold.kexMaterialIds()),
                labels(onHonor.kexMaterialIds()),
            )
            assertEquals(
                "KEXACK root material (set + scalar), Fold expected vs HONOR actual, by label",
                labels(onFold.ackMaterialIds()),
                labels(onHonor.ackMaterialIds()),
            )
            assertEquals("HONOR KEX material is the union over ids A and B", listOf(KF, KH).sorted(), labels(onHonor.kexMaterialIds()))
            assertEquals("HONOR KEXACK material is the union over ids A and B", listOf(AF, AH).sorted(), labels(onHonor.ackMaterialIds()))

            val honorRoot = root(honorKeys, foldKeys, onHonor)
            val foldRoot = root(foldKeys, honorKeys, onFold)
            assertTrue("the Fold's Shielded message decrypts on the HONOR", reads(foldRoot, foldKeys, honorRoot, honorKeys, b))
            assertTrue("the HONOR's Shielded message decrypts on the Fold", reads(honorRoot, honorKeys, foldRoot, foldKeys, a))
        }

    @Test
    fun `2 - the Fold's later KEX under an id the HONOR never filed - attributed by its verified sender and matching key`() =
        runBlocking {
            val onHonor = honorSplit(forged = false)
            freshProcess()
            val onFold = foldSplit()

            assertTrue(
                "the HONOR attributes KF (id B never filed, verified sender = the Fold, key = the Fold's stored key); " +
                    "HONOR KEX material: ${labels(onHonor.kexMaterialIds())}",
                KF in labels(onHonor.kexMaterialIds()),
            )
            assertEquals(
                "KEX root material (set + scalar), Fold expected vs HONOR actual, by label",
                labels(onFold.kexMaterialIds()),
                labels(onHonor.kexMaterialIds()),
            )
            assertEquals(
                "KEXACK root material (set + scalar), Fold expected vs HONOR actual, by label",
                labels(onFold.ackMaterialIds()),
                labels(onHonor.ackMaterialIds()),
            )
            val honorRoot = root(honorKeys, foldKeys, onHonor)
            val foldRoot = root(foldKeys, honorKeys, onFold)
            assertTrue("the Fold's Shielded message decrypts on the HONOR", reads(foldRoot, foldKeys, honorRoot, honorKeys, b))
        }

    @Test
    fun `3 - a mined KEX claiming the Fold under an unfiled id with a different key - never enters the HONOR's material`() =
        runBlocking {
            val onHonor = honorSplit(forged = true)
            assertFalse(
                "a KEX claiming the Fold with a different key is not in the HONOR's KEX material: ${labels(onHonor.kexMaterialIds())}",
                KX in labels(onHonor.kexMaterialIds()),
            )
            assertFalse(
                "a KEX claiming the Fold with a different key is not in the HONOR's KEXACK material: " +
                    "${labels(onHonor.ackMaterialIds())}",
                KX in labels(onHonor.ackMaterialIds()),
            )
        }

    // ---- the phones -------------------------------------------------------------------------------------------------

    /** The HONOR of the split history: KH and KA under its id A; the Fold's KF under B (never filed); with [forged], KX. */
    private suspend fun honorSplit(forged: Boolean): Stored =
        phone(peer = fold, ours = honorKeys, theirs = foldKeys, ownId = a, filed = emptyList(), otherChats = 0) { f ->
            f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
            f.received(KA, ackMemo(a, fold, foldKeys), minedAt = NEW + 7)
            f.received(KF, kexMemo(b, fold, foldKeys), minedAt = NEW + 9)
            if (forged) f.received(KX, kexMemo(FORGED_ID, fold, E2EEncryption.generateKeyPair()), minedAt = NEW + 11)
        }

    /** The Fold of the split history: the HONOR's KH under A; its own KA under A; its own KF under its id B. */
    private suspend fun foldSplit(): Stored =
        phone(peer = honor, ours = foldKeys, theirs = honorKeys, ownId = b, filed = listOf(a), otherChats = 0) { f ->
            f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
            f.sent(KA, ackMemo(a, SELF, foldKeys), to = honor, minedAt = NEW + 7)
            f.ownKex(KF, to = honor, convId = b, minedAt = NEW + 9)
        }

    /**
     * One phone whose chat with [peer] is established ([ours] and [theirs] keys held): its own id [ownId], the ids [filed]
     * filed under [peer] before the pass, [otherChats] other contacts' key exchanges, then the chat's transactions.
     */
    private suspend fun phone(
        peer: String,
        ours: E2EKeyPair,
        theirs: E2EKeyPair,
        ownId: String,
        filed: List<String>,
        otherChats: Int,
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
        repeat(otherChats) { n ->
            val other = contact(seed = 15900 + n)
            f.received("other-$n", kexMemo("OTHR%04d".format(n), other, E2EEncryption.generateKeyPair()), minedAt = NEW + 1)
        }
        chat(f)
        f.pass(times = 3)
        labelsByTxid.putAll(listOf(KH, KA, KF, AF, AH, KX).associateBy { f.txid(it) })
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

    /** The production derivation on one side (no Quantum Shield on either). */
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

    // ---- the stored root material (as the first RED) ----------------------------------------------------------------

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
        const val KH = "zc1571d-honor-kex"
        const val KF = "zc1571d-fold-kex"
        const val KA = "zc1571d-fold-ack"
        const val AF = "zc1571d-fold-ack-of-kh"
        const val AH = "zc1571d-honor-ack-of-kf"
        const val KX = "zc1571d-forged-kex"

        /** An id neither phone ever filed. */
        const val FORGED_ID = "ZC1571XX"

        val PLAINTEXT = "zc1571 dual-ack shielded".toByteArray()

        /** More ids than a 16-bucket HashMap holds before it grows (12). */
        const val OTHER_CHATS = 12
    }
}
