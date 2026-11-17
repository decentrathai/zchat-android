package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.util.Log
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import java.io.File
import java.security.KeyStore

/**
 * ZC-868 (register, high): the master-key call (`MasterKey.Builder…build()`) MAKES a new Keystore key when the Keystore
 * answers "absent", and every store of the app and the recovery-phrase store share the default key name. On a false
 * "absent" (a Keystore that is not ready yet) a new default key would replace the shared one, and every existing store,
 * the phrase store included, could never be read again.
 *
 * So before any master-key call ([checkBeforeMakingKey], from the app's one opener) and before the wallet starts
 * ([checkBeforeWalletStart]: the SDK's own opener makes the same call at every wallet start): when an encrypted store
 * file that holds both key entries already exists under that key name (a live store; a kept copy of a store that could
 * not be read never counts) and the Keystore says the key is absent, it is
 * asked once more; when it still says absent, no key is made and the open fails closed with the storage error the
 * start-up screen already shows ([MasterKeyAbsentException]). A true first run (no such file) makes the key as before;
 * a Keystore that cannot be asked at all changes nothing. It reads file names and raw text only.
 */
object MasterKeyGuard {
    private const val TAG = "MasterKeyGuard"
    private const val SECOND_ASK_AFTER_MS = 300L
    private const val PREFS_FOLDER = "shared_prefs"
    private const val XML = ".xml"
    private const val BAK = ".bak"

    /** The key of each store that does not use the default one (the AI store has its own). */
    private val OWN_KEY_NAMES = mapOf(AiPreferences.PREFS_NAME to AiPreferences.MASTER_KEY_ALIAS)

    /** Is the Keystore key of this name there: the Keystore's answer, or null when it cannot be asked (replaceable). */
    @Volatile
    private var keystoreHasKey: (String) -> Boolean? = { keyName ->
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyName)
        } catch (e: Exception) {
            Log.w(TAG, "the Keystore cannot be asked (${e.javaClass.simpleName})")
            null
        }
    }

    @Volatile
    private var waitBeforeSecondAsk: (Long) -> Unit = { ms -> Thread.sleep(ms) }

    /** Before the master-key call for the store [name] under [keyName]. Throws [MasterKeyAbsentException] instead of a key. */
    fun checkBeforeMakingKey(
        context: Context,
        name: String,
        keyName: String,
    ) = refuseOnAbsentKey(context, name, keyName, beforeWalletStart = false)

    /**
     * Before the wallet starts (the SDK's own opener makes the default key's master-key call at every wallet start).
     * Its refusal is about the phrase store's key, so the start-up screen shows the recovery-phrase screen for it
     * ([MasterKeyAbsentException.beforeWalletStart]).
     */
    fun checkBeforeWalletStart(context: Context) =
        refuseOnAbsentKey(context, name = "", keyName = MasterKey.DEFAULT_MASTER_KEY_ALIAS, beforeWalletStart = true)

    private fun refuseOnAbsentKey(
        context: Context,
        name: String,
        keyName: String,
        beforeWalletStart: Boolean,
    ) {
        if (!protectedStoreExists(context, keyName, except = name)) return
        if (keystoreHasKey(keyName) != false) return
        waitBeforeSecondAsk(SECOND_ASK_AFTER_MS)
        if (keystoreHasKey(keyName) != false) return
        Log.w(TAG, "a store under the key $keyName exists and the Keystore says the key is absent: no key is made")
        throw MasterKeyAbsentException(keyName, beforeWalletStart)
    }

    /**
     * A LIVE store file under [keyName], other than [except], that holds both key entries (its `.bak` twin counts too).
     * Review finding 2 (REVIEW_T7aR_2_20261020.md): a kept copy of a store that could not be read
     * (`<store>__unreadable_<ms>`) never counts. It was set aside because its key could not open it, and no Reset
     * deletes the AI store's copies, so counting it would block every new key for good once a key is really gone (the
     * AI tab, a claim of a wallet whose set-aside chats hold AI data). A live store keeps the protection; with a
     * wallet the live phrase store is always one under the default key.
     */
    private fun protectedStoreExists(
        context: Context,
        keyName: String,
        except: String,
    ): Boolean {
        val folder = context.applicationInfo?.dataDir?.let { File(it, PREFS_FOLDER) } ?: return false
        return folder.listFiles().orEmpty().any { file ->
            val store = file.name.removeSuffix(BAK).removeSuffix(XML)
            file.isFile &&
                store != except &&
                UNREADABLE_STORE_COPY_MARKER !in store &&
                keyNameOf(store) == keyName &&
                holdsBothKeyEntries(file)
        }
    }

    private fun keyNameOf(store: String): String = OWN_KEY_NAMES[store] ?: MasterKey.DEFAULT_MASTER_KEY_ALIAS

    private fun holdsBothKeyEntries(file: File): Boolean =
        try {
            val text = file.readText()
            TINK_KEYSET_ENTRIES.all { entry -> "name=\"$entry\"" in text }
        } catch (e: Exception) {
            Log.w(TAG, "a store file cannot be read (${e.javaClass.simpleName})")
            false
        }
}

/**
 * ZC-868: the Keystore says the shared key is absent while stores made with it exist: nothing was made or changed.
 * [beforeWalletStart]: the refusal of the wallet's start (the phrase store's key), shown as the recovery-phrase screen.
 */
class MasterKeyAbsentException(
    keyName: String,
    val beforeWalletStart: Boolean = false,
) : EncryptedStoreUnavailableException(
        "The Keystore says the key $keyName is absent while stores made with it exist; no new key was made.",
        IllegalStateException("Keystore key absent: $keyName"),
    )
