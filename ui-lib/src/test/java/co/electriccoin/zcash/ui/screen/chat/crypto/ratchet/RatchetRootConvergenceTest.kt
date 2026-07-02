package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B1/B2 convergence proof: the ratchet root must be IDENTICAL on both devices of a conversation.
 *
 * The fix derives the root's KEX/KEXACK material from a SORTED SET of mined txids (see
 * ChatViewModel.getOrCreateMessageProcessor). Both devices observe the same mined KEX/KEXACK
 * transactions (own via the SendTransaction scan, the peer's via the ReceiveTransaction scan), so as
 * long as the *set* is equal the derived bytes — and therefore the root — are equal regardless of the
 * order each device happened to add them in. These tests pin that invariant, plus backward-compat with
 * the pre-fix single-scalar derivation, without needing two physical devices.
 */
class RatchetRootConvergenceTest {

    // Exercise the PRODUCTION derivation helper (not a mirror) so the test can't drift from the code.
    private fun canonical(txids: Set<String>, legacyScalar: String? = null): ByteArray =
        E2ERatchet.canonicalTxidMaterial(txids, legacyScalar)

    private val ecdh = ByteArray(32) { 7 }
    private val psk: ByteArray? = null

    private fun root(kex: ByteArray, ack: ByteArray): ByteArray =
        E2ERatchet.deriveRatchetRoot(ecdhSharedSecret = ecdh, psk = psk, kexTxid = kex, kexAckTxid = ack)

    @Test
    fun sameTxidSet_addedInEitherOrder_yieldsIdenticalRoot() {
        // Device A observed its own KEX first, then the peer's; device B observed them the other way.
        val deviceA = root(canonical(setOf("kexAAA", "kexBBB")), canonical(setOf("ackCCC")))
        val deviceB = root(canonical(setOf("kexBBB", "kexAAA")), canonical(setOf("ackCCC")))
        assertContentEquals(deviceA, deviceB, "Same txid set in different insertion order must derive the same root")
    }

    @Test
    fun singleScalarMatchesSingletonSet_backwardCompatible() {
        // A pre-fix healthy chat had only the legacy scalar (empty set). Its canonical bytes must equal
        // the old `getE2EKexTxId().toByteArray()` so existing conversations keep decrypting after update.
        val legacyBytes = "kexAAA".toByteArray(Charsets.UTF_8)
        val newBytes = canonical(emptySet(), legacyScalar = "kexAAA")
        assertContentEquals(legacyBytes, newBytes, "Single scalar must produce byte-identical material (no migration break)")

        // Empty (KEX predating txid storage) → empty bytes, same as the old `?: ByteArray(0)` fallback.
        assertContentEquals(ByteArray(0), canonical(emptySet(), legacyScalar = null))
    }

    @Test
    fun divergentSets_produceDifferentRoots() {
        // The pre-fix bug: A kept only its own KEX txid, B kept only its own → different roots → AEADBadTag.
        val onlyA = root(canonical(setOf("kexAAA")), canonical(setOf("ackCCC")))
        val onlyB = root(canonical(setOf("kexBBB")), canonical(setOf("ackCCC")))
        assertFalse(onlyA.contentEquals(onlyB), "Different KEX sets must derive different roots (sanity)")

        // ...and once BOTH converge on the full set, the roots match — this is the heal.
        val healedA = root(canonical(setOf("kexAAA", "kexBBB")), canonical(setOf("ackCCC")))
        val healedB = root(canonical(setOf("kexBBB", "kexAAA")), canonical(setOf("ackCCC")))
        assertContentEquals(healedA, healedB)
        assertFalse(onlyA.contentEquals(healedA), "Adding the peer's txid changes the root (cache must be invalidated)")
    }

    @Test
    fun deriveRatchetRoot_isDeterministic() {
        val a = root(canonical(setOf("x")), canonical(setOf("y")))
        val b = root(canonical(setOf("x")), canonical(setOf("y")))
        assertContentEquals(a, b)
        assertTrue(a.size == 32, "root is a 32-byte key")
    }
}
