package co.electriccoin.zcash.ui.common.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheck
import co.electriccoin.zcash.ui.screen.walletstartup.gatedSecretState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// To make this more multiplatform compatible, we need to remove the dependency on Context
// for loading the preferences.
// TODO [#292]: Should be moved to SDK-EXT-UI module.
// TODO [#292]: https://github.com/Electric-Coin-Company/zashi-android/issues/292
class WalletViewModel(
    synchronizerProvider: SynchronizerProvider,
    private val walletRepository: WalletRepository,
    startupStorageCheck: StartupStorageCheck,
) : ViewModel() {
    val synchronizer = synchronizerProvider.synchronizer

    /**
     * R2-3: stays [SecretState.LOADING] until the recovery phrase store and the chat stores opened
     * ([StartupStorageCheck]), so neither onboarding nor the main app opens before or over a storage error.
     */
    val secretState: StateFlow<SecretState> =
        combine(walletRepository.secretState, startupStorageCheck.state, ::gatedSecretState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = gatedSecretState(walletRepository.secretState.value, startupStorageCheck.state.value)
            )

    fun createNewWallet() {
        walletRepository.createNewWallet()
    }

    fun createNewWalletForOnboarding() {
        walletRepository.createNewWalletForOnboarding()
    }

    fun completeOnboarding() {
        walletRepository.completeOnboarding()
    }

    fun persistExistingWalletWithSeedPhrase(
        network: ZcashNetwork,
        seedPhrase: SeedPhrase,
        birthday: BlockHeight
    ) {
        viewModelScope.launch { walletRepository.restoreWallet(network, seedPhrase, birthday) }
    }
}

/**
 * Represents the state of the wallet secret.
 */
enum class SecretState {
    LOADING,
    NONE,
    READY
}

/**
 * This constant sets the default limitation on the length of the stack trace in the [co.electriccoin.zcash.ui.common.model.SynchronizerError]
 */
const val STACKTRACE_LIMIT = 250

// TODO [#529]: Localize Synchronizer Errors
// TODO [#529]: https://github.com/Electric-Coin-Company/zashi-android/issues/529
