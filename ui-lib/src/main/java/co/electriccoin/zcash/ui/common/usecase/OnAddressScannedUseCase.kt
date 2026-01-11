package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ADDRESS_BOOK
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.HOMEPAGE
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.RESTORE_SEED
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.SEND
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ZCHAT
import co.electriccoin.zcash.ui.screen.send.Send

class OnAddressScannedUseCase(
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase,
    private val prefillZchat: PrefillZchatUseCase,
    private val prefillRestoreSeed: PrefillRestoreSeedUseCase
) {
    operator fun invoke(
        address: String,
        addressType: AddressType,
        scanArgs: ScanArgs
    ) {
        require(addressType is AddressType.Valid)

        when (scanArgs.flow) {
            SEND -> {
                prefillSend.request(PrefillSendData.FromAddressScan(address = address))
                navigationRouter.back()
            }

            ADDRESS_BOOK -> navigationRouter.replace(AddZashiABContactArgs(address))

            ZCHAT -> {
                prefillZchat.request(address)
                navigationRouter.back()
            }

            HOMEPAGE ->
                navigationRouter.replace(
                    Send(
                        address,
                        when (addressType) {
                            AddressType.Shielded -> cash.z.ecc.sdk.model.AddressType.UNIFIED
                            AddressType.Tex -> cash.z.ecc.sdk.model.AddressType.TEX
                            AddressType.Transparent -> cash.z.ecc.sdk.model.AddressType.TRANSPARENT
                            AddressType.Unified -> cash.z.ecc.sdk.model.AddressType.UNIFIED
                            else -> cash.z.ecc.sdk.model.AddressType.UNIFIED
                        }
                    )
                )

            RESTORE_SEED -> {
                // RESTORE_SEED is handled directly in ScanZashiAddressVM before reaching here
                // This case should never be reached, but we need to handle it for exhaustiveness
                navigationRouter.back()
            }
        }
    }
}
