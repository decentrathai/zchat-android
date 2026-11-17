package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Stage 1 of D1-recovery (seed-recoverable E2E history): pure, deterministic
 * (walletSeed, peerIndex) -> P-256 keypair derivation.
 *
 * Frozen construction (from the approved design, seed-recoverable-e2e-design.md §2):
 *   okm = HKDF-SHA256(ikm, salt="ZCHAT_E2E_SEED", info="zchat-e2e-identity-v3" || LE32(index), len=48)
 *   d   = (OS2IP(okm) mod (n-1)) + 1          // 48-byte wide reduction, no rejection loop
 *   Q   = d * G  on secp256r1 (pure-Kotlin point multiplication — no BouncyCastle on classpath)
 * Emitted in E2EEncryption.generateKeyPair()'s exact format (E2EEncryption.kt:137-147):
 *   publicKey  = Base64(std, padded) of X.509 SPKI DER (named-curve P-256, uncompressed point)
 *   privateKey = Base64(std, padded) of PKCS#8 DER
 *
 * The NIST k*G vectors below are the published P-256 point-multiplication test vectors
 * (point-at-infinity.org "Test Vectors for the NIST Elliptic Curves" set; k=n-1 additionally
 * follows from point negation; the large-scalar vector is the RFC 6979 A.2.5 P-256 key).
 * All were independently re-verified against OpenSSL (python `cryptography`) before freezing.
 */
class E2ESeedDerivationTest {

    // --- Curve constants (secp256r1 / NIST P-256, SEC 2 v2.0 §2.4.2) ---
    private val n = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    private val seedA = ByteArray(32) { it.toByte() }
    private val seedB = ByteArray(32) { (it + 1).toByte() }

    // ------------------------------------------------------------------
    // 1. NIST P-256 point-multiplication KATs — tripwire for the EC core
    // ------------------------------------------------------------------

    private data class KGVector(val k: BigInteger, val x: String, val y: String)

    private val kgVectors = listOf(
        // k = 1 (the base point G itself, SEC 2 / FIPS 186-4)
        KGVector(
            BigInteger.ONE,
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"
        ),
        // k = 2
        KGVector(
            BigInteger.valueOf(2),
            "7CF27B188D034F7E8A52380304B51AC3C08969E277F21B35A60B48FC47669978",
            "07775510DB8ED040293D9AC69F7430DBBA7DADE63CE982299E04B79D227873D1"
        ),
        // k = 3
        KGVector(
            BigInteger.valueOf(3),
            "5ECBE4D1A6330A44C8F7EF951D4BF165E6C6B721EFADA985FB41661BC6E7FD6C",
            "8734640C4998FF7E374B06CE1A64A2ECD82AB036384FB83D9A79B127A27D5032"
        ),
        // k = 4
        KGVector(
            BigInteger.valueOf(4),
            "E2534A3532D08FBBA02DDE659EE62BD0031FE2DB785596EF509302446B030852",
            "E0F1575A4C633CC719DFEE5FDA862D764EFC96C3F30EE0055C42C23F184ED8C6"
        ),
        // k = 5
        KGVector(
            BigInteger.valueOf(5),
            "51590B7A515140D2D784C85608668FDFEF8C82FD1F5BE52421554A0DC3D033ED",
            "E0C17DA8904A727D8AE1BF36BF8A79260D012F00D4D80888D1D0BB44FDA16DA4"
        ),
        // k = 112233445566778899 (large multi-word scalar, exercises many double+add steps)
        KGVector(
            BigInteger("112233445566778899"),
            "339150844EC15234807FE862A86BE77977DBFB3AE3D96F4C22795513AEAAB82F",
            "B1C14DDFDC8EC1B2583F51E85A5EB3A155840F2034730E9B5ADA38B674336A21"
        ),
        // RFC 6979 A.2.5: private key x -> public key (Ux, Uy). Full-width 256-bit scalar.
        KGVector(
            BigInteger("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721", 16),
            "60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6",
            "7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299"
        ),
        // k = n - 1 == -1 (mod n): (n-1)G = -G = (Gx, p - Gy). Worst-case scalar boundary.
        KGVector(
            BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632550", 16),
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
            "B01CBD1C01E58065711814B583F061E9D431CCA994CEA1313449BF97C840AE0A"
        )
    )

