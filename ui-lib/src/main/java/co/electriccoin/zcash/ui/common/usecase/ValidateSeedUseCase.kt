package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase

class ValidateSeedUseCase {
    @Suppress("TooGenericExceptionCaught")
    operator fun invoke(words: List<String>): SeedPhrase? =
        when (val result = validate(words)) {
            is SeedValidationResult.Valid -> result.seedPhrase
            else -> null
        }

    /**
     * Validates [words] and preserves the failure type so callers can distinguish a wrong
     * checksum from an unrecognized word or an incorrect word count.
     */
    @Suppress("TooGenericExceptionCaught")
    fun validate(words: List<String>): SeedValidationResult =
        try {
            val seed = words.joinToString(" ") { it.trim() }
            Mnemonics.MnemonicCode(seed).validate()
            SeedValidationResult.Valid(SeedPhrase.new(seed))
        } catch (_: Mnemonics.InvalidWordException) {
            SeedValidationResult.InvalidWords
        } catch (_: Mnemonics.ChecksumException) {
            SeedValidationResult.InvalidChecksum
        } catch (_: Mnemonics.WordCountException) {
            SeedValidationResult.InvalidFormat
        } catch (_: Exception) {
            SeedValidationResult.InvalidFormat
        }
}

sealed interface SeedValidationResult {
    data class Valid(val seedPhrase: SeedPhrase) : SeedValidationResult

    data object InvalidChecksum : SeedValidationResult

    data object InvalidWords : SeedValidationResult

    data object InvalidFormat : SeedValidationResult
}
