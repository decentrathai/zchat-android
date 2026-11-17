package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.unreadableStoreCopyNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/**
 * ZC-709 test support: the starts of one phone ([files]) for the kept-copy tests, each start a new process
 * ([StoreOpenerApi]) whose opener is given the folder's copy names (the app's environment, `copyNames`) when
 * [withCopyNames] is true. A store with no key sets that opens gets fresh ones, as the library makes them, so a
 * recreated store holds its own key entries (and nothing of the user's).
 */
internal class ChatCopyLaunches(
    val files: ChatCopyPhoneFiles,
    val store: String = "zchat_e2e_keys_encrypted",
    private val withCopyNames: Boolean = true,
) {
    /** What the phone's security system does when the store is asked for. */
    enum class Keystore {
        /** It answers. */
        ANSWERS,

        /** It does not answer at all. */
        OUT,

        /** It answers, and a store that holds anything of the user's does not decrypt (the key is gone). */
        KEY_GONE,
    }

    private var keysets = 0

    fun copies(): KeptChatStoreCopies = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

    /** The copy files of the store that hold anything. */
    fun copyFiles(): List<String> = files.copies().filter { files.entries(it).isNotEmpty() }

    /** One start the user sees; its start-up state. */
    fun launch(keystore: Keystore): StartupStorageCheck.State {
        val env: Map<String, Any?> = if (withCopyNames) mapOf("copyNames" to { unreadableStoreCopyNames(files.listing()) }) else emptyMap()
        val check =
            StartupStorageCheck(
                openSeedStore = {},
                openChatStores = {
                    StoreOpenerApi.open(StoreOpenerApi.newProcess(), store, files::file, nowMillis = files::tick, env = env) {
                        open(keystore)
                    }
                },
                ioDispatcher = Dispatchers.Unconfined,
                keptChatCopies = { copies().unhandled() },
                chatCopyRestoreFailed = { copies().restoreFailed() },
                chatCopyRestorePaused = { copies().restorePaused() },
            )
        runBlocking { check.run() }
        return check.state.value
    }

    /** The starts in a row an outage stops before the store is set aside. */
    fun outage() = repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR) { launch(Keystore.OUT) }

    fun repair(restart: () -> Unit = {}): WalletStartupRepair =
        WalletStartupRepair(
            persistableWalletProvider = unusedDependency(),
            walletRestoringStateProvider = unusedDependency(),
            walletDataDatabase =
                object : WalletDataDatabase {
                    override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
                },
            appRestarter = { restart() },
            keptChatCopies = copies(),
        )

    /**
     * Brings the kept copy back to the start-up offer the way a user can: a clean start, the paused and parked screens
     * read, a day on the clock and Settings' "Restore old chats". False when the copy cannot be offered any more (the
     * key was called gone).
     */
    fun bringOffer(): Boolean {
        repeat(OFFER_TRIES) {
            when (launch(Keystore.ANSWERS)) {
                is StartupStorageCheck.State.ChatCopyKept -> return true
                is StartupStorageCheck.State.ChatCopyRestorePaused -> copies().acknowledgeRestorePaused()
                is StartupStorageCheck.State.ChatCopyRestoreParked -> copies().acknowledgeRestoreParked()
                is StartupStorageCheck.State.ChatCopyRestoreFailed -> return false
                else -> {
                    files.advance(2 * DAY_MS)
                    if (copies().held().isNotEmpty()) copies().retryHeld()
                }
            }
        }
        return false
    }

    private fun open(keystore: Keystore): SharedPreferences =
        when (keystore) {
            Keystore.ANSWERS -> fresh()
            Keystore.OUT -> throw KeyStoreException("synthetic: the phone's security system did not answer")
            Keystore.KEY_GONE ->
                if (files.entries(store).keys.any { it !in KEYSET_ENTRIES }) {
                    throw AEADBadTagException("synthetic: the stored keyset does not decrypt")
                } else {
                    fresh()
                }
        }

    /** Like the library: a store with no key sets gets fresh ones; the store object itself holds nothing here. */
    private fun fresh(): SharedPreferences {
        if (files.entries(store).isEmpty()) {
            keysets++
            files
                .file(store)
                .edit()
                .putString(KEY_KEYSET, "fresh-key-keyset-$keysets")
                .putString(VALUE_KEYSET, "fresh-value-keyset-$keysets")
                .commit()
        }
        return RecordingPrefs()
    }

    companion object {
        const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        const val VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
        val KEYSET_ENTRIES = setOf(KEY_KEYSET, VALUE_KEYSET)
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val OFFER_TRIES = 8

        /** What the user's chats look like on disk: the store's key sets and one entry. */
        fun chats(tag: String): Map<String, Any?> =
            mapOf(
                KEY_KEYSET to "keyset-ciphertext-$tag-1",
                VALUE_KEYSET to "keyset-ciphertext-$tag-2",
                "AQ-$tag-encrypted-key" to "AU-$tag-encrypted-value",
            )
    }
}
