package co.electriccoin.zcash.ui.screen.wallettab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsFromMessageUseCase
import co.electriccoin.zcash.ui.screen.ai.AiTab
import co.electriccoin.zcash.ui.screen.chat.ChatList
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import co.electriccoin.zcash.ui.screen.send.Send
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WalletTabVM(
    private val navigationRouter: NavigationRouter,
    private val navigateToReceive: NavigateToReceiveUseCase,
    private val navigateToSwap: NavigateToSwapUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val shieldFundsFromMessage: ShieldFundsFromMessageUseCase,
) : ViewModel() {

    // Transparent balance available to shield (null when there's nothing to shield / below the SDK
    // threshold). Drives the "Shield transparent funds" banner — the 1-click entry point Zashi shows on
    // its home screen but ZCHAT's custom wallet tab never surfaced. Shielding is what makes transparent
    // funds spendable + private; the button reuses the SAME reviewed shield flow (ShieldFundsFromMessage).
    val shieldableTransparent: StateFlow<Zatoshi?> =
        observeSelectedWalletAccount()
            .map { account -> account?.takeIf { it.isShieldingAvailable }?.totalTransparentBalance }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun onShield() = viewModelScope.launch { shieldFundsFromMessage() }

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
