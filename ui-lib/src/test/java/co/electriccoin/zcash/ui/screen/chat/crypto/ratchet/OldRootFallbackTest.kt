package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.common.sendattempt.NetworkUpgradeHeights
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1792 (owner W-142, P-A; PA_DESIGN.md sections 1 and 2): a message the responder sealed BEFORE it paid its answer
 * (root = HKDF over the KEX txid and an EMPTY KEXACK set) is unreadable on the initiator once the answer is mined, because
 * the root then also holds the answer's txid and nothing ever tries the earlier root. P-A: on an AEAD failure, retry with
 * the root as it stood at the row's height. This file pins the pure half, reached through the seam named in
 * [OldRootFallbackApi] (`crypto/ratchet/OldRootFallback.kt`, which does not exist yet):
 *
 * - which candidate roots are tried and in which order (N = 3: C1, C2, C3), including the 40-block expiry window (40 before
 *   NU7, 120 from NU7 on: the design's "40 to confirm" is ChainTiming.maxExpiryDelta of the row's height);
 * - the throwaway opener: a wrong root, wrong conversation id (AAD), wrong direction or a forged counter is null, never an
 *   exception, and nothing outlives the call.
 *
 * Receive-only (W-142): E2ERatchet.kt, the root derivation and every format stay as they are; the candidates are folded with
 * `E2ERatchet.canonicalTxidMaterial` and `deriveRatchetRoot` themselves. Every key and txid is synthetic.
 */
class OldRootFallbackTest {
    private val preNu7 = NetworkUpgradeHeights(nu7 = null)
    private val withNu7 = NetworkUpgradeHeights(nu7 = NU7)

    @Before
    fun setUp() {
        OldRootFallbackApi.resetProcessState()
    }

    // ---- the expiry window -------------------------------------------------------------------------------------------

    @Test
    fun `the window is the expiry delta of the row's height - 40 before NU7 and 120 from NU7 on`() {
        assertEquals(40L, OldRootFallbackApi.expiryWindow(100L, preNu7))
        assertEquals(40L, OldRootFallbackApi.expiryWindow(NU7 - 1, withNu7))
        assertEquals(120L, OldRootFallbackApi.expiryWindow(NU7, withNu7))
        assertEquals(
            "the same number the app's own expiry arithmetic uses (ChainTiming), not a second constant",
            ChainTiming.maxExpiryDelta(NU7 + 500, withNu7),
            OldRootFallbackApi.expiryWindow(NU7 + 500, withNu7),
        )
    }

    @Test
    fun `the budgets are the design's - 3 attempts per row and 64 failed attempts per peer per pass`() {
        assertEquals(3, OldRootFallbackApi.maxAttemptsPerRow())
        assertEquals(64, OldRootFallbackApi.maxFailedAttemptsPerPeerPerPass())
    }

    // ---- candidate order ---------------------------------------------------------------------------------------------

    @Test
    fun `the 12 Nov shape - the answer mined after the row gives the root with an empty KEXACK set`() {
        // The responder sealed with {K1} and an empty KEXACK set; the answer A1 mined later. The initiator's current root
        // holds both.
        val got =
            candidates(
                kex = setOf("k1"),
                kexScalar = "k1",
                ack = setOf("a1"),
                ackScalar = "a1",
                heights = mapOf("k1" to 100L, "a1" to 200L),
                row = 150L,
            )
        assertEquals(listOf(Cand("k1", "")), got)
    }

    @Test
    fun `an exchange mined above the row is in no candidate`() {
        val got =
            candidates(
                kex = setOf("k1", "k2"),
                kexScalar = null,
                ack = setOf("a1", "a2"),
                ackScalar = null,
                heights = mapOf("k1" to 100L, "k2" to 300L, "a1" to 120L, "a2" to 400L),
                row = 150L,
            )
        assertTrue("candidates $got", got.isNotEmpty())
        assertTrue("a candidate holds k2 or a2, mined above the row: $got", got.none { "k2" in it.kex || "a2" in it.ack })
        assertEquals("C1 is everything at or below the row", Cand("k1", "a1"), got.first())
    }

