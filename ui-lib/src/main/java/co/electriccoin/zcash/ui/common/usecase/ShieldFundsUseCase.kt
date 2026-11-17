package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShieldFundsUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val navigateToError: NavigateToErrorUseCase,
    private val messageAvailabilityDataSource: MessageAvailabilityDataSource,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var job: Job? = null

    operator fun invoke(closeCurrentScreen: Boolean) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                messageAvailabilityDataSource.onShieldingInitiated()

                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        createKeystoneShieldProposal()
                    }

                    is ZashiAccount -> {
                        if (closeCurrentScreen) {
                            navigationRouter.back()
                        }
                        shieldZashiFunds()
                    }
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun shieldZashiFunds() {
        try {
            // ZC-101: submit the shield proposal THIS call created, not the shared cell's current value.
            val proposal = zashiProposalRepository.createShieldProposal()
            when (val result = zashiProposalRepository.submit(proposal)) {
                is SubmitResult.Failure,
                is SubmitResult.GrpcFailure,
                is SubmitResult.Partial -> navigateToError(ErrorArgs.ShieldingError(result))

                // SentUnconfirmed: the shielding transaction may already be on the wire (the
                // settlement call threw after the broadcast), so never the "Unable to shield" error that
                // invites the user to shield again — the same double-spend shape as Send. But say so
                // (R2-6 a): ErrorVM renders this outcome as "Sent — Not Confirmed", the Send screen's
                // wording, instead of the silence that left the user to shield again.
                is SubmitResult.SentUnconfirmed -> navigateToError(ErrorArgs.ShieldingError(result))

                is SubmitResult.Success -> {
                    // do nothing
                }
            }
        } catch (e: Exception) {
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        } finally {
            zashiProposalRepository.clear()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun createKeystoneShieldProposal() {
        try {
            keystoneProposalRepository.createShieldProposal()
            keystoneProposalRepository.createPCZTFromProposal()
            navigationRouter.forward(SignKeystoneTransactionArgs)
        } catch (e: Exception) {
            keystoneProposalRepository.clear()
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        }
    }
}
