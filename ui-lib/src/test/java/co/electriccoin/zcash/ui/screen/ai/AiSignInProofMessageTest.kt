package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

/**
 * AI-KEY-D (W-53; AI-SIGNIN-KEY-SPEC 2.2 and 2.3): the one message the AI sign-in key signs.
 *
 * Nine lines, UTF-8, each ended by one line feed. The server never reads a message from a request: it builds the
 * same nine lines from its own values and checks the signature, so one different byte is a refused sign-in.
 *
 * The two fixed vectors were made with OpenSSL by the design lane for the key V2 (the seed bytes 00..3f). THE SAME
 * `register` VECTOR STANDS IN THE SERVER'S SUITE (work order AI-SIGNIN-KEY-SERVER-CHANGE section 7:
 * apps/backend/src/testing/aiSignInKeyVectors.ts, REGISTER_VECTOR, and SIGNIN_VECTOR beside it), with the same public
 * key, so the two halves cannot drift apart unnoticed. Every 64-hex value is computed here, never written down.
 */
class AiSignInProofMessageTest {
    private val keyId = AiSignInApi.sha256Hex(AiSignInApi.der(AiSignInApi.V2_PUBLIC))
    private val nonceA = "A".repeat(NONCE_CHARS)
    private val nonceB = "B".repeat(NONCE_CHARS)
    private val testWallet = AiSignInApi.sha256Hex("zchat-test-address".toByteArray(Charsets.US_ASCII))

    private fun signinMessage() = AiSignInApi.message("signin", nonceA, EXP, keyId, create = true)

    private fun registerMessage() =
        AiSignInApi.message("register", nonceB, EXP, keyId, account = TEST_ACCOUNT, wallet = testWallet)

    @Test
    fun the_signin_vector_byte_for_byte() {
        val expected =
            "ZCHAT-AI-AUTH-V1\n" +
                "aud:api.zsend.xyz\n" +
                "purpose:signin\n" +
                "nonce:$nonceA\n" +
                "exp:1791800000\n" +
                "key:$keyId\n" +
                "account:-\n" +
                "wallet:-\n" +
                "create:1\n"

        assertEquals(expected, signinMessage())
        assertEquals(SIGNIN_BYTES, signinMessage().toByteArray(Charsets.UTF_8).size)
        assertTrue(
            "the design's signature does not verify the app's message",
            E2EEncryption.verify(AiSignInApi.V2_PUBLIC, signinMessage(), SIGNIN_SIGNATURE),
        )
    }

    @Test
    fun the_register_vector_shared_with_the_server_byte_for_byte() {
        val expected =
            "ZCHAT-AI-AUTH-V1\n" +
                "aud:api.zsend.xyz\n" +
                "purpose:register\n" +
                "nonce:$nonceB\n" +
                "exp:1791800000\n" +
                "key:$keyId\n" +
                "account:acct_TEST-0001\n" +
                "wallet:$testWallet\n" +
                "create:0\n"

        assertEquals(expected, registerMessage())
        assertEquals(REGISTER_BYTES, registerMessage().toByteArray(Charsets.UTF_8).size)
        assertTrue(
            "the signature the server's suite pins does not verify the app's message",
            E2EEncryption.verify(AiSignInApi.V2_PUBLIC, registerMessage(), REGISTER_SIGNATURE),
        )
    }

    @Test
    fun neither_signature_verifies_the_other_message() {
        assertFalse(E2EEncryption.verify(AiSignInApi.V2_PUBLIC, registerMessage(), SIGNIN_SIGNATURE))
        assertFalse(E2EEncryption.verify(AiSignInApi.V2_PUBLIC, signinMessage(), REGISTER_SIGNATURE))
        assertFalse(E2EEncryption.verify(AiSignInApi.V1_PUBLIC, signinMessage(), SIGNIN_SIGNATURE))
    }

