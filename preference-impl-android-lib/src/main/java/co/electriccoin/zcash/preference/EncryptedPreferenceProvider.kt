package co.electriccoin.zcash.preference

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.preference.api.PreferenceProvider

/**
 * The encrypted preferences that hold the wallet (its recovery phrase). [filename] and [open] exist so a JVM test
 * can open a stand-in store and make opening it fail like a phone-security (Keystore) error.
 */
class EncryptedPreferenceProvider(
    private val context: Context,
    private val filename: String = ENCRYPTED_PREFERENCES_FILENAME,
    private val open: (Context, String) -> SharedPreferences = ::openEncryptedSharedPreferences,
) : PreferenceHolder() {
    override suspend fun create(): PreferenceProvider = AndroidPreferenceProvider.newEncrypted(context, filename, open)

    companion object {
        const val ENCRYPTED_PREFERENCES_FILENAME = "co.electriccoin.zcash.encrypted"
    }
}
