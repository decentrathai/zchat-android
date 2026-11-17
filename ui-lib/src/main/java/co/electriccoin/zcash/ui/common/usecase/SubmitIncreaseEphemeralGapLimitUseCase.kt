package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SubmitIncreaseEphemeralGapLimitUseCase(
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val biometricRepository: BiometricRepository,
    private val ephemeralAddressRepository: EphemeralAddressRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * ZC-101 (money): [proposal] MUST be the exact gap-limit proposal instance the confirm screen
     * (EphemeralLockVM) DISPLAYED, captured when the confirmation UI rendered — i.e. BEFORE this
     * use case suspends on the biometric prompt. This use case deliberately never reads the shared
     * `transactionProposal` cell: an inbound auto-send (KEX-ack, read receipt, group control memo)
     * can overwrite that cell while the user is inside the biometric dialog, and a post-biometric
     * cell read would settle THAT proposal while silently dropping the gap-limit bump.
     */
    suspend operator fun invoke(proposal: TransactionProposal) {
        try {
            biometricRepository.requestBiometrics(
                request =
                    BiometricRequest(
                        message =
                            stringRes(
                                R.string.authentication_system_ui_subtitle,
                                stringRes(R.string.authentication_use_case_send_funds)
                            )
                    )
            )
            when (accountDataSource.getSelectedAccount()) {
                is KeystoneAccount -> navigationRouter.replace(SignKeystoneTransactionArgs)
                is ZashiAccount -> {
                    submitZashiProposal(proposal)
                    navigationRouter.back()
                }
            }
        } catch (_: BiometricsFailureException) {
            // do nothing
        } catch (_: BiometricsCancelledException) {
            // do nothing
        }
    }

    private fun submitZashiProposal(proposal: TransactionProposal) {
        scope.launch {
            try {
                // ZC-101: settle exactly the proposal the confirm screen displayed — threaded
                // through invoke() from before the biometric window — never the shared cell, which
                // a concurrent proposal creation may have overwritten by now.
                val result = zashiProposalRepository.submit(proposal)
                invalidateEphemeralAddress(result)
                zashiProposalRepository.clear()
            } catch (_: Exception) {
                // do nothing
            }
        }
    }

    private suspend fun invalidateEphemeralAddress(result: SubmitResult) {
        when (result) {
            is SubmitResult.Failure,
            is SubmitResult.GrpcFailure,
            is SubmitResult.SentUnconfirmed,
            is SubmitResult.Success -> ephemeralAddressRepository.invalidate()

            is SubmitResult.Partial -> {
                // do nothing
            }
        }
    }
}
