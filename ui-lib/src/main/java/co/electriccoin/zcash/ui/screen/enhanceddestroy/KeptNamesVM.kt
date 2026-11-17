package co.electriccoin.zcash.ui.screen.enhanceddestroy

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.util.KeptName
import co.electriccoin.zcash.ui.screen.chat.util.KeptNames
import co.electriccoin.zcash.ui.screen.chat.util.KeptNamesRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * DESTROY-ADV-N (register ZC-722; owner W-41, W-43, W-47, W-52; spec revision 2c, 4.3 and 5.2): the screen "Names
 * you kept".
 *
 * A kept name is a NAME, not a contact (decision B). The only way one becomes a contact is the user's own "Save as
 * contact" tap, one name per tap: it does what the chat's own Add contact does (the chat contact book and the wallet's
 * Contacts list, chain "zcash"), checks that the contact book now has the address, and only then takes the name off
 * the list. There is no "save all". A name whose address is already a saved contact is not shown. A list that cannot
 * be read shows its message and only "Erase the whole list", which asks first.
 */
class KeptNamesVM(
    private val keptNames: KeptNames,
    private val contactBook: ContactBook,
    private val addressBookRepository: AddressBookRepository,
    private val navigationRouter: NavigationRouter,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    constructor(
        context: Context,
        contactBook: ContactBook,
        addressBookRepository: AddressBookRepository,
        navigationRouter: NavigationRouter,
    ) : this(KeptNames.of(context), contactBook, addressBookRepository, navigationRouter, Dispatchers.IO)

    private val mutableState =
        MutableStateFlow(
            KeptNamesState(
                entries = emptyList(),
                unreadable = false,
                allSaved = false,
                eraseConfirmShown = false,
                notice = null,
                onBack = ::onBack,
            )
        )
    val state: StateFlow<KeptNamesState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun onBack() = navigationRouter.back()

    /** Saves the one entry of [address] as a contact; only a write that took takes it off the list. */
    fun onSaveAsContact(address: String) {
        viewModelScope.launch {
            val notice =
                withContext(ioDispatcher) {
                    val entry = shownEntries()?.firstOrNull { it.address == address } ?: return@withContext null
                    contactBook.addContact(Contact(address = entry.address, name = entry.name, addedAt = Instant.now()))
                    addressBookRepository.saveContact(name = entry.name, address = entry.address, chain = "zcash")
                    when {
                        !contactBook.hasContact(entry.address) -> R.string.kept_names_save_failed
                        keptNames.remove(entry.address) -> R.string.kept_names_saved
                        // Saved as a contact; the list could not be changed, and the entry is not shown any more.
                        else -> R.string.kept_names_saved
                    }
                }
            reload(notice)
        }
    }

    /** Takes [address] off the list without saving it. */
    fun onRemove(address: String) {
        viewModelScope.launch {
            val removed = withContext(ioDispatcher) { keptNames.remove(address) }
            reload(if (removed) null else R.string.kept_names_change_failed)
        }
    }

    /** "Erase the whole list": asks first. */
    fun onEraseWholeList() {
        mutableState.update { it.copy(eraseConfirmShown = true) }
    }

    fun onEraseDismissed() {
        mutableState.update { it.copy(eraseConfirmShown = false) }
    }

    fun onEraseConfirmed() {
        mutableState.update { it.copy(eraseConfirmShown = false) }
        viewModelScope.launch {
            val erased = withContext(ioDispatcher) { keptNames.eraseAll() }
            reload(if (erased) null else R.string.kept_names_change_failed)
            if (erased) navigationRouter.back()
        }
    }

    fun onNoticeShown() {
        mutableState.update { it.copy(notice = null) }
    }

    /** The list's names whose address is not a saved contact; null when the list cannot be read. */
    private fun shownEntries(): List<KeptName>? = shown(keptNames.read())

    private fun shown(read: KeptNamesRead): List<KeptName>? =
        when (read) {
            is KeptNamesRead.Names -> read.entries.filterNot { contactBook.hasContact(it.address) }
            KeptNamesRead.NoList -> emptyList()
            KeptNamesRead.CannotBeRead -> null
        }

    private suspend fun reload(notice: Int? = null) {
        val read = withContext(ioDispatcher) { keptNames.read() }
        val shown = withContext(ioDispatcher) { shown(read) }
        mutableState.update { state ->
            state.copy(
                entries = shown.orEmpty().map { KeptNameItem(it.address, it.name, stringResByAddress(it.address)) },
                unreadable = read is KeptNamesRead.CannotBeRead,
                allSaved = read is KeptNamesRead.Names && read.entries.isNotEmpty() && shown.isNullOrEmpty(),
                notice = notice ?: state.notice,
            )
        }
    }
}
