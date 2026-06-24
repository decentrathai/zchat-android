package co.electriccoin.zcash.ui.screen.wallettab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.screen.ai.AiTab
import co.electriccoin.zcash.ui.screen.chat.ChatList
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import co.electriccoin.zcash.ui.screen.send.Send
import kotlinx.coroutines.launch

class WalletTabVM(
    private val navigationRouter: NavigationRouter,
    private val navigateToReceive: NavigateToReceiveUseCase,
    private val navigateToSwap: NavigateToSwapUseCase,
) : ViewModel() {

    fun onReceive() = viewModelScope.launch { navigateToReceive() }

    fun onSend() = navigationRouter.forward(Send())

    fun onSwap() = viewModelScope.launch { navigateToSwap() }

    fun onChatsTab() = navigationRouter.replace(ChatList)

    // replace() (like onChatsTab): AI is a peer content tab. Using forward() here while AI itself leaves via
    // replace() (AndroidAiTab onChatsTab/onWalletTab) makes the bottom-nav tabs accumulate a stranded
    // back-stack entry (tap AI, Wallet, AI, Chats -> Back returns to a stale Wallet). Keep tabs single-depth.
    fun onAiTab() = navigationRouter.replace(AiTab)

    fun onMoreTab() = navigationRouter.forward(MoreArgs)
}