    @Test
    fun `an exchange mined in the row's own block counts as at or below it`() {
        val got =
            candidates(
                kex = setOf("k1"),
                kexScalar = null,
                ack = setOf("a1"),
                ackScalar = null,
                heights = mapOf("k1" to 100L, "a1" to 150L),
                row = 150L,
            )
        // C1 = {k1} / {a1} is the current root and is left out; C2 drops a1 (150 is within the window of 150).
        assertEquals(listOf(Cand("k1", "")), got)
    }

    @Test
    fun `when C1 is the current root and nothing is inside the window there is nothing to try`() {
        val got =
            candidates(
                kex = setOf("k1"),
                kexScalar = "k1",
                ack = setOf("a1"),
                ackScalar = "a1",
                heights = mapOf("k1" to 100L, "a1" to 120L),
                row = 5_000L,
            )
        assertEquals(emptyList<Cand>(), got)
    }

    @Test
    fun `C2 drops the newest exchange when it was mined within the window below the row`() {
        val got =
            candidates(
                kex = setOf("k1"),
                kexScalar = null,
                ack = setOf("a1"),
                ackScalar = null,
                heights = mapOf("k1" to 100L, "a1" to 130L),
                row = 150L,
            )
        // C1 = {k1}/{a1} is the current root; C2 = {k1}/{}; C3 would drop k1 too (50 below the row, outside 40).
        assertEquals(listOf(Cand("k1", "")), got)
    }

    @Test
    fun `C3 drops the two newest only when both were mined within the window`() {
        val heights = mapOf("k1" to 100L, "k2" to 140L, "a1" to 145L)
        val inWindow =
            candidates(
                kex = setOf("k1", "k2"),
                kexScalar = null,
                ack = setOf("a1"),
                ackScalar = null,
                heights = heights,
                row = 150L,
            )
        assertEquals("C2 without a1, C3 without a1 and k2", listOf(Cand("k1|k2", ""), Cand("k1", "")), inWindow)

        val oneOutside =
            candidates(
                kex = setOf("k1", "k2"),
                kexScalar = null,
                ack = setOf("a1"),
                ackScalar = null,
                heights = heights,
                row = 181L,
            )
        assertEquals("k2 at 140 is 41 below 181: only C2 (a1 at 145 is 36 below)", listOf(Cand("k1|k2", "")), oneOutside)
    }

    @Test
    fun `at most three candidates, in the order C1 C2 C3, none equal to the current root`() {
        val got =
            candidates(
                kex = setOf("k1", "k2"),
                kexScalar = null,
                ack = setOf("a1", "a2"),
                ackScalar = null,
                heights = mapOf("k1" to 10L, "k2" to 140L, "a1" to 145L, "a2" to 300L),
                row = 148L,
            )
        assertEquals(listOf(Cand("k1|k2", "a1"), Cand("k1|k2", ""), Cand("k1", "")), got)
        assertTrue("never more than N = 3", got.size <= 3)
        assertTrue("the current root holds a2, no candidate does", got.none { "a2" in it.ack })
    }

    @Test
    fun `newest is by height and then by txid, and an exchange is one txid whichever kind`() {
        // Two exchanges at one height: the higher txid is the newer one (a KEX k9 and a KEXACK a1 in the same block).
        val got =
            candidates(
                kex = setOf("k1", "k9"),
                kexScalar = null,
                ack = setOf("a1"),
                ackScalar = null,
                heights = mapOf("k1" to 140L, "k9" to 149L, "a1" to 149L),
                row = 150L,
            )
        // current = k1|k9 / a1 and is skipped. Newest = k9 (149, txid k9 > a1): C2 = k1 / a1. Then a1 (149): C3 = k1 / {}.
        assertEquals(listOf(Cand("k1", "a1"), Cand("k1", "")), got)
    }

