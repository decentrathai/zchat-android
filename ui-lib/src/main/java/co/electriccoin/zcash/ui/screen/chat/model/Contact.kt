package co.electriccoin.zcash.ui.screen.chat.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * ZC-733-B: true while the saved names cannot be read (the store cannot be opened, or its list cannot be read); the
     * chat list and the pickers of saved contacts say so. Default: never.
     */
    val unreadable: StateFlow<Boolean> get() = NEVER_UNREADABLE

    /** ZC-733-B: [addContact] that answers whether it was done (false, with nothing written, while unreadable). */
    fun tryAddContact(contact: Contact): Boolean {
        addContact(contact)
        return true
    }

    /** ZC-733-B: [removeContact] that answers whether it was done. */
    fun tryRemoveContact(address: String): Boolean {
        removeContact(address)
        return true
    }

    /** ZC-733-B: [updateContactName] that answers whether it was done. */
    fun tryUpdateContactName(
        address: String,
        newName: String,
    ): Boolean {
        updateContactName(address, newName)
        return true
    }
}

/** ZC-733-B: the flag of a contact book that is always readable. */
private val NEVER_UNREADABLE: StateFlow<Boolean> = MutableStateFlow(false)
