package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.sdk.fixture.SeedPhraseFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ValidateSeedUseCase] — the seed-import gate. It must cleanly distinguish a
 * genuinely valid mnemonic from the three distinct failure modes (bad checksum, unknown word, wrong
 * word count) so the UI can show the right error, and it must ONLY return a [SeedPhrase] for a valid
 * phrase (never for a phrase that merely "looks" right). BIP39 validation is deterministic, so this
 * runs entirely off-device (kotlin-bip39 is a plain JVM dependency).
 */
class ValidateSeedUseCaseTest {

    private val useCase = ValidateSeedUseCase()

    // A known-good 24-word mnemonic (shared with the SDK fixtures).
    private val validWords: List<String> = SeedPhraseFixture.SEED_PHRASE.split(" ")

    @Test
    fun `valid mnemonic validates as Valid and carries a SeedPhrase`() {
        val result = useCase.validate(validWords)
        assertTrue("expected Valid, got $result", result is SeedValidationResult.Valid)
        assertNotNull((result as SeedValidationResult.Valid).seedPhrase)
    }

    @Test
    fun `invoke returns a SeedPhrase only for a valid mnemonic`() {
        assertNotNull(useCase(validWords))
    }

    @Test
    fun `invoke returns null for every invalid mnemonic`() {
        assertNull(useCase(badChecksumWords()))
        assertNull(useCase(badWordWords()))
        assertNull(useCase(wrongCountWords()))
    }

    @Test
    fun `a valid-word mnemonic with a broken checksum is InvalidChecksum`() {
        // Swap the last (checksum-bearing) word for a DIFFERENT valid BIP39 word: all words are in the
        // wordlist, the count is right, but the embedded checksum no longer matches.
        val result = useCase.validate(badChecksumWords())
        assertEquals(SeedValidationResult.InvalidChecksum, result)
    }

    @Test
    fun `a mnemonic containing a non-wordlist token is InvalidWords`() {
        val result = useCase.validate(badWordWords())
        assertEquals(SeedValidationResult.InvalidWords, result)
    }

    @Test
    fun `a mnemonic with the wrong word count is InvalidFormat`() {
        val result = useCase.validate(wrongCountWords())
        assertEquals(SeedValidationResult.InvalidFormat, result)
    }

    @Test
    fun `an empty word list is InvalidFormat, not a crash`() {
        val result = useCase.validate(emptyList())
        assertEquals(SeedValidationResult.InvalidFormat, result)
    }

    @Test
    fun `surrounding whitespace on each word is trimmed before validation`() {
        val padded = validWords.map { "  $it  " }
        assertTrue(useCase.validate(padded) is SeedValidationResult.Valid)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** Valid words, right count, but the final checksum word replaced by another valid word. */
    private fun badChecksumWords(): List<String> {
        val words = validWords.toMutableList()
        val last = words.last()
        // "abandon" is the first BIP39 word; picking a different-but-valid word breaks the checksum.
        words[words.lastIndex] = if (last == "abandon") "ability" else "abandon"
        return words
    }

    /** Right count, but one token is not in the BIP39 wordlist. */
    private fun badWordWords(): List<String> {
        val words = validWords.toMutableList()
        words[3] = "zzzzzz" // not a BIP39 word
        return words
    }

    /** A phrase whose length is not a permitted BIP39 word count (12/15/18/21/24). */
    private fun wrongCountWords(): List<String> = listOf("abandon", "abandon", "abandon", "abandon", "abandon")
}
