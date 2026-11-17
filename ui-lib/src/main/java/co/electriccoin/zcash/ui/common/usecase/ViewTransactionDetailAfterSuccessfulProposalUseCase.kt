package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatPaymentExit
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs

class ViewTransactionDetailAfterSuccessfulProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
) {
    /** ZC-1273: this exit replaces the stack, the chat with it, so a chat payment's mark ends here. Set by Koin. */
    var chatPaymentExit: ChatPaymentExit? = null

    operator fun invoke(txId: String) {
        chatPaymentExit?.clear()
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        navigationRouter.replaceAll(TransactionDetailArgs(txId))
    }
}
