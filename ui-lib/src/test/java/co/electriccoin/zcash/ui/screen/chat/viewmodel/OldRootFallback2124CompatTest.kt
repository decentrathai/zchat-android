package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.NetworkUpgradeHeights
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.OldRootFallbackApi
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1792 (owner W-142, P-A; compat-2124; PA_DESIGN.md section 3) — the receive-side fallback changes nothing for a chat a
 * 2.12.4 phone is in, and recovers the one 2.12.4 case that has the same hole.
 *
 * The 2.12.4 phone is [Frozen2124Sealer], copied from v2.12.4 (a1fb4136f): its root derivation and its ratchet primitives,
 * written out here and not taken from HEAD, so a drift in HEAD's `deriveRatchetRoot`, `canonicalTxidMaterial`, nonce, AAD or
 * wire format fails here (the vectors made outside this code base are in RatchetStateFrom2124DecryptsOnceTest).
 *
 * 1. a single-exchange chat (one KEX, one KEXACK, as 2.12.4 stores them): the root HEAD derives is byte-equal to 2.12.4's, a
 *    HEAD sender's wire is byte-equal to 2.12.4's, and every row a 2.12.4 sender sealed opens by the FIRST path in the real
 *    pass: zero fallback tries, and nothing written outside the current root's own floors;
 * 2. state a 2.12.4 build wrote (the legacy scalars only, empty sets): the candidates fold to the bytes 2.12.4 folded, and a
 *    2.12.4 responder whose automatic answer failed, so it sealed before its answer was paid, is recovered like the Seeker;
 * 3. nothing is added to the wire: the fallback reads, it sends nothing and writes no format (E2ERatchet.kt, the memo and the
 *    envelope stay as they are: the frozen bytes above are the proof for the first two; no preference key is read here that
 *    2.12.4 did not know besides the stored text under its new row-bound key).
 *
 * Seam the fix must create, reached by name: `crypto/ratchet/OldRootFallback.kt` (OldRootFallbackApi). Every key, id and
 * txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldRootFallback2124CompatTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val seeker = OldRootPhone.contact(seed = 17923)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val seekerKeys = E2EEncryption.generateKeyPair()

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
        OldRootFallbackApi.resetProcessState()
    }

    // ---- the derivation and the wire: byte for byte -------------------------------------------------------------------

    @Test
    fun `a single-exchange chat derives the 2-12-4 root, from the sets and from the scalars alone`() {
        val ecdh = E2EEncryption.deriveSharedSecret(honorKeys.privateKey, seekerKeys.publicKey)
        val frozen = Frozen2124Sealer.root(ecdh, listOf(KH_ID), listOf(KA_ID))

        val fromSets =
            E2ERatchet.deriveRatchetRoot(
                ecdh,
                null,
                E2ERatchet.canonicalTxidMaterial(setOf(KH_ID), null),
                E2ERatchet.canonicalTxidMaterial(setOf(KA_ID), null),
            )
        val fromScalars =
            E2ERatchet.deriveRatchetRoot(
                ecdh,
                null,
                E2ERatchet.canonicalTxidMaterial(emptySet(), KH_ID),
                E2ERatchet.canonicalTxidMaterial(emptySet(), KA_ID),
            )
        val fromBoth =
            E2ERatchet.deriveRatchetRoot(
                ecdh,
                null,
                E2ERatchet.canonicalTxidMaterial(setOf(KH_ID), KH_ID),
                E2ERatchet.canonicalTxidMaterial(setOf(KA_ID), KA_ID),
            )

        assertArrayEquals("sets", frozen, fromSets)
        assertArrayEquals("scalars only (a 2.12.4 build's state)", frozen, fromScalars)
        assertArrayEquals("set and scalar of one txid", frozen, fromBoth)
        // And the answer-less root (2.12.4 with its automatic answer unpaid): the material is the KEX txid alone.
        val frozenNoAck = Frozen2124Sealer.root(ecdh, listOf(KH_ID), emptyList())
        assertArrayEquals(frozenNoAck, E2ERatchet.deriveRatchetRoot(ecdh, null, KH_ID.toByteArray(), ByteArray(0)))
    }

    @Test
    fun `a HEAD sender's wire is byte-equal to the 2-12-4 sender's, so a 2-12-4 peer reads it`() =
        runBlocking {
            val ecdh = E2EEncryption.deriveSharedSecret(honorKeys.privateKey, seekerKeys.publicKey)
            val root = Frozen2124Sealer.root(ecdh, listOf(KH_ID), listOf(KA_ID))
            val honorIsLower = honorKeys.publicKey < seekerKeys.publicKey
            val head = E2ERatchet(root, CONV, honorIsLower, InMemoryRatchetStateStore())
            for (counter in 0L..2L) {
                val ct = head.encrypt("hello $counter".toByteArray())
                val wire = CiphertextWireFormat.serialize(ct)
                assertEquals("counter $counter", Frozen2124Sealer.seal(root, CONV, honorIsLower, "hello $counter", counter), wire)
            }
        }

    // ---- the real pass, first path ------------------------------------------------------------------------------------

    @Test
    fun `every row a 2-12-4 sender sealed in a single-exchange chat opens by the first path with zero fallback tries`() =
        runBlocking {
            val phone = initiator()
            val ecdh = E2EEncryption.deriveSharedSecret(honorKeys.privateKey, seekerKeys.publicKey)
            val root = Frozen2124Sealer.root(ecdh, listOf(phone.txid(KH)), listOf(phone.txid(KA)))
            val seekerIsLower = seekerKeys.publicKey < honorKeys.publicKey
            val honorIsLower = !seekerIsLower
            // Three from the contact, two of ours, all after the answer.
            for (i in 0..2) {
                val wire = Frozen2124Sealer.seal(root, CONV, seekerIsLower, "from 2.12.4 #$i", counter = i.toLong())
                phone.f.received("in-$i", phone.inboundMemo(CONV, wire), minedAt = NEW + 30 + i)
            }
            for (i in 0..1) {
                val wire = Frozen2124Sealer.seal(root, CONV, honorIsLower, "ours #$i", counter = i.toLong())
                phone.f.sent("out-$i", phone.ownMemo(CONV, wire), to = seeker, minedAt = NEW + 40 + i)
            }

            val rows = phone.passes(3)

            for (i in 0..2) assertEquals("from 2.12.4 #$i", phone.textOf(rows, "in-$i"))
            for (i in 0..1) assertEquals("ours #$i", phone.textOf(rows, "out-$i"))
            assertFalse("no banner", phone.hasDecryptFailure())
            assertEquals("not one fallback try in a chat the current root reads", 0L, OldRootFallbackApi.attemptCount())
            val currentFloorKey = E2ERatchet.floorKeyFor(phone.root(setOf(KH), setOf(KA)))
            assertTrue(
                "every floor is the current root's or the conversation id's mirror of it: ${phone.floors.keys.map { it.take(14) }}",
                phone.floors.keys.all { it.startsWith("$currentFloorKey:") || it.startsWith("$CONV:") },
            )
        }

    // ---- what a 2.12.4 build left behind ------------------------------------------------------------------------------

    @Test
    fun `state holding scalars only gives the candidate bytes 2-12-4 folded`() {
        // A 2.12.4 build stored only the legacy scalars (the sets are empty): its root material was the txid itself.
        val got =
            OldRootFallbackApi.candidates(
                kexTxids = emptySet(),
                kexScalar = KH_ID,
                kexAckTxids = emptySet(),
                kexAckScalar = KA_ID,
                minedHeights = mapOf(KH_ID to 100L, KA_ID to 200L),
                rowHeight = 150L,
                upgrades = NetworkUpgradeHeights(nu7 = null),
            )
        assertEquals(1, got.size)
        assertArrayEquals("the KEX scalar's bytes, as 2.12.4 folded them", KH_ID.toByteArray(), got.single().first)
        assertArrayEquals("an empty KEXACK material, as 2.12.4 folded it", ByteArray(0), got.single().second)
        val ecdh = E2EEncryption.deriveSharedSecret(honorKeys.privateKey, seekerKeys.publicKey)
        assertArrayEquals(
            "so the root is the one a 2.12.4 responder derived before its answer was paid",
            Frozen2124Sealer.root(ecdh, listOf(KH_ID), emptyList()),
            E2ERatchet.deriveRatchetRoot(ecdh, null, got.single().first, got.single().second),
        )
    }

    @Test
    fun `a 2-12-4 responder whose automatic answer failed is recovered, from a build that stored scalars only`() =
        runBlocking {
            val phone = initiator()
            phone.stored(kex = emptySet(), kexScalar = KH, ack = emptySet(), ackScalar = KA)
            val ecdh = E2EEncryption.deriveSharedSecret(honorKeys.privateKey, seekerKeys.publicKey)
            val sealedBeforeAnswer = Frozen2124Sealer.root(ecdh, listOf(phone.txid(KH)), emptyList())
            val seekerIsLower = seekerKeys.publicKey < honorKeys.publicKey
            val old = Frozen2124Sealer.seal(sealedBeforeAnswer, CONV, seekerIsLower, "sent before the answer")
            phone.f.received("m-old", phone.inboundMemo(CONV, old), minedAt = NEW + 8)
            // And one it sealed after the answer mined: the current root opens it, with no help.
            val afterAnswer = Frozen2124Sealer.root(ecdh, listOf(phone.txid(KH)), listOf(phone.txid(KA)))
            phone.f.received(
                "m-new",
                phone.inboundMemo(CONV, Frozen2124Sealer.seal(afterAnswer, CONV, seekerIsLower, "sent after the answer")),
                minedAt = NEW + 30,
            )

            val rows = phone.passes(3)

            assertEquals("sent after the answer: the first path", "sent after the answer", phone.textOf(rows, "m-new"))
            assertEquals("sent before the answer: the fallback", "sent before the answer", phone.textOf(rows, "m-old"))
            assertFalse(phone.hasDecryptFailure())
            val attempts = OldRootFallbackApi.attemptCount()
            assertTrue("the fallback tried (attempts = $attempts) and only for the old row", attempts in 1..9)
        }

    // ---- support ------------------------------------------------------------------------------------------------------

    /** The HONOR after reading both exchanges: KH (own, mined NEW + 5) and KA (received, mined NEW + 20). */
    private suspend fun initiator(): OldRootPhone {
        val phone = OldRootPhone(scheduler, seeker, honorKeys, seekerKeys, ownConvId = CONV, filedConvIds = emptyList())
        phone.open()
        phone.stored(kex = setOf(KH), kexScalar = KH, ack = setOf(KA), ackScalar = KA)
        phone.f.ownKex(KH, to = seeker, convId = CONV, minedAt = NEW + 5)
        val payload = E2EEncryption.createKEXAckPayload(seeker, seekerKeys.publicKey, seekerKeys.privateKey)
        phone.f.received(KA, ZMSGProtocol.createV4KEXAckMessage(CONV, seeker, payload), minedAt = NEW + 20)
        return phone
    }

    private fun freshProcess() {
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        OldRootFallbackApi.resetProcessState()
    }

    private companion object {
        const val CONV = "ZC1792CA"
        const val KH = "zc1792c-honor-kex"
        const val KA = "zc1792c-seeker-ack"

        /** Literal txids for the pure checks (any string is a txid to the derivation). */
        const val KH_ID = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
        const val KA_ID = "f9e8d7c6b5a4938271605f4e3d2c1b0af9e8d7c6b5a4938271605f4e3d2c1b0a"
    }
}
