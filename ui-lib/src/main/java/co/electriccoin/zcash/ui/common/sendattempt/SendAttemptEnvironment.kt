package co.electriccoin.zcash.ui.common.sendattempt

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZashiAccount

/**
 * F8 (ZC-112): what a ViewModel needs to record send attempts: the ledger and the hash of the ZCHAT account an attempt
 * is made by. Passed as one constructor parameter; a ViewModel that holds none (a unit test that installs none) records
 * nothing and behaves as before F8.
 */
interface SendAttemptEnvironment {
    val ledger: SendAttemptLedger

    /** [SendAttemptProcess.accountHash] of the ZCHAT account, or null while none is known (no attempt begins then). */
    fun accountHash(): String?
}

/** The app's environment: the stored ledger and the ZCHAT account (the one account with a spending key). */
class WalletSendAttemptEnvironment(
    override val ledger: SendAttemptLedger,
    private val accountDataSource: AccountDataSource,
) : SendAttemptEnvironment {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun accountHash(): String? =
        try {
            accountDataSource.allAccounts.value
                ?.firstNotNullOfOrNull { it as? ZashiAccount }
                ?.sdkAccount
                ?.accountUuid
                ?.let { SendAttemptProcess.accountHash(it.value) }
        } catch (e: Exception) {
            null
        }
}
