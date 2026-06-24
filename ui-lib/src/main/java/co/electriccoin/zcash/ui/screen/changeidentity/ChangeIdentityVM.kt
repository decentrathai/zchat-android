package co.electriccoin.zcash.ui.screen.changeidentity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import co.electriccoin.zcash.ui.common.util.redactAddress
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
    private val accountDataSource: AccountDataSource
) : ViewModel() {

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
        selectedNotification = NotificationOption.NOTIFY_ALL,
        contactCount = 0,
        estimatedCost = "0.00001 ZEC",
        isProcessing = false,
        showConfirmationDialog = false,
        showSuccessDialog = false,
        errorMessage = null,
        onBack = ::onBack,
        onModeSelected = ::onModeSelected,
        onNotificationSelected = ::onNotificationSelected,
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
                _state.update { it.copy(currentAddress = "Error loading address") }
            }
        }
    }

    private fun calculateContactCount() {
        viewModelScope.launch {
            // Get unique contacts from address book and conversation list
            val addressBookContacts = zchatPreferences.getAllContactAddresses()
            val chatContacts = zchatPreferences.getAllConversationPeerAddresses()
            val uniqueContacts = (addressBookContacts + chatContacts).toSet()
            val count = uniqueContacts.size

            // Calculate estimated cost: ~0.0001 ZEC per contact notification
            val costZatoshi = count * 10000L // 0.0001 ZEC per notification
            val costZec = costZatoshi / 100_000_000.0
            val costFormatted = if (costZec < 0.00001) "< 0.00001 ZEC" else String.format("%.5f ZEC", costZec)

            _state.update {
                it.copy(
                    contactCount = count,
                    estimatedCost = costFormatted
                )
            }
        }
    }

    private fun onBack() {
        navigationRouter.back()
    }

    private fun onModeSelected(mode: IdentityMode) {
        _state.update { it.copy(selectedMode = mode) }
    }

    private fun onNotificationSelected(notification: NotificationOption) {
        _state.update { it.copy(selectedNotification = notification) }
    }

    private fun onConfirmClick() {
        _state.update { it.copy(showConfirmationDialog = true) }
    }

    private fun onConfirmationDialogDismiss() {
        _state.update { it.copy(showConfirmationDialog = false) }
    }

    private fun onConfirmationDialogConfirm() {
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
                        errorMessage = stringRes("Failed: ${e.message ?: "Unknown error"}")
                    )
                }
            }
        }
    }

    private suspend fun performFullReset() {
        // Run the wallet wipe FIRST and act ONLY on a confirmed reset. ResetZashiUseCase requests biometrics
        // and, if the user CANCELS/fails them (or it errors), returns false WITHOUT wiping anything. The old
        // code wiped identityManager.clearAll() up-front and then UNCONDITIONALLY showed "Success! Your wallet
        // has been reset" — even on the cancel path, where nothing was reset, the wallet stayed READY so the
        // screen was never torn down, and the success dialog's OK was a permanent dead no-op (FULL_RESET).
        val didReset = resetZashiUseCase(keepFiles = false)

        if (didReset) {
            identityManager.clearAll()
            _state.update {
                it.copy(
                    isProcessing = false,
                    showSuccessDialog = true
                )
            }
        } else {
            // Cancelled / failed / errored — nothing was reset and the wallet is intact. Return to the
            // editable form with a re-tappable button and NO false success dialog.
            _state.update { it.copy(isProcessing = false) }
        }
    }

    private suspend fun performDiversifiedChange() {
        try {
            // Step 1: Request a new diversified address from the SDK
            accountDataSource.requestNextShieldedAddress()

            // Step 2: Get the new address
            val newAddress = getDefaultUnifiedAddress()
            val oldAddress = _state.value.currentAddress

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

            // Step 5: Send ADDR notifications if enabled
            if (_state.value.selectedNotification == NotificationOption.NOTIFY_ALL) {
                sendAddressChangeNotifications(oldAddress, newAddress)
            }

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
                    errorMessage = stringRes("Failed to change identity: ${e.message}")
                )
            }
        }
    }

    /**
     * Send ADDR notification messages to all contacts.
     * This notifies contacts that the user has changed their address.
     */
    private suspend fun sendAddressChangeNotifications(oldAddress: String, newAddress: String) {
        // Get all unique contacts
        val addressBookContacts = zchatPreferences.getAllContactAddresses()
        val chatContacts = zchatPreferences.getAllConversationPeerAddresses()
        val uniqueContacts = (addressBookContacts + chatContacts).toSet()

        // For each contact, we would send an ADDR message
        // This requires access to the Synchronizer to send transactions
        // For now, we log the notification intent
        // In a full implementation, this would:
        // 1. Create an ADDR message using ZMSGProtocol.createV4ADDRMessage()
        // 2. Send a transaction to each contact with the ADDR message in the memo
        // 3. Sign the message with the new private key

        // TODO: Implement actual notification sending
        // This requires integration with the send flow which is complex
        // For now, the identity change works but notifications are not sent

        android.util.Log.d("ChangeIdentityVM", "Would notify ${uniqueContacts.size} contacts of address change from ${oldAddress.redactAddress()} to ${newAddress.redactAddress()}")
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
