package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * EncryptedSharedPreferences-based implementation of ContactBook.
 *
 * Contacts (a peer's shielded address + the nickname you gave them) are sensitive: the address
 * reveals WHO you converse with and the nickname is personal. They used to sit in a plaintext
 * SharedPreferences XML readable by anyone with filesystem access (#190). They are now stored in an
 * EncryptedSharedPreferences file (AES-256-GCM values, AES-256-SIV keys, master key in the Android
 * Keystore — same scheme as the E2E key store), and the legacy plaintext blob is migrated once and
 * then scrubbed.
 *
 * ZC-733-A: the store is opened through its holder ([LazyEncryptedStore]): the one opener with the chat stores' rules,
 * asked by the start-up check ([askAtStart]) after the chat storage; every other read gets the holder's answer, and the
 * reads below keep degrading a failure to an empty list. The one-time move of the old plain list runs right after
 * every successful open, before the store is handed out.
 */
class ContactBookImpl(context: Context) : ContactBook {

    private val appContext = context.applicationContext

    // ZC-733-A: the holder opens the store (never on the main thread: callers read contacts from IO-dispatched
    // view-model scopes). The one-time migration from the old plaintext store runs after every successful open.
    private val store = LazyEncryptedStore(appContext, PREFS_NAME, afterOpen = { migrateLegacyPlaintextIfNeeded(it) })

    // Lazy over the holder: the first successful open is kept; a failed one throws the holder's error each time.
    private val prefs: SharedPreferences by lazy { store.get() }

    /** ZC-733-B: true while the store cannot be opened or its list cannot be read; a read that works clears it. */
    private val unreadableState = MutableStateFlow(false)

    override val unreadable: StateFlow<Boolean> = unreadableState.asStateFlow()

    /** ZC-733-A: the start-up check's ask (counted, may set the store aside with a copy); its caller catches. */
    fun askAtStart() {
        try {
            store.askAtStart()
        } catch (e: Exception) {
            unreadableState.value = true
            throw e
        }
    }

    /**
     * One-time move of the pre-#190 plaintext contact blob into the encrypted store, then scrub the
     * plaintext copy. Guarded on the encrypted store NOT already holding contacts so a re-run (e.g. a
     * partially-failed previous migration) can't clobber newer encrypted data with a stale plaintext
     * snapshot.
     *
     * R2-5 (a): the plaintext copy is removed ONLY once the encrypted copy is proven: committed synchronously
     * (apply() has no failure channel) and read back. A failed, throwing or unreadable write leaves the plaintext
     * list in place, so the next start migrates it again instead of the user losing every contact.
     */
    private fun migrateLegacyPlaintextIfNeeded(encrypted: SharedPreferences) {
        val legacy = appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
        val legacyBlob = legacy.getString(KEY_CONTACTS, null) ?: return
        if (!encrypted.contains(KEY_CONTACTS)) {
            val committed = encrypted.edit().putString(KEY_CONTACTS, legacyBlob).commit()
            if (!committed || encrypted.getString(KEY_CONTACTS, null) != legacyBlob) return
        }
        // The encrypted store now holds a readable contact list and is authoritative: scrub the plaintext copy.
        legacy.edit().remove(KEY_CONTACTS).commit()
    }

    companion object {
        internal const val PREFS_NAME = "zchat_contact_book_enc"
        private const val PREFS_NAME_LEGACY = "zchat_contact_book"
        private const val KEY_CONTACTS = "contacts"
    }

    // Zcash bech32m addresses are canonically lowercase. Compare on a trimmed/lowercased key so
    // peer addresses routed through different surfaces (incoming sender hash, outgoing resolve)
    // still match the saved contact, instead of showing the raw "u1..." string in the chat list.
    private fun String.canonical(): String = trim().lowercase()

    override fun addContact(contact: Contact) {
        val key = contact.address.canonical()
        // Load through the THROWING loader: a transient decrypt/Keystore error must NOT be swallowed to
        // emptyList here (as the read paths do), because saveContacts() below would then overwrite the
        // whole book with just this one entry, destroying every existing contact. Abort the write instead.
        val contacts = try {
            loadContactsOrThrow().toMutableList()
        } catch (e: Exception) {
            return
        }
        contacts.removeAll { it.address.canonical() == key }
        contacts.add(contact)
        saveContacts(contacts)
    }

    override fun removeContact(address: String) {
        val key = address.canonical()
        // Same guard as addContact: don't let a failed read collapse the book to a single-entry (or empty)
        // overwrite — abort the mutation and leave the intact on-disk data untouched.
        val contacts = try {
            loadContactsOrThrow().toMutableList()
        } catch (e: Exception) {
            return
        }
        contacts.removeAll { it.address.canonical() == key }
        saveContacts(contacts)
    }

    override fun getContact(address: String): Contact? {
        val key = address.canonical()
        return getAllContactsInternal().find { it.address.canonical() == key }
    }

    override fun getAllContacts(): List<Contact> {
        return getAllContactsInternal().sortedBy { it.name.lowercase() }
    }

    override fun hasContact(address: String): Boolean {
        val key = address.canonical()
        return getAllContactsInternal().any { it.address.canonical() == key }
    }

    override fun updateContactName(address: String, newName: String) {
        val contact = getContact(address) ?: return
        addContact(contact.copy(name = newName))
    }

    // ZC-733-B: the three saves that answer whether they were done. While the list cannot be read they make one more
    // try of the store first; when it still cannot be read they write nothing and answer false.
    override fun tryAddContact(contact: Contact): Boolean {
        val contacts = readableContacts() ?: return false
        val key = contact.address.canonical()
        contacts.removeAll { it.address.canonical() == key }
        contacts.add(contact)
        saveContacts(contacts)
        return true
    }

    override fun tryRemoveContact(address: String): Boolean {
        val contacts = readableContacts() ?: return false
        val key = address.canonical()
        contacts.removeAll { it.address.canonical() == key }
        saveContacts(contacts)
        return true
    }

    override fun tryUpdateContactName(
        address: String,
        newName: String,
    ): Boolean {
        val contacts = readableContacts() ?: return false
        val key = address.canonical()
        val index = contacts.indexOfFirst { it.address.canonical() == key }
        if (index < 0) return false
        contacts[index] = contacts[index].copy(name = newName)
        saveContacts(contacts)
        return true
    }

    /** ZC-733-B: the list for a save, after one more try of the store when it cannot be read; null when it still cannot. */
    @Suppress("SwallowedException")
    private fun readableContacts(): MutableList<Contact>? {
        val first = runCatching { loadContactsOrThrow() }
        if (first.isSuccess) return first.getOrThrow().toMutableList().also { unreadableState.value = false }
        runCatching { store.tryAgain() }
        return try {
            loadContactsOrThrow().toMutableList().also { unreadableState.value = false }
        } catch (e: Exception) {
            unreadableState.value = true
            null
        }
    }

    /** P2e: the contact list as a store that moves with the wallet that owns it. ZC-733-B: through its holder. */
    fun walletScopedStores(): List<WalletScopedStore> = listOf(store.walletStore(PREFS_NAME) { prefs })

    override fun clearAll() {
        // commit() (not apply()) — a destroy/reset may kill the process immediately after, and the
        // contact list must be gone on disk before that, not pending an async flush.
        // ZC-733-B: through the holder: an open store is cleared, one that is not open is erased as a file.
        runCatching { store.wipe() }
        // Also scrub any lingering legacy plaintext store, in case a wipe happens before first read
        // triggered the migration that normally removes it.
        runCatching {
            appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun getAllContactsInternal(): List<Contact> {
        // READ paths only: degrade a read failure to an empty list so the chat list (which reads contacts
        // while building conversations) keeps rendering instead of crashing. MUTATION paths must instead
        // call loadContactsOrThrow() directly so a failed read aborts the write rather than overwriting.
        return try {
            loadContactsOrThrow().also { unreadableState.value = false }
        } catch (e: Exception) {
            // ZC-733-B: said, not shown empty: the chat list's line and the pickers' line follow this flag.
            unreadableState.value = true
            emptyList()
        }
    }

    /**
     * Load contacts, propagating any decrypt/Keystore/parse failure to the caller. A genuinely-absent
     * store (no KEY_CONTACTS yet) returns an empty list WITHOUT throwing, so a first-ever add still works.
     */
    private fun loadContactsOrThrow(): List<Contact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            Contact(
                address = obj.getString("address"),
                name = obj.getString("name"),
                addedAt = Instant.ofEpochMilli(obj.getLong("addedAt"))
            )
        }
    }

    private fun saveContacts(contacts: List<Contact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            val obj = JSONObject().apply {
                put("address", contact.address)
                put("name", contact.name)
                put("addedAt", contact.addedAt.toEpochMilli())
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }
}
