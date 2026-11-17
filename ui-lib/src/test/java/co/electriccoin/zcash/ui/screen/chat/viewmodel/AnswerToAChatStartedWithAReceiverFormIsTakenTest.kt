package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.ZcashReceivers
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.TIP
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.receiverForm
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
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-757 round 13 (ZC-1164) — the answer to a chat this phone started with a receiver form of the peer's address is taken.
 *
 * No restore. The user starts a chat with the peer's Sapling address (compose accepts it) or with a Unified Address that
 * holds only some of the peer's receivers. Our key exchange X is filed normally: X maps to that text and our id is our own
 * for it, the send paid that text. The peer answers under X claiming its full Unified Address U (its own address, which
 * holds that receiver). At 93b6bccec the round 9-12 refusal ("an answer filed under one address but claiming another
 * holder of the paid receiver") does not ask whether X was filed by a restore guess, so this honest answer is refused
 * every pass and its txid stays out of the filed address's KEXACK set: no key, no chat. At 70cdc3010 and in 2.12.4 it went
 * to handleKEXMessage, which stores the key at the address X maps to (the mapping wins over the claimed address).
 *
 * Wanted: the answer is taken by leg 1, its key stored at the filed address, its txid in the filed address's KEXACK set,
 * and X still maps to the filed address. The restore-guess refusal (an id carrying the nosendid marker, filed under a
 * guessed holder, claiming M1 or a built T) stays pinned by RebuiltGroupMemberFromRestoredHistoryTest's (k) round 10-12
 * cases. Every address, key, id and txid is synthetic; failure messages name addresses by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerToAChatStartedWithAReceiverFormIsTakenTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    /** The peer's full Unified Address U (a Sapling and an Orchard receiver). */
    private val peer = contact(seed = 11641)

    /** U's Sapling receiver as a Sapling address (zs…). */
    private val peerSapling = saplingAddress(peer)

    /** A smaller Unified Address: U's Orchard receiver only. */
    private val peerSmaller = receiverForm(peer, typecode = 3)

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
    fun `premise - the Sapling form and the smaller address are receivers within the peer's address, not its text`() {
        assertTrue("the Sapling form decodes to U's Sapling receiver", ZcashReceivers.within(peerSapling, peer))
        assertTrue("the smaller address sits within U", ZcashReceivers.within(peerSmaller, peer))
        assertTrue("the forms differ from U's text", peerSapling != peer && peerSmaller != peer)
    }

    @Test
    fun `1 - a chat started with the peer's Sapling address, the peer answers claiming its full address - the answer is taken`() =
        runBlocking { answerIsTaken(filed = peerSapling, label = "sapling") }

    @Test
    fun `2 - a chat started with a smaller unified address of the peer, the peer answers claiming its full address - taken`() =
        runBlocking { answerIsTaken(filed = peerSmaller, label = "smaller") }

    private suspend fun answerIsTaken(
        filed: String,
        label: String,
    ) {
        val f = HandshakePassFixture(scheduler, filed, firstContact = false)
        val kexSets = ConcurrentHashMap<String, Set<String>>()
        val ackSets = ConcurrentHashMap<String, Set<String>>()
        f.extra = { method, a ->
            @Suppress("UNCHECKED_CAST")
            when (method.name) {
                "getKexTxIds" -> kexSets[a[0] as String].orEmpty()
                "setKexTxIds" -> kexSets.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getKexAckTxIds" -> ackSets[a[0] as String].orEmpty()
                "setKexAckTxIds" -> ackSets.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                else -> ClaimMarkMaps.UNHANDLED
            }
        }
        // A fresh chat start: our own id for the text the user entered, our key exchange paid to that text.
        val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(filed)
        f.open()
        f.ownKex("zc1164-$label-own", to = filed, convId = own, minedAt = NEW + 5)
        val theirs = E2EEncryption.generateKeyPair()
        val answer =
            ZMSGProtocol.createV4KEXAckMessage(own, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey))
        f.received("zc1164-$label-ack", answer, minedAt = NEW + 7)

        f.pass(times = 3)

        assertEquals("$label: our id is mapped to", "filed", name(f.ids.conv(own), filed))
        assertEquals(
            "$label: the peer's answer's key at the filed address (no key: the answer was refused)",
            theirs.publicKey,
            f.prefs.e2ePeerKey[filed],
        )
        assertTrue(
            "$label: the answer's txid is in the filed address's KEXACK set (left out: refused)",
            f.txid("zc1164-$label-ack") in ackSets[filed].orEmpty(),
        )
    }

    // ---- ZC-757 round 15b (review of 579c08790, 27 Oct): the honest side of round 15 (c) ---------------------------------
    // Round 15 (c) refuses an answer filed under one address and claiming another while one of our own key exchanges under
    // its id has outputs this pass cannot read; a later pass judges it again. For the honest chat started with a smaller
    // address of the peer: (A) the unreadable pass writes nothing, the readable pass takes the answer; (B) finding 1 (P2):
    // the refused answer's height is not reported to the processed-KEX floor, so a complete pass can let the floor rise
    // over it and the readable pass then skips it for good (hasProcessedKexTx); (C) finding 2 (P3): with a second answer
    // under the id that is not refused, the unreadable pass writes the KEXACK set without the refused one (the root moves).

    @Test
    fun `15b A - our key exchange's outputs unreadable for one pass, then readable - nothing written, then the answer is taken`() =
        runBlocking {
            val c = startedWithSmaller("zc757a")
            c.f.unreadableOutputs += c.ownLabel
            c.f.pass(times = 3)

            assertEquals("unreadable pass: a key stored at the filed address", null, c.f.prefs.e2ePeerKey[peerSmaller])
            assertEquals("unreadable pass: a key stored at the full address", null, c.f.prefs.e2ePeerKey[peer])
            assertEquals(
                "unreadable pass: the answer's txid in the filed address's KEXACK set",
                false,
                c.ackTxId in c.root.ack(peerSmaller),
            )

            c.f.unreadableOutputs.clear()
            c.f.pass(times = 3)
            c.assertTaken("readable pass")
        }

    @Test
    fun `15b B - a complete pass while the answer waits never lets the processed-KEX floor skip it - taken once readable`() =
        runBlocking {
            val c = startedWithSmaller("zc757b")
            c.f.unreadableOutputs += c.ownLabel
            // Complete, stable passes (the history scan complete, the same list twice) while our outputs cannot be read.
            c.f.pass(times = 3)
            assertEquals("unreadable pass: a key stored at the filed address", null, c.f.prefs.e2ePeerKey[peerSmaller])

            // As the same wallet's passes do: more processed key exchange answers than the store keeps (500), mined at
            // the answer's height, at or below the fully scanned height. The floor rises only as far as the ceiling the
            // passes reported allows (KI-24 G-KI24-2): never over an answer still waiting.
            repeat(FILLERS) { n -> c.kexStore.markKexTxProcessed("%064x".format(0x7575_0000L + n), ANSWER_AT, TIP) }

            c.f.unreadableOutputs.clear()
            c.f.pass(times = 3)
            c.assertTaken("readable pass after the floor could rise")
        }

    @Test
    fun `15b C - while our outputs are unreadable the filed address's KEXACK set and scalar stay as they were`() =
        runBlocking {
            val c = startedWithSmaller("zc757c")
            // A second answer under X from the peer with the same key, claiming the filed address itself (never refused).
            c.f.received(
                "zc757c-ack2",
                ZMSGProtocol.createV4KEXAckMessage(
                    c.own,
                    peerSmaller,
                    E2EEncryption.createKEXAckPayload(peerSmaller, c.theirs.publicKey, c.theirs.privateKey),
                ),
                minedAt = ANSWER_AT + 1,
            )
            c.f.pass(times = 3)
            c.assertTaken("readable pass")
            val ack2 = c.f.txid("zc757c-ack2")
            assertEquals(
                "fixture: both answers in the filed address's KEXACK set",
                true,
                c.ackTxId in c.root.ack(peerSmaller) && ack2 in c.root.ack(peerSmaller),
            )
            val setBefore = c.root.ack(peerSmaller)
            val scalarBefore = c.root.ackScalar[peerSmaller]

            c.f.unreadableOutputs += c.ownLabel
            c.f.pass(times = 3)

            assertEquals(
                "unreadable pass: the filed address's KEXACK set (by label; 'first answer' left out moves the root)",
                labels(setBefore, c),
                labels(c.root.ack(peerSmaller), c),
            )
            assertEquals(
                "unreadable pass: the filed address's KEXACK scalar unchanged",
                true,
                c.root.ackScalar[peerSmaller] == scalarBefore,
            )
        }

    /** A chat this phone started with [peerSmaller]: our key exchange X to it, the peer's answer claiming [peer]. */
    private class SmallerChat(
        val f: HandshakePassFixture,
        val root: Root,
        val kexStore: co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences,
        val own: String,
        val ownLabel: String,
        val ackTxId: String,
        val theirs: E2EKeyPair,
        val filed: String,
    ) {
        fun assertTaken(phase: String) {
            assertEquals(
                "$phase: the peer's key stored at the filed address (false: the answer was refused or skipped)",
                true,
                f.prefs.e2ePeerKey[filed] == theirs.publicKey,
            )
            assertEquals("$phase: the answer's txid in the filed address's KEXACK set", true, ackTxId in root.ack(filed))
        }
    }

    private suspend fun startedWithSmaller(tag: String): SmallerChat {
        val f = HandshakePassFixture(scheduler, peerSmaller, firstContact = false)
        val root = Root()
        // The processed-KEX store (its set, floor and ceiling) is the real one, as in KexFloorWaitsForAPutOffOfferTest.
        val kexStore = realPreferences().first
        f.extra = { method, a ->
            when {
                method.name in KEX_STORE_CALLS ->
                    try {
                        method.invoke(kexStore, *a)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                else -> root.answer(method.name, a)
            }
        }
        val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(peerSmaller)
        f.open()
        val ownLabel = "$tag-own"
        f.ownKex(ownLabel, to = peerSmaller, convId = own, minedAt = NEW + 5)
        val theirs = E2EEncryption.generateKeyPair()
        val answer =
            ZMSGProtocol.createV4KEXAckMessage(own, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey))
        f.received("$tag-ack", answer, minedAt = ANSWER_AT)
        return SmallerChat(f, root, kexStore, own, ownLabel, f.txid("$tag-ack"), theirs, peerSmaller)
    }

    private fun labels(
        set: Set<String>,
        c: SmallerChat,
    ): List<String> = set.map { if (it == c.ackTxId) "first answer" else "other" }.sorted()

    /** The KEX/KEXACK txid sets and scalars the pass writes (the ratchet root material), by address. */
    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        fun ack(address: String): Set<String> = acks[address].orEmpty()

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

    private fun name(
        address: String?,
        filed: String,
    ): String =
        when (address) {
            null -> "none"
            filed -> "filed"
            peer -> "peer's full address"
            else -> "other (${address.length} chars)"
        }

    private companion object {
        /** The answer's mined height. */
        const val ANSWER_AT = NEW + 7

        /** More processed answers than the processed-KEX store keeps (500), so its floor may rise. */
        const val FILLERS = 501

        val KEX_STORE_CALLS = setOf("hasProcessedKexTx", "markKexTxProcessed", "limitKexFloor")

        const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        /** The Sapling address (Bech32, HRP "zs") of [address]'s Sapling receiver. */
        fun saplingAddress(address: String): String {
            val hex = ZcashReceivers.of(address)!!.single { it.typecode == 2 }.hex
            val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            val data = toFiveBit(bytes)
            val values = hrpExpand("zs") + data + IntArray(6)
            val mod = polymod(values) xor 1
            val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
            return "zs1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
        }

        private fun toFiveBit(bytes: ByteArray): IntArray {
            val out = mutableListOf<Int>()
            var acc = 0
            var bits = 0
            for (b in bytes) {
                acc = (acc shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    out += (acc ushr bits) and 31
                }
                acc = acc and 0xff
            }
            if (bits > 0) out += (acc shl (5 - bits)) and 31
            return out.toIntArray()
        }

        private fun hrpExpand(hrp: String): IntArray = (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

        private fun polymod(values: IntArray): Int {
            val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
            var chk = 1
            for (v in values) {
                val top = chk ushr 25
                chk = ((chk and 0x1ffffff) shl 5) xor v
                for (i in 0 until 5) {
                    if ((top ushr i) and 1 == 1) chk = chk xor generator[i]
                }
            }
            return chk
        }
    }
}
