package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * TDD spec for the Stage B deterministic-root symmetric ratchet.
 *
 * See docs/superpowers/specs/2026-04-12-e2e-ratchet-deterministic-design.md for the design.
 * Tests are written one at a time, each watched RED before implementation, watched GREEN after.
 */
class E2ERatchetTest {

    private val testRootKey = ByteArray(32) { it.toByte() }
    private val testConvId = "CONV0001"

    @Test
    fun alice_encrypts_bob_decrypts_single_message() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val plaintext = "hello bob, this is alice".toByteArray()
        val ciphertext = alice.encrypt(plaintext)
        val decrypted = bob.decrypt(ciphertext)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun alice_sends_three_messages_bob_receives_in_order() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val msg1 = "first".toByteArray()
        val msg2 = "second".toByteArray()
        val msg3 = "third".toByteArray()

        val ct1 = alice.encrypt(msg1)
        val ct2 = alice.encrypt(msg2)
        val ct3 = alice.encrypt(msg3)

        // Counters must advance deterministically
        assertEquals(0L, ct1.counter)
        assertEquals(1L, ct2.counter)
        assertEquals(2L, ct3.counter)

        // Each ciphertext must be distinct (different derived keys + nonces)
        assertFalse(ct1.bytes.contentEquals(ct2.bytes))
        assertFalse(ct2.bytes.contentEquals(ct3.bytes))
        assertFalse(ct1.bytes.contentEquals(ct3.bytes))

