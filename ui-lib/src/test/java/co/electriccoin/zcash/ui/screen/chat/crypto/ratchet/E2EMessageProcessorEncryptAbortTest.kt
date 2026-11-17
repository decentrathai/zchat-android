package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Encrypt-failure ABORT regression: when the encrypt path fails, the send must be aborted —
 * the plaintext must NEVER escape [E2EMessageProcessor.encryptOutgoing] (e.g. as a
 * "graceful" plaintext fallback that would end up in an on-chain memo forever).
 *
 * The realistic failure injected here is the documented fail-closed persist:
 * [RatchetStateStore.save] / [RatchetStateStore.saveCounterFloor] throw when the synchronous
 * commit fails (see [EncryptedPrefsRatchetStateStore.save]), and [E2ERatchet.encrypt] promises
 * "if either fails, encrypt() throws and the ciphertext never leaves the device".
 *
 * MUTATION THAT REDDENS THESE TESTS: wrap the body of `encryptOutgoing` (or the store writes in
 * `E2ERatchet.encrypt`) in a catch that falls back to returning the plaintext — then
 * `runCatching { encryptOutgoing(...) }` SUCCEEDS and the returned string contains the secret,
 * failing both the isFailure assertion and the plaintext-leak tripwire. A subtler mutation that
 * merely swallows the persist failure (returning a real ciphertext whose counter never reached
 * disk) also reddens the isFailure assertion.
 */
class E2EMessageProcessorEncryptAbortTest {

    private val testRootKey = ByteArray(32) { it.toByte() }
    private val testConvId = "CONV0001"
    private val secret = "TOP-SECRET plaintext that must never leave the device unencrypted"

    @Test
    fun failed_state_persist_aborts_the_send_and_leaks_no_plaintext() = runTest {
        val store = FailableStore(failSave = true)
        val alice = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = store,
        )

        val result = runCatching { alice.encryptOutgoing(secret) }

        // Reverted fix (catch → plaintext fallback) makes this SUCCEED with the raw plaintext.
        assertTrue(
            result.isFailure,
            "encryptOutgoing must ABORT (throw) when the counter cannot be persisted — " +
                "got success: ${result.getOrNull()?.take(40)}",
        )
        // Plaintext-leak tripwire: whatever a mutated implementation returns, it must not
        // carry the plaintext (a plaintext fallback fails here even if isFailure were bypassed).
        assertFalse(
            result.getOrNull()?.contains(secret) == true,
            "plaintext leaked out of the aborted encrypt path",
        )
    }

    @Test
    fun failed_counter_floor_persist_also_aborts_the_send() = runTest {
        // Second write on the send path (D8 durable floor). Same fail-closed contract:
        // if it cannot reach disk, the ciphertext must not leave the device.
        val store = FailableStore(failFloor = true)
        val alice = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = store,
        )

        val result = runCatching { alice.encryptOutgoing(secret) }

        // Mutation: swallowing the floor-persist failure makes this succeed → red.
        assertTrue(result.isFailure, "encryptOutgoing must ABORT when the counter floor persist fails")
        assertFalse(result.getOrNull()?.contains(secret) == true, "plaintext leaked on floor-persist failure")
    }

    @Test
    fun aborted_send_does_not_consume_the_counter_retry_reuses_it_safely() = runTest {
        // Abort semantics, positive half: a send whose persist failed was never "committed",
        // so the counter is NOT consumed — the retry (once the store works again) encrypts at
        // the SAME counter and the peer can decrypt it. (Safe: the failed attempt's ciphertext
        // never left the device, so the (key, nonce) pair was never exposed.)
        val store = FailableStore(failSave = true)
        val alice = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = store,
        )

        assertTrue(runCatching { alice.encryptOutgoing(secret) }.isFailure)

        store.failSave = false
        val wire = alice.encryptOutgoing(secret)
        val ct = CiphertextWireFormat.parse(wire)
        // Mutation: if the failed attempt had advanced the counter anyway (persist-after-advance
        // ordering broken), the retry would carry counter 1 and this assertion goes red.
        assertEquals(0L, ct?.counter, "aborted send must not consume the counter; retry restarts at 0")

        val bob = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )
        assertEquals(secret, bob.decryptIncoming(wire), "retried send must decrypt normally")
    }

    /**
     * [RatchetStateStore] whose persist calls can be made to fail, modeling
     * `commit() == false → IOException` in the production store. Everything else
     * delegates to [InMemoryRatchetStateStore].
     */
    private class FailableStore(
        var failSave: Boolean = false,
        var failFloor: Boolean = false,
    ) : RatchetStateStore {
        private val delegate = InMemoryRatchetStateStore()

        override suspend fun load(convId: String): RatchetConversationState? = delegate.load(convId)

        override suspend fun save(state: RatchetConversationState) {
            if (failSave) throw IOException("simulated failed synchronous commit of ratchet state")
            delegate.save(state)
        }

        override suspend fun mutexFor(convId: String): Mutex = delegate.mutexFor(convId)

        override suspend fun loadCounterFloor(convId: String, direction: Byte): Long =
            delegate.loadCounterFloor(convId, direction)

        override suspend fun saveCounterFloor(convId: String, direction: Byte, value: Long) {
            if (failFloor) throw IOException("simulated failed synchronous commit of counter floor")
            delegate.saveCounterFloor(convId, direction, value)
        }

        override suspend fun delete(convId: String) = delegate.delete(convId)
    }
}
