package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stage 3 of D1-recovery — R5 nonce-reuse mitigation (seed-recoverable-e2e-design.md §6 R5).
 *
 * THE HAZARD: after a seed restore the ratchet root re-derives BYTE-IDENTICAL (Stage 2,
 * [co.electriccoin.zcash.ui.screen.chat.crypto.E2ESeedRecoveryProofTest]), but the restored
 * device's ratchet state AND counter floors start EMPTY — nothing survives a device wipe.
 * Message key and nonce are deterministic functions of (root, direction, counter), so a send
 * counter restarting at 0 REUSES an AES-GCM (key, nonce) pair already burned by the pre-wipe
 * device — a catastrophic GCM break (keystream/auth-key leak across two ciphertexts).
 *
 * THE MITIGATION under test: the blockchain rescan re-decrypts our OWN on-chain messages
 * through the stateless own-outgoing path ([E2ERatchet.decrypt]); each successful own-direction
 * decrypt must FLOOR the send counter at (observed counter + 1) in the durable floor store —
 * the same D8 store [E2ERatchet.applyCounterFloors] already clamps from — so the first
 * post-restore send resumes ABOVE every counter ever burned under this root.
 *
 * This exercises the REAL wired path: rescan → E2EMessageProcessor.decryptIncoming →
 * E2ERatchet.decrypt (own direction) → floor → next encryptOutgoing.
 */
class E2ERatchetRestoreFloorTest {

    private val rootKey = ByteArray(32) { (it * 7 + 13).toByte() }
    private val convId = "CONVR5AA"

    private fun processor(store: RatchetStateStore, isLower: Boolean = true) =
        E2EMessageProcessor(rootKey = rootKey, convId = convId, isLower = isLower, store = store)

    private fun dirAndCounter(wire: String): Pair<Byte, Long> {
        val ct = CiphertextWireFormat.parse(wire) ?: error("unparseable wire: $wire")
        return ct.direction to ct.counter
    }

    // ------------------------------------------------------------------
    // R5 core: two sends across a simulated restore never share (root, direction, counter)
    // ------------------------------------------------------------------

    @Test
    fun restored_device_never_reuses_a_burned_send_counter() = runTest {
        // ORIGINAL device: sends 3 messages under the root — counters 0, 1, 2 burned on-chain.
        val original = processor(InMemoryRatchetStateStore())
        val onChain = (0 until 3).map { original.encryptOutgoing("pre-wipe message $it") }
        val burned = onChain.map { dirAndCounter(it) }.toSet()
        assertEquals(setOf(0L, 1L, 2L), burned.map { it.second }.toSet(), "fixture: counters 0..2 on-chain")

        // RESTORE: a brand-new device — EMPTY ratchet state, EMPTY floor store (nothing local
        // survives a wipe+reinstall) — re-derives the SAME root from seed + chain (Stage 2).
        val restored = processor(InMemoryRatchetStateStore())

        // RESCAN: history decrypts via the stateless own-outgoing path (this is what
        // convertToConversations → tryDecryptMessage does for our own sent memos).
        onChain.forEachIndexed { i, wire ->
            assertEquals("pre-wipe message $i", restored.decryptIncoming(wire), "own history must recover")
        }

        // THE R5 ASSERTION: the next send must use counter 3 — NEVER 0 — so no
        // (root, direction, counter) triple ever repeats under the re-derived root.
        val postRestore1 = dirAndCounter(restored.encryptOutgoing("post-restore 1"))
        val postRestore2 = dirAndCounter(restored.encryptOutgoing("post-restore 2"))
        assertTrue(postRestore1 !in burned, "post-restore send REUSED burned (dir, counter) $postRestore1")
        assertTrue(postRestore2 !in burned, "post-restore send REUSED burned (dir, counter) $postRestore2")
        assertEquals(3L, postRestore1.second, "first post-restore send must resume at highest-observed + 1")
        assertEquals(4L, postRestore2.second, "second post-restore send must keep advancing")
        assertNotEquals(postRestore1, postRestore2, "two post-restore sends must not collide either")
        assertEquals(5, (burned + postRestore1 + postRestore2).size, "all 5 (dir, counter) pairs unique")
    }

    // ------------------------------------------------------------------
    // Floors derive from the HIGHEST observed counter, not from scan completeness/order
    // ------------------------------------------------------------------

    @Test
    fun partial_out_of_order_rescan_still_floors_from_highest_observed_counter() = runTest {
        val original = processor(InMemoryRatchetStateStore())
        val onChain = (0 until 5).map { original.encryptOutgoing("msg $it") }

        val restored = processor(InMemoryRatchetStateStore())
        // Only SOME history decrypts, newest first (scan order is not guaranteed; the
        // own-outgoing path is stateless so any subset in any order must work).
        assertEquals("msg 4", restored.decryptIncoming(onChain[4]))
        assertEquals("msg 1", restored.decryptIncoming(onChain[1]))

        // Floor must anchor on the HIGHEST counter seen (4), not the last one seen (1).
        val next = dirAndCounter(restored.encryptOutgoing("post-restore"))
        assertEquals(5L, next.second, "send floor must be highest-observed + 1 even for partial/out-of-order rescan")
    }

    // ------------------------------------------------------------------
    // Scoping: PEER-direction history must not inflate OUR send counter
    // ------------------------------------------------------------------

    @Test
    fun peer_direction_history_does_not_raise_our_send_counter() = runTest {
        // Peer (the isLower=false side) sent us 3 messages; we never sent anything.
        val peer = processor(InMemoryRatchetStateStore(), isLower = false)
        val peerWires = (0 until 3).map { peer.encryptOutgoing("from peer $it") }

        val restored = processor(InMemoryRatchetStateStore(), isLower = true)
        peerWires.forEachIndexed { i, wire ->
            assertEquals("from peer $i", restored.decryptIncoming(wire))
        }

        // Our own direction was never used — counter 0 is correct AND safe here.
        val first = dirAndCounter(restored.encryptOutgoing("our first ever"))
        assertEquals(0L, first.second, "peer-direction history must not consume OUR send counters")
        val peerDir = dirAndCounter(peerWires[0]).first
        assertNotEquals(peerDir, first.first, "directions must differ")
    }

    // ------------------------------------------------------------------
    // Composition with D8: the floor lives in the wipe-surviving store, so the existing
    // localized-state-wipe protection keeps working through the same mechanism.
    // ------------------------------------------------------------------

    @Test
    fun rescan_floor_composes_with_d8_state_wipe_floor() = runTest {
        // Rescan on the restored device floors the counter...
        val floors = mutableMapOf<String, Long>()
        val restoredStore = InMemoryRatchetStateStore(floors)
        val restored = processor(restoredStore)
        val original = processor(InMemoryRatchetStateStore())
        val onChain = (0 until 2).map { original.encryptOutgoing("m$it") }
        onChain.forEach { restored.decryptIncoming(it) }
        assertEquals(2L, dirAndCounter(restored.encryptOutgoing("after rescan")).second)

        // ...and a D8 corruption event (encrypted STATE wiped, PLAIN floor file survives —
        // modeled by a fresh store over the SAME floors map) still cannot roll it back.
        val postWipe = processor(InMemoryRatchetStateStore(floors))
        assertEquals(3L, dirAndCounter(postWipe.encryptOutgoing("after d8 wipe")).second)
    }
}
