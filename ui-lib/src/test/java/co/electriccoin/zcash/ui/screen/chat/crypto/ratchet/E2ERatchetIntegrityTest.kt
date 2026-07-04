package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Additional integrity properties for [E2ERatchet] beyond the core behavioural spec in
 * [E2ERatchetTest] and the convergence proof in [RatchetRootConvergenceTest]:
 *
 *  - the deterministic root BINDS to the exact KEX/KEXACK txid material (swapping the two
 *    changes the root — the on-chain handshake context is authenticated into the key), and
 *  - the send-counter CEILING forces a re-key before the AES-GCM counter-derived nonce space
 *    is exhausted (nonce reuse under a fixed key = catastrophic GCM break).
 *
 * Pure JVM: [E2ERatchet] uses only javax.crypto + java.security (no android.util.Base64), so these
 * belong in src/test, matching [E2ERatchetTest]. No connected device needed.
 */
class E2ERatchetIntegrityTest {

    // Mirrors the private constant in E2ERatchet. If the production ceiling changes, this test must be
    // updated in lockstep — that is intentional: the value is a security-relevant nonce-space bound.
    private val maxSendCounter = 1_000_000L

    private val testRootKey = ByteArray(32) { it.toByte() }
    private val testConvId = "CONV0001"

    // ---- root binds to the ORDERED (kex, kexack) roles: swapping them changes the root ----

    @Test
    fun swappingKexAndKexAckTxid_changesRoot() {
        val ecdh = ByteArray(32) { it.toByte() }
        // Two DISTINCT txid materials so the swap is observable.
        val kex = E2ERatchet.canonicalTxidMaterial(setOf("kexAAA"), legacyScalar = null)
        val ack = E2ERatchet.canonicalTxidMaterial(setOf("ackBBB"), legacyScalar = null)
        assertFalse(kex.contentEquals(ack), "precondition: kex and ack material must differ")

        val root = E2ERatchet.deriveRatchetRoot(ecdh, psk = null, kexTxid = kex, kexAckTxid = ack)
        val swapped = E2ERatchet.deriveRatchetRoot(ecdh, psk = null, kexTxid = ack, kexAckTxid = kex)

        assertFalse(
            root.contentEquals(swapped),
            "sha256(kex || ack) is order-sensitive — swapping KEX/KEXACK txid must change the root",
        )
    }

    // ---- canonicalTxidMaterial: order-independent WITHIN a set; empty+null → ByteArray(0) ----

    @Test
    fun canonicalTxidMaterial_isOrderIndependentWithinSet() {
        // A||B and B||A as insertion order into the SAME set must yield byte-identical material, because
        // the helper sorts before joining. (Complements RatchetRootConvergenceTest at the material level.)
        val ab = E2ERatchet.canonicalTxidMaterial(setOf("txA", "txB"), legacyScalar = null)
        val ba = E2ERatchet.canonicalTxidMaterial(setOf("txB", "txA"), legacyScalar = null)
        assertContentEquals(ab, ba)
    }

    @Test
    fun canonicalTxidMaterial_emptySetAndNullScalar_isEmptyBytes() {
        // Matches the old `?: ByteArray(0)` fallback so pre-txid-storage chats derive an unchanged root.
        assertContentEquals(
            ByteArray(0),
            E2ERatchet.canonicalTxidMaterial(emptySet(), legacyScalar = null),
        )
    }

    // ---- send-counter ceiling: last permitted encrypt succeeds, the next one THROWS (re-key) ----

    @Test
    fun encryptAtCeilingMinusOneSucceeds_nextEncryptThrows() = runTest {
        val store = InMemoryRatchetStateStore()
        // Pre-seed the persisted state right below the ceiling. isLower=true ⇒ our direction is A2B, so
        // the A2B counter is the one encrypt() reads/advances.
        store.save(
            RatchetConversationState(
                convId = testConvId,
                nextCounterA2B = maxSendCounter - 1, // the LAST counter encrypt() is allowed to emit
                nextCounterB2A = 0L,
                seenCountersA2B = emptySet(),
                seenCountersB2A = emptySet(),
            )
        )
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = store,
        )

        // At MAX_SEND_COUNTER - 1: require(counter < MAX) holds ⇒ succeeds, and advances to MAX.
        val ok = alice.encrypt("last message before the ceiling".toByteArray())
        assertTrue(ok.counter == maxSendCounter - 1, "final permitted counter should be MAX_SEND_COUNTER - 1")

        // At MAX_SEND_COUNTER: require(counter < MAX) fails ⇒ throws, forcing a re-KEX to reset counters.
        // Without this guard the counter-derived GCM nonce space would eventually wrap and reuse a nonce
        // under a fixed message key — a catastrophic GCM confidentiality/integrity break.
        val result = runCatching { alice.encrypt("one past the ceiling".toByteArray()) }
        assertFalse(result.isSuccess, "encrypt at MAX_SEND_COUNTER MUST throw (nonce-reuse guard)")
        assertTrue(
            result.exceptionOrNull() is IllegalArgumentException,
            "expected IllegalArgumentException from the require() guard, got ${result.exceptionOrNull()}",
        )
    }
}
