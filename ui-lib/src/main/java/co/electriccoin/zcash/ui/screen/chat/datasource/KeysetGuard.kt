package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.LegacyKeysetSerialization
import java.security.GeneralSecurityException

private const val TAG = "ZchatPreferences"

/** Waits between the tries to make a new recovery-phrase store (as the chat opener's retries). */
private val PHRASE_STORE_RETRY_DELAYS_MS = longArrayOf(50L, 150L)

/**
 * ZC-705 (store-opener design, revision 2a, section 3.4): the guard on the two key entries of an encrypted
 * preferences file. Pure: no Keystore call, no state of its own (its counter lives in the process's
 * [StoreOpenLaunch]) and no write of a key entry anywhere in it.
 *
 * Why: when the encryption library's own Keystore check fails while a NEW file is made, it writes that file's key
 * set without encryption and returns normally, and every later open accepts it silently. The opener
 * ([openEncryptedStore]) and the recovery-phrase store's open ([openPhraseStoreGuarded]) look at the two entries after
 * every open: a file that held nothing before the call and now has an unprotected entry is put back as it was and made
 * again; a file that existed is never changed, only counted and logged by its kind (never by a bundle's file name,
 * which ends in the wallet's owner tag).
 */
object KeysetGuard {
    /** The name a set-aside bundle is logged by: its file name ends in the wallet's owner tag. */
    const val KIND_SET_ASIDE_BUNDLE = "a set-aside bundle"

    /** The name a kept copy is logged by. */
    const val KIND_KEPT_COPY = "a kept copy"

    /** The name the recovery-phrase store is logged by. */
    const val KIND_PHRASE_STORE = "the recovery-phrase store"

    private const val CLEAR_KEYSET_FIRST_BYTE = 0x08
    private const val HEX_RADIX = 16

    /**
     * True when [entry] is a key set stored WITHOUT encryption: hex text whose first byte is 0x08 (the field of a
     * plain key set's primary key id; a protected one starts 0x12) and which the library's own clear reader accepts
     * (the very call the library's fallback makes). Absent, empty, not hex or refused: false. Never throws.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun isPlain(entry: String?): Boolean {
        if (entry.isNullOrEmpty() || entry.length % 2 != 0) return false
        val bytes = hexBytes(entry) ?: return false
        if (bytes.isEmpty() || bytes[0].toInt() != CLEAR_KEYSET_FIRST_BYTE) return false
        return try {
            LegacyKeysetSerialization.parseKeyset(BinaryKeysetReader.withBytes(bytes), InsecureSecretKeyAccess.get())
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** True when either of the two key entries of [plain] (the raw view of a file) is [isPlain]. Two reads. */
    fun hasPlainEntry(plain: SharedPreferences): Boolean =
        TINK_KEYSET_ENTRIES.any { isPlain(plain.getString(it, null)) }

    /**
     * The name by which a store is logged: its file name for the fixed names, [KIND_SET_ASIDE_BUNDLE] for a bundle
     * (its name ends in the wallet's owner tag), [KIND_KEPT_COPY] for a copy.
     */
    fun kindOf(storeName: String): String =
        when {
            storeName.startsWith(SET_ASIDE_PREFS_PREFIX) -> KIND_SET_ASIDE_BUNDLE
            storeName.contains(UNREADABLE_STORE_COPY_MARKER) -> KIND_KEPT_COPY
            else -> storeName
        }

    /** The line logged for a store that existed before the call and has an unprotected key entry. */
    fun keptAsItIsLine(kind: String): String = "an unprotected key entry in $kind; kept as it is"

    /** The line logged for a new chat store kept by the one exception (the three-start rule). */
    fun keptAfterLimitLine(kind: String): String = "an unprotected key entry in $kind; kept after three starts in a row"

    /** The line logged when a new file made in the clear could not be put back. */
    fun notUndoneLine(kind: String): String = "an unprotected key entry in $kind could not be undone"

    /** The line logged outside the main process, where the guard changes nothing. */
    fun notMainProcessLine(kind: String): String = "an unprotected key entry in $kind; not the main process: kept as made"

    private fun hexBytes(text: String): ByteArray? {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val high = Character.digit(text[2 * i], HEX_RADIX)
            val low = Character.digit(text[2 * i + 1], HEX_RADIX)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }
}

/** ZC-705: a new file came out of the library with an unprotected key entry; the try is undone and fails. */
class KeysetNotWrappedException(
    message: String
) : GeneralSecurityException(message)

/**
 * ZC-705: the recovery-phrase store did not exist and could not be made with protected key entries (the phone's
 * security system did not work at any of the tries). No file is left behind and nothing was deleted.
 */
class NewStoreNotMadeException(
    cause: Throwable?
) : IllegalStateException("The protected storage for the recovery phrase could not be set up", cause)

