@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.wallettab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.ActivityWidgetVM
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
                    // B11: show sub-0.001 "dust" digits (like Send/Pay) — ZCHAT messaging balances are
                    // typically tiny, so showDust=false rendered a real 0.00078 balance as "0.000" here
                    // while the chat-list header showed the true amount. Same totalBalance source; only
                    // the display differed.
                    showDust = true,
                )
            )
        }
    val walletTabVM = koinViewModel<WalletTabVM>()
    val activityWidgetVM = koinViewModel<ActivityWidgetVM>()
    val balanceState by balanceWidgetVM.state.collectAsStateWithLifecycle()
    val activityState by activityWidgetVM.state.collectAsStateWithLifecycle()
    val shieldableTransparent by walletTabVM.shieldableTransparent.collectAsStateWithLifecycle()
    val oldBalanceCard by walletTabVM.oldBalanceCard.collectAsStateWithLifecycle()
    val accountChip by walletTabVM.accountChip.collectAsStateWithLifecycle()
    val catchUpPercent by walletTabVM.catchUpPercent.collectAsStateWithLifecycle()
    val updateRequired by walletTabVM.updateRequired.collectAsStateWithLifecycle()
    // QA2 Q2-06 (ZC-166): the shared show/hide balances eye.
    val topAppBarVM = koinActivityViewModel<ZashiTopAppBarVM>()
    val topAppBarState by topAppBarVM.state.collectAsStateWithLifecycle()

    WalletTabView(
        accountChip = accountChip,
        catchUpPercent = catchUpPercent,
        updateRequired = updateRequired != null,
        balanceWidgetState = balanceState,
        activityWidgetState = activityState,
        shieldableTransparent = shieldableTransparent,
        oldBalanceCard = oldBalanceCard,
        onReceive = { walletTabVM.onReceive() },
        onSend = { walletTabVM.onSend() },
        onSwap = { walletTabVM.onSwap() },
        onShield = { walletTabVM.onShield() },
        onMoveOldBalance = { walletTabVM.onMoveOldBalance() },
        onDismissOldBalance = { walletTabVM.onDismissOldBalance() },
        onChatsTab = { walletTabVM.onChatsTab() },
        onAiTab = { walletTabVM.onAiTab() },
        onMoreTab = { walletTabVM.onMoreTab() },
        balanceVisibilityButton = topAppBarState.balanceVisibilityButton,
    )
}
