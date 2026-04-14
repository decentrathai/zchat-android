package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration-level tests for the full E2E stack: plaintext → ratchet encrypt →
 * wire format serialize → (transport) → wire format parse → ratchet decrypt → plaintext.
 *
 * This exercises the path that ChatViewModel will use.
 */
class E2EMessageProcessorTest {

    private val testRootKey = ByteArray(32) { it.toByte() }
    private val testConvId = "CONV0001"

    @Test
    fun full_stack_roundtrip_plaintext_to_wire_string_and_back() = runTest {
        val alice = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val original = "hello from alice over the full E2E stack"

        // Alice encrypts → wire string (this is what goes into the ZMSG memo)
        val wireString = alice.encryptOutgoing(original)
        assertTrue(wireString.startsWith("E2E1:"), "Must produce E2E1: wire format, got: ${wireString.take(20)}")
        assertFalse(wireString.contains(original), "Plaintext must not leak into wire format")

        // Bob decrypts ← wire string (this is what ChatViewModel receives from memo)
        val recovered = bob.decryptIncoming(wireString)
        assertEquals(original, recovered)
    }

    @Test
    fun plaintext_passthrough_when_not_e2e_prefixed() = runTest {
        val bob = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // Non-E2E content should pass through unchanged
        val plain = "just a regular message"
        assertEquals(plain, bob.decryptIncoming(plain))
    }

    @Test
    fun three_messages_roundtrip_counters_advance() = runTest {
        val alice = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val msgs = listOf("one", "two", "three")
        val wires = msgs.map { alice.encryptOutgoing(it) }

        // All distinct wire strings
        assertEquals(3, wires.toSet().size)

        // Bob decrypts all three
        for ((idx, wire) in wires.withIndex()) {
            assertEquals(msgs[idx], bob.decryptIncoming(wire))
        }
    }

    @Test
    fun bidirectional_alice_and_bob_both_send() = runTest {
        val alice = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = true,
            store = InMemoryRatchetStateStore(),
        )
        val bob = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        val fromAlice = alice.encryptOutgoing("hi bob")
        val fromBob = bob.encryptOutgoing("hi alice")

        assertEquals("hi bob", bob.decryptIncoming(fromAlice))
        assertEquals("hi alice", alice.decryptIncoming(fromBob))
    }

    @Test
    fun malformed_e2e1_wire_throws_instead_of_returning_raw_blob() = runTest {
        val bob = E2EMessageProcessor(
            rootKey = testRootKey,
            convId = testConvId,
            isLower = false,
            store = InMemoryRatchetStateStore(),
        )

        // A malformed E2E1: payload must NOT be returned as message text — otherwise the user
        // sees encrypted bytes as the message. It must raise, so the caller can show
        // "🔐 Encrypted message (unable to decrypt)" instead.
        val junkCases = listOf(
            "E2E1:not-hex:0:base64",
            "E2E1::::",
            "E2E1:0:0:not-base-64!!",
            "E2E1:incomplete",
        )
        for (junk in junkCases) {
            var threw = false
            try {
                bob.decryptIncoming(junk)
            } catch (e: Exception) {
                threw = true
            }
            assertTrue(threw, "expected throw for $junk")
        }
    }
}
