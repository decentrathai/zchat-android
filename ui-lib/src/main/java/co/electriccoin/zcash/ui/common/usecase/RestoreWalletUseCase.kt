package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository

class RestoreWalletUseCase(
    private val walletRepository: WalletRepository,
    private val context: Context,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider
) {
    suspend operator fun invoke(
        seedPhrase: SeedPhrase,
        enableTor: Boolean,
        birthday: BlockHeight,
    ) {
        if (enableTor) {
            isTorEnabledStorageProvider.store(true)
        }
        val restored =
            walletRepository.restoreWallet(
                network = ZcashNetwork.fromResources(context),
                seedPhrase = seedPhrase,
                birthday = birthday
            )
        if (!restored) throw DifferentWalletOnDeviceException()
    }
}

/**
 * The import was refused because this device already holds a wallet with a different seed (for example
 * one created earlier in onboarding). Replacing it would lose that wallet's seed and funds.
 */
class DifferentWalletOnDeviceException : IllegalStateException(DIFFERENT_WALLET_ON_DEVICE)

private const val DIFFERENT_WALLET_ON_DEVICE = "A wallet with a different recovery phrase is already on this device"
