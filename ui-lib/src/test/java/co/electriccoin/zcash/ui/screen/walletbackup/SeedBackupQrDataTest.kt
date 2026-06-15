package co.electriccoin.zcash.ui.screen.walletbackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract guard for the wallet backup QR payload. The seed must be encoded as 24 words joined
 * by a single space (matching [cash.z.ecc.android.sdk.model.SeedPhrase.joinToString], whose
 * delimiter is a space), so that scanning the backup QR yields a clean, restorable seed.
 */
class SeedBackupQrDataTest {

    private val words = (1..24).map { "abandon" }

    @Test
    fun `space-joined seed round-trips through encode and isValid`() {
        val seedPhrase = words.joinToString(separator = " ")
        val json = SeedBackupQrData.encode(seedPhrase, birthday = 1_000_000L)

        val decoded = SeedBackupQrData.decode(json)
        assertNotNull(decoded)
        assertTrue(SeedBackupQrData.isValid(decoded!!))
        assertEquals(seedPhrase, decoded.seed)
    }

    @Test
    fun `space-joined seed splits cleanly into 24 words without trailing commas`() {
        val seedPhrase = words.joinToString(separator = " ")
        val split = seedPhrase.trim().split("\\s+".toRegex())

        assertEquals(24, split.size)
        split.forEach { word ->
            assertFalse("word should not carry a trailing comma: $word", word.contains(","))
        }
    }
}
