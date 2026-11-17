package co.electriccoin.zcash.ui.screen.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-KEY-D (W-53; AI-SIGNIN-KEY-SPEC 1.2 d): the AI sign-in key and its proof leave no trace on the phone. The two
 * files write no log line, print nothing and store nothing; what they throw carries a fixed text, never a value;
 * and making the key does not change the seed bytes it was given (their owner wipes them).
 */
class AiSignInKeyLeavesNoTraceTest {
    private val files = listOf("AiSignInKey.kt", "AiSignInProof.kt")

    @Test
    fun the_two_files_write_no_log_print_nothing_and_store_nothing() {
        files.forEach { name ->
            val source = AiSignInApi.read(AiSignInApi.mainAiFile(name))
            listOf("Log.", "Twig", "println", "print(", "SharedPreferences", "File(", "Preferences").forEach { word ->
                assertFalse("$name names $word", word in source)
            }
            assertFalse("$name calls a put…( function", Regex("""\.put\w*\(""").containsMatchIn(source))
        }
    }

    @Test
    fun every_refusal_has_a_fixed_text() {
        files.forEach { name ->
            val source = AiSignInApi.read(AiSignInApi.mainAiFile(name))
            // No string template anywhere in the two files, so no text they throw can carry a value.
            assertFalse("$name holds a string template", '$' in source)
            assertFalse("$name passes on another exception's text", ".message" in source)
            val refusals = Regex("""\b(require|check)\(""").findAll(source).count()
            val withFixedText = Regex("""\b(require|check)\([^\n]*\) \{ "[^"\n]+" \}""").findAll(source).count()
            assertEquals("$name: every require and check has its own fixed text on its line", refusals, withFixedText)
            Regex("""\bthrow \w+\(([^\n]*)\)""").findAll(source).forEach { thrown ->
                assertTrue("$name throws without a fixed text", Regex("""^"[^"]+"$""").matches(thrown.groupValues[1]))
            }
        }
    }

    @Test
    fun derive_does_not_change_the_seed_it_is_given() {
        val seed = AiSignInApi.bytes(SEED_BYTES)

        AiSignInApi.derive(seed)

        assertArrayEquals(AiSignInApi.bytes(SEED_BYTES), seed)
    }

    @Test
    fun an_empty_seed_is_refused_with_a_fixed_text() {
        val refusal = runCatching { AiSignInApi.derive(ByteArray(0)) }.exceptionOrNull()

        assertTrue("an empty seed was not refused: $refusal", refusal is IllegalArgumentException)
    }

    private companion object {
        const val SEED_BYTES = 64
    }
}
