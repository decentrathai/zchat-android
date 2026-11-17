package co.electriccoin.zcash.ui.screen.changeidentity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiResetLine
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.deletewallet.ResetAnswer
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChangeIdentityVM(
    private val navigationRouter: NavigationRouter,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val zchatPreferences: ZchatPreferences,
    private val resetZashiUseCase: ResetZashiUseCase,
    private val identityManager: IdentityManager,
    private val accountDataSource: AccountDataSource,
    private val aiAccountAccess: AiAccountAccess? = null
) : ViewModel() {
    private var aiLine = AiResetLine.PLAIN
    private var aiCheck: Job? = null
    private var lineOnTheDialog = AiResetLine.PLAIN

    // Round 3: how many lines were shown: a read that started before the last of them is older than it, and shows nothing.
    private var linesShown = 0

    private val _state = MutableStateFlow(createInitialState())
    val state: StateFlow<ChangeIdentityState> = _state.asStateFlow()

    init {
        loadCurrentAddress()
        calculateContactCount()
        initializeIdentityIfNeeded()
    }

    private fun createInitialState() = ChangeIdentityState(
        currentAddress = "",
        selectedMode = IdentityMode.DIVERSIFIED,
        contactCount = 0,
        isProcessing = false,
        showConfirmationDialog = false,
        showSuccessDialog = false,
        errorMessage = null,
        onBack = ::onBack,
        onModeSelected = ::onModeSelected,
        onConfirmClick = ::onConfirmClick,
        onConfirmationDialogDismiss = ::onConfirmationDialogDismiss,
        onConfirmationDialogConfirm = ::onConfirmationDialogConfirm,
        onSuccessDialogDismiss = ::onSuccessDialogDismiss,
        onErrorDismiss = ::onErrorDismiss
    )

    private fun initializeIdentityIfNeeded() {
        viewModelScope.launch {
            try {
                // Initialize the default identity if this is the first time
                if (identityManager.getAllIdentities().isEmpty()) {
                    val address = getDefaultUnifiedAddress()
                    (identityManager as? IdentityManagerImpl)?.initializeDefaultIdentity(address, "Default")
                }
            } catch (e: Exception) {
                // Non-critical, will be initialized when address is loaded
            }
        }
    }

    private fun loadCurrentAddress() {
        viewModelScope.launch {
            try {
                val address = getDefaultUnifiedAddress()
                _state.update { it.copy(currentAddress = address) }
            } catch (e: Exception) {
                // R4-6 (12): blank, and the card says so in the phone's language (identity_error_loading_address).
                _state.update { it.copy(currentAddress = "") }
            }
        }
    }

    private fun calculateContactCount() {
        viewModelScope.launch {
            // Get unique contacts from address book and conversation list
            val addressBookContacts = zchatPreferences.getAllContactAddresses()
            val chatContacts = zchatPreferences.getAllConversationPeerAddresses()
            val uniqueContacts = (addressBookContacts + chatContacts).toSet()
            _state.update { it.copy(contactCount = uniqueContacts.size) }
        }
    }

    private fun onBack() {
        navigationRouter.back()
    }

    private fun onModeSelected(mode: IdentityMode) {
        _state.update { it.copy(selectedMode = mode) }
    }

    private fun onConfirmClick() {
        _state.update { it.copy(showConfirmationDialog = true) }
        if (_state.value.selectedMode == IdentityMode.FULL_RESET) readAiLine()
    }

    /**
     * AI-KEY-T (spec 5.3): the dialog says what the Reset screen says. The stored state first; the one check of this
     * screen at most once and at most 8 seconds, on the way to a Full Wallet Reset only. W-97: Confirm waits for what is
     * left of it, with the form showing that it is working.
     */
    private fun readAiLine() {
        val access = aiAccountAccess ?: return
        if (aiCheck == null) {
            aiCheck = viewModelScope.launch { access.resetScreenCheck(::showAiLine) }
        } else {
            viewModelScope.launch {
                val before = linesShown
                val read = access.resetLine()
                if (linesShown == before) showAiLine(read)
            }
        }
    }

    private fun showAiLine(line: AiResetLine) {
        linesShown++
        aiLine = line
        val note =
            when (line) {
                AiResetLine.UNREADABLE -> R.string.identity_reset_ai_note_ai_unreadable
                AiResetLine.KEPT -> R.string.identity_reset_ai_note_ai_kept
                AiResetLine.KEPT_PARKED -> R.string.identity_reset_ai_note_ai_kept_parked
                AiResetLine.LINKED -> R.string.identity_reset_ai_note_ai_linked
                AiResetLine.PLAIN -> null
            }
        _state.update { it.copy(aiResetNote = note) }
    }

    private fun onConfirmationDialogDismiss() {
        _state.update { it.copy(showConfirmationDialog = false) }
    }

    private fun onConfirmationDialogConfirm() {
        lineOnTheDialog = aiLine
        _state.update {
            it.copy(
                showConfirmationDialog = false,
                isProcessing = true
            )
        }

        viewModelScope.launch {
            try {
                when (_state.value.selectedMode) {
                    IdentityMode.FULL_RESET -> performFullReset()
                    IdentityMode.DIVERSIFIED -> performDiversifiedChange()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        // R4-6 (12): a resource, not the raw exception text — that was English on every phone and
                        // could carry technical detail the user cannot act on.
                        errorMessage = stringRes(R.string.identity_error_failed)
                    )
                }
            }
        }
    }

    private suspend fun performFullReset() {
        aiCheck?.join()
        // W-97: the dialog is closed by now, so only the warning it did show counts as shown. A store that became unreadable
        // during the wait makes the Reset refuse (nothing deleted), and the next dialog shows that warning.
        val unreadableShown = lineOnTheDialog == AiResetLine.UNREADABLE && aiLine == AiResetLine.UNREADABLE
        // ZC-090-R: a started Reset ends with ZCHAT's restart (the identities file is deleted at the next start), so
        // there is no success to show and nothing to clear here. One that was cancelled at the unlock prompt gives the
        // form back; one that did not start says so with the Reset's own text: nothing was deleted.
        when (resetZashiUseCase(keepFiles = false, aiUnreadableWarningShown = unreadableShown)) {
            ResetAnswer.STARTED -> Unit
            ResetAnswer.CANCELLED -> _state.update { it.copy(isProcessing = false) }
            ResetAnswer.NOT_STARTED ->
                _state.update {
                    it.copy(isProcessing = false, errorMessage = stringRes(R.string.delete_wallet_failed))
                }
            ResetAnswer.NOT_STARTED_PAYMENT_RUNNING ->
                _state.update {
                    it.copy(isProcessing = false, errorMessage = stringRes(R.string.delete_wallet_failed_payment_running))
                }
            ResetAnswer.NOT_STARTED_PAYMENT_OPEN ->
                _state.update {
                    it.copy(isProcessing = false, errorMessage = stringRes(R.string.delete_wallet_failed_payment_open))
                }
        }
    }

    private suspend fun performDiversifiedChange() {
        try {
            // ZC-350: the chat identity before the change. GetDefaultUnifiedAddressUseCase returns the pinned chat
            // identity (P2g), so asking the wallet for its next address may change nothing at all.
            val oldAddress = getDefaultUnifiedAddress()

            // ZC-350 (review 4): a saved identity never moves, so this mode cannot change it in this version. Decide
            // before the wallet is asked for its next address, so nothing changes, and say so (no retry offered).
            if (getDefaultUnifiedAddress.isPinned()) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = stringRes(R.string.identity_error_diversified_pinned)
                    )
                }
                return
            }

            // Step 1: Request a new diversified address from the SDK
            accountDataSource.requestNextShieldedAddress()

            // Step 2: Get the new address
            val newAddress = getDefaultUnifiedAddress()

            // ZC-350: an identity that did not change is never told as "Success!": the user would share the old
            // address as a new one. No identity is made for it.
            if (newAddress == oldAddress) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = stringRes(R.string.identity_error_change_failed)
                    )
                }
                return
            }

            // Step 3: Create a new identity with the new address
            val identityCount = identityManager.getAllIdentities().size
            val newIdentity = (identityManager as? IdentityManagerImpl)?.createDiversifiedIdentity(
                address = newAddress,
                name = "Identity ${identityCount + 1}"
            )

            if (newIdentity == null) {
                throw Exception("Failed to create new identity")
            }

            // Step 4: Set the new identity as active
            identityManager.setActiveIdentity(newIdentity.id)

            // R2-7 (a): no contact is notified (nothing ever sent the ADDR notice); the screen says so.
            _state.update {
                it.copy(
                    isProcessing = false,
                    showSuccessDialog = true,
                    currentAddress = newAddress
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isProcessing = false,
                    errorMessage = stringRes(R.string.identity_error_change_failed)
                )
            }
        }
    }

    private fun onSuccessDialogDismiss() {
        _state.update { it.copy(showSuccessDialog = false) }

        // Navigate back on OK for BOTH modes. Diversified always needs it. A successful FULL_RESET normally
        // tears this screen down via the secretState->NONE bounce (wallet deleted), but that StateFlow
        // emission can lag the WhileSubscribed window — backing out here guarantees the OK button is never a
        // dead no-op even if the bounce is delayed.
        navigationRouter.back()
    }

    private fun onErrorDismiss() {
        _state.update { it.copy(errorMessage = null) }
    }
}
