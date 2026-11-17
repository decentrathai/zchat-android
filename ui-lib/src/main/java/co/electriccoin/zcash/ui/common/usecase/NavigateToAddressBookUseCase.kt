package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookArgs

class NavigateToAddressBookUseCase(
    private val navigationRouter: NavigationRouter,
    private val biometricRepository: BiometricRepository
) {
    suspend operator fun invoke() {
        try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    title = stringRes(R.string.authentication_address_book_title),
                    message = stringRes(R.string.authentication_address_book_subtitle)
                )
            )
            navigationRouter.forward(AddressBookArgs)
        } catch (_: BiometricsFailureException) {
            // do nothing
        } catch (_: BiometricsCancelledException) {
            // do nothing
        }
    }
}
