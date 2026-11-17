package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.screen.ai.AiSignInApi.Independent
import co.electriccoin.zcash.ui.screen.chat.crypto.E2ESeedDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * AI-KEY-D (W-53: "double check that we will not disclose and kill privacy by this way"; AI-SIGNIN-KEY-SPEC 1.2 a).
 *
 * The AI sign-in key is made from the same seed as every other key of the wallet, by a function of its own: its own
 * salt and its own label. It is none of the chat keys and none of the relay keys of that seed, in either half, so the
 * AI server cannot tie it to a chat or relay identity, and it is never a spending key.
 */
class AiSignInKeyIsNoOtherKeyTest {
    private val seeds = listOf(AiSignInApi.bytes(SEED_BYTES), ByteArray(SEED_BYTES) { (it * 7 + 3).toByte() })

    @Test
    fun it_is_none_of_the_chat_keys_of_the_same_seed() {
        seeds.forEach { seed ->
            val ai = AiSignInApi.derive(seed)
            (0 until CHAT_KEYS).forEach { index ->
                val chat = E2ESeedDerivation.deriveKeyPair(seed, index)
                assertNotEquals("chat key $index has the same public half", chat.publicKey, ai.publicKey)
                assertNotEquals("chat key $index has the same private half", chat.privateKey, ai.privateKey)
            }
        }
    }

    @Test
    fun it_is_none_of_the_relay_keys_of_the_same_seed() {
        seeds.forEach { seed ->
            val ai = AiSignInApi.derive(seed)
            val x = AiSignInApi.xOf(ai.publicKey)
            val d = AiSignInApi.privateNumber(ai.privateKey)
            (0 until RELAY_KEYS).forEach { index ->
                val relay = NOSTRIdentity.fromSeed(seed, index)
                assertFalse("relay key $index has the same X coordinate", relay.publicKey.contentEquals(x))
                assertNotEquals("relay key $index has the same private number", BigInteger(1, relay.privateKey), d)
            }
        }
    }

    @Test
    fun the_chat_keys_salt_or_label_gives_another_key() {
        seeds.forEach { seed ->
            val d = AiSignInApi.privateNumber(AiSignInApi.derive(seed).privateKey)

            assertEquals("the key is not the one of its own salt and label", Independent.privateNumber(seed), d)
            assertNotEquals(Independent.privateNumber(seed, salt = CHAT_SALT), d)
            assertNotEquals(Independent.privateNumber(seed, label = CHAT_LABEL), d)
            assertNotEquals(Independent.privateNumber(seed, salt = CHAT_SALT, label = CHAT_LABEL), d)
        }
    }

    @Test
    fun the_two_literals_stand_in_one_file_only() {
        AiSignInApi.read(AiSignInApi.mainAiFile("AiSignInKey.kt"))
        val holders =
            AiSignInApi
                .productSources()
                .filter { file -> file.readText().let { Independent.SALT in it || Independent.LABEL in it } }
                .map { it.name }

        assertEquals(listOf("AiSignInKey.kt"), holders)
    }

    @Test
    fun the_new_files_name_no_spending_key_and_no_relay_class() {
        listOf("AiSignInKey.kt", "AiSignInProof.kt").forEach { name ->
            val source = AiSignInApi.read(AiSignInApi.mainAiFile(name))
            listOf("DerivationTool", "UnifiedSpendingKey", "Secp256k1", "NOSTRIdentity").forEach { word ->
                assertFalse("$name names $word", word in source)
            }
        }
    }

    @Test
    fun a_comment_beside_the_label_says_what_must_never_happen_to_it() {
        val source = AiSignInApi.read(AiSignInApi.mainAiFile("AiSignInKey.kt"))
        val beside = source.substringBefore("\"${Independent.LABEL}\"").takeLast(COMMENT_REACH).lowercase()

        assertTrue("no word that another service needs another label", "another service" in beside)
        assertTrue("no word that another service needs another label", "another label" in beside)
        assertTrue(
            "no word that a later version keeps deriving and comparing every earlier one",
            "later version" in beside && "every earlier" in beside,
        )
    }

    private companion object {
        const val SEED_BYTES = 64
        const val CHAT_KEYS = 64
        const val RELAY_KEYS = 16
        const val COMMENT_REACH = 1_200

        // The chat keys' own constants (E2ESeedDerivation), written out: this test must not read them from the code.
        const val CHAT_SALT = "ZCHAT_E2E_SEED"
        const val CHAT_LABEL = "zchat-e2e-identity-v3"
    }
}
