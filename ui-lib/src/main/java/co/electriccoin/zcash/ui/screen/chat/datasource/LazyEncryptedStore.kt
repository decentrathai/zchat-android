package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ZC-733 (store-opener design, revision 2a, section 3.6 item 2): the small holder of one encrypted store that is not a
 * chat store — the contact book, the picture index, the AI store. It opens the store through the one opener
 * ([openEncryptedStore], with the app's environment) and the one way to open a file ([openEncryptedFile]), so these
 * stores get the chat stores' rules: the typed error, the retries inside one call, one count per process, the guard on
 * the key entries, an exact copy before anything is emptied. And it remembers for the process what the store answered:
 * not asked yet, open, or unavailable (with the error).
 *
 *  - [askAtStart]: the start-up check's ask. It opens as a start the user sees ([ChatStoreStart.userVisible]), so this
 *    is the one ask that may count a start and, by the opener's rules (three starts in a row, or a lost key), set the
 *    store aside with a copy and start it empty. It makes its own try unless the store is open, also after a failed
 *    [get]; with the store open it opens nothing. It throws what the opener throws (its caller catches).
 *  - [get]: every other use. Open: the store. Not asked yet: one background try (never counts, never clears).
 *    Unavailable: the remembered error at once, with no Keystore call and no wait.
 *  - [tryAgain]: one more background try without the opener's waits, for an act of the user (Retry).
 *
 * [get] and [tryAgain] throw [OtherStoreUnavailableException] with the opener's error as its cause: a use somebody adds
 * later without a catch then ends the app instead of showing the chat stores' Try-again screen with a false text
 * (SynchronizerStartupFailureGuard does not take it). [afterOpen] runs once after every successful open, before the
 * store is handed out (the contact book's move of its old plain list).
 *
 * ZC-733-B (rule S12): at a change of wallet the claim gets [walletStore], whose store is opened by [forWalletChange]:
 * open, that object; a store of nobody (the owner record says so, after a Delete Wallet without "keep") that cannot be
 * opened is erased and made new only when something is brought in; a wallet's store (or one with no record) that
 * cannot be opened is set aside with an exact copy, tagged with that wallet, and starts empty. A store with no file is
 * not made by a change of wallet that has nothing to bring into it. [wipe] is Delete Wallet's: an open store is
 * cleared, a store that is not open is erased as a file.
 */
class LazyEncryptedStore(
    private val context: Context,
    val name: String,
    private val masterKeyName: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS,
    private val afterOpen: (SharedPreferences) -> Unit = {},
) {
    /** The library calls behind [openEncryptedFile]; the real ones, a JVM test hands its own. */
    @Volatile
    var fileCalls: EncryptedFileCalls = LibraryEncryptedFileCalls

    /** The opener's wait between its tries; a JVM test hands one that does not wait. */
    @Volatile
    var sleep: (Long) -> Unit = Thread::sleep

    private val lock = Any()
    private var store: SharedPreferences? = null
    private var failure: Exception? = null
    private val unavailableState = MutableStateFlow(false)

    /** True while the last ask of this process could not open the store. */
    val unavailable: StateFlow<Boolean> = unavailableState.asStateFlow()

    /** The start-up check's ask (see the class comment). Throws what the opener throws. */
    fun askAtStart(): SharedPreferences =
        synchronized(lock) {
            store ?: open(userVisible = ChatStoreStart.userVisible, wait = sleep)
        }

    /** Every other use (see the class comment). Throws [OtherStoreUnavailableException]. */
    @Suppress("TooGenericExceptionCaught")
    fun get(): SharedPreferences =
        synchronized(lock) {
            store?.let { return it }
            failure?.let { throw unavailableError(it) }
            try {
                open(userVisible = false, wait = sleep)
            } catch (e: Exception) {
                throw unavailableError(e)
            }
        }

    /** One more background try without waits, for Retry. Throws [OtherStoreUnavailableException]. */
    @Suppress("TooGenericExceptionCaught")
    fun tryAgain(): SharedPreferences =
        synchronized(lock) {
            store?.let { return it }
            try {
                open(userVisible = false, wait = {})
            } catch (e: Exception) {
                throw unavailableError(e)
            }
        }

    /**
     * ZC-733-B: the store for a change of wallet (see the class comment). Throws the opener's error as it is, so a claim
     * that must fail fails as it does for a chat store.
     */
    fun forWalletChange(): SharedPreferences = synchronized(lock) { store ?: openForChange(makeNew = true)!! }

    /** ZC-718: true when the store is open in this process. */
    fun isOpen(): Boolean = synchronized(lock) { store != null }

    /** ZC-718: true when the store is open or its file is on the phone (asking makes no file). */
    fun isThere(): Boolean = synchronized(lock) { store != null || storeFileIsThere(context, name) }

    /** ZC-733-B: Delete Wallet's wipe: an open store is cleared; one that is not open is erased as a file. */
    fun wipe() {
        synchronized(lock) {
            val open = store
            if (open != null) {
                open.edit().clear().commit()
            } else {
                erase()
            }
        }
    }

    /** ZC-733-B: the claim's wallet store of [storeId], over this holder (see the class comment). */
    fun walletStore(storeId: String): WalletScopedStore = walletStore(storeId, null)

    /**
     * ZC-733-B: [walletStore] with [current], the object the store's class reads its store through (asked before an
     * open for the change of wallet, so an object the class already holds is handed over as it is).
     */
    fun walletStore(
        storeId: String,
        current: (() -> SharedPreferences)?,
    ): WalletScopedStore =
        object : WalletScopedStore {
            override val storeId: String = storeId

            override fun exportEntries(): Map<String, Any?> =
                synchronized(lock) {
                    if (store == null && !storeFileIsThere(context, name)) return emptyMap()
                    val prefs = currentOrNull(current) ?: forWalletChange()
                    prefs.all.filterValues { it != null }.mapValues { (_, v) -> if (v is Set<*>) LinkedHashSet(v) else v }
                }

            override fun importEntries(entries: Map<String, Any?>) {
                synchronized(lock) {
                    val prefs =
                        if (entries.isEmpty()) {
                            if (store == null && !storeFileIsThere(context, name)) return
                            currentOrNull(current) ?: store ?: openForChange(makeNew = false) ?: return
                        } else {
                            currentOrNull(current) ?: forWalletChange()
                        }
                    val editor = prefs.edit().clear()
                    entries.forEach { (key, value) -> editor.putTyped(key, value) }
                    check(editor.commit()) { "Store $storeId could not be written" }
                }
            }
        }

    /** [current]'s object, or null when there is none or it cannot be read now (a background try, never counted). */
    @Suppress("SwallowedException")
    private fun currentOrNull(current: (() -> SharedPreferences)?): SharedPreferences? =
        try {
            current?.invoke()
        } catch (e: EncryptedStoreUnavailableException) {
            null
        }

    /**
     * ZC-733-B: opens the store for a change of wallet. Nobody's: one more background try; when that fails too the file
     * is erased, and a new store is opened when [makeNew] (null otherwise). A wallet's, or no record: one open as the
     * user's own act with the failure treated as final (a copy first, then an empty store). Call under [lock].
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun openForChange(makeNew: Boolean): SharedPreferences? {
        if (chatOwnerOf { context.getSharedPreferences(it, Context.MODE_PRIVATE) } == NO_CHAT_OWNER) {
            try {
                return open(userVisible = false, wait = sleep)
            } catch (e: Exception) {
                Log.w(TAG, "The store $name of nobody's chats cannot be opened (${e.javaClass.simpleName}); it is erased")
                // ZC-1031: the AI store may hold the only key to AI credit and may become readable later: it is set
                // aside as a kept copy (spared by every wipe, S14) before its file goes. Every other store is erased.
                if (name == AiPreferences.PREFS_NAME) {
                    setAsideUnreadableStore(name, ::plainPrefs, e)
                }
            }
            erase()
            return if (makeNew) open(userVisible = false, wait = sleep) else null
        }
        return open(userVisible = true, wait = sleep, finalFailure = true)
    }

    /** ZC-733-B: erases the store's file; the holder then says "not asked yet", as the disk does. Call under [lock]. */
    private fun erase() {
        context.deleteSharedPreferences(name)
        store = null
        failure = null
        unavailableState.value = false
    }

    /** Opens the store through the opener; remembers the answer. Call under [lock]. */
    @Suppress("TooGenericExceptionCaught")
    private fun open(
        userVisible: Boolean,
        wait: (Long) -> Unit,
        finalFailure: Boolean = false,
    ): SharedPreferences {
        try {
            val opened =
                openEncryptedStore(
                    name = name,
                    open = { openEncryptedFile(context, name, masterKeyName, fileCalls) },
                    plainPrefs = ::plainPrefs,
                    sleep = wait,
                    userVisible = userVisible,
                    env = appStoreOpenEnv(context).apply { treatFailureAsFinal = finalFailure },
                )
            afterOpen(opened)
            store = opened
            failure = null
            unavailableState.value = false
            return opened
        } catch (e: Exception) {
            failure = e
            unavailableState.value = true
            throw e
        }
    }

    /** A store file's plain view (no decryption): the opener's and the set-aside's. */
    private fun plainPrefs(file: String): SharedPreferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "ZchatPreferences"
    }

    private fun unavailableError(cause: Exception): OtherStoreUnavailableException =
        cause as? OtherStoreUnavailableException
            ?: OtherStoreUnavailableException("Encrypted storage $name cannot be opened right now; its data is kept.", cause)
}
