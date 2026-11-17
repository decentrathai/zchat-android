package co.electriccoin.zcash.ui.debug

import android.content.Context
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.SharedPrefKeysetWriter

/**
 * ZC-705, DEBUG BUILDS ONLY (phone checks K2 to K4): the one place in the repo that serializes a key set without
 * encryption. It puts a store in the state the encryption library's own fallback leaves (a key set written in the
 * clear), so the guard can be seen at work on a phone. Called from [TestHooksReceiver]'s KEYSET_SCRATCH action only,
 * which works on one fixed scratch store and never on a real one (KeysetScratchHookIsDebugOnlyTest).
 */
internal object KeysetScratch {
    private const val KEY_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_ENTRY = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    private const val KEYSTORE_URI_PREFIX = "android-keystore://"

    /**
     * Reads the two key entries of [file] with the phone's master key and writes them back unencrypted. The store's
     * data entries are not touched (they stay readable with the same key sets).
     */
    fun writeKeyEntriesInTheClear(
        context: Context,
        file: String,
    ) {
        DeterministicAeadConfig.register()
        AeadConfig.register()
        for (entry in listOf(KEY_ENTRY, VALUE_ENTRY)) {
            val handle =
                AndroidKeysetManager
                    .Builder()
                    .withSharedPref(context, entry, file)
                    .withMasterKeyUri(KEYSTORE_URI_PREFIX + MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .build()
                    .keysetHandle
            CleartextKeysetHandle.write(handle, SharedPrefKeysetWriter(context, entry, file))
        }
    }

    /** The first two characters of each key entry of [file] ("--" for an absent one): "12" protected, "08" plain. */
    fun prefixes(
        context: Context,
        file: String,
        plainPrefs: (Context, String) -> android.content.SharedPreferences,
    ): String {
        val raw = plainPrefs(context, file)
        return listOf(KEY_ENTRY, VALUE_ENTRY).joinToString("/") { raw.getString(it, null)?.take(2) ?: "--" }
    }
}
