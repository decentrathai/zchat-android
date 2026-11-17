package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatPaymentExit
import co.electriccoin.zcash.ui.screen.chat.model.follow

/**
 * The send result screen's exit to the transaction history, for a success and (ZC-611) a stored failure alike.
 *
 * ZC-960: it drops the swap quote too, so no stale price outlives the flow it belonged to. [swapRepository] is always
 * given by Koin; it is optional only so the result-screen tests that build this use case need no swap repository.
 *
 * ZC-1273: a payment sent from a chat returns to that chat ([chatPaymentExit], set by Koin; null is a send from the wallet).
 */
class ViewTransactionsAfterSuccessfulProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val swapRepository: SwapRepository? = null,
) {
    var chatPaymentExit: ChatPaymentExit? = null

    operator fun invoke() {
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        swapRepository?.clearQuote()
        val exit = chatPaymentExit
        if (exit != null) navigationRouter.follow(exit.command()) else navigationRouter.backToRoot()
    }
}
