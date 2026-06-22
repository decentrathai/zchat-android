package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NOSTRIdentityTest {

    private val testSeed = ByteArray(64) { it.toByte() }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    @Test
    fun publicKey_is_32_byte_x_only() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertEquals(32, identity.publicKey.size, "x-only pubkey must be 32 bytes for Schnorr verify")
    }

    // #250/#251 — the exact v4 rotation-ZBOOT auth flow: sign sha256(signedData) with our key,
    // peer verifies against our x-only pubkey. This round-trip was previously untested and a clean
    // 2-device retest surfaced "ZBOOT signature INVALID" — this nails down whether the CRYPTO is sound
    // (so any on-device failure is an index/key mismatch, not a sign/verify bug).
    @Test
    fun v4_signHashSchnorr_roundtrips_with_verifyHashSchnorr() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val hash = sha256("convId|deadbeefpubkeyhex|wss://relay.zsend.xyz|1")
        val sig = identity.signHashSchnorr(hash)
        assertEquals(64, sig.size, "BIP-340 Schnorr signature is 64 bytes")
        assertTrue(
            NOSTRIdentity.verifyHashSchnorr(sig, hash, identity.publicKey),
            "a signature must verify against the signer's own x-only pubkey",
        )
    }

    @Test
    fun v4_verify_rejects_wrong_pubkey() {
        val signer = NOSTRIdentity.fromSeed(testSeed)
        val other = NOSTRIdentity.fromSeed(ByteArray(64) { (it + 7).toByte() })
        val hash = sha256("c|p|wss://r|2")
        val sig = signer.signHashSchnorr(hash)
        assertFalse(
            NOSTRIdentity.verifyHashSchnorr(sig, hash, other.publicKey),
            "a signature must NOT verify against a different identity's pubkey (MITM gate)",
        )
    }

    @Test
    fun v4_verify_rejects_tampered_message() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val sig = identity.signHashSchnorr(sha256("original"))
        assertFalse(
            NOSTRIdentity.verifyHashSchnorr(sig, sha256("tampered"), identity.publicKey),
            "a signature must NOT verify over a different message hash",
        )
    }

    // Mirrors the rotation case: announce is signed with the OLD-index identity (the one the peer holds)
    // and the peer verifies against that SAME old-index pubkey. Same seed + same index ⇒ same keypair ⇒ verifies.
    @Test
    fun v4_rotation_signed_by_old_index_verifies_against_that_index_pubkey() {
        val seed = ByteArray(64) { (it * 3 + 1).toByte() }
        val oldIdx = 0
        val signer = NOSTRIdentity.fromSeed(seed, oldIdx)        // Seeker signs the announce with its idx0 key
        val peerHeldPubkey = NOSTRIdentity.fromSeed(seed, oldIdx).publicKey  // Honor holds the idx0 pubkey
        val hash = sha256("conv1234|newpubkeyhex0|wss://relay.zsend.xyz|1")
        val sig = signer.signHashSchnorr(hash)
        assertTrue(
            NOSTRIdentity.verifyHashSchnorr(sig, hash, peerHeldPubkey),
            "rotation announce signed by old-index key must verify against the peer-held old-index pubkey",
        )
        // And must FAIL against a DIFFERENT index's pubkey (the cruft/mismatch case seen on-device).
        val differentIdxPubkey = NOSTRIdentity.fromSeed(seed, 2).publicKey
        assertFalse(
            NOSTRIdentity.verifyHashSchnorr(sig, hash, differentIdxPubkey),
            "verify must fail when the peer holds a DIFFERENT rotation-index pubkey (index mismatch)",
        )
    }

    // #252 REGRESSION — models the relay-ack-advance strand. The peer went OFFLINE during our idx0→idx1
    // rotation, so they never adopted idx1 and still hold our idx0 pubkey. The OLD code advanced our
    // "known idx" to the CURRENT index on a bare relay ack, so the NEXT announce got signed with the idx1
    // key the peer never received → verify FAILS against their held idx0 pubkey → permanent strand. The
    // FIX advances the known idx only on PROVEN adoption, so we keep signing with idx0 (the key they DO
    // hold) → verify SUCCEEDS and they can finally jump forward. This pins the index-selection invariant.
    @Test
    fun v4_rotation_signed_with_prematurely_advanced_index_fails_but_held_index_succeeds() {
        val seed = ByteArray(64) { (it * 5 + 2).toByte() }
        val peerHeldPubkey = NOSTRIdentity.fromSeed(seed, 0).publicKey // peer is stuck holding our idx0 key
        val hash = sha256("conv|currentpubkeyhex|wss://relay.zsend.xyz|2")

        // BUG path: known idx prematurely advanced to 1 (relay ack, peer offline) → sign with idx1.
        val prematureSig = NOSTRIdentity.fromSeed(seed, 1).signHashSchnorr(hash)
        assertFalse(
            NOSTRIdentity.verifyHashSchnorr(prematureSig, hash, peerHeldPubkey),
            "announce signed with a prematurely-advanced index must NOT verify against the key the peer still holds",
        )

        // FIX path: known idx stays at the peer-held 0 (no false advance) → sign with idx0.
        val heldSig = NOSTRIdentity.fromSeed(seed, 0).signHashSchnorr(hash)
        assertTrue(
            NOSTRIdentity.verifyHashSchnorr(heldSig, hash, peerHeldPubkey),
            "announce signed with the peer-held index must verify, letting a missed rotation self-heal",
        )
    }

    @Test
    fun derive_produces_32_byte_private_key() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertEquals(32, identity.privateKey.size)
    }

    @Test
    fun same_seed_produces_same_keys() {
        val id1 = NOSTRIdentity.fromSeed(testSeed)
        val id2 = NOSTRIdentity.fromSeed(testSeed)
        assertTrue(id1.privateKey.contentEquals(id2.privateKey))
        assertTrue(id1.publicKey.contentEquals(id2.publicKey))
    }

    @Test
    fun different_seeds_produce_different_keys() {
        val seed2 = ByteArray(64) { (it + 100).toByte() }
        val id1 = NOSTRIdentity.fromSeed(testSeed)
        val id2 = NOSTRIdentity.fromSeed(seed2)
        assert(!id1.privateKey.contentEquals(id2.privateKey))
    }

    @Test
    fun npub_starts_with_npub1() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertTrue(identity.npub.startsWith("npub1"), "npub should start with npub1 but was: ${identity.npub}")
    }

    @Test
    fun signNIP98Event_produces_nonempty_base64() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val event = identity.signNIP98Event("https://nostr.build/upload", "POST")
        assertNotNull(event)
        assertTrue(event.isNotEmpty())
        // Should be valid base64
        Base64.getDecoder().decode(event)
    }
}
