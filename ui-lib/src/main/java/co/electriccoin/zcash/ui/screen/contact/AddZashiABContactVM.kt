package co.electriccoin.zcash.ui.screen.contact

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.ContactAddressValidationResult
import co.electriccoin.zcash.ui.common.usecase.SaveABContactUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateContactNameResult
import co.electriccoin.zcash.ui.common.usecase.ValidateGenericABContactNameUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateZashiABContactAddressUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddZashiABContactVM(
    args: AddZashiABContactArgs,
    private val validateContactAddress: ValidateZashiABContactAddressUseCase,
    private val validateContactName: ValidateGenericABContactNameUseCase,
    private val saveContact: SaveABContactUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val contactAddress = MutableStateFlow(args.address.orEmpty())
    private val contactName = MutableStateFlow("")
    private val isSavingContact = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val contactAddressError =
        contactAddress
            .mapLatest { address ->
                if (address.isEmpty()) {
                    null
                } else {
                    when (validateContactAddress(address)) {
                        ContactAddressValidationResult.Invalid ->
                            stringRes(R.string.contact_address_error_invalid)

                        ContactAddressValidationResult.NotUnique ->
                            stringRes(R.string.contact_address_error_not_unique)

                        ContactAddressValidationResult.Valid -> null
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private val contactAddressState =
        combine(contactAddress, contactAddressError) { address, contactAddressError ->
            TextFieldState(
                value = stringRes(address),
                error = contactAddressError,
                onValueChange = { newValue ->
                    // Trim on input — addresses never contain whitespace, and a whitespace-only
                    // entry would otherwise pass isNotEmpty() yet persist blank. Mirrors
                    // AddGenericABContactVM.onAddressChange.
                    contactAddress.update { newValue.trim() }
                }
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val contactNameError =
        contactName
            .mapLatest { name ->
                if (name.isEmpty()) {
                    null
                } else {
                    when (validateContactName(name)) {
                        ValidateContactNameResult.TooLong ->
                            stringRes(R.string.contact_name_error_too_long)

                        ValidateContactNameResult.NotUnique ->
                            stringRes(R.string.contact_name_error_not_unique)

                        ValidateContactNameResult.Valid ->
                            null
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private val contactNameState =
        combine(contactName, contactNameError) { name, contactNameError ->
            TextFieldState(
                value = stringRes(name),
                error = contactNameError,
                onValueChange = { newValue ->
                    contactName.update { newValue }
                }
            )
        }

    private val saveButtonState =
        combine(contactAddressState, contactNameState, isSavingContact) { address, name, isSavingContact ->
            ButtonState(
                text = stringRes(R.string.add_new_contact_primary_btn),
                isEnabled =
                    address.error == null &&
                        name.error == null &&
                        contactAddress.value.isNotBlank() &&
                        contactName.value.isNotBlank(),
                onClick = ::onSaveButtonClick,
                isLoading = isSavingContact,
                hapticFeedbackType = HapticFeedbackType.Confirm
            )
        }

    val state =
        combine(contactAddressState, contactNameState, saveButtonState) { address, name, saveButton ->
            ABContactState(
                info = null,
                title = stringRes(R.string.add_new_contact_title),
                walletAddress = address,
                contactName = name,
                chain = null,
                negativeButton = null,
                positiveButton = saveButton,
                onBack = ::onBack
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private fun onBack() = navigationRouter.back()

    private fun onSaveButtonClick() =
        viewModelScope.launch {
            if (isSavingContact.value) return@launch
            isSavingContact.update { true }
            // Re-validate before persisting: the reactive error flows can still read null during the
            // async validation window (e.g. right after a cold start while the synchronizer is still
            // initializing), which would otherwise let an invalid or duplicate address be saved.
            val isValid =
                validateContactAddress(contactAddress.value) == ContactAddressValidationResult.Valid &&
                    validateContactName(contactName.value.trim()) == ValidateContactNameResult.Valid
            if (!isValid) {
                isSavingContact.update { false }
                return@launch
            }
            saveContact(
                name = contactName.value.trim(),
                address = contactAddress.value,
                chain = null
            )
            // Intentionally NOT resetting isSavingContact here: saveContact is fire-and-forget and
            // navigates back, so keeping the guard latched prevents a rapid double-tap from enqueueing
            // a second save (duplicate contact row) plus a second back() (double-pop).
        }
}
