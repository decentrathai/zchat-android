package co.electriccoin.zcash.ui.screen.enhanceddestroy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyKeepSave
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.view.destroyPinVerifyErrorText
import co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DESTROY-ADV-C (register ZC-722; owner W-41, W-43, W-47, W-52; spec revision 2c, 5.1, 5.5 and 8): the screen "What
 * Destroy All erases".
 *
 * The choice is made here (or at the Destroy PIN set-up), never when Destroy runs (W-52). It is saved only behind the
 * Destroy PIN, with Destroy's own check and lockout; with no Destroy PIN there is no Save, only the way to set one up.
 * The phone's screen lock is no proof for the choice. Save goes through DestroyManager, which makes the list's key for
 * a kept choice (nothing is saved when it cannot), deletes it again for "Destroy everything" when no list is on the
 * phone, and holds the choice with the wallet-scope generation it was saved for.
 */
class DestroyOptionsVM(
    private val zchatPreferences: ZchatPreferences,
    private val destroyManager: DestroyManager,
    private val navigationRouter: NavigationRouter,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DestroyOptionsState(summary = R.string.advanced_settings_destroy_options_all))
    val state: StateFlow<DestroyOptionsState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val (hasPin, keep, existingList) =
                withContext(ioDispatcher) {
                    Triple(zchatPreferences.hasDestroyPin(), destroyManager.reloadDestroyKeep(), destroyManager.keptListExists())
                }
            val keepsContacts = CONTACTS in keep
            mutableState.update {
                it.copy(
                    hasPin = hasPin,
                    choice = if (keepsContacts) DestroyChoice.KEEP_SOME else DestroyChoice.EVERYTHING,
                    contactsTicked = keepsContacts,
                    summary = summaryOf(keepsContacts),
                    existingList = existingList,
                )
            }
        }
    }

    fun onBack() = navigationRouter.back()

    fun onToggleAdvanced() = mutableState.update { it.copy(advancedOpen = !it.advancedOpen) }

    /** "Destroy everything": the line about an earlier list is read again (a file check; nothing is decrypted). */
    fun onChooseEverything() {
        mutableState.update { it.copy(choice = DestroyChoice.EVERYTHING, notice = null, keyFailed = false, saveFailed = false) }
        viewModelScope.launch {
            val existing = withContext(ioDispatcher) { destroyManager.keptListExists() }
            mutableState.update { it.copy(existingList = existing) }
        }
    }

    fun onChooseKeep() =
        mutableState.update { it.copy(choice = DestroyChoice.KEEP_SOME, notice = null, keyFailed = false, saveFailed = false) }

    /** The tick is usable only under "Keep some things". */
    fun onTickContacts(ticked: Boolean) =
        mutableState.update { if (it.choice == DestroyChoice.KEEP_SOME) it.copy(contactsTicked = ticked) else it }

    /** Save asks for the Destroy PIN; with no PIN there is no Save. */
    fun onSave() = mutableState.update { if (it.hasPin) it.copy(pinDialogShown = true, pinInput = "", pinError = null) else it }

    fun onPinChange(pin: String) {
        if (pin.length <= MAX_PIN && pin.all { it.isDigit() }) mutableState.update { it.copy(pinInput = pin, pinError = null) }
    }

    fun onPinDismiss() = mutableState.update { it.copy(pinDialogShown = false, pinInput = "", pinError = null) }

    /** The one way out of this screen when no Destroy PIN is set: the set-up, which saves the PIN and the choice. */
    fun onSetPin() {
        if (!mutableState.value.hasPin) navigationRouter.forward(DestroyPinSetup(isCreatingWallet = false))
    }

    fun onNoticeShown() = mutableState.update { it.copy(notice = null) }

    fun onPinConfirm() {
        val current = mutableState.value
        if (!current.pinDialogShown || current.pinChecking) return
        val pin = current.pinInput
        val keep = if (current.choice == DestroyChoice.KEEP_SOME && current.contactsTicked) setOf(CONTACTS) else emptySet()
        mutableState.update { it.copy(pinChecking = true, pinError = null) }
        viewModelScope.launch {
            val result =
                runCatching { zchatPreferences.verifyDestroyPinWithLockout(pin) }
                    .getOrDefault(DestroyPinVerifyResult.Failed(attemptsRemaining = 0))
            if (result != DestroyPinVerifyResult.Success) {
                mutableState.update { it.copy(pinChecking = false, pinError = destroyPinVerifyErrorText(result)) }
                return@launch
            }
            val saved =
                withContext(ioDispatcher) {
                    destroyManager.saveDestroyKeep(keep) { zchatPreferences.setDestroyKeep(it) }
                }
            mutableState.update {
                when (saved) {
                    DestroyKeepSave.SAVED ->
                        it.copy(
                            pinDialogShown = false,
                            pinInput = "",
                            pinChecking = false,
                            summary = summaryOf(CONTACTS in keep),
                            notice =
                                if (CONTACTS in keep) {
                                    R.string.destroy_options_saved_keep_contacts
                                } else {
                                    R.string.destroy_options_saved_all
                                },
                            keyFailed = false,
                            saveFailed = false,
                        )
                    DestroyKeepSave.KEY_FAILED ->
                        it.copy(pinDialogShown = false, pinInput = "", pinChecking = false, keyFailed = true, saveFailed = false)
                    // ZC-1016: the store refused the write: its own text, not the key's.
                    DestroyKeepSave.NOT_SAVED ->
                        it.copy(pinDialogShown = false, pinInput = "", pinChecking = false, keyFailed = false, saveFailed = true)
                }
            }
        }
    }

    private fun summaryOf(keepsContacts: Boolean): Int =
        if (keepsContacts) R.string.advanced_settings_destroy_options_keep_contacts else R.string.advanced_settings_destroy_options_all

    private companion object {
        const val CONTACTS = "contacts"
        const val MAX_PIN = 8
    }
}