        // Bob decrypts each in order, recovers the correct plaintext
        assertContentEquals(msg1, bob.decrypt(ct1))
        assertContentEquals(msg2, bob.decrypt(ct2))
        assertContentEquals(msg3, bob.decrypt(ct3))
    }

    @Test
    fun alice_and_bob_send_concurrently_both_chains_advance_independently() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // Alice and Bob each send one message before receiving the other's
        val aliceMsg = "from alice".toByteArray()
        val bobMsg = "from bob".toByteArray()
        val ctFromAlice = alice.encrypt(aliceMsg)
        val ctFromBob = bob.encrypt(bobMsg)

        // Directions are opposite — each party uses its own chain
        assertEquals(0x00.toByte(), ctFromAlice.direction)
        assertEquals(0x01.toByte(), ctFromBob.direction)

        // Both chains start at counter 0 independently (neither blocks the other)
        assertEquals(0L, ctFromAlice.counter)
        assertEquals(0L, ctFromBob.counter)

        // Cross-decrypt: each party can read the other's message
        assertContentEquals(aliceMsg, bob.decrypt(ctFromAlice))
        assertContentEquals(bobMsg, alice.decrypt(ctFromBob))

        // Each party's next send advances their own chain only
        val ctFromAlice2 = alice.encrypt("alice msg 2".toByteArray())
        val ctFromBob2 = bob.encrypt("bob msg 2".toByteArray())
        assertEquals(1L, ctFromAlice2.counter)
        assertEquals(1L, ctFromBob2.counter)
    }

    @Test
    fun two_ratchets_with_same_root_produce_identical_ciphertexts() = runTest {
        // Determinism property: two independent ratchet instances with the same root,
        // convId, direction, and fresh state produce byte-identical ciphertexts for the
        // same plaintext at the same counter. This is the restore-from-seed guarantee:
        // a new device re-deriving the root can re-decrypt every historical message.
        val alice1 = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val alice2 = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )

        val plaintext = "deterministic message".toByteArray()
        val ct1 = alice1.encrypt(plaintext)
        val ct2 = alice2.encrypt(plaintext)

        assertContentEquals(ct1.bytes, ct2.bytes)
        assertEquals(ct1.counter, ct2.counter)
        assertEquals(ct1.direction, ct2.direction)
    }

    @Test
    fun root_key_deterministic_from_ecdh_and_kex_context() {
        val ecdh = ByteArray(32) { it.toByte() }
        val kexTxid = ByteArray(32) { (it + 1).toByte() }
        val kexAckTxid = ByteArray(32) { (it + 2).toByte() }

        val root1 = E2ERatchet.deriveRatchetRoot(ecdh, psk = null, kexTxid, kexAckTxid)
        val root2 = E2ERatchet.deriveRatchetRoot(ecdh, psk = null, kexTxid, kexAckTxid)

        assertContentEquals(root1, root2)
        assertEquals(32, root1.size)
    }

    @Test
    fun root_key_differs_when_psk_present_vs_absent() {
        val ecdh = ByteArray(32) { it.toByte() }
        val kexTxid = ByteArray(32) { (it + 1).toByte() }
        val kexAckTxid = ByteArray(32) { (it + 2).toByte() }
        val psk = ByteArray(32) { (it + 100).toByte() }

        val withoutPsk = E2ERatchet.deriveRatchetRoot(ecdh, psk = null, kexTxid, kexAckTxid)
        val withPsk = E2ERatchet.deriveRatchetRoot(ecdh, psk = psk, kexTxid, kexAckTxid)

        assertFalse(withoutPsk.contentEquals(withPsk))
        assertEquals(32, withPsk.size)
    }

    @Test
    fun a2b_and_b2a_chains_derive_distinct_keys_at_same_counter() = runTest {
        // Same root, same convId, same counter — but the direction byte makes the derived
        // keys independent. Cross-direction ciphertext must fail to decrypt, because both
        // the key AND the AAD differ.
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val ctAlice = alice.encrypt("alice".toByteArray())
        val ctBob = bob.encrypt("bob".toByteArray())

        // Same counter (both 0) but different ciphertext bytes — proves distinct keys/AAD
        assertEquals(ctAlice.counter, ctBob.counter)
        assertFalse(ctAlice.bytes.contentEquals(ctBob.bytes))

        // Sanity: if we flip the direction byte on Alice's ciphertext and hand it to Bob,
        // Bob's decrypt — which now tries the B2A chain against A2B-encrypted bytes —
        // must fail the AEAD auth check.
        val tampered = ctAlice.copy(direction = 0x01)
        val failed = runCatching { bob.decrypt(tampered) }
        assertFalse(failed.isSuccess, "Cross-direction decrypt MUST fail; got success")
    }

    @Test
    fun replay_of_same_counter_is_rejected() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val ciphertext = alice.encrypt("this message is sent once".toByteArray())

        // First decrypt: succeeds
        val first = bob.decrypt(ciphertext)
        assertContentEquals("this message is sent once".toByteArray(), first)

        // Replay: identical ciphertext (same direction, same counter, same bytes) must be
        // rejected. A naive counter-based ratchet without a seen-counter set would happily
        // decrypt again because the key derivation is deterministic. The seen-counter
        // window prevents this replay attack.
        val replayed = runCatching { bob.decrypt(ciphertext) }
        assertFalse(replayed.isSuccess, "Replay MUST be rejected; got success")
    }

    @Test
    fun out_of_order_delivery_two_then_one_both_decrypt() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val msg1 = "first".toByteArray()
        val msg2 = "second".toByteArray()
        val ct1 = alice.encrypt(msg1)
        val ct2 = alice.encrypt(msg2)

        // Bob receives them out of order: ct2 first, then ct1
        assertContentEquals(msg2, bob.decrypt(ct2))
        assertContentEquals(msg1, bob.decrypt(ct1))
    }

    @Test
    fun skip_ahead_then_backfill_earlier_counters() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // Alice sends 5 messages
        val msgs = (0..4).map { "msg $it".toByteArray() }
        val cts = msgs.map { alice.encrypt(it) }

        // Bob jumps to ct[4] first (as if msgs 0-3 were still stuck in the mempool),
        // then backfills the earlier counters in arbitrary order.
        assertContentEquals(msgs[4], bob.decrypt(cts[4]))
        assertContentEquals(msgs[0], bob.decrypt(cts[0]))
        assertContentEquals(msgs[2], bob.decrypt(cts[2]))
        assertContentEquals(msgs[1], bob.decrypt(cts[1]))
        assertContentEquals(msgs[3], bob.decrypt(cts[3]))

        // Every counter is now seen — any replay must be rejected
        for (ct in cts) {
            val replay = runCatching { bob.decrypt(ct) }
            assertFalse(replay.isSuccess, "Replay of counter ${ct.counter} MUST be rejected")
        }
    }

    @Test
    fun counter_at_max_skip_boundary_accepted() = runTest {
        // A legitimate peer could conceivably have sent 1000 messages to us before we
        // came online. Our very first decrypt should accept any counter in [0, MAX_SKIP]
        // (inclusive) because maxSeen starts at 0.
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // Alice "sends" 1001 messages but Bob only sees the last one (counter=1000).
        // We simulate this by encrypting 1001 times on Alice's side.
        repeat(1000) { alice.encrypt("warm-up $it".toByteArray()) }
        val atBoundary = alice.encrypt("at MAX_SKIP boundary".toByteArray())
        assertEquals(1000L, atBoundary.counter)

        // Bob receives only this one — counter=1000 must be accepted (equal to MAX_SKIP)
        val decrypted = bob.decrypt(atBoundary)
        assertContentEquals("at MAX_SKIP boundary".toByteArray(), decrypted)
    }

    @Test
    fun counter_above_max_skip_rejected_as_dos_protection() = runTest {
        // A malicious peer (or DoS attacker) claiming counter > MAX_SKIP with no prior
        // state MUST be rejected without doing the expensive chain walk.
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // Alice sends 1002 messages — counter=1001 is just beyond bob's MAX_SKIP window
        // of [0..1000] when bob's maxSeen is 0.
        repeat(1001) { alice.encrypt("warm-up $it".toByteArray()) }
        val beyond = alice.encrypt("beyond MAX_SKIP".toByteArray())
        assertEquals(1001L, beyond.counter)

        // Bob MUST reject this — otherwise an attacker can force arbitrary HMAC work
        val rejected = runCatching { bob.decrypt(beyond) }
        assertFalse(rejected.isSuccess, "Counter beyond MAX_SKIP MUST be rejected")
    }

    @Test
    fun tampered_ciphertext_fails_aead_check_and_does_not_poison_seen_set() = runTest {
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val original = alice.encrypt("authentic".toByteArray())
        val tamperedBytes = original.bytes.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tampered = original.copy(bytes = tamperedBytes)

        val failed = runCatching { bob.decrypt(tampered) }
        assertFalse(failed.isSuccess, "Tampered ciphertext MUST fail AEAD check")

        // Crucial: the failed decrypt must NOT have marked counter=0 as seen, otherwise
        // a retransmit of the LEGITIMATE message would be rejected as replay.
        val legitimate = bob.decrypt(original)
        assertContentEquals("authentic".toByteArray(), legitimate)
    }

    @Test
    fun cross_conversation_replay_fails_due_to_aad_binding() = runTest {
        // Same rootKey, same direction, same counter — but different convId. The AAD
        // includes convId so the AEAD auth check catches the cross-conversation replay.
        val aliceConvA = E2ERatchet(
            rootKey = testRootKey,
            convId = "CONVAAAA",
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bobConvB = E2ERatchet(
            rootKey = testRootKey,
            convId = "CONVBBBB",
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val ct = aliceConvA.encrypt("for conv A only".toByteArray())

        // Same ciphertext fed to a ratchet on a DIFFERENT conversation must fail
        val failed = runCatching { bobConvB.decrypt(ct) }
        assertFalse(failed.isSuccess, "Cross-conversation replay MUST fail AAD check")
    }

    @Test
    fun restore_simulation_fresh_store_rederives_and_decrypts_history() = runTest {
        // Alice sends 5 messages to Bob. Then Bob's phone dies; he restores on a new
        // device with a fresh store and the SAME rootKey (re-derived from seed + on-chain
        // KEX context). He must be able to decrypt all 5 history messages in order.
        val aliceStore = InMemoryRatchetStateStore()
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = aliceStore,
        )
        val msgs = (0..4).map { "historic msg $it".toByteArray() }
        val cts = msgs.map { alice.encrypt(it) }

        // Bob's old state is lost. New device, fresh in-memory store, same rootKey.
        val restoredBob = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // Walk the blockchain in order (simulated as a List) and decrypt each message.
        for ((idx, ct) in cts.withIndex()) {
            val plaintext = restoredBob.decrypt(ct)
            assertContentEquals(msgs[idx], plaintext)
        }
    }

    @Test
    fun concurrent_encrypts_produce_distinct_monotonic_counters() = runTest {
        // The mutex must serialize concurrent encrypts so that no two calls observe the
        // same `nextCounter`. We dispatch N=50 encrypt coroutines in parallel and assert
        // that exactly one ciphertext exists for each counter in [0, N-1].
        val alice = E2ERatchet(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )

        val n = 50
        val ciphertexts = coroutineScope {
            (0 until n).map { i ->
                async { alice.encrypt("msg $i".toByteArray()) }
            }.awaitAll()
        }

        // Every counter in [0, n-1] must be represented exactly once
        val counters = ciphertexts.map { it.counter }.toSet()
        assertEquals(n, counters.size, "Expected $n distinct counters, got ${counters.size}")
        assertEquals((0L until n.toLong()).toSet(), counters)
    }
}
