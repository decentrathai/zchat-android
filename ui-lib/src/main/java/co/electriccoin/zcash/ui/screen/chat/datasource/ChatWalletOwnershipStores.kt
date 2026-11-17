package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManagerImpl
import java.io.File

/**
 * A SharedPreferences file as a [WalletScopedStore]. For an encrypted store pass the
 * EncryptedSharedPreferences instance: entries leave it decrypted and go back in through its own
 * editor, so they are always written with the keyset that file really has.
 */
class PrefsWalletScopedStore(
    override val storeId: String,
    private val prefs: () -> SharedPreferences,
) : WalletScopedStore {
    override fun exportEntries(): Map<String, Any?> =
        prefs().all.filterValues { it != null }.mapValues { (_, v) -> if (v is Set<*>) LinkedHashSet(v) else v }

    override fun importEntries(entries: Map<String, Any?>) {
        val store = prefs()
        val editor = store.edit().clear()
        entries.forEach { (key, value) -> editor.putTyped(key, value) }
        check(editor.commit()) { "Chat store $storeId could not be written" }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun SharedPreferences.Editor.putTyped(
    key: String,
    value: Any?
) {
    when (value) {
        null -> Unit
        is String -> putString(key, value)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Set<*> -> putStringSet(key, (value as Set<String>).toMutableSet())
        else -> throw IllegalArgumentException("Store entry $key has an unsupported type ${value.javaClass.name}")
    }
}

/** Plain file holding only the owner tag (a one-way hash, never the phrase). */
class PrefsChatOwnerRecord(
    context: Context
) : ChatOwnerRecord {
    // Lazy: this is built with WalletRepository at start-up, possibly on the main thread; first use is on IO.
    private val prefs by lazy { context.applicationContext.getSharedPreferences(CHAT_OWNER_PREFS_NAME, Context.MODE_PRIVATE) }

    override fun owner(): String? = prefs.getString(CHAT_OWNER_KEY, null)

    override fun setOwner(tag: String) {
        check(prefs.edit().putString(CHAT_OWNER_KEY, tag).commit()) { "Chat owner could not be written" }
    }
}

internal const val CHAT_OWNER_PREFS_NAME = "zchat_chat_owner"

/** The entry of [CHAT_OWNER_PREFS_NAME] that holds the owner tag (also read by [chatOwnerOf]). */
internal const val CHAT_OWNER_KEY = "owner"
internal const val SET_ASIDE_PREFS_PREFIX = "zchat_set_aside_"

/**
 * Opens one set-aside bundle ([EncryptedSetAsideBundles]). A bundle holds another wallet's whole chat history, so
 * it is under the same KR-1 rules as a live chat store ([openEncryptedStore]).
 *
 * R4-5: including the R3-3 rule. Only a start the user sees may count a launch towards recreating a bundle or
 * recreate one; a background start (boot, a scheduled job) that cannot open it stops with the bundle untouched.
 *
 * C6: a bundle is named after its own wallet and never holds the live wallet's chats, so its kept copies are not
 * tagged with the live chat owner (restoring one only writes the bundle file back).
 */
internal fun openSetAsideBundle(
    name: String,
    open: () -> SharedPreferences,
    plainPrefs: (String) -> SharedPreferences,
    userVisible: Boolean = ChatStoreStart.userVisible,
    env: StoreOpenEnv = StoreOpenEnv(),
): SharedPreferences =
    openEncryptedStore(
        name = name,
        open = open,
        plainPrefs = plainPrefs,
        userVisible = userVisible,
        copyOwner = { null },
        env = env,
    )

/**
 * One EncryptedSharedPreferences file per set-aside wallet ("zchat_set_aside_<tag>"): each store's
 * entries as one JSON value, plus a completion mark written in the same commit. Not in any backup rule.
 */
class EncryptedSetAsideBundles(
    context: Context
) : SetAsideBundles {
    private val appContext = context.applicationContext

    private fun fileName(tag: String): String {
        require(tag.matches(TAG_PATTERN)) { "Not a chat owner tag" }
        return "$SET_ASIDE_PREFS_PREFIX$tag"
    }

    private fun exists(name: String) = File(File(appContext.applicationInfo.dataDir, "shared_prefs"), "$name.xml").isFile

    private fun open(name: String): SharedPreferences =
        openSetAsideBundle(
            name = name,
            open = { openEncryptedFile(appContext, name) },
            plainPrefs = { appContext.getSharedPreferences(it, Context.MODE_PRIVATE) },
            // ZC-705: never the three-start rule for a bundle (a different wallet cannot be saved over a kept one).
            env = appStoreOpenEnv(appContext),
        )

    override fun write(
        tag: String,
        content: Map<String, Map<String, Any?>>
    ) {
        write(tag, content) { true }
    }

    override fun write(
        tag: String,
        content: Map<String, Map<String, Any?>>,
        mayPublish: () -> Boolean,
    ) {
        val bundle = open(fileName(tag))
        val editor = bundle.edit().clear()
        content.forEach { (storeId, entries) -> editor.putString(STORE_KEY_PREFIX + storeId, encodeStoreEntries(entries)) }
        editor.putString(KEY_COMPLETE, content.keys.sorted().joinToString(","))
        // With delete: what passed the question is on disk before a delete of the Reset that gave up removes it.
        synchronized(this) {
            if (!mayPublish()) return
            check(editor.commit()) { "Set-aside chats could not be written" }
        }
        check(read(tag)?.keys == content.keys) { "Set-aside chats did not read back" }
    }

    override fun read(tag: String): Map<String, Map<String, Any?>>? {
        val name = fileName(tag)
        if (!exists(name)) return null
        val bundle = open(name)
        val complete = bundle.getString(KEY_COMPLETE, null) ?: return null
        val storeIds = complete.split(',').filter { it.isNotEmpty() }
        return storeIds.associateWith { id ->
            decodeStoreEntries(checkNotNull(bundle.getString(STORE_KEY_PREFIX + id, null)) { "Set-aside store $id is missing" })
        }
    }

    override fun delete(tag: String) {
        synchronized(this) { appContext.deleteSharedPreferences(fileName(tag)) }
    }

    private companion object {
        const val KEY_COMPLETE = "__complete__"
        const val STORE_KEY_PREFIX = "store:"
        val TAG_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * Every live chat store of this app, and the in-memory copies to re-read after they change. The owners
 * are resolved on first use: this is built with WalletRepository at start-up, and opening the encrypted
 * chat stores there would do Keystore work on the main thread (and fail start-up on a Keystore error).
 */
class LiveChatStoresImpl(
    zchatPreferences: () -> ZchatPreferences,
    contactBook: () -> ContactBookImpl,
    avatarStore: () -> AvatarStore,
    addressCache: () -> AddressCacheImpl,
    identityManager: () -> IdentityManagerImpl,
    // ZC-718: further stores that follow the wallet (the AI store); none by default.
    private val furtherStores: () -> List<WalletScopedStore> = { emptyList() },
) : LiveChatStores {
    private val zchatPreferences by lazy(zchatPreferences)
    private val contactBook by lazy(contactBook)
    private val avatarStore by lazy(avatarStore)
    private val addressCache by lazy(addressCache)
    private val identityManager by lazy(identityManager)

    override fun stores(): List<WalletScopedStore> =
        zchatPreferences.walletScopedStores() +
            contactBook.walletScopedStores() +
            avatarStore.walletScopedStores() +
            addressCache.walletScopedStores() +
            identityManager.walletScopedStores() +
            furtherStores()

    override fun beginChange() {
        zchatPreferences.beginWalletScopeChange()
    }

    override fun reload() {
        zchatPreferences.reloadWalletScopedStores()
        avatarStore.reloadWalletScopedStores()
        addressCache.reloadWalletScopedStores()
        identityManager.reloadWalletScopedStores()
    }
}