    // ---- the window's edge -------------------------------------------------------------------------------------------

    @Test
    fun `before NU7 an exchange 40 blocks below the row is inside the window and 41 is outside`() {
        val at40 = candidates(setOf("k1"), null, setOf("a1"), null, mapOf("k1" to 10L, "a1" to 110L), row = 150L, upgrades = preNu7)
        assertEquals("a1 is 40 below 150", listOf(Cand("k1", "")), at40)
        val at41 = candidates(setOf("k1"), null, setOf("a1"), null, mapOf("k1" to 10L, "a1" to 109L), row = 150L, upgrades = preNu7)
        assertEquals("a1 is 41 below 150", emptyList<Cand>(), at41)
    }

    @Test
    fun `from NU7 on the window is 120 - 120 blocks below is inside and 121 is outside`() {
        val row = NU7 + 500
        val at120 =
            candidates(
                setOf("k1"),
                null,
                setOf("a1"),
                null,
                mapOf("k1" to NU7 - 10, "a1" to row - 120),
                row = row,
                upgrades = withNu7,
            )
        assertEquals(listOf(Cand("k1", "")), at120)
        val at121 =
            candidates(
                setOf("k1"),
                null,
                setOf("a1"),
                null,
                mapOf("k1" to NU7 - 10, "a1" to row - 121),
                row = row,
                upgrades = withNu7,
            )
        assertEquals(emptyList<Cand>(), at121)
    }

    @Test
    fun `the window is read at the row's height - a row just below NU7 keeps 40`() {
        val row = NU7 - 1
        val at41 =
            candidates(
                setOf("k1"),
                null,
                setOf("a1"),
                null,
                mapOf("k1" to NU7 - 200, "a1" to row - 41),
                row = row,
                upgrades = withNu7,
            )
        assertEquals(emptyList<Cand>(), at41)
        val at40 =
            candidates(
                setOf("k1"),
                null,
                setOf("a1"),
                null,
                mapOf("k1" to NU7 - 200, "a1" to row - 40),
                row = row,
                upgrades = withNu7,
            )
        assertEquals(listOf(Cand("k1", "")), at40)
    }

    // ---- scalars and unknown heights ---------------------------------------------------------------------------------

    @Test
    fun `a legacy scalar counts only when its height is known and at or below the row`() {
        // 2.12.4 state: scalars only, empty sets. The scalar k1 is known and below the row, the scalar a1 is above it.
        val aboveRow = candidates(emptySet(), "k1", emptySet(), "a1", mapOf("k1" to 100L, "a1" to 200L), row = 150L)
        assertEquals(listOf(Cand("k1", "")), aboveRow)
        // ZC-1831 (QA2 pa3, [test-change-ok: ZC-1831]): a scalar with no known height that the wallet does not list fails
        // closed: nothing is tried (no expiry-window check can be made for it).
        val unknown = candidates(emptySet(), "k1", emptySet(), "a1", mapOf("k1" to 100L), row = 150L)
        assertEquals(emptyList<Cand>(), unknown)
        // ZC-1865 (QA2 pa4): a scalar with no height that the wallet lists as pending (an own exchange that is not mined: it
        // has no height at all) is in no candidate, as it was before ZC-1831: it will be mined above the row.
        val pending =
            OldRootFallbackApi
                .candidates(emptySet(), "k1", emptySet(), "a1", mapOf("k1" to 100L), 150L, preNu7, pending = setOf("a1"))
                .map { Cand(String(it.first), String(it.second)) }
        assertEquals(listOf(Cand("k1", "")), pending)
        val bothUnknown = candidates(emptySet(), "k1", emptySet(), "a1", emptyMap(), row = 150L)
        assertTrue("nothing known, nothing to try: $bothUnknown", bothUnknown.none { it.kex.isNotEmpty() || it.ack.isNotEmpty() })
    }

