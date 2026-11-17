package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * P2e — the chats on this phone belong to one wallet (one recovery phrase).
 *
 * Every chat store (messages, contacts, chat keys, Nostr state, identities, avatars) is owned by the
 * wallet that made it. When a DIFFERENT recovery phrase is loaded, the previous wallet's chats are set
 * aside in a bundle named after that wallet and the live stores start empty; they are never deleted.
 * When that phrase is loaded again, its bundle is put back. The same phrase keeps everything in place.
 *
 * The owner is named by [chatOwnerTag], a one-way hash of the phrase — never the phrase itself.
 *
 * D6 (REVIEW_7C F17): the kept chat copies, paused restores and restore marks on the phone that name no wallet
 * ([untaggedMarks]) are given to the wallet that owns the chats whenever the owner record is filled or handed over,
 * so none of them is ever counted as the next wallet's.
 */
class ChatWalletOwnership(
    private val record: ChatOwnerRecord,
    private val bundles: SetAsideBundles,
    private val live: LiveChatStores,
    private val untaggedMarks: UntaggedChatMarks,
) {
    /**
     * Called by Delete Wallet before anything is erased: the chats on the phone belong to the wallet
     * being deleted. Only fills an empty record — an install from before this feature has none.
     * D6: everything on the phone that names no wallet is that wallet's too; throws (recording nothing) when
     * that could not be written.
     */
    @Synchronized
    fun recordOwnerIfUnset(walletTag: String) {
        if (record.owner() == null) {
            tagUntaggedWith(walletTag)
            record.setOwner(walletTag)
        }
    }

    /** Called after Delete Wallet erased the chat stores on purpose: they belong to no wallet now. */
    @Synchronized
    fun markWiped() {
        record.setOwner(NO_CHAT_OWNER)
    }

    /**
     * Called before [walletTag]'s wallet is saved as the wallet of this phone, so no chat code can run
     * for it until its chats are in place. Throws (and leaves the previous wallet's chats where they
     * were) when the chats cannot be set aside.
     */
    @Synchronized
    fun claimFor(walletTag: String): ChatClaim {
        val owner = record.owner()
        if (owner == walletTag) return ChatClaim.KEPT
        if (owner == null) {
            // Installed before P2e: nothing says whose chats these are. Give them to this wallet, hide nothing.
            // D6: the kept copies and restore marks on the phone included.
            tagUntaggedWith(walletTag)
            record.setOwner(walletTag)
            return ChatClaim.ADOPTED
        }
        // Round 8 D11 (Grok CLI review, G14): the live stores are about to change wallets. Under the wallet-scope lock
        // (which the held group replies' generation check and write also take), the generation goes up BEFORE
        // anything is exported or imported, so a reply of the outgoing wallet is never written into the next one's
        // stores between the import and the reload.
        return synchronized(ZchatPreferences.walletScopeLock) {
            // D6 (REVIEW_7C F17): whatever on the phone names no wallet is the outgoing wallet's; tagged before its
            // chats are set aside, so the next wallet is never offered them. A failed write stops the claim here.
            // ZC-296: and before the wallet scope changes, so a failed write leaves a Join in progress running.
            if (owner != NO_CHAT_OWNER) tagUntaggedWith(owner)
            live.beginChange()
            moveStores(owner, walletTag)
        }
    }

    /** [claimFor] for a claim that moves the stores: [owner] (or [NO_CHAT_OWNER]) hands them to [walletTag]. */
    private fun moveStores(
        owner: String,
        walletTag: String,
    ): ChatClaim {
        val stores = live.stores()
        if (owner != NO_CHAT_OWNER) {
            // The kept copies were tagged with [owner] by [claimFor] before the wallet scope changed (ZC-296).
            // Copy first; nothing is cleared unless the whole copy is on disk (write throws otherwise).
            bundles.write(owner, stores.associate { it.storeId to it.exportEntries() })
            // From here the live stores belong to nobody: a retry after a crash clears them again and
            // never copies a half-cleared state over the full copy just written.
            record.setOwner(NO_CHAT_OWNER)
        }
        val own = bundles.read(walletTag)
        val ownAi = own?.get(AI_STORE)
        // ZC-1031: the claiming wallet's own saved key is the saved one; when its saved AI part holds no token (or there
        // is none), nobody's live key is kept beside that saved AI data.
        val ownAiHasKey = ownAi?.get(AiPreferences.ACCESS_KEY_ENTRIES.first()) != null
        val liveAi = if (owner == NO_CHAT_OWNER) liveAiEntries(stores) else emptyMap()
        val keptKey = if (ownAiHasKey) emptyMap() else liveAccessKey(liveAi)
        // ZC-1177: parked keys are the only way to a paid account the phrase cannot open: neither list loses an entry.
        // ZC-1195: nor does nobody's live key get lost under the wallet's own key: it is parked unless known to be unpaid.
        val ownToken = ownAi?.get(AiPreferences.ACCESS_KEY_ENTRIES.first()) as? String
        val parked =
            AiPreferences.joinParkedKeys(
                AiPreferences.joinParkedKeys(
                    ownAi?.get(AiPreferences.KEY_PARKED_KEYS) as? String,
                    liveAi[AiPreferences.KEY_PARKED_KEYS] as? String,
                ),
                if (ownAiHasKey) AiPreferences.liveKeyToPark(liveAi, ownToken) else null,
            )
        val keptAi = if (parked == null) keptKey else keptKey + (AiPreferences.KEY_PARKED_KEYS to parked)
        try {
            stores.forEach { store ->
                val entries =
                    if (store.storeId == AI_STORE && keptAi.isNotEmpty()) {
                        ownAi.orEmpty() + keptAi
                    } else {
                        own?.get(store.storeId).orEmpty()
                    }
                store.importEntries(entries)
            }
        } finally {
            live.reload()
        }
        record.setOwner(walletTag)
        if (own != null) bundles.delete(walletTag)
        return if (own != null) ChatClaim.RESTORED else ChatClaim.STARTED_EMPTY
    }

    /**
     * ZC-1031 (rule K-0, W-52): the AI access key in the live AI store, when nobody's chats are claimed: the token and
     * its user id, nothing else of the store (a verdict was an earlier wallet's answer); empty when there is no token.
     * A Reset that kept nothing (an earlier development build) left it there, and it is the only way to the credit paid
     * for.
     */
    private fun liveAccessKey(live: Map<String, Any?>): Map<String, Any?> {
        if (live[AiPreferences.ACCESS_KEY_ENTRIES.first()] == null) return emptyMap()
        return live.filterKeys { it in AiPreferences.ACCESS_KEY_ENTRIES }
    }

    /** The live AI store's entries; empty when it cannot be read. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun liveAiEntries(stores: List<WalletScopedStore>): Map<String, Any?> =
        try {
            stores.firstOrNull { it.storeId == AI_STORE }?.exportEntries().orEmpty()
        } catch (e: Exception) {
            emptyMap()
        }

    private fun tagUntaggedWith(walletTag: String) {
        check(untaggedMarks.tagUntaggedWith(walletTag)) { "The owner of the kept chat copies could not be written" }
    }
}

/**
 * D6 (REVIEW_7C F17): the kept chat copies, paused restores and restore marks on the phone that name no wallet
 * ([KeptChatStoreCopies]).
 */
fun interface UntaggedChatMarks {
    /** Tags every one of them with [owner]. True when that is on disk, or there was nothing to tag. */
    fun tagUntaggedWith(owner: String): Boolean
}

/** What [ChatWalletOwnership.claimFor] did. */
enum class ChatClaim {
    /** The chats on the phone already belonged to this wallet. */
    KEPT,

    /** An install from before P2e (no owner recorded): everything on the phone was given to this wallet. */
    ADOPTED,

    /** Another wallet's chats were set aside (or none were there); this wallet starts with no chats. */
    STARTED_EMPTY,

    /** This wallet's own chats, set aside earlier, were put back. */
    RESTORED,
}

/** Owner value meaning "the live chat stores belong to no wallet and must be empty". */
internal const val NO_CHAT_OWNER = "none"

/** ZC-1031: the AI store's id among the wallet-scoped stores. */
private const val AI_STORE = AiPreferences.PREFS_NAME

/** Where the owner of the live chat stores is written down. */
interface ChatOwnerRecord {
    /** The owner tag, [NO_CHAT_OWNER], or null on an install that never recorded one. */
    fun owner(): String?

    /** Must be on disk when this returns; throws otherwise. */
    fun setOwner(tag: String)
}

/** Set-aside chats, one bundle per wallet tag: store id -> that store's entries. */
interface SetAsideBundles {
    /** Replaces the whole bundle for [tag]. Must be on disk when this returns; throws otherwise. */
    fun write(
        tag: String,
        content: Map<String, Map<String, Any?>>
    )

    /**
     * The same, asking [mayPublish] once, right before the commit: false publishes nothing. A writer that outlives the
     * Reset it belongs to is refused here (W-97, RT_ROUND2 item 5).
     */
    fun write(
        tag: String,
        content: Map<String, Map<String, Any?>>,
        mayPublish: () -> Boolean,
    ) {
        if (mayPublish()) write(tag, content)
    }

    /** The complete bundle for [tag], or null when there is none. */
    fun read(tag: String): Map<String, Map<String, Any?>>?

    fun delete(tag: String)
}

/** One store that belongs to a wallet and moves with it. */
interface WalletScopedStore {
    /** Stable name of the store inside a bundle. */
    val storeId: String

    /** Every entry, readable (decrypted for an encrypted store). Throws when the store cannot be read. */
    fun exportEntries(): Map<String, Any?>

    /** Replaces the store's whole content with [entries]. Throws when it could not be written. */
    fun importEntries(entries: Map<String, Any?>)
}

/** The live chat stores and the in-memory copies that must be re-read after they change. */
interface LiveChatStores {
    fun stores(): List<WalletScopedStore>

    /**
     * Round 8 D11 (G14) — called before any store is exported or rewritten for another wallet: marks the in-memory
     * state of the outgoing wallet as stale ([ZchatPreferences.beginWalletScopeChange]).
     */
    fun beginChange() {}

    /** Re-reads every in-memory copy of the stores from disk. */
    fun reload()
}

/**
 * One-way name of the wallet that owns the chats: SHA-256 over a fixed label and the phrase words
 * (trimmed, lower case). The phrase cannot be recovered from it.
 */
fun chatOwnerTag(seedWords: List<String>): String {
    val normalized = seedWords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.joinToString(" ")
    val digest = MessageDigest.getInstance("SHA-256").digest("$CHAT_OWNER_TAG_LABEL$normalized".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private const val CHAT_OWNER_TAG_LABEL = "zchat-chat-owner-v1\n"

/** Encodes one store's entries (the SharedPreferences value types) as JSON, keeping each value's type. */
internal fun encodeStoreEntries(entries: Map<String, Any?>): String {
    val out = JSONObject()
    entries.forEach { (key, value) ->
        val typed =
            when (value) {
                null -> return@forEach
                is String -> JSONArray().put("S").put(value)
                is Boolean -> JSONArray().put("B").put(value)
                is Int -> JSONArray().put("I").put(value)
                is Long -> JSONArray().put("L").put(value.toString())
                is Float -> JSONArray().put("F").put(value.toString())
                is Set<*> -> JSONArray().put("T").put(JSONArray(value.map { it as String }))
                else -> throw IllegalArgumentException("Store entry $key has an unsupported type ${value.javaClass.name}")
            }
        out.put(key, typed)
    }
    return out.toString()
}

/** Inverse of [encodeStoreEntries]. */
internal fun decodeStoreEntries(json: String): Map<String, Any?> {
    val obj = JSONObject(json)
    val out = LinkedHashMap<String, Any?>()
    obj.keys().forEach { key ->
        val typed = obj.getJSONArray(key)
        out[key] =
            when (val type = typed.getString(0)) {
                "S" -> typed.getString(1)
                "B" -> typed.getBoolean(1)
                "I" -> typed.getInt(1)
                "L" -> typed.getString(1).toLong()
                "F" -> typed.getString(1).toFloat()
                "T" -> {
                    val arr = typed.getJSONArray(1)
                    (0 until arr.length()).mapTo(LinkedHashSet()) { arr.getString(it) }
                }
                else -> throw IllegalArgumentException("Unknown entry type $type for $key")
            }
    }
    return out
}
