package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol

/**
 * P2g: where our own chat address (the identity every chat message carries) is kept once it has
 * been read, one per wallet account. [accountKey] names the account (its SDK account id).
 */
interface OwnChatIdentityStore {
    /** The identity saved for [accountKey], or null when none has been saved yet. */
    fun pinnedIdentity(accountKey: String): String?

    /** Saves [address] as the identity of [accountKey] unless one is saved already; returns the saved one. */
    fun pinIdentityIfAbsent(
        accountKey: String,
        address: String
    ): String

    /** Keeps nothing: every read is fresh. For callers built without a store (tests). */
    object NotPinned : OwnChatIdentityStore {
        override fun pinnedIdentity(accountKey: String): String? = null

        override fun pinIdentityIfAbsent(
            accountKey: String,
            address: String
        ): String = address
    }
}

/**
 * #205 self-address registry, and (P2g) the saved chat identity.
 *
 * A wallet presents several valid unified-address strings, so "is this address me?" cannot be a raw
 * string compare. We record the hash of every representation of OUR OWN address we observe and match
 * by hash. Backed by [prefs] (the wallet-scoped `zchat_preferences` store), so it survives process
 * death, is shared by ChatViewModel and GroupViewModel, and moves with the wallet's chats (P2e).
 *
 * Two lists, so your own chat address is never dropped:
 *  - kept for good: each saved chat identity, plus every hash a 2.12.4 install had recorded (at most
 *    16, and it cannot be told which of them was the identity);
 *  - capped at [MAX_SELF_ADDR_HASHES], oldest dropped first: every other address registered, in
 *    practice the rotating Receive address ZChat sees on each start. It is stored as an ordered
 *    string, because SharedPreferences hands a string set back in no particular order.
 */
class SelfAddressRegistry(
    private val prefs: SharedPreferences
) : OwnChatIdentityStore {
    // The file itself is the lock: Android hands out one SharedPreferences object per file, so every
    // registry over the same file (and every read of the identity) is serialised.
    private val lock: Any = prefs

    fun register(address: String) {
        if (address.isBlank()) return
        val hash = ZMSGProtocol.generateAddressHash(address)
        synchronized(lock) {
            if (keptForGood().contains(hash)) return
            val recent = recent()
            if (recent.lastOrNull() == hash) return
            recent.remove(hash)
            recent.add(hash)
            while (recent.size > MAX_SELF_ADDR_HASHES) recent.removeAt(0)
            prefs.edit().putString(RECENT_SELF_ADDR_HASHES_KEY, recent.joinToString("\n")).apply()
        }
    }

    fun isSelf(address: String): Boolean {
        if (address.isBlank()) return false
        val hash = ZMSGProtocol.generateAddressHash(address)
        return keptForGood().contains(hash) || recent().contains(hash)
    }

    override fun pinnedIdentity(accountKey: String): String? =
        prefs.getString(identityKey(accountKey), null)?.takeIf { it.isNotBlank() }

    override fun pinIdentityIfAbsent(
        accountKey: String,
        address: String
    ): String {
        synchronized(lock) {
            pinnedIdentity(accountKey)?.let { return it }
            if (address.isBlank()) return address
            val hash = ZMSGProtocol.generateAddressHash(address)
            val kept = HashSet(keptForGood()).apply { add(hash) }
            val recent = recent().apply { remove(hash) }
            // One write: the identity and its "this is me" entry are saved together, and at once.
            prefs
                .edit()
                .putString(identityKey(accountKey), address)
                .putStringSet(SELF_ADDR_HASHES_KEY, kept)
                .putString(RECENT_SELF_ADDR_HASHES_KEY, recent.joinToString("\n"))
                .commit()
            return address
        }
    }

    private fun keptForGood(): Set<String> = prefs.getStringSet(SELF_ADDR_HASHES_KEY, emptySet()) ?: emptySet()

    private fun recent(): MutableList<String> =
        prefs
            .getString(RECENT_SELF_ADDR_HASHES_KEY, null)
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            ?.toMutableList() ?: mutableListOf()

    private fun identityKey(accountKey: String) = "$OWN_CHAT_IDENTITY_KEY_PREFIX$accountKey"

    companion object {
        // #205 self-address hashes. Since P2g: the list kept for good (saved identities, and what an
        // older install had recorded under this key).
        const val SELF_ADDR_HASHES_KEY = "self_addr_hashes"

        // P2g: every other own address seen, newest last, capped.
        const val RECENT_SELF_ADDR_HASHES_KEY = "self_addr_hashes_recent"
        const val MAX_SELF_ADDR_HASHES = 16

        // P2g: the saved chat identity of one wallet account ("own_chat_identity:<account id>").
        const val OWN_CHAT_IDENTITY_KEY_PREFIX = "own_chat_identity:"
    }
}
