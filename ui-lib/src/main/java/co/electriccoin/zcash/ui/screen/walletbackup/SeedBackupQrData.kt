package co.electriccoin.zcash.ui.screen.walletbackup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Data class representing seed backup information to be encoded in a QR code.
 *
 * WARNING: This QR code contains your seed phrase in PLAIN TEXT.
 * Anyone who scans this QR code can access your wallet and steal your funds.
 * Keep this QR code secure and never share it.
 *
 * @param version Schema version for future compatibility
 * @param seed 24 words separated by spaces
 * @param birthday Block height at wallet creation
 */
@Serializable
data class SeedBackupQrData(
    @SerialName("v")
    val version: Int = CURRENT_VERSION,
    @SerialName("seed")
    val seed: String,
    @SerialName("birthday")
    val birthday: Long
) {
    companion object {
        const val CURRENT_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Encode seed backup data to JSON string for QR code.
         */
        fun encode(seedPhrase: String, birthday: Long): String {
            val data = SeedBackupQrData(
                seed = seedPhrase,
                birthday = birthday
            )
            return json.encodeToString(data)
        }

        /**
         * Decode JSON string from QR code to seed backup data.
         * Returns null if parsing fails.
         */
        fun decode(jsonString: String): SeedBackupQrData? {
            return try {
                json.decodeFromString<SeedBackupQrData>(jsonString)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Validate decoded seed backup data.
         * Returns true if the data appears valid.
         */
        fun isValid(data: SeedBackupQrData): Boolean {
            // Check version
            if (data.version < 1) return false

            // Check seed has 24 words
            val words = data.seed.trim().split("\\s+".toRegex())
            if (words.size != 24) return false

            // Check birthday is reasonable (after Zcash launch)
            if (data.birthday < 0) return false

            return true
        }
    }
}
