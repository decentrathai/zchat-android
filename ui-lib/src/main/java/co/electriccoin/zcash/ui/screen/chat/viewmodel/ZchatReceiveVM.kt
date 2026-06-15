package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.screen.chat.model.ZchatReceiveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ZchatReceiveVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter
) : ViewModel() {

    private val showingTransparent = MutableStateFlow(false)

    // Store the default unified address (consistent after wallet restore)
    private val defaultUnifiedAddress = MutableStateFlow<String?>(null)

    // True once the default-address load has failed (exception or null/empty result).
    // Lets combine() emit Error instead of spinning on Loading forever.
    private val loadFailed = MutableStateFlow(false)

    init {
        loadDefaultAddress()
    }

    private fun loadDefaultAddress() {
        viewModelScope.launch {
            loadFailed.value = false
            try {
                val address = getDefaultUnifiedAddress()
                if (address.isEmpty()) {
                    loadFailed.value = true
                } else {
                    defaultUnifiedAddress.value = address
                }
            } catch (_: Exception) {
                loadFailed.value = true
            }
        }
    }

    val state = combine(
        observeSelectedWalletAccount.require(),
        showingTransparent,
        defaultUnifiedAddress,
        loadFailed
    ) { account, isShowingTransparent, defaultAddress, hasFailed ->
        if (hasFailed) {
            return@combine ZchatReceiveState.Error(
                message = "Couldn't load your address. Please try again.",
                onRetry = { loadDefaultAddress() },
                onBack = { navigationRouter.back() }
            )
        }

        // Wait for the default unified address to be loaded
        // This address (diversifier 0) is deterministic and consistent after wallet restore
        // Do NOT use the account.unified.address as fallback - it may be a different diversified address
        if (defaultAddress == null) {
            return@combine ZchatReceiveState.Loading
        }

        val transparentAddress = account.transparent.address.address

        // Guard against corrupted account data yielding blank addresses, which would
        // otherwise flow into a blank QR / Text / copy / share.
        if (defaultAddress.isEmpty() || transparentAddress.isEmpty()) {
            return@combine ZchatReceiveState.Error(
                message = "Couldn't load your address. Please try again.",
                onRetry = { loadDefaultAddress() },
                onBack = { navigationRouter.back() }
            )
        }

        ZchatReceiveState.Success(
            shieldedAddress = defaultAddress,
            transparentAddress = transparentAddress,
            showingTransparent = isShowingTransparent,
            onCopyAddress = {
                val address = if (isShowingTransparent) {
                    transparentAddress
                } else {
                    defaultAddress
                }
                copyToClipboard(address)
            },
            onShowTransparent = { showingTransparent.update { true } },
            onShowShielded = { showingTransparent.update { false } },
            onBack = { navigationRouter.back() }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
        initialValue = ZchatReceiveState.Loading
    )
}
