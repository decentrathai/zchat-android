package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAvailability
import co.electriccoin.zcash.ui.common.model.KeystoneConnectionsClosedException
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ParseKeystoneSignInRequestUseCase(
    private val navigationRouter: NavigationRouter
) : BaseKeystoneScanner() {
    override suspend fun onSuccess(ur: UR) {
        // K0-1 (2.13.0): a scanned account QR opens nothing while new Keystone connections are closed.
        if (!KeystoneAvailability.NEW_CONNECTIONS) throw KeystoneConnectionsClosedException()
        parseOrThrow(ur)
        navigationRouter.replace(SelectKeystoneAccount(ur.toString()))
    }

    @Suppress("TooGenericExceptionCaught")
    @Throws(InvalidKeystoneSignInQRException::class)
    private suspend fun parseOrThrow(ur: UR) =
        withContext(Dispatchers.Default) {
            try {
                keystoneSDK.parseZcashAccounts(ur)
            } catch (e: Exception) {
                throw InvalidKeystoneSignInQRException(e)
            }
        }
}

class InvalidKeystoneSignInQRException(
    cause: Throwable
) : Exception(cause)
