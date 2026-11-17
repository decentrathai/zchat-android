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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1571 (cause 1) — two phones that read the SAME mined key-exchange history of their chat must derive the SAME ratchet
 * root material, or every Shielded (ratchet) message fails on the receiver ("its decryption key isn't on this device").
 *
 * The chat (qa3 Section E.2, Fold <-> HONOR, split history): the HONOR started it with a key exchange KH under its own id A;
 * the Fold answered with KA under A; later the Fold (switching the chat to Shielded) sent its own key exchange KF under its
 * own id B. Both ids name the same contact on both phones. The real history pass (convertToConversations) of each phone runs
 * over its own view of those three mined transactions (same txids, the memos as each side wrote or received them); the HONOR
 * also holds other contacts' key exchanges under other ids, as a phone with more chats does. The root material is what
 * getOrCreateMessageProcessorUngated feeds deriveRatchetRoot: canonicalTxidMaterial(KEX set, KEX scalar) and
 * canonicalTxidMaterial(KEXACK set, KEXACK scalar), read from what the pass stored for the contact.
 *
 * At 5af0a19ae the pass REPLACES the contact's KEX set once per id (ChatViewModel ~11301-11322): with KEX txids under two
 * ids the stored set is the one of whichever id is handled last, and an id this phone has not filed under the contact is
 * skipped. The KEX scalar is this phone's own KEX (~10296-10305). Case 1 is the pass as it runs: the HONOR never files the
 * Fold's id B (it holds the Fold's key, so the first-contact mapping does not run), keeps {KH} + scalar KH; the Fold files
 * both ids and keeps {KF} + scalar KF. Cases 2 and 3 file B on the HONOR too (the order of the two ids then decides).
 * Every address, key, id and txid is synthetic; failure messages name txids by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitHistoryRatchetRootConvergenceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val honor = contact(seed = 15711)
    private val fold = contact(seed = 15712)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val foldKeys = E2EEncryption.generateKeyPair()

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
    fun `1 - the pass as it runs (the HONOR never files the Fold's new id) - equal root material, Fold message reads on HONOR`() =
        runBlocking { converges(honorFilesB = false, otherChats = 0) }

    @Test
    fun `2 - the HONOR files the Fold's new id under the Fold too - equal root material, Fold message reads on the HONOR`() =
        runBlocking { converges(honorFilesB = true, otherChats = 0) }

    @Test
    fun `3 - as 2, the HONOR with more chats than the Fold (another map order) - equal root material, the message reads`() =
        runBlocking { converges(honorFilesB = true, otherChats = OTHER_CHATS) }

    private suspend fun converges(
        honorFilesB: Boolean,
        otherChats: Int,
    ) {
        val (a, b) = idsWhoseOrderFlips()
        val onHonor = honorPhone(a, b, honorFilesB, otherChats)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        val onFold = foldPhone(a, b)

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

        // The production derivation on each side (no Quantum Shield on either), then one Fold ratchet message.
        val honorRoot =
            E2ERatchet.deriveRatchetRoot(
                E2EEncryption.deriveSharedSecret(honorKeys.privateKey, foldKeys.publicKey),
                null,
                onHonor.kexMaterial(),
                onHonor.ackMaterial(),
            )
        val foldRoot =
            E2ERatchet.deriveRatchetRoot(
                E2EEncryption.deriveSharedSecret(foldKeys.privateKey, honorKeys.publicKey),
                null,
                onFold.kexMaterial(),
                onFold.ackMaterial(),
            )
        val foldIsLower = foldKeys.publicKey < honorKeys.publicKey
        val sent = E2ERatchet(foldRoot, b, foldIsLower, InMemoryRatchetStateStore()).encrypt(PLAINTEXT)
        val read = runCatching { E2ERatchet(honorRoot, b, !foldIsLower, InMemoryRatchetStateStore()).decrypt(sent) }
        assertTrue(
            "the Fold's Shielded message decrypts on the HONOR (failed: ${read.exceptionOrNull()?.javaClass?.simpleName})",
            read.getOrNull()?.contentEquals(PLAINTEXT) == true,
        )
    }

    // ---- the two phones ---------------------------------------------------------------------------------------------

    /**
     * The HONOR: its own KH under its id [a]; the Fold's answer KA under [a]; the Fold's KF under [b] (with [honorFilesB]
     * the id B is filed under the Fold before the pass, as a handler that maps it would leave it); [otherChats] other
     * contacts' key exchanges under other ids.
     */
    private suspend fun honorPhone(
        a: String,
        b: String,
        honorFilesB: Boolean,
        otherChats: Int,
    ): Stored {
        val f = HandshakePassFixture(scheduler, fold, firstContact = false)
        val root = Root()
        f.extra = { method, args -> root.answer(method.name, args) }
        f.prefs.ourKeyStore[fold] = honorKeys.publicKey to honorKeys.privateKey
        f.prefs.e2ePeerKey[fold] = foldKeys.publicKey
        f.ids.store.restoreOwnConversationId(a, fold)
        if (honorFilesB) f.ids.store.setConversationMapping(b, fold)
        f.open()
        repeat(otherChats) { n ->
            val other = contact(seed = 15800 + n)
            val keys = E2EEncryption.generateKeyPair()
            f.received(
                "other-$n",
                kexMemo(otherId(n), other, keys),
                minedAt = NEW + 1,
            )
        }
        f.ownKex(KH, to = fold, convId = a, minedAt = NEW + 5)
        f.received(KA, ackMemo(a, fold, foldKeys), minedAt = NEW + 7)
        f.received(KF, kexMemo(b, fold, foldKeys), minedAt = NEW + 9)
        f.pass(times = 3)
        labelsByTxid.putAll(listOf(KH, KA, KF).associateBy { f.txid(it) })
        return root.storedFor(fold)
    }

    /** The Fold: the HONOR's KH under [a]; its own answer KA under [a]; its own KF under its id [b]. */
    private suspend fun foldPhone(
        a: String,
        b: String,
    ): Stored {
        val f = HandshakePassFixture(scheduler, honor, firstContact = false)
        val root = Root()
        f.extra = { method, args -> root.answer(method.name, args) }
        f.prefs.ourKeyStore[honor] = foldKeys.publicKey to foldKeys.privateKey
        f.prefs.e2ePeerKey[honor] = honorKeys.publicKey
        f.ids.store.restoreOwnConversationId(b, honor)
        f.ids.store.setConversationMapping(a, honor)
        f.open()
        f.received(KH, kexMemo(a, honor, honorKeys), minedAt = NEW + 5)
        f.sent(KA, ackMemo(a, SELF, foldKeys), to = honor, minedAt = NEW + 7)
        f.ownKex(KF, to = honor, convId = b, minedAt = NEW + 9)
        f.pass(times = 3)
        labelsByTxid.putAll(listOf(KH, KA, KF).associateBy { f.txid(it) })
        return root.storedFor(honor)
    }

    private fun kexMemo(
        convId: String,
        sender: String,
        keys: E2EKeyPair,
    ): String {
        val payload = E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey)
        return ZMSGProtocol.createV4KEXMessage(convId, sender, payload)
    }

    private fun ackMemo(
        convId: String,
        sender: String,
        keys: E2EKeyPair,
    ): String {
        val payload = E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey)
        return ZMSGProtocol.createV4KEXAckMessage(convId, sender, payload)
    }

    // ---- the stored root material ------------------------------------------------------------------------------------

    private class Stored(
        val kexSet: Set<String>,
        val kexScalar: String?,
        val ackSet: Set<String>,
        val ackScalar: String?,
    ) {
        /** The txids canonicalTxidMaterial folds for the KEX half (set plus scalar). */
        fun kexMaterialIds(): Set<String> = kexSet + listOfNotNull(kexScalar)

        fun ackMaterialIds(): Set<String> = ackSet + listOfNotNull(ackScalar)

        fun kexMaterial(): ByteArray = E2ERatchet.canonicalTxidMaterial(kexSet, kexScalar)

        fun ackMaterial(): ByteArray = E2ERatchet.canonicalTxidMaterial(ackSet, ackScalar)
    }

    /** The KEX/KEXACK txid sets and scalars the pass writes, by address (the reference harness's Root). */
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

    private fun label(txid: String?): String = txid?.let { labelsByTxid[it] ?: "other" } ?: "none"

    private fun labels(txids: Set<String>): List<String> = txids.map { label(it) }.sorted()

    /**
     * Two ids A and B whose order in the pass's per-id HashMap flips between a phone with this chat only and one with
     * [OTHER_CHATS] more ids (the table grows from 16 to 32 buckets). Checked with real HashMaps filled in scan order.
     */
    private fun idsWhoseOrderFlips(): Pair<String, String> {
        val candidates = (0 until 400).map { "ZC%04dQ".format(it).padEnd(8, 'X').take(8) }
        for (a in candidates) {
            for (b in candidates) {
                if (a == b) continue
                val small =
                    HashMap<String, Int>()
                        .apply {
                            put(a, 0)
                            put(b, 0)
                        }.keys
                        .toList()
                val big =
                    HashMap<String, Int>()
                        .apply {
                            repeat(OTHER_CHATS) { put(otherId(it), 0) }
                            put(a, 0)
                            put(b, 0)
                        }.keys
                        .filter { it == a || it == b }
                if (small != big) return a to b
            }
        }
        error("no id pair whose order flips")
    }

    private fun otherId(n: Int): String = "OTHR%04d".format(n)

    private companion object {
        const val KH = "zc1571-honor-kex"
        const val KA = "zc1571-fold-ack"
        const val KF = "zc1571-fold-kex"

        val PLAINTEXT = "zc1571 shielded from the Fold".toByteArray()

        /** More ids than a 16-bucket HashMap holds before it grows (12). */
        const val OTHER_CHATS = 12
    }
}