    @Test
    fun control_the_vectors_belong_to_the_public_key_v2() {
        // Passes before the message builder exists: the nine lines written by hand, and the JDK's own check.
        val byHand =
            "ZCHAT-AI-AUTH-V1\naud:api.zsend.xyz\npurpose:signin\nnonce:$nonceA\nexp:1791800000\nkey:$keyId\n" +
                "account:-\nwallet:-\ncreate:1\n"

        assertEquals(SIGNIN_BYTES, byHand.length)
        assertTrue(E2EEncryption.verify(AiSignInApi.V2_PUBLIC, byHand, SIGNIN_SIGNATURE))
    }

    @Test
    fun nine_lines_in_their_order_each_ended_by_one_line_feed() {
        val parts = registerMessage().split("\n")

        assertEquals(LINES + 1, parts.size)
        assertEquals("", parts.last())
        assertEquals("ZCHAT-AI-AUTH-V1", parts.first())
        assertEquals(
            listOf("aud", "purpose", "nonce", "exp", "key", "account", "wallet", "create"),
            parts.subList(1, LINES).map { it.substringBefore(':') },
        )
    }

    @Test
    fun the_service_name_is_the_host_of_the_default_address() {
        val host = URI(AiApiClient.DEFAULT_BASE_URL).host

        assertEquals(host, AiSignInApi.serviceName())
        assertEquals("aud:$host", signinMessage().split("\n")[1])
    }

    @Test
    fun the_purposes_are_register_signin_and_claim_and_rotate_is_refused() {
        listOf("register", "signin", "claim").forEach { purpose ->
            assertEquals("purpose:$purpose", AiSignInApi.message(purpose, nonceA, EXP, keyId).split("\n")[2])
        }
        listOf("rotate", "Signin", "", "login").forEach { purpose ->
            refused("the purpose '$purpose'") { AiSignInApi.message(purpose, nonceA, EXP, keyId) }
        }
    }

    @Test
    fun a_value_outside_its_form_is_refused() {
        refused("an expiry of 9 digits") { AiSignInApi.message("signin", nonceA, EXP_9_DIGITS, keyId) }
        refused("an expiry of 11 digits") { AiSignInApi.message("signin", nonceA, EXP_11_DIGITS, keyId) }
        refused("a negative expiry") { AiSignInApi.message("signin", nonceA, -EXP, keyId) }
        refused("a one-time number of 42 characters") { AiSignInApi.message("signin", "A".repeat(NONCE_CHARS - 1), EXP, keyId) }
        refused("a one-time number of 44 characters") { AiSignInApi.message("signin", "A".repeat(NONCE_CHARS + 1), EXP, keyId) }
        refused("a one-time number with a + sign") { AiSignInApi.message("signin", "+" + "A".repeat(NONCE_CHARS - 1), EXP, keyId) }
        refused("an upper-case key id") { AiSignInApi.message("signin", nonceA, EXP, keyId.uppercase()) }
        refused("a key id of 63 characters") { AiSignInApi.message("signin", nonceA, EXP, keyId.drop(1)) }
        listOf("", "a b", "a".repeat(ACCOUNT_CHARS + 1), "acct/1", "acct:1").forEach { account ->
            refused("the account id '$account'") {
                AiSignInApi.message("register", nonceA, EXP, keyId, account = account)
            }
        }
        listOf("TICKET", testWallet.uppercase(), testWallet.drop(1), "", "none").forEach { wallet ->
            refused("the wallet line '$wallet'") {
                AiSignInApi.message("register", nonceA, EXP, keyId, account = TEST_ACCOUNT, wallet = wallet)
            }
        }
    }

    @Test
    fun the_wallet_line_is_a_64_hex_value_a_dash_or_the_word_ticket() {
        listOf(testWallet, "-", "ticket").forEach { wallet ->
            val message = AiSignInApi.message("register", nonceA, EXP, keyId, account = TEST_ACCOUNT, wallet = wallet)
            assertEquals("wallet:$wallet", message.split("\n")[WALLET_LINE])
        }
    }

