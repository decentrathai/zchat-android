package co.electriccoin.zcash.ui.screen.chat.crypto

import androidx.annotation.VisibleForTesting
import java.math.BigInteger
import java.security.spec.ECPoint
import java.util.Base64

/**
 * Stage 1 of D1-recovery: pure, deterministic derivation of a per-peer P-256 (secp256r1)
 * E2E identity keypair from the wallet seed, so that a seed restore can re-derive the exact
 * private keys and recover on-chain chat history (see seed-recoverable-e2e-design.md §2).
 *
 * FROZEN CONSTRUCTION — never change any constant below without introducing a new versioned
 * info string treated as a NEW key generation (a silent change would permanently desync the
 * ratchet root convergence after restore and destroy users' history):
 *
 *   okm = HKDF-SHA256(ikm = walletSeed,
 *                     salt = "ZCHAT_E2E_SEED",
 *                     info = "zchat-e2e-identity-v3" || LE32(index),
 *                     length = 48)
 *   d   = (OS2IP(okm) mod (n - 1)) + 1     // FIPS 186-5 style 48-byte wide reduction:
 *                                          // always in [1, n-1], no rejection loop, no bias
 *   Q   = d * G                            // pure-Kotlin Jacobian point multiplication
 *                                          // (JCA cannot deterministically generate EC keys
 *                                          //  portably and BouncyCastle is not a dependency)
 *
 * Output format is byte-for-byte the same canonical encoding [E2EEncryption.generateKeyPair]
 * emits (E2EEncryption.kt:143-144): public = Base64(std, padded) of the X.509 SPKI DER for a
 * named-curve P-256 key with an uncompressed point (91 bytes); private = Base64(std, padded)
 * of a PKCS#8 DER (minimal RFC 5915 ECPrivateKey, 67 bytes). This matters because pubkey
 * Base64 strings are compared byte-for-byte across the protocol (D7 txid fold-in, isLower,
 * key-change guards). The private key is only ever parsed via PKCS8EncodedKeySpec locally.
 *
 * NOT wired into key generation yet — Stage 1 is an isolated pure module (wiring is Stage 3).
 * Non-constant-time BigInteger math is an accepted trade-off per the design (runs rarely,
 * locally, on an identity key whose IKM — the seed — already gates total compromise).
 */
object E2ESeedDerivation {

