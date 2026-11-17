package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatPaymentExit
import co.electriccoin.zcash.ui.screen.chat.model.follow
import co.electriccoin.zcash.ui.screen.pay.PayArgs
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.swap.SwapArgs
import kotlinx.coroutines.flow.first

/**
 * Leaves a send flow: drops the proposal and returns to the screen it was built on.
 *
 * ZC-540: the proposal is read as it is NOW, never waited for (`getTransactionProposal()` waits for a non-null one).
 * It is already gone when View Transaction on the send result screen finished the flow
 * ([ViewTransactionDetailAfterSuccessfulProposalUseCase] clears both repositories) and the user came back to that
 * screen: its Close, arrow and system BACK then waited for ever and did nothing. With no proposal the flow is left
 * without asking the Send form to clear: no Send form is reached, and [ObserveClearSendUseCase] would hold the clear
 * until the next form opened, perhaps one that Send again had just filled. The result screen's exit
 * ([clearSendForm] true) goes to the root, which pops the Send entry with it, so the old form cannot be sent again.
 *
 * ZC-1273: a payment sent from a chat has no Send form under it (backTo(Send) would pop nothing and leave the user on the
 * sending screen): it returns to its chat ([ChatPaymentExit]).
 */
class CancelProposalFlowUseCase(
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val observeClearSend: ObserveClearSendUseCase,
    private val accountDataSource: AccountDataSource,
    private val swapRepository: SwapRepository,
    private val chatPaymentExit: ChatPaymentExit = ChatPaymentExit(),
) {
    suspend operator fun invoke(clearSendForm: Boolean = true) {
        val proposal =
            when (accountDataSource.getSelectedAccount()) {
                is ZashiAccount -> zashiProposalRepository.transactionProposal.first()
                is KeystoneAccount -> keystoneProposalRepository.transactionProposal.first()
            }

        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()

        if (chatPaymentExit.isMarked) {
            swapRepository.clearQuote()
            navigationRouter.follow(chatPaymentExit.command())
            return
        }

        when (proposal) {
            null -> {
                // ZC-767: the finished flow may have been a swap; its quote has no owner left.
                swapRepository.clearQuote()
                if (clearSendForm) navigationRouter.backToRoot() else navigationRouter.backTo(Send::class)
            }
            is ExactInputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(SwapArgs::class)
            }
            is ExactOutputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(PayArgs::class)
            }
            else -> {
                if (clearSendForm) observeClearSend.requestClear()
                navigationRouter.backTo(Send::class)
            }
        }
    }
}