    /**
     * ZC-1831 (QA2 pa3, GPT crypto review of P-A2 finding 2, [test-change-ok: ZC-1831]): every mined transaction's height is
     * known before the loop (ZC-1824), so a stored txid with no height is not mined or not in this wallet's history. Leaving
     * it out rebuilt the earlier root without it and skipped the 40/120-block expiry check that bounds which exchanges may
     * be left out. It fails closed: nothing is tried for the row.
     */
    @Test
    fun `ZC-1831 - a stored txid whose height this pass does not know leaves nothing to try`() {
        assertEquals(emptyList<Cand>(), candidates(setOf("k1", "k2"), null, setOf("a1"), null, mapOf("k1" to 100L), row = 150L))
        assertEquals(
            "a KEXACK with no height too",
            emptyList<Cand>(),
            candidates(setOf("k1"), null, setOf("a1", "a2"), null, mapOf("k1" to 100L, "a1" to 120L), row = 150L),
        )
        assertEquals(
            "a txid far below the row with no height is not dropped silently either",
            emptyList<Cand>(),
            candidates(setOf("k0", "k1"), null, setOf("a1"), null, mapOf("k1" to 100L, "a1" to 200L), row = 150L),
        )
        // Control: every height known, the exchange above the row is simply in no candidate.
        assertEquals(
            listOf(Cand("k1", "")),
            candidates(setOf("k1"), null, setOf("a1"), null, mapOf("k1" to 100L, "a1" to 200L), row = 150L),
        )
    }

    @Test
    fun `a scalar that is also in the set is one exchange, not two`() {
        val withScalar = candidates(setOf("k1"), "k1", setOf("a1"), "a1", mapOf("k1" to 100L, "a1" to 200L), row = 150L)
        val withoutScalar = candidates(setOf("k1"), null, setOf("a1"), null, mapOf("k1" to 100L, "a1" to 200L), row = 150L)
        assertEquals(withoutScalar, withScalar)
        assertEquals(listOf(Cand("k1", "")), withScalar)
    }

    @Test
    fun `the candidate bytes are exactly what canonicalTxidMaterial gives, so the root is the one the sender derived`() {
        // k2 is mined above the row; the scalar k3 is below it and is folded in like the app folds it.
        val got =
            OldRootFallbackApi.candidates(
                setOf("k2", "k1"),
                "k3",
                emptySet(),
                null,
                mapOf("k3" to 5L, "k1" to 10L, "k2" to 200L),
                rowHeight = 100L,
                upgrades = preNu7,
            )
        assertEquals(1, got.size)
        assertArrayEquals(E2ERatchet.canonicalTxidMaterial(setOf("k1"), "k3"), got.single().first)
        assertArrayEquals(E2ERatchet.canonicalTxidMaterial(emptySet(), null), got.single().second)
    }

    // ---- the excluded case (ZC-1794) ---------------------------------------------------------------------------------

    @Test
    fun `ZC-1794 - a message sealed while the sender's own answer was paid but not mined stays out of reach`() {
        // The sender's root held its own answer a1 as a pending scalar, a1 mined AFTER the row. The wording the owner
        // approved (the root "as it stood at the row's height") has no exchange mined after the row, so the sender's root
        // (k1 / a1) is not a candidate. Recorded as a residual (ZC-1794); widening it is a new decision.
        val got = candidates(setOf("k1"), "k1", setOf("a1", "a2"), "a1", mapOf("k1" to 100L, "a1" to 200L, "a2" to 300L), row = 150L)
        assertTrue("k1 / a1 must not be a candidate: $got", got.none { it == Cand("k1", "a1") })
        assertEquals(listOf(Cand("k1", "")), got)
    }

    // ---- the opener ---------------------------------------------------------------------------------------------------

