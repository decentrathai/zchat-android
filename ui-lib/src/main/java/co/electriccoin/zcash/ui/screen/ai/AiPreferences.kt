package co.electriccoin.zcash.ui.screen.ai

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.LazyEncryptedStore
import co.electriccoin.zcash.ui.screen.chat.datasource.WalletScopedStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap

/**
 * Persists the per-device bearer token so re-installing the app doesn't mint a fresh
 * $0.20 free trial. Kept in its own EncryptedSharedPreferences file to avoid coupling
 * to the chat module's ZchatPreferences.
 *
 * Stored fields:
 *   - "ai_token": the opaque bearer the server issued
 *   - "ai_user_id": the matching userId
 *   - "ai_link_state": AI-KEY-S, what is known about that account (JSON, written by [AiAccountAccess])
 *   - "ai_parked_keys": AI-KEY-S, access keys kept and never used (JSON list of token, userId and, since round 2, paid)
 *
 * Threat model: same as e2ePrefs — AES-256-GCM via Android Keystore. On rooted devices
 * + Keystore-extraction the attacker can read the token and use it; per Phase-1 v1.0
 * design that's acceptable since the token only buys their own AI quota.
 *
 * ZC-733-A: the store is opened through ONE holder per application context ([holder], in the companion), whatever the
 * number of AiPreferences objects: the one opener with the chat stores' rules. The start-up check asks for it only when
 * its file exists ([askAtStartIfPresent]); a read that fails throws the typed error, which the AI tab's view model
 * catches in one guard (no sign-up is sent while the store cannot be opened).
 *
 * ZC-718: the AI store follows its wallet. A change of wallet moves it with the chat stores, as the holder's own wallet
 * store ([walletScopedStores]: no file and nothing to bring in, nothing is made; one that cannot be opened is set aside
 * with a copy); a Reset without "keep" keeps what the recovery phrase cannot open ([entriesKeptAtReset], AI-KEY-R) in the
 * reset wallet's own set-aside file before the store is cleared ([AiResetKeep]).
 */
class AiPreferences(context: Context) {
    // Build EncryptedSharedPreferences/Tink lazily so the constructor stays cheap — the Keystore +
    // keyset disk reads (~241ms) used to run synchronously when the AI tab composable created this on
    // the main thread (StrictMode DiskReadViolation). The first access is now forced off the main
    // thread by AiTabVM (viewModelScope on Dispatchers.IO), so the UI never blocks on it.
    private val appContext = context.applicationContext
    private val store = holder(appContext)

    // Lazy over the holder: the first successful open is kept; a failed one throws the holder's error each time.
    private val prefs: SharedPreferences by lazy { store.get() }

    /** ZC-733-A: Retry of the AI tab: one more try of the store, off the main thread. True when it is open. */
    fun tryOpenAgain(): Boolean =
        try {
            store.tryAgain()
            true
        } catch (_: EncryptedStoreUnavailableException) {
            false
        }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    // Chat and image models are picked + remembered independently — selecting a model in one mode
    // must not leave the other mode pointing at an incompatible model (e.g. an image model in chat).
    fun getSelectedChatModel(): String? = prefs.getString(KEY_SELECTED_MODEL, null)

