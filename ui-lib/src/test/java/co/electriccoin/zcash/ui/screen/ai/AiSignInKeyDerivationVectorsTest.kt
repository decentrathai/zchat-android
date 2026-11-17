package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.ai.AiSignInApi.Independent
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * AI-KEY-D (W-53; AI-SIGNIN-KEY-SPEC 2.1 and 2.2): the AI sign-in key made from the recovery phrase's seed.
 *
 *   okm = HKDF-SHA256(ikm = seed, salt = "ZCHAT_AI_SIGNIN_SEED", info = "zchat-ai-signin-v1:zsend.xyz", 48 bytes)
 *   d   = (OS2IP(okm) mod (n - 1)) + 1 on P-256;  the public half as the 91-byte SubjectPublicKeyInfo, in Base64
 *   key id = lower-case hex of the SHA-256 of those 91 bytes
 *
 * The public halves below are the design's vectors V1 and V2, which the server's suite pins too. The private halves
 * were computed INDEPENDENTLY (Python hmac and hashlib, and a P-256 multiplication written for the purpose) from the
 * formula, and this class makes them a third time with the JDK alone. They are FROZEN FOR EVER: a user's AI account
 * and its paid credit are found by this key and by nothing else. If a case here ever fails, the derivation drifted.
 * NEVER update these values; a change needs a new label, and the app must keep deriving and comparing every
 * earlier one. Both seeds are public test data.
 */
class AiSignInKeyDerivationVectorsTest {
    @Test
    fun frozen_vector_v1() {
        val key = AiSignInApi.derive(AiSignInApi.bytes(V1_SEED_BYTES))

        assertEquals("frozen public half (V1) drifted", AiSignInApi.V1_PUBLIC, key.publicKey)
        assertEquals("frozen private half (V1) drifted", V1_PRIVATE, key.privateKey)
        assertEquals(AiSignInApi.sha256Hex(AiSignInApi.der(AiSignInApi.V1_PUBLIC)), AiSignInApi.keyId(key.publicKey))
    }

    @Test
    fun frozen_vector_v2() {
        val key = AiSignInApi.derive(AiSignInApi.bytes(V2_SEED_BYTES))

        assertEquals("frozen public half (V2) drifted", AiSignInApi.V2_PUBLIC, key.publicKey)
        assertEquals("frozen private half (V2) drifted", V2_PRIVATE, key.privateKey)
        val keyId = AiSignInApi.keyId(key.publicKey)
        assertEquals(AiSignInApi.sha256Hex(AiSignInApi.der(AiSignInApi.V2_PUBLIC)), keyId)
        assertTrue("the key id is not 64 lower-case hex characters", Regex("[0-9a-f]{64}").matches(keyId))
    }

    /** A control: passes before the key exists. It proves the pinned values, not the app's code. */
    @Test
    fun control_the_frozen_vectors_follow_from_the_formula_with_the_jdk_alone() {
        listOf(
            Triple(V1_SEED_BYTES, AiSignInApi.V1_PUBLIC, V1_PRIVATE),
            Triple(V2_SEED_BYTES, AiSignInApi.V2_PUBLIC, V2_PRIVATE),
        ).forEach { (seedBytes, publicHalf, privateHalf) ->
            val d = Independent.privateNumber(AiSignInApi.bytes(seedBytes))
            assertEquals("the pinned private half is not the formula's number", d, AiSignInApi.privateNumber(privateHalf))

            // d belongs to the pinned public half: what d signs, the pinned public half verifies.
            val factory = KeyFactory.getInstance("EC")
            val message = "control message $seedBytes".toByteArray()
            val signature =
                Signature.getInstance(ECDSA).run {
                    initSign(factory.generatePrivate(ECPrivateKeySpec(d, Independent.p256)))
                    update(message)
                    sign()
                }
            val verifies =
                Signature.getInstance(ECDSA).run {
                    initVerify(factory.generatePublic(X509EncodedKeySpec(AiSignInApi.der(publicHalf))))
                    update(message)
                    verify(signature)
                }
            assertTrue("the pinned public half does not belong to the formula's number", verifies)
        }
    }

    @Test
    fun same_seed_same_key() {
        val seed = AiSignInApi.bytes(V2_SEED_BYTES)

        // No assertEquals: the halves are a data class, and its failure message would print both private halves.
        assertTrue("one seed gave two keys", AiSignInApi.derive(seed) == AiSignInApi.derive(seed.copyOf()))
    }

    @Test
    fun another_seed_another_key() {
        val one = AiSignInApi.derive(AiSignInApi.bytes(V2_SEED_BYTES))
        val other = AiSignInApi.derive(AiSignInApi.bytes(V2_SEED_BYTES).also { it[0] = 1 })

        assertNotEquals(one.publicKey, other.publicKey)
        assertNotEquals(one.privateKey, other.privateKey)
    }

    @Test
    fun public_key_is_a_91_byte_p256_spki() {
        val der = AiSignInApi.der(AiSignInApi.derive(AiSignInApi.bytes(V2_SEED_BYTES)).publicKey)

        assertEquals(AiSignInApi.SPKI_BYTES, der.size)
        // The same 26 bytes the server asks for, and the uncompressed-point mark after them.
        assertArrayEquals(
            AiSignInApi.der(AiSignInApi.V1_PUBLIC).copyOf(AiSignInApi.SPKI_HEADER_BYTES),
            der.copyOf(AiSignInApi.SPKI_HEADER_BYTES),
        )
        assertEquals(UNCOMPRESSED_POINT, der[AiSignInApi.SPKI_HEADER_BYTES])
        val loaded = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
        assertEquals(Independent.p256.order, loaded.params.order)
        assertEquals(Independent.p256.generator, loaded.params.generator)
    }

    @Test
    fun private_half_signs_what_the_public_half_verifies() {
        val key = AiSignInApi.derive(AiSignInApi.bytes(V2_SEED_BYTES))
        val signature = E2EEncryption.sign(key.privateKey, "a message")

        assertTrue(E2EEncryption.verify(key.publicKey, "a message", signature))
        assertFalse(E2EEncryption.verify(key.publicKey, "another message", signature))
        assertFalse(E2EEncryption.verify(AiSignInApi.V1_PUBLIC, "a message", signature))
    }

    @Test
    fun the_label_is_frozen() {
        val source = AiSignInApi.read(AiSignInApi.mainAiFile("AiSignInKey.kt"))

        assertEquals("the salt literal", 1, Regex.fromLiteral("\"${Independent.SALT}\"").findAll(source).count())
        assertEquals("the label literal", 1, Regex.fromLiteral("\"${Independent.LABEL}\"").findAll(source).count())
        // The key the app makes is the key of exactly these two literals.
        val seed = AiSignInApi.bytes(V2_SEED_BYTES)
        assertEquals(
            Independent.privateNumber(seed),
            AiSignInApi.privateNumber(AiSignInApi.derive(seed).privateKey),
        )
    }

    private companion object {
        const val V1_SEED_BYTES = 32
        const val V2_SEED_BYTES = 64
        const val UNCOMPRESSED_POINT: Byte = 0x04
        const val ECDSA = "SHA256withECDSA"

        // PKCS#8 of test data (the seeds 00..1f and 00..3f); see the class comment before touching them.
        const val V1_PRIVATE =
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCFh6Wy48jtLjhrdP0jbmu6ZnLFDiH+tLBaEOPx4ydN+w=="
        const val V2_PRIVATE =
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAb+8cpFcb2mperRuFqldp+uhbdqI5L071MO0h2ZJZADg=="
    }
}
