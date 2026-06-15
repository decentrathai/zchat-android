package co.electriccoin.zcash.ui.screen.chat.model

import java.time.Instant

/**
 * Represents a contact in the ZCHAT contact book.
 */
data class Contact(
    val address: String,
    val name: String,
    val addedAt: Instant = Instant.now()
)

/**
 * Interface for contact book storage.
 */
interface ContactBook {
    fun addContact(contact: Contact)
    fun removeContact(address: String)
    fun getContact(address: String): Contact?
    fun getAllContacts(): List<Contact>
    fun hasContact(address: String): Boolean
    fun updateContactName(address: String, newName: String)

    /**
     * Wipe every saved contact. Contacts are sensitive (who you talk to + personal nicknames) and live
     * in their own encrypted store outside ZchatPreferences, so destroy/reset must clear them here too
     * — otherwise a "Delete Wallet"/reset leaves the old contacts attached to a fresh wallet.
     */
    fun clearAll()
}
