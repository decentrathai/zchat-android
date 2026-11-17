package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [DecryptedTextCacheKey] — the ONE cache-key derivation shared by the decrypt path
 * (ChatViewModel.tryDecryptMessage) and the send-time plaintext self-cache.
 *
 * [DecryptedTextCacheKey.of] hashes `"<len(convId)>:<convId>:<wire>"`. The conversation id is part of
 * the key so that the same envelope replayed into a DIFFERENT conversation cannot resolve to a row
 * learned in the original one — without it the cache answered from ciphertext alone and acted as a
 * plaintext oracle.
 *
 * [DecryptedTextCacheKey.legacyOf] is the pre-binding derivation, kept only so rows written by
 * older builds can be migrated on first use. It must keep hashing the bare ciphertext, byte for
 * byte, or those rows become unaddressable — and because the ratchet refuses to re-decrypt a
 * consumed counter, an unaddressable row is a message that becomes permanently unreadable.
 *
 * Expected values are INDEPENDENT vectors (computed outside this codebase), not round-tripped
 * through the implementation.
 */
class DecryptedTextCacheKeyTest {
    // ---------------------------------------------------------------- legacy derivation (migration)

    @Test
    fun legacy_matches_known_sha256_vector() {
        // FIPS 180-2 appendix vector for "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DecryptedTextCacheKey.legacyOf("abc"),
        )
    }

    @Test
    fun legacy_empty_string_hashes_to_sha256_of_empty() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DecryptedTextCacheKey.legacyOf(""),
        )
    }

    @Test
    fun legacy_realistic_e2e1_wire_string_derivation_is_pinned() {
        // Rows written by shipped builds are keyed exactly like this. If this vector ever changes,
        // every pre-existing cached plaintext is orphaned and those messages stop being readable.
        assertEquals(
            "3b676a6b3664ed4d3b47c628edbd2bbf2683a20d30e0e012f92cb75c99a8ff01",
            DecryptedTextCacheKey.legacyOf("E2E1:00:000000000000002a:aGVsbG8gZW5jcnlwdGVkIHdvcmxk"),
        )
    }

    @Test
    fun legacy_hashes_utf8_bytes_not_default_charset() {
        assertEquals(
            "b3b362ce3938e31c27851b32b35cda21da100b775c89185d7d73ea11c7cbe7bc",
            DecryptedTextCacheKey.legacyOf("héllo ✓ ratchet"),
        )
    }

    // ------------------------------------------------------- conversation-bound derivation (current)

    @Test
    fun bound_key_composition_is_length_prefixed() {
        // Pins the exact composition: sha256("<len(convId)>:<convId>:<wire>"). legacyOf is a bare
        // sha256 of its argument, so it doubles as an independent expression of that string here.
        val wire = "E2E1:00:0000000000000001:AAAA"
        assertEquals(
            DecryptedTextCacheKey.legacyOf("6:conv-1:$wire"),
            DecryptedTextCacheKey.of("conv-1", wire),
        )
    }

    /**
     * The property the whole change exists for: an identical envelope seen in two different
     * conversations MUST NOT share a cache row. Otherwise replaying a captured ciphertext into an
     * attacker-controlled conversation returns the plaintext learned in the victim's original one.
     */
    @Test
    fun same_ciphertext_in_different_conversations_yields_different_keys() {
        val wire = "E2E1:00:000000000000002a:aGVsbG8gZW5jcnlwdGVkIHdvcmxk"
        val a = DecryptedTextCacheKey.of("conv-alice", wire)
        val b = DecryptedTextCacheKey.of("conv-mallory", wire)
        assertNotEquals(a, b, "A replayed envelope must not resolve to another conversation's cached plaintext")
    }

    @Test
    fun bound_key_differs_from_legacy_key_for_same_ciphertext() {
        val wire = "E2E1:00:000000000000002a:aGVsbG8gZW5jcnlwdGVkIHdvcmxk"
        assertNotEquals(
            DecryptedTextCacheKey.legacyOf(wire),
            DecryptedTextCacheKey.of("conv-alice", wire),
            "Bound and legacy derivations must be distinguishable so migration is observable",
        )
    }

    @Test
    fun conversation_and_ciphertext_boundary_is_unambiguous() {
        // The separator must not allow two different (convId, wire) pairs to collide by shifting
        // the boundary. convId is an opaque id, so pin the current behaviour explicitly.
        assertNotEquals(
            DecryptedTextCacheKey.of("a", "b:c"),
            DecryptedTextCacheKey.of("a:b", "c"),
            "Boundary shifting must not produce the same cache row",
        )
    }

    @Test
    fun output_is_64_lowercase_hex_chars_and_input_sensitive() {
        val a = DecryptedTextCacheKey.of("conv-1", "E2E1:00:0000000000000001:AAAA")
        val b = DecryptedTextCacheKey.of("conv-1", "E2E1:00:0000000000000002:AAAA")
        assertTrue(a.matches(Regex("[0-9a-f]{64}")), "Expected 64 lowercase hex chars, was: $a")
        assertNotEquals(a, b, "Different ciphertexts must map to different cache rows")
        assertEquals(a, DecryptedTextCacheKey.of("conv-1", "E2E1:00:0000000000000001:AAAA")) // deterministic
    }
}