    @Test
    fun p256_base_point_multiplication_matches_published_nist_vectors() {
        for (v in kgVectors) {
            val point = E2ESeedDerivation.multiplyBasePoint(v.k)
            assertEquals(
                BigInteger(v.x, 16),
                point.affineX,
                "x mismatch for k=${v.k.toString(16)}"
            )
            assertEquals(
                BigInteger(v.y, 16),
                point.affineY,
                "y mismatch for k=${v.k.toString(16)}"
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. Determinism
    // ------------------------------------------------------------------

    @Test
    fun same_seed_and_index_derive_byte_identical_keypair_across_100_runs() {
        val reference = E2ESeedDerivation.deriveKeyPair(seedA, 0)
        repeat(99) { run ->
            val again = E2ESeedDerivation.deriveKeyPair(seedA.copyOf(), 0)
            assertEquals(reference.publicKey, again.publicKey, "public key drifted on run $run")
            assertEquals(reference.privateKey, again.privateKey, "private key drifted on run $run")
        }
    }

    // ------------------------------------------------------------------
    // 3. Distinctness
    // ------------------------------------------------------------------

    @Test
    fun different_seed_derives_different_keypair() {
        val a = E2ESeedDerivation.deriveKeyPair(seedA, 0)
        val b = E2ESeedDerivation.deriveKeyPair(seedB, 0)
        assertNotEquals(a.publicKey, b.publicKey)
        assertNotEquals(a.privateKey, b.privateKey)
    }

    @Test
    fun different_index_derives_different_keypair() {
        val i0 = E2ESeedDerivation.deriveKeyPair(seedA, 0)
        val i1 = E2ESeedDerivation.deriveKeyPair(seedA, 1)
        assertNotEquals(i0.publicKey, i1.publicKey)
        assertNotEquals(i0.privateKey, i1.privateKey)
    }

    @Test
    fun first_twelve_indices_all_derive_distinct_public_keys() {
        val pubs = (0 until 12).map { E2ESeedDerivation.deriveKeyPair(seedA, it).publicKey }
        assertEquals(12, pubs.toSet().size, "index collision within first 12 indices")
    }

    // ------------------------------------------------------------------
    // 4. Scalar validity — d in [1, n-1], and the wide-reduction boundaries
    // ------------------------------------------------------------------

    @Test
    fun derived_scalar_is_always_in_valid_p256_range() {
        for (idx in 0 until 8) {
            for (seed in listOf(seedA, seedB, ByteArray(64) { (it * 7).toByte() })) {
                val d = E2ESeedDerivation.deriveScalar(seed, idx)
                assertTrue(d >= BigInteger.ONE, "scalar below 1 for idx=$idx")
                assertTrue(d <= n.subtract(BigInteger.ONE), "scalar >= n for idx=$idx")
            }
        }
    }

    @Test
    fun wide_reduction_maps_every_48_byte_input_into_valid_range() {
        // The design replaces the FIPS 186-4 rejection loop with FIPS 186-5 style wide
        // reduction: d = (OS2IP(okm48) mod (n-1)) + 1. These boundary inputs pin down the
        // exact reduction (modulus n-1 and the +1 offset) so no input needs rejection.
        val nMinus1 = n.subtract(BigInteger.ONE)

        // All-zero candidate (would be REJECTED as d=0 under the naive approach) -> d = 1.
        assertEquals(BigInteger.ONE, E2ESeedDerivation.reduceToScalar(ByteArray(48)))

        // OS2IP(okm) == n-1 -> (n-1) mod (n-1) = 0 -> d = 1.
        assertEquals(BigInteger.ONE, E2ESeedDerivation.reduceToScalar(i2osp(nMinus1, 48)))

        // OS2IP(okm) == n-2 -> d = n-1 (the maximum valid scalar).
        assertEquals(nMinus1, E2ESeedDerivation.reduceToScalar(i2osp(n.subtract(BigInteger.valueOf(2)), 48)))

        // All-0xFF (maximum 384-bit candidate, far above n) still lands in [1, n-1].
        val dMax = E2ESeedDerivation.reduceToScalar(ByteArray(48) { 0xFF.toByte() })
        assertTrue(dMax >= BigInteger.ONE && dMax <= nMinus1)
    }

    // ------------------------------------------------------------------
    // 5. Format parity — drop-in for E2EEncryption.generateKeyPair()'s encoding
    // ------------------------------------------------------------------

    @Test
    fun public_key_encoding_matches_generateKeyPair_spki_format_exactly() {
        val derived = E2ESeedDerivation.deriveKeyPair(seedA, 0)
        val jca = E2EEncryption.generateKeyPair()

        val derivedDer = Base64.getDecoder().decode(derived.publicKey)
        val jcaDer = Base64.getDecoder().decode(jca.publicKey)

        // Same canonical X.509 SPKI shape: 26-byte named-curve P-256 header,
        // then 0x04 || X(32) || Y(32) uncompressed point = 91 bytes total.
        assertEquals(jcaDer.size, derivedDer.size, "SPKI DER length differs from generateKeyPair()")
        assertEquals(91, derivedDer.size)
        assertContentEquals(
            jcaDer.copyOfRange(0, 26),
            derivedDer.copyOfRange(0, 26),
            "SPKI header/template differs from generateKeyPair()"
        )
        assertEquals(0x04.toByte(), derivedDer[26], "point must be uncompressed (0x04)")

        // Base64 variant parity: strict standard-alphabet decode + re-encode is the identity,
        // i.e. same alphabet and padding as Base64.getEncoder() used at E2EEncryption.kt:143.
        assertEquals(derived.publicKey, Base64.getEncoder().encodeToString(derivedDer))
        assertEquals(derived.privateKey, Base64.getEncoder().encodeToString(Base64.getDecoder().decode(derived.privateKey)))
    }

    @Test
    fun emitted_der_parses_and_reencodes_identically_via_jca_keyfactory() {
        val derived = E2ESeedDerivation.deriveKeyPair(seedA, 3)
        val kf = KeyFactory.getInstance("EC")

        // Public: parse through the EXACT production consume path spec (X509EncodedKeySpec,
        // as in E2EEncryption.deriveSharedSecret/verify) and require the JCA re-encoding to
        // be byte-identical — proves our DER is the canonical form the platform emits.
        val ourPubDer = Base64.getDecoder().decode(derived.publicKey)
        val jcaPub = kf.generatePublic(X509EncodedKeySpec(ourPubDer))
        assertContentEquals(ourPubDer, jcaPub.encoded, "JCA SPKI re-encoding differs from ours")

        // Private: must parse through the production spec (PKCS8EncodedKeySpec, as in
        // E2EEncryption.deriveSharedSecret/sign). Re-encoding equality is provider-dependent
        // for PKCS#8 (optional embedded public key), so parseability is the contract here.
        val ourPrivDer = Base64.getDecoder().decode(derived.privateKey)
        val jcaPriv = kf.generatePrivate(PKCS8EncodedKeySpec(ourPrivDer))
        assertEquals("EC", jcaPriv.algorithm)
    }

    // ------------------------------------------------------------------
    // 6. JCA cross-checks — catches bugs in the hand-rolled point math
    // ------------------------------------------------------------------

    @Test
    fun jca_public_key_constructed_from_derived_point_equals_our_encoding() {
        // Build a java.security.PublicKey from the raw derived point via ECPublicKeySpec
        // (JCA can CONSTRUCT from a known point even though it cannot derive one
        // deterministically) and assert its SPKI equals ours byte-for-byte.
        val d = E2ESeedDerivation.deriveScalar(seedA, 5)
        val point: ECPoint = E2ESeedDerivation.multiplyBasePoint(d)

        val params = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val jcaPub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))

        val derived = E2ESeedDerivation.deriveKeyPair(seedA, 5)
        assertContentEquals(
            Base64.getDecoder().decode(derived.publicKey),
            jcaPub.encoded,
            "JCA-constructed SPKI for the derived point differs from our encoding"
        )
    }

    @Test
    fun ecdh_between_derived_pair_and_jca_pair_is_symmetric_through_production_path() {
        // If Q != d*G (hand-rolled math bug), the two directions CANNOT agree:
        //   left  = HKDF(ECDH(d_derived, Q_jca))   -- our priv, their pub
        //   right = HKDF(ECDH(d_jca, Q_derived))   -- their priv, our pub
        // Equality proves (d, Q) is a consistent pair on the curve. Uses the REAL
        // E2EEncryption.deriveSharedSecret (PKCS8/X509 parse + ECDH + HKDF V2).
        val derived = E2ESeedDerivation.deriveKeyPair(seedA, 2)
        val jca = E2EEncryption.generateKeyPair()

        val left = E2EEncryption.deriveSharedSecret(derived.privateKey, jca.publicKey)
        val right = E2EEncryption.deriveSharedSecret(jca.privateKey, derived.publicKey)
        assertContentEquals(left, right, "ECDH asymmetry: derived public key is not d*G")
    }

    @Test
    fun ecdsa_sign_verify_roundtrip_through_production_path() {
        val derived = E2ESeedDerivation.deriveKeyPair(seedB, 1)
        val message = "zchat-kex-payload-test"
        val signature = E2EEncryption.sign(derived.privateKey, message)
        assertTrue(
            E2EEncryption.verify(derived.publicKey, message, signature),
            "signature by derived private key must verify against derived public key"
        )
        assertTrue(
            !E2EEncryption.verify(derived.publicKey, message + "tampered", signature),
            "tampered message must not verify"
        )
    }

    // ------------------------------------------------------------------
    // 7. KAT freeze — anti-drift tripwire (values frozen at first GREEN, never changed)
    // ------------------------------------------------------------------

    @Test
    fun kat_freeze_known_seed_and_index_produce_frozen_keypair() {
        // ikm = 00 01 02 ... 1F, index = 0 and index = 7.
        // These Base64 strings were computed INDEPENDENTLY (Python hmac/hashlib HKDF +
        // OpenSSL EC via the `cryptography` package) from the frozen construction and are
        // FROZEN FOREVER. If this test ever fails, the derivation drifted — that silently
        // destroys every user's restored chat history. NEVER update these values; a
        // construction change requires a new versioned info string ("-v4") treated as a
        // new key generation.
        val idx0 = E2ESeedDerivation.deriveKeyPair(seedA, 0)
        assertEquals(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFUTCUAZm0ZdA933dq1EN1UEzhc9xi8HUnN8dK3QExGtp" +
                "hagtCKfNxlM7d9Knnr4OR5iulwLnZi64gA96IHcggg==",
            idx0.publicKey,
            "frozen public key (idx 0) drifted"
        )
        assertEquals(
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCB67BE+bp7cCgAwHMOjXiTQ+Z7InJ8YuaqH" +
                "9XX335/GOw==",
            idx0.privateKey,
            "frozen private key (idx 0) drifted"
        )

        val idx7 = E2ESeedDerivation.deriveKeyPair(seedA, 7)
        assertEquals(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENEl5Oc2wPS23Iq7V+BjJ6WNoPLpI4oVHxj3rEFJvIKmV" +
                "wrDy8q5lZzZR6RbGUqwi+lTMu291GaJx0sAJD/MMKQ==",
            idx7.publicKey,
            "frozen public key (idx 7) drifted"
        )
        assertEquals(
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAlM8eZuC7NAt8WLrUc/YRYBqBoddXvlA6x" +
                "JyDIqdN0tQ==",
            idx7.privateKey,
            "frozen private key (idx 7) drifted"
        )
    }

    // ------------------------------------------------------------------
    // 8. Input validation
    // ------------------------------------------------------------------

    @Test
    fun rejects_empty_seed_and_negative_index() {
        try {
            E2ESeedDerivation.deriveKeyPair(ByteArray(0), 0)
            fail("empty ikm must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            E2ESeedDerivation.deriveKeyPair(seedA, -1)
            fail("negative index must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- helpers ---

    /** I2OSP: big-endian, left-zero-padded to [length] bytes. */
    private fun i2osp(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        val start = if (raw.size > length) raw.size - length else 0
        val copyLen = raw.size - start
        System.arraycopy(raw, start, out, length - copyLen, copyLen)
        return out
    }
}
