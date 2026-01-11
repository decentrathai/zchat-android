package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * SharedPreferences-based implementation of ContactBook.
 * Stores contacts locally on the device.
 */
class ContactBookImpl(context: Context) : ContactBook {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "zchat_contact_book"
        private const val KEY_CONTACTS = "contacts"
    }

    override fun addContact(contact: Contact) {
        val contacts = getAllContactsInternal().toMutableList()
        // Remove existing if present (update)
        contacts.removeAll { it.address == contact.address }
        contacts.add(contact)
        saveContacts(contacts)
    }

    override fun removeContact(address: String) {
        val contacts = getAllContactsInternal().toMutableList()
        contacts.removeAll { it.address == address }
        saveContacts(contacts)
    }

    override fun getContact(address: String): Contact? {
        return getAllContactsInternal().find { it.address == address }
    }

    override fun getAllContacts(): List<Contact> {
        return getAllContactsInternal().sortedBy { it.name.lowercase() }
    }

    override fun hasContact(address: String): Boolean {
        return getAllContactsInternal().any { it.address == address }
    }

    override fun updateContactName(address: String, newName: String) {
        val contact = getContact(address) ?: return
        addContact(contact.copy(name = newName))
    }

    private fun getAllContactsInternal(): List<Contact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                Contact(
                    address = obj.getString("address"),
                    name = obj.getString("name"),
                    addedAt = Instant.ofEpochMilli(obj.getLong("addedAt"))
                )
            }
        } catch (e: Exception) {
            emptyList()
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
