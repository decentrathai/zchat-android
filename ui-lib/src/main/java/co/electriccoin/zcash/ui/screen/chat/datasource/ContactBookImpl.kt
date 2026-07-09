package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
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
 */
class ContactBookImpl(context: Context) : ContactBook {

    private val appContext = context.applicationContext

    // Build the Keystore-backed store lazily so construction stays cheap (the Keystore + keyset disk
    // reads are ~hundreds of ms — must not run on the main thread; callers read contacts from IO-
    // dispatched view-model scopes). The one-time migration from the old plaintext store runs on first
    // access, inside the same lazy block.
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        migrateLegacyPlaintextIfNeeded(encrypted)
        encrypted
    }

    /**
     * One-time move of the pre-#190 plaintext contact blob into the encrypted store, then scrub the
     * plaintext copy. Guarded on the encrypted store NOT already holding contacts so a re-run (e.g. a
     * partially-failed previous migration) can't clobber newer encrypted data with a stale plaintext
     * snapshot.
     */
    private fun migrateLegacyPlaintextIfNeeded(encrypted: SharedPreferences) {
        val legacy = appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
        val legacyBlob = legacy.getString(KEY_CONTACTS, null) ?: return
        if (!encrypted.contains(KEY_CONTACTS)) {
            encrypted.edit().putString(KEY_CONTACTS, legacyBlob).apply()
        }
        // Scrub the plaintext copy regardless — the encrypted store is now authoritative.
        legacy.edit().remove(KEY_CONTACTS).apply()
    }

    companion object {
        private const val PREFS_NAME = "zchat_contact_book_enc"
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

    override fun clearAll() {
        // commit() (not apply()) — a destroy/reset may kill the process immediately after, and the
        // contact list must be gone on disk before that, not pending an async flush.
        runCatching { prefs.edit().clear().commit() }
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
            loadContactsOrThrow()
        } catch (e: Exception) {
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
