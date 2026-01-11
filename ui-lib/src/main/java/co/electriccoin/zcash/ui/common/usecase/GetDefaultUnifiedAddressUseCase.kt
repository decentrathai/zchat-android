package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider

/**
 * Use case to get the default unified address (diversifier index 0).
 * This address is deterministic and will be the same after wallet restore,
 * unlike diversified addresses which may change based on usage.
 *
 * This is specifically used for ZCHAT where we want users to have a consistent
 * receiving address that doesn't change after wallet recovery.
 */
class GetDefaultUnifiedAddressUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase
) {
    /**
     * Gets the default unified address for the selected account.
     * This address uses diversifier index 0 and will always be the same for a given seed phrase.
     */
    suspend operator fun invoke(): String {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val selectedAccount = getSelectedWalletAccount()

        // Use the SDK account directly from the selected wallet account
        // This avoids UUID matching issues after wallet restore
        return synchronizer.getUnifiedAddress(selectedAccount.sdkAccount)
    }
}