    // --- secp256r1 domain parameters (SEC 2 v2.0 §2.4.2 / FIPS 186-4 D.1.2.3) ---
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = P.subtract(BigInteger.valueOf(3)) // a = -3 mod p
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)

    // --- Frozen derivation constants (see class doc; changing these destroys recovery) ---
    private val HKDF_SALT = "ZCHAT_E2E_SEED".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_PREFIX = "zchat-e2e-identity-v3".toByteArray(Charsets.UTF_8)
    private const val OKM_LENGTH = 48
    private const val FIELD_BYTES = 32

    /**
     * X.509 SubjectPublicKeyInfo header for a named-curve P-256 public key, followed by the
     * 65-byte uncompressed point (0x04 || X || Y). Byte-identical to what JCA providers
     * (SunEC, Conscrypt) emit for [E2EEncryption.generateKeyPair]:
     *   SEQUENCE(89) { SEQUENCE(19) { OID 1.2.840.10045.2.1 (ecPublicKey),
     *                                 OID 1.2.840.10045.3.1.7 (prime256v1) },
     *                  BIT STRING(66, 0 unused) }
     */
    private val SPKI_HEADER = byteArrayOf(
        0x30, 0x59, 0x30, 0x13,
        0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        0x03, 0x42, 0x00
    )

    /**
     * PKCS#8 PrivateKeyInfo header for a minimal RFC 5915 ECPrivateKey (version 1, no
     * embedded public key, named curve prime256v1), followed by the 32-byte scalar:
     *   SEQUENCE(65) { INTEGER 0, SEQUENCE(19){ OID ecPublicKey, OID prime256v1 },
     *                  OCTET STRING(39) { SEQUENCE(37) { INTEGER 1, OCTET STRING(32) } } }
     * Parses via PKCS8EncodedKeySpec on every JCA provider (the exact production consume
     * path in E2EEncryption.deriveSharedSecret/sign).
     */
    private val PKCS8_HEADER = byteArrayOf(
        0x30, 0x41, 0x02, 0x01, 0x00,
        0x30, 0x13,
        0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        0x04, 0x27, 0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20
    )

    /**
     * Derive the deterministic P-256 E2E keypair for ([ikm], [index]).
     * Same inputs always produce the byte-identical [E2EKeyPair]; different seed or index
     * produce an unrelated keypair.
     *
     * @param ikm input keying material (the wallet seed bytes; caller owns zeroization)
     * @param index monotonic per-account peer index (>= 0)
     */
    fun deriveKeyPair(
        ikm: ByteArray,
        index: Int,
    ): E2EKeyPair = keyPairForScalar(deriveScalar(ikm, index))

    /**
     * The keypair of the private number [d] in the canonical encoding of the class doc. Shared by the chat keys
     * ([deriveKeyPair]) and the AI sign-in key (AiSignInKey, AI-KEY-D), which reaches [d] by its own salt and label.
     * No constant, header or formula of the chat keys is touched by that second caller.
     */
    internal fun keyPairForScalar(d: BigInteger): E2EKeyPair {
        val q = multiplyBasePoint(d)
        check(isOnCurve(q.affineX, q.affineY)) {
            "Derived public point is not on P-256 — point multiplication is broken"
        }
        val publicDer = SPKI_HEADER + byteArrayOf(0x04) + i2osp(q.affineX) + i2osp(q.affineY)
        val privateDer = PKCS8_HEADER + i2osp(d)
        return E2EKeyPair(
            publicKey = Base64.getEncoder().encodeToString(publicDer),
            privateKey = Base64.getEncoder().encodeToString(privateDer)
        )
    }

    /**
     * HKDF-SHA256 expand to 48 bytes with domain separation, then wide-reduce into a valid
     * P-256 scalar. Result is always in [1, n-1] (see [reduceToScalar]).
     */
    @VisibleForTesting
    fun deriveScalar(ikm: ByteArray, index: Int): BigInteger {
        require(ikm.isNotEmpty()) { "ikm must not be empty" }
        require(index >= 0) { "index must be non-negative" }
        val okm = HKDF.deriveKey(
            ikm = ikm,
            salt = HKDF_SALT,
            info = HKDF_INFO_PREFIX + le32(index),
            length = OKM_LENGTH
        )
        return reduceToScalar(okm)
    }

    /**
     * d = (OS2IP(okm) mod (n-1)) + 1. With a 48-byte (384-bit) candidate the modulo bias
     * against the 256-bit n is < 2^-128 (FIPS 186-5 A.2.1 "extra random bits" method), and
     * the +1 shift makes 0 unreachable — every input maps into [1, n-1], no rejection loop.
     */
    @VisibleForTesting
    fun reduceToScalar(okm: ByteArray): BigInteger =
        BigInteger(1, okm).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE)

    /**
     * Q = k * G on secp256r1 via left-to-right double-and-add over Jacobian coordinates.
     * Correctness is pinned by the published NIST k*G vectors in E2ESeedDerivationTest.
     * Not constant-time (accepted, see class doc).
     */
    @VisibleForTesting
    fun multiplyBasePoint(k: BigInteger): ECPoint {
        require(k >= BigInteger.ONE && k < N) { "scalar must be in [1, n-1]" }
        val g = JacobianPoint(GX, GY, BigInteger.ONE)
        var result = JacobianPoint.INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = result.double()
            if (k.testBit(i)) {
                result = result.add(g)
            }
        }
        return result.toAffine()
    }

    /** y^2 == x^3 + ax + b (mod p) */
    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean =
        y.multiply(y).mod(P) ==
            x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P)

    /** I2OSP: fixed-length (32-byte) big-endian encoding of a field element / scalar. */
    private fun i2osp(value: BigInteger): ByteArray {
        val raw = value.toByteArray() // may carry a leading sign byte or be short
        val out = ByteArray(FIELD_BYTES)
        val start = if (raw.size > FIELD_BYTES) raw.size - FIELD_BYTES else 0
        val copyLen = raw.size - start
        System.arraycopy(raw, start, out, FIELD_BYTES - copyLen, copyLen)
        return out
    }

    /** 32-bit little-endian encoding of the peer index (frozen wire order). */
    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    /**
     * P-256 point in Jacobian projective coordinates (x = X/Z^2, y = Y/Z^3); Z == 0 is the
     * point at infinity. Formulas: doubling "dbl-2001-b" (exploits a = -3) and the standard
     * Jacobian addition, both from the EFD (hyperelliptic.org/EFD) / Guide to ECC alg 3.21-3.22.
     */
    private class JacobianPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger) {

        val isInfinity: Boolean get() = z.signum() == 0

        fun double(): JacobianPoint {
            if (isInfinity || y.signum() == 0) return INFINITY
            val delta = z.multiply(z).mod(P)                                   // Z1^2
            val gamma = y.multiply(y).mod(P)                                   // Y1^2
            val beta = x.multiply(gamma).mod(P)                                // X1*Y1^2
            val alpha = BigInteger.valueOf(3)
                .multiply(x.subtract(delta)).multiply(x.add(delta)).mod(P)     // 3(X1-Z1^2)(X1+Z1^2)
            val x3 = alpha.multiply(alpha).subtract(beta.shiftLeft(3)).mod(P)  // alpha^2 - 8*beta
            val z3 = y.add(z).multiply(y.add(z)).subtract(gamma).subtract(delta).mod(P)
            val y3 = alpha.multiply(beta.shiftLeft(2).subtract(x3))
                .subtract(gamma.multiply(gamma).shiftLeft(3)).mod(P)           // alpha(4*beta-X3) - 8*gamma^2
            return JacobianPoint(x3, y3, z3)
        }

        fun add(other: JacobianPoint): JacobianPoint {
            if (isInfinity) return other
            if (other.isInfinity) return this
            val z1z1 = z.multiply(z).mod(P)
            val z2z2 = other.z.multiply(other.z).mod(P)
            val u1 = x.multiply(z2z2).mod(P)
            val u2 = other.x.multiply(z1z1).mod(P)
            val s1 = y.multiply(other.z).multiply(z2z2).mod(P)
            val s2 = other.y.multiply(z).multiply(z1z1).mod(P)
            if (u1 == u2) {
                return if (s1 == s2) double() else INFINITY // P + (-P) = O
            }
            val h = u2.subtract(u1).mod(P)
            val r = s2.subtract(s1).mod(P)
            val h2 = h.multiply(h).mod(P)
            val h3 = h2.multiply(h).mod(P)
            val u1h2 = u1.multiply(h2).mod(P)
            val x3 = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P)
            val y3 = r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)).mod(P)
            val z3 = h.multiply(z).multiply(other.z).mod(P)
            return JacobianPoint(x3, y3, z3)
        }

        fun toAffine(): ECPoint {
            check(!isInfinity) { "point at infinity has no affine representation" }
            val zInv = z.modInverse(P)
            val zInv2 = zInv.multiply(zInv).mod(P)
            return ECPoint(
                x.multiply(zInv2).mod(P),
                y.multiply(zInv2).multiply(zInv).mod(P)
            )
        }

        companion object {
            val INFINITY = JacobianPoint(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        }
    }
}
