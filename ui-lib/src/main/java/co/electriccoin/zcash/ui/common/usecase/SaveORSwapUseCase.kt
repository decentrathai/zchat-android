package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import java.math.BigDecimal

class SaveORSwapUseCase(
    private val swapRepository: SwapRepository,
    private val metadataRepository: MetadataRepository,
    private val navigationRouter: NavigationRouter,
    // private val ephemeralAddressRepository: EphemeralAddressRepository,
) {
    operator fun invoke() {
        android.util.Log.d("ZCHAT_SWAP", "SaveORSwapUseCase invoked, quote type=${swapRepository.quote.value?.javaClass?.simpleName}")
        val quote = (swapRepository.quote.value as? SwapQuoteData.Success)?.quote
        android.util.Log.d("ZCHAT_SWAP", "Quote is ${if (quote != null) "present" else "NULL"}")
        if (quote != null) {
            metadataRepository.markTxAsSwap(
                depositAddress = quote.depositAddress.address,
                provider = quote.provider,
                totalFees = Zatoshi(0),
                totalFeesUsd = BigDecimal(0),
                amountOutFormatted = quote.amountOutFormatted,
                mode = quote.mode,
                status = SwapStatus.PENDING,
                origin = quote.originAsset,
                destination = quote.destinationAsset
            )
            // ephemeralAddressRepository.invalidate()
            swapRepository.clear()
        }
        // Always navigate back — even if quote expired/cleared
        android.util.Log.d("ZCHAT_SWAP", "Navigating back via replaceAll(ChatList)")
        navigationRouter.replaceAll(co.electriccoin.zcash.ui.screen.chat.ChatList)
    }
}
