package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.exception.InitializeException
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAvailability
import co.electriccoin.zcash.ui.common.model.KeystoneConnectionsClosedException
import com.keystone.module.ZcashAccount
import com.keystone.module.ZcashAccounts

class CreateKeystoneAccountUseCase(
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
) {
    @Throws(InitializeException.ImportAccountException::class, KeystoneConnectionsClosedException::class)
    suspend operator fun invoke(
        accounts: ZcashAccounts,
        account: ZcashAccount
    ) {
        // K0-1 (2.13.0): the one place that imports a Keystone account refuses while new connections are closed,
        // whatever screen reaches it. Nothing is imported or selected.
        if (!KeystoneAvailability.NEW_CONNECTIONS) throw KeystoneConnectionsClosedException()
        val createdAccount =
            accountDataSource.importKeystoneAccount(
                ufvk = account.ufvk,
                seedFingerprint = accounts.seedFingerprint,
                index = account.index.toLong()
            )
        accountDataSource.selectAccount(createdAccount)
        navigationRouter.backToRoot()
    }
}
