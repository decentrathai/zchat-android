@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.wallettab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AndroidWalletTab() {
    val balanceWidgetVM =
        koinViewModel<BalanceWidgetVM> {
            parametersOf(
                BalanceWidgetArgs(
                    isBalanceButtonEnabled = false,
                    isExchangeRateButtonEnabled = true,
                    showDust = false,
                )
            )
        }
    val walletTabVM = koinViewModel<WalletTabVM>()
    val balanceState by balanceWidgetVM.state.collectAsStateWithLifecycle()

    WalletTabView(
        balanceWidgetState = balanceState,
        onReceive = { walletTabVM.onReceive() },
        onSend = { walletTabVM.onSend() },
        onSwap = { walletTabVM.onSwap() },
        onChatsTab = { walletTabVM.onChatsTab() },
        onMoreTab = { walletTabVM.onMoreTab() },
    )
}