    fun setSelectedChatModel(modelId: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    fun getSelectedImageModel(): String? = prefs.getString(KEY_SELECTED_IMAGE_MODEL, null)

    fun setSelectedImageModel(modelId: String) {
        prefs.edit().putString(KEY_SELECTED_IMAGE_MODEL, modelId).apply()
    }

    // ── AI history (kept until the user clears) ─────────────────────────────────────────────────
    // Chat transcripts persist as JSON here (text is small). Generated image BYTES are stored by
    // AiImageStore in filesDir; only their metadata JSON lives here.
    fun getConversationsJson(): String? = prefs.getString(KEY_CONVERSATIONS, null)

    fun setConversationsJson(json: String) {
        prefs.edit().putString(KEY_CONVERSATIONS, json).apply()
    }

    fun getImagesJson(): String? = prefs.getString(KEY_IMAGES, null)

    fun setImagesJson(json: String) {
        prefs.edit().putString(KEY_IMAGES, json).apply()
    }

    /** Auto-delete retention for chats + images, in days. 0 = keep forever (default). */
    fun getRetentionDays(): Int = prefs.getInt(KEY_RETENTION_DAYS, 0)

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    /** Last-used image aspect-ratio label ("1:1", "16:9", …) — see AiTabVM.IMAGE_ASPECTS. */
    fun getImageAspect(): String = prefs.getString(KEY_IMAGE_ASPECT, "1:1") ?: "1:1"

    fun setImageAspect(aspect: String) {
        prefs.edit().putString(KEY_IMAGE_ASPECT, aspect).apply()
    }

    fun saveCredentials(token: String, userId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    /** AI-KEY-S: the verdict for the saved key's account (spec 4.2); [AiAccountAccess] reads what it wrote. */
    internal fun getLinkStateJson(): String? = prefs.getString(KEY_LINK_STATE, null)

    /** AI-KEY-T: the store's file is on the phone, or it is open. Asking opens nothing and makes nothing. */
    internal fun isThere(): Boolean = store.isThere()

    internal fun hasParkedKeys(): Boolean = parkedKeys(prefs.getString(KEY_PARKED_KEYS, null)).length() > 0

    /** AI-KEY-P: the account ids of the parked keys, in their order. */
    internal fun parkedUserIds(): List<String> =
        parkedEntries(parkedKeys(prefs.getString(KEY_PARKED_KEYS, null))).map { it.optString(PARKED_USER_ID) }

    /** AI-KEY-P: the parked key of this account, for "Try to link" and nothing else. */
    internal fun parkedToken(userId: String): String? =
        parkedEntries(parkedKeys(prefs.getString(KEY_PARKED_KEYS, null)))
            .firstOrNull { it.optString(PARKED_USER_ID) == userId }
            ?.optString(PARKED_TOKEN)

    /** Item 20: what was known of this account's paid credit when its key was parked; null in an entry from before that. */
    internal fun parkedPaid(userId: String): String? =
        parkedEntries(parkedKeys(prefs.getString(KEY_PARKED_KEYS, null)))
            .firstOrNull { it.optString(PARKED_USER_ID) == userId }
            ?.optString(PARKED_PAID)
            ?.ifEmpty { null }

    /**
     * AI-KEY-S (spec 4.6, M12): the ONE write of this store that follows a network answer. Under the wallet-scope lock
     * (a claim and a wipe hold it) it asks [stillThisWallet], which re-reads the owner tag and the wallet-scope counter,
     * and writes nothing when they changed. False when nothing was written. The caller asks whether a wallet is saved
     * just before: that read is a suspending one and does not belong under this lock.
     */
    internal fun writeIfStillThisWallet(
        stillThisWallet: () -> Boolean,
        change: Editing.() -> Unit,
    ): Boolean =
        synchronized(ZchatPreferences.walletScopeLock) {
            if (!stillThisWallet()) return false
            val editor = prefs.edit()
            Editing(editor, prefs.getString(KEY_PARKED_KEYS, null)).change()
            editor.commit()
        }

    /** One fenced write's changes ([writeIfStillThisWallet]). */
    internal class Editing(
        private val editor: SharedPreferences.Editor,
        parked: String?,
    ) {
        private var list = parkedKeys(parked)

        fun putPair(
            token: String,
            userId: String,
        ) {
            editor.putString(KEY_TOKEN, token).putString(KEY_USER_ID, userId)
        }

        fun removePair() {
            editor.remove(KEY_TOKEN).remove(KEY_USER_ID)
        }

        fun putLinkStateJson(json: String) {
            editor.putString(KEY_LINK_STATE, json)
        }

        fun removeLink() {
            editor.remove(KEY_LINK_STATE)
        }

        /** Appends the pair to the list of parked keys: kept, and used for no request. */
        fun park(
            token: String,
            userId: String,
            paid: String? = null,
        ) {
            val entry = JSONObject().put(PARKED_TOKEN, token).put(PARKED_USER_ID, userId)
            paid?.let { entry.put(PARKED_PAID, it) }
            list.put(entry)
            editor.putString(KEY_PARKED_KEYS, list.toString())
        }

        /** AI-KEY-P (change 2): the parked key of this account leaves the list, and an empty list leaves the store. */
        fun retire(userId: String) {
            keepOnly { it.optString(PARKED_USER_ID) != userId }
        }

        /** Item 22: this one refused key leaves the list; another key of the same account stays. */
        fun retireKey(token: String) {
            keepOnly { it.optString(PARKED_TOKEN) != token }
        }

        private fun keepOnly(keep: (JSONObject) -> Boolean) {
            val kept = parkedEntries(list).filter(keep)
            if (kept.size == list.length()) return
            list = JSONArray().apply { kept.forEach { put(it) } }
            if (kept.isEmpty()) editor.remove(KEY_PARKED_KEYS) else editor.putString(KEY_PARKED_KEYS, list.toString())
        }
    }

    /** ZC-718: a checked write; throws when the store could not be cleared. */
    fun clear() {
        check(prefs.edit().clear().commit()) { "The AI store could not be cleared" }
    }

    /**
     * ZC-718: the AI store for a change of wallet: the holder's own wallet store (ZC-733-B), never the plain adapter,
     * which would make the file and its Keystore key at every change of wallet.
     */
    fun walletScopedStores(): List<WalletScopedStore> = listOf(store.walletStore(PREFS_NAME))

    /**
     * AI-KEY-R (W-52, W-53; spec 5.3, M6): what a Reset without "keep" keeps of this store in the reset wallet's own
     * set-aside file. The saved pair, unless its account's verdict is linked or its last paid answer was no: a state for
     * another account id, or one that cannot be read, says nothing about this key and the pair stays. With the pair, a paid
     * yes of its own account (round 3, M1: an issued top-up address outlives the Reset). The list of parked keys, always and
     * whole. Never a verdict, a chat list, a picture list or a setting. Makes no file when there is none; throws when the
     * store cannot be read, which is not "nothing to keep".
     */
    fun entriesKeptAtReset(): Map<String, Any?> {
        if (!store.isThere()) return emptyMap()
        val kept = linkedMapOf<String, Any?>()
        val token = prefs.getString(KEY_TOKEN, null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val linkState = prefs.getString(KEY_LINK_STATE, null)
        if (token != null && !phraseOpensOrNoCredit(linkState, userId)) {
            kept[KEY_TOKEN] = token
            userId?.let { kept[KEY_USER_ID] = it }
            if (linkStateOf(linkState, userId)?.optString(LINK_PAID) == PAID_YES) {
                kept[KEY_LINK_STATE] = JSONObject().put(LINK_ACCOUNT_ID, userId).put(LINK_PAID, PAID_YES).toString()
            }
        }
        prefs.getString(KEY_PARKED_KEYS, null)?.takeIf { parkedKeys(it).length() > 0 }?.let { kept[KEY_PARKED_KEYS] = it }
        return kept
    }

    /** ZC-718: the Reset's clear: an open store is cleared with a checked write; one that is not open is erased. */
    internal fun clearForReset() {
        if (store.isOpen()) clear() else store.wipe()
    }

    companion object {
        internal const val PREFS_NAME = "zchat_ai_prefs"
        internal const val MASTER_KEY_ALIAS = "zchat_ai_master_key"
        private const val KEY_TOKEN = "ai_token"
        private const val KEY_USER_ID = "ai_user_id"

        /** ZC-1031: the entries of the access key (the token first), which a claim of nobody's chats keeps. */
        internal val ACCESS_KEY_ENTRIES: List<String> = listOf(KEY_TOKEN, KEY_USER_ID)
        private const val KEY_LINK_STATE = "ai_link_state"
        internal const val KEY_PARKED_KEYS = "ai_parked_keys"
        private const val PARKED_TOKEN = "token"
        private const val PARKED_USER_ID = "userId"
        private const val PARKED_PAID = "paid"
        private const val KEY_SELECTED_MODEL = "ai_selected_model"
        private const val KEY_SELECTED_IMAGE_MODEL = "ai_selected_image_model"
        private const val KEY_CONVERSATIONS = "ai_conversations"
        private const val KEY_IMAGES = "ai_images"
        private const val KEY_RETENTION_DAYS = "ai_retention_days"
        private const val KEY_IMAGE_ASPECT = "ai_image_aspect"

        private const val LINK_ACCOUNT_ID = "accountId"
        private const val LINK_VERDICT = "verdict"
        private const val LINK_PAID = "paid"
        private const val LINKED = "linked"
        private const val PAID_NO = "no"
        private const val PAID_YES = "yes"

        /** ai_link_state ([AiAccountAccess]) of the account [userId]; null when it is another account's or unreadable. */
        private fun linkStateOf(
            json: String?,
            userId: String?,
        ): JSONObject? =
            try {
                json?.let { JSONObject(it) }?.takeIf { userId != null && it.optString(LINK_ACCOUNT_ID) == userId }
            } catch (e: JSONException) {
                null
            }

        private fun phraseOpensOrNoCredit(
            linkState: String?,
            userId: String?,
        ): Boolean {
            val link = linkStateOf(linkState, userId) ?: return false
            return link.optString(LINK_VERDICT) == LINKED || link.optString(LINK_PAID) == PAID_NO
        }

        /**
         * ZC-1195 (spec 4.5, M1): nobody's live key that a claim would overwrite with the wallet's own key [ownToken], as
         * a one-entry parked list. Null for the very same key and for an account known to hold no paid credit, whatever
         * its verdict; anything else counts as paid, a verdict of a linked account included, and a key to credit is
         * never lost.
         */
        internal fun liveKeyToPark(
            live: Map<String, Any?>,
            ownToken: String?,
        ): String? {
            val token = live[KEY_TOKEN] as? String ?: return null
            if (token == ownToken) return null
            val userId = live[KEY_USER_ID] as? String
            if (linkStateOf(live[KEY_LINK_STATE] as? String, userId)?.optString(LINK_PAID) == PAID_NO) return null
            return JSONArray().put(JSONObject().put(PARKED_TOKEN, token).put(PARKED_USER_ID, userId.orEmpty())).toString()
        }

        private fun parkedKeys(json: String?): JSONArray =
            try {
                JSONArray(json ?: "[]")
            } catch (e: JSONException) {
                JSONArray()
            }

        private fun parkedEntries(list: JSONArray): List<JSONObject> =
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }

        /**
         * ZC-1177: two parked lists joined, [first]'s entries first, each key once. A list alone comes back as it was
         * written; null when neither holds a key.
         */
        internal fun joinParkedKeys(
            first: String?,
            second: String?,
        ): String? {
            val one = parkedKeys(first)
            val other = parkedKeys(second)
            if (other.length() == 0) return first.takeIf { one.length() > 0 }
            if (one.length() == 0) return second
            val seen = HashSet<String>()
            val joined = JSONArray()
            listOf(one, other).forEach { list ->
                for (index in 0 until list.length()) {
                    val entry = list.optJSONObject(index) ?: continue
                    if (seen.add(entry.optString(PARKED_TOKEN))) joined.put(entry)
                }
            }
            return joined.toString()
        }

        private val holders = WeakHashMap<Context, LazyEncryptedStore>()

        /** ZC-733-A: the one holder of the AI store for this application context (the app has one). */
        fun holder(context: Context): LazyEncryptedStore {
            val app = context.applicationContext ?: context
            return synchronized(holders) { holders.getOrPut(app) { LazyEncryptedStore(app, PREFS_NAME, MASTER_KEY_ALIAS) } }
        }

        /**
         * ZC-733-A: the start-up check's ask, made only when the AI store's file (or its .bak twin) exists, so a phone
         * that never used the AI tab gets no file and no key of the AI key name. Throws what the opener throws.
         */
        fun askAtStartIfPresent(context: Context) {
            if (isOnThisPhone(context)) holder(context).askAtStart()
        }

        /** True when the AI store's file (or its .bak twin) exists. Opens nothing and makes nothing. */
        fun isOnThisPhone(context: Context): Boolean {
            val folder = File(context.applicationInfo.dataDir, "shared_prefs")
            return File(folder, "$PREFS_NAME.xml").exists() || File(folder, "$PREFS_NAME.xml.bak").exists()
        }
    }
}
