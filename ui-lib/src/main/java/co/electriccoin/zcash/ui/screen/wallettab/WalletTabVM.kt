package co.electriccoin.zcash.ui.screen.wallettab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
import co.electriccoin.zcash.ui.screen.chat.ChatList
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

    fun onMoreTab() = navigationRouter.forward(AdvancedSettingsArgs)
}