    @Test
    fun `the opener reads an envelope sealed under the candidate root, in both directions`() =
        runBlocking {
            val setup = Setup()
            for (fromContact in listOf(true, false)) {
                val sealer = setup.sealer(senderIsLower = fromContact != setup.weAreLower, root = setup.root("k1", ""))
                val envelope = sealer.encrypt("old root text".toByteArray())
                val opened = setup.open(envelope, "k1", "", fromContact = fromContact)
                assertNotNull("fromContact=$fromContact", opened)
                assertEquals("old root text", String(opened!!))
            }
        }

    @Test
    fun `the opener reads a high counter - its window is seeded with the envelope's counter`() =
        runBlocking {
            val setup = Setup()
            val envelope = sealAt(setup, setup.root("k1", ""), senderIsLower = !setup.weAreLower, counter = 5_000L, text = "high")
            assertEquals(5_000L, envelope.counter)
            assertEquals(
                "the current root's window check already ran before the fallback",
                "high",
                String(setup.open(envelope, "k1", "", true)!!),
            )
        }

    @Test
    fun `the opener reads the same envelope twice - nothing is kept between calls`() =
        runBlocking {
            val setup = Setup()
            val envelope = setup.sealer(!setup.weAreLower, setup.root("k1", "")).encrypt("again".toByteArray())
            assertEquals("again", String(setup.open(envelope, "k1", "", true)!!))
            assertEquals(
                "a throwaway ratchet has no seen-set to refuse a second read",
                "again",
                String(setup.open(envelope, "k1", "", true)!!),
            )
        }

    @Test
    fun `a wrong root, wrong conversation id or wrong direction is null, never an exception`() =
        runBlocking {
            val setup = Setup()
            val envelope = setup.sealer(!setup.weAreLower, setup.root("k1", "")).encrypt("x".toByteArray())
            assertNull("wrong KEX material", setup.open(envelope, "k2", "", true))
            assertNull("wrong KEXACK material", setup.open(envelope, "k1", "a1", true))
            assertNull("wrong conversation id (AAD)", setup.open(envelope, "k1", "", true, convId = "OTHERCNV"))
            assertNull("a contact's envelope on an own row", setup.open(envelope, "k1", "", fromContact = false))
            val own = setup.sealer(setup.weAreLower, setup.root("k1", "")).encrypt("y".toByteArray())
            assertNull("our own envelope on a received row", setup.open(own, "k1", "", fromContact = true))
            val other = Setup()
            val foreign = other.sealer(!other.weAreLower, other.root("k1", "")).encrypt("z".toByteArray())
            assertNull("another key pair's envelope", setup.open(foreign, "k1", "", true))
            assertNull("bytes that are no ciphertext", setup.open(Ciphertext(envelope.direction, 3L, ByteArray(40)), "k1", "", true))
        }

    @Test
    fun `a forged counter at the send ceiling is null at once, with no chain walk`() =
        runBlocking {
            val setup = Setup()
            val sealer = setup.sealer(!setup.weAreLower, setup.root("k1", ""))
            val real = sealer.encrypt("x".toByteArray())
            val started = System.nanoTime()
            for (counter in listOf(1_000_000L, 5_000_000L, Long.MAX_VALUE)) {
                assertNull("counter $counter", setup.open(Ciphertext(real.direction, counter, real.bytes), "k1", "", true))
                assertNull(
                    "own direction, counter $counter",
                    setup.open(Ciphertext(setup.myDirection, counter, real.bytes), "k1", "", false),
                )
            }
            assertTrue("a refused counter must not walk the chain", (System.nanoTime() - started) < 2_000_000_000L)
        }