    @Test
    fun a_line_feed_in_any_field_is_refused() {
        listOf("\n", "\r").forEach { feed ->
            refused("a line feed in the purpose") { AiSignInApi.message("signin$feed", nonceA, EXP, keyId) }
            refused("a line feed in the one-time number") {
                AiSignInApi.message("signin", nonceA.dropLast(1) + feed, EXP, keyId)
            }
            refused("a line feed in the key id") { AiSignInApi.message("signin", nonceA, EXP, keyId.dropLast(1) + feed) }
            refused("a line feed in the account id") {
                AiSignInApi.message("register", nonceA, EXP, keyId, account = "acct${feed}create:1")
            }
            refused("a line feed in the wallet line") {
                AiSignInApi.message("register", nonceA, EXP, keyId, account = TEST_ACCOUNT, wallet = "-$feed")
            }
        }
    }

    @Test
    fun the_signing_function_takes_the_fields_and_signs_exactly_the_message() {
        val key = AiSignInApi.derive(AiSignInApi.bytes(V2_SEED_BYTES))
        val signature =
            AiSignInApi.sign(key.privateKey, "register", nonceB, EXP, keyId, account = TEST_ACCOUNT, wallet = testWallet)

        assertTrue(E2EEncryption.verify(AiSignInApi.V2_PUBLIC, registerMessage(), signature))
        assertFalse(E2EEncryption.verify(AiSignInApi.V2_PUBLIC, signinMessage(), signature))
        refused("signing for the purpose 'rotate'") { AiSignInApi.sign(key.privateKey, "rotate", nonceB, EXP, keyId) }
    }

    @Test
    fun there_is_one_signing_function_for_this_key() {
        val proof = AiSignInApi.read(AiSignInApi.mainAiFile("AiSignInProof.kt"))

        assertEquals("AiSignInProof.kt signs in one place", 1, Regex.fromLiteral("E2EEncryption.sign(").findAll(proof).count())
        assertEquals("AiSignInProof.kt builds the message for its one signature", 1, Regex("""\bfun sign\(""").findAll(proof).count())
        val others =
            AiSignInApi
                .productSources()
                .filter { it.name != "AiSignInProof.kt" }
                .filter { file -> file.readText().let { "AiSignInKey" in it && "E2EEncryption.sign(" in it } }
                .map { it.name }
        assertEquals("another file passes the AI sign-in key to E2EEncryption.sign", emptyList<String>(), others)
    }

    private fun refused(
        what: String,
        build: () -> String,
    ) {
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            assertFalse("the refusal of $what quotes a value", expected.message.orEmpty().contains(nonceA))
            return
        }
        fail("$what was not refused")
    }

    private companion object {
        const val V2_SEED_BYTES = 64
        const val EXP = 1_791_800_000L
        const val EXP_9_DIGITS = 999_999_999L
        const val EXP_11_DIGITS = 10_000_000_000L
        const val SIGNIN_BYTES = 212
        const val REGISTER_BYTES = 290
        const val LINES = 9
        const val ACCOUNT_CHARS = 64
        const val NONCE_CHARS = 43
        const val WALLET_LINE = 7
        const val TEST_ACCOUNT = "acct_TEST-0001"
        const val SIGNIN_SIGNATURE =
            "MEUCIC1Md3oHPOb0Bd4/OQzaIESXqrM6ZEbKpc9GUYU1yRPwAiEAwaxWE4+fdyX1jR8AYzKUD3Qzy8s4VEnbE8b2/ErNAyc="
        const val REGISTER_SIGNATURE =
            "MEUCIBeKSVkj70NNvuvJDfvQSUC6g2wKjMlu2zp2kCxOIqzHAiEAmAq2YgT4VIokB8HlLJtawiwL7Wjo0Ri0YB6n9ubrJlo="
    }
}