/**
 * ZC-705: puts a file that held nothing before this call back to what it was: [fileWasThere] false removes it,
 * true empties it (the opener's own clear before a recreate leaves an empty file). When that way is refused the other
 * way is tried once. False when both were refused: the file stays as the library left it. Called from
 * [openEncryptedStore] and from the recovery-phrase store's guarded open only, and only in the main process.
 */
internal fun undoNewStoreFile(
    name: String,
    fileWasThere: Boolean,
    plainPrefs: (String) -> SharedPreferences,
    env: StoreOpenEnv,
): Boolean {
    val remove: () -> Boolean = { env.removeStoreFile?.invoke(name) ?: false }
    val empty: () -> Boolean = { plainPrefs(name).edit().clear().commit() }
    // A JVM file is a map (no remove function): "put back" empties it.
    val removeFirst = !fileWasThere && env.removeStoreFile != null
    if (if (removeFirst) remove() else empty()) return true
    return if (removeFirst) empty() else remove()
}

/**
 * ZC-705: opens the recovery-phrase store (co.electriccoin.zcash.encrypted) with the guard. The preferences module is
 * not edited: the provider is built with this function as its open call (di/CoreModule.kt).
 */
fun openPhraseStoreGuarded(
    context: Context,
    filename: String,
): SharedPreferences =
    openPhraseStoreGuardedWith(
        filename = filename,
        open = { openEncryptedFile(context, filename) },
        plainPrefs = { context.getSharedPreferences(it, Context.MODE_PRIVATE) },
        env = appStoreOpenEnv(context),
        sleep = Thread::sleep,
    )

/**
 * ZC-705 (spec 3.4.5): the work behind [openPhraseStoreGuarded], with the open call, the plain view of a file, the
 * environment and the wait passed in (the JVM tests drive this one).
 *
 *  - The file EXISTED (it held anything before the call): nothing is changed, whatever its key entries are. An
 *    unprotected entry is counted and logged as [KeysetGuard.KIND_PHRASE_STORE]; the store is returned; an error of
 *    the open is thrown on as today (one try, no count, never cleared).
 *  - The file was NEW (a first run, or a start after a finished Destroy): when the open failed or left an unprotected
 *    key entry, whatever it made is undone (main process only), and after 50 ms and then 150 ms it is tried again.
 *    After the third time [NewStoreNotMadeException] is thrown: there is no wallet yet, and nothing was deleted.
 *    The three-start exception of the chat stores never applies here: a first run that cannot make this store has no
 *    wallet to lock out.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal fun openPhraseStoreGuardedWith(
    filename: String,
    open: () -> SharedPreferences,
    plainPrefs: (String) -> SharedPreferences,
    env: StoreOpenEnv,
    sleep: (Long) -> Unit,
): SharedPreferences {
    var last: Throwable? = null
    for (attempt in 0..PHRASE_STORE_RETRY_DELAYS_MS.size) {
        val wasNew = plainPrefs(filename).all.isEmpty()
        val fileWasThere = env.storeFileExists?.invoke(filename) ?: true
        if (!wasNew) {
            val store = open()
            if (KeysetGuard.hasPlainEntry(plainPrefs(filename)) &&
                env.launch.notePlainKeyset(filename, KeysetGuard.KIND_PHRASE_STORE)
            ) {
                guardError(env.launch, KeysetGuard.keptAsItIsLine(KeysetGuard.KIND_PHRASE_STORE))
            }
            return store
        }
        try {
            val store = open()
            if (!KeysetGuard.hasPlainEntry(plainPrefs(filename))) return store
            if (!env.mayChangeFiles) {
                env.launch.notePlainKeyset(filename, KeysetGuard.KIND_PHRASE_STORE)
                guardError(env.launch, KeysetGuard.notMainProcessLine(KeysetGuard.KIND_PHRASE_STORE))
                return store
            }
            if (!undoNewStoreFile(filename, fileWasThere, plainPrefs, env)) {
                guardError(env.launch, KeysetGuard.notUndoneLine(KeysetGuard.KIND_PHRASE_STORE))
            }
            last = KeysetNotWrappedException("a new ${KeysetGuard.KIND_PHRASE_STORE} came out with an unprotected key entry")
        } catch (e: Exception) {
            last = e
            // Whatever a failed open of a new file left (one key entry, say) held nothing of the user's.
            if (env.mayChangeFiles && plainPrefs(filename).all.isNotEmpty()) {
                undoNewStoreFile(filename, fileWasThere, plainPrefs, env)
            }
        }
        Log.w(TAG, "The recovery-phrase store could not be set up (attempt ${attempt + 1}, ${last.javaClass.simpleName})")
        if (attempt < PHRASE_STORE_RETRY_DELAYS_MS.size) sleep(PHRASE_STORE_RETRY_DELAYS_MS[attempt])
    }
    throw NewStoreNotMadeException(last)
}

/** One error-level line of the guard: logged, and kept in the process's [StoreOpenLaunch] (never a key or a tag). */
internal fun guardError(
    launch: StoreOpenLaunch,
    line: String,
) {
    Log.e(TAG, line)
    launch.noteGuardLine(line)
}