    @Test
    fun `the opener never touches a ratchet store the caller holds`() =
        runBlocking {
            // The current root's store, with state under the conversation id and a floor under its root key. The opener is
            // given no store; a build that reached for one (or for the conversation id's mirrored floor) shows here.
            val setup = Setup()
            val floors = mutableMapOf<String, Long>()
            val real = InMemoryRatchetStateStore(floors)
            val current = setup.root("k1", "a1")
            val live = E2ERatchet(current, setup.convId, setup.weAreLower, real)
            val fromPeer = E2ERatchet(current, setup.convId, !setup.weAreLower, InMemoryRatchetStateStore())
            assertEquals("seed", String(live.decrypt(fromPeer.encrypt("seed".toByteArray()))))
            val before = floors.toMap() to real.load(setup.convId)

            val oldSealer = setup.sealer(!setup.weAreLower, setup.root("k1", ""))
            val oldEnvelope = oldSealer.encrypt("old".toByteArray())
            assertEquals("old", String(setup.open(oldEnvelope, "k1", "", true)!!))
            assertNull(setup.open(oldEnvelope, "k2", "", true))
            val ownOld = setup.sealer(setup.weAreLower, setup.root("k1", "")).encrypt("own old".toByteArray())
            assertEquals("own old", String(setup.open(ownOld, "k1", "", false)!!))

            assertEquals("floors and state of the current root are byte-equal after", before, floors.toMap() to real.load(setup.convId))
            assertEquals(
                "the old root has no floor of its own in the live store",
                0L,
                real.loadCounterFloor(E2ERatchet.floorKeyFor(setup.root("k1", "")), setup.peerDirection),
            )
        }

    // ---- support -----------------------------------------------------------------------------------------------------

    private fun candidates(
        kex: Set<String>,
        kexScalar: String?,
        ack: Set<String>,
        ackScalar: String?,
        heights: Map<String, Long>,
        row: Long,
        upgrades: NetworkUpgradeHeights = preNu7,
    ): List<Cand> =
        OldRootFallbackApi
            .candidates(kex, kexScalar, ack, ackScalar, heights, row, upgrades)
            .map { Cand(String(it.first), String(it.second)) }

    /** A candidate as the material strings the root is derived from ("k1|k2" is the sorted join of two txids). */
    private data class Cand(
        val kex: String,
        val ack: String,
    )

    /** One envelope at exactly [counter]: the sender's stored send counter starts there, so there is one chain walk. */
    private suspend fun sealAt(
        setup: Setup,
        root: ByteArray,
        senderIsLower: Boolean,
        counter: Long,
        text: String,
    ): Ciphertext {
        val store = InMemoryRatchetStateStore()
        store.save(
            RatchetConversationState(
                convId = setup.convId,
                nextCounterA2B = if (senderIsLower) counter else 0L,
                nextCounterB2A = if (senderIsLower) 0L else counter,
                seenCountersA2B = emptySet(),
                seenCountersB2A = emptySet(),
            ),
        )
        return E2ERatchet(root, setup.convId, senderIsLower, store).encrypt(text.toByteArray())
    }

    /** Two phones (we and the contact) with one ECDH secret; roots are derived exactly as the app derives them. */
    private class Setup {
        private val ours = E2EEncryption.generateKeyPair()
        private val theirs = E2EEncryption.generateKeyPair()
        val ecdh: ByteArray = E2EEncryption.deriveSharedSecret(ours.privateKey, theirs.publicKey)
        val weAreLower: Boolean = ours.publicKey < theirs.publicKey
        val convId = "ZC1792PA"
        val myDirection: Byte = if (weAreLower) 0x00 else 0x01
        val peerDirection: Byte = if (weAreLower) 0x01 else 0x00

        fun root(
            kex: String,
            ack: String,
        ): ByteArray = E2ERatchet.deriveRatchetRoot(ecdh, null, kex.toByteArray(), ack.toByteArray())

        fun sealer(
            senderIsLower: Boolean,
            root: ByteArray,
        ) = E2ERatchet(root, convId, senderIsLower, InMemoryRatchetStateStore())

        suspend fun open(
            envelope: Ciphertext,
            kex: String,
            ack: String,
            fromContact: Boolean,
            convId: String = this.convId,
        ): ByteArray? =
            OldRootFallbackApi.open(ecdh, null, kex.toByteArray(), ack.toByteArray(), convId, weAreLower, fromContact, envelope)
    }

    private companion object {
        const val NU7 = 5_000L
    }
}
