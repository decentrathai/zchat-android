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

    init {
        loadDefaultAddress()
    }

    private fun loadDefaultAddress() {
        viewModelScope.launch {
            try {
                val address = getDefaultUnifiedAddress()
                defaultUnifiedAddress.value = address
            } catch (_: Exception) {
                // Fall back to account address if default fails
            }
        }
    }

    val state = combine(
        observeSelectedWalletAccount.require(),
        showingTransparent,
        defaultUnifiedAddress
    ) { account, isShowingTransparent, defaultAddress ->
        // Wait for the default unified address to be loaded
        // This address (diversifier 0) is deterministic and consistent after wallet restore
        // Do NOT use the account.unified.address as fallback - it may be a different diversified address
        if (defaultAddress == null) {
            return@combine ZchatReceiveState.Loading
        }

        ZchatReceiveState.Success(
            shieldedAddress = defaultAddress,
            transparentAddress = account.transparent.address.address,
            showingTransparent = isShowingTransparent,
            onCopyAddress = {
                val address = if (isShowingTransparent) {
                    account.transparent.address.address
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
