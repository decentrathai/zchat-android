package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SubmitKSProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val swapRepository: SwapRepository,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    private val prefillSend: PrefillSendUseCase,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Submit a prepared Keystone proposal and navigate to Transaction Progress screen.
     */
    suspend operator fun invoke() {
        val proposal = keystoneProposalRepository.getTransactionProposal()
        swapRepository.clear()
        // A scanned request's prefill waits for the next Send screen; this payment settles it.
        prefillSend.clear()
        submitKSProposal(proposal)
        navigationRouter.replace(TransactionProgressArgs)
    }

    private fun submitKSProposal(proposal: TransactionProposal) {
        scope.launch {
            try {
                // ZC-101: settle exactly the proposal captured above, not the shared cell's current value.
                val result = keystoneProposalRepository.submit(proposal)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (_: Exception) {
                // do nothing
            }
        }
    }
}
