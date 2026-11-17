package co.electriccoin.zcash.ui.screen.balances

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.spendable.SpendableBalanceArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

class BalanceWidgetVM(
    private val args: BalanceWidgetArgs,
    accountDataSource: AccountDataSource,
    exchangeRateRepository: ExchangeRateRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<BalanceWidgetState> =
        combine(
            accountDataSource.selectedAccount.filterNotNull(),
            exchangeRateRepository.state,
        ) { account, exchangeRateUsd ->
            createState(account, exchangeRateUsd)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    account = accountDataSource.allAccounts.value?.firstOrNull { it.isSelected },
                    exchangeRateUsd = exchangeRateRepository.state.value
                )
        )

    @Suppress("CyclomaticComplexMethod")
    private fun createState(account: WalletAccount?, exchangeRateUsd: ExchangeRateState): BalanceWidgetState {
        // T1b-3 (T1 finding 5b): on a screen that pays someone else the button is that screen's budget. Sealed
        // Orchard pays only our own address (ZIP 258) and Sapling would cross in public, so it is confirmed
        // Ironwood, the same figure the Send screen's own limits use. The Spendable sheet shows the rest.
        val spendable = account?.let { if (args.paysAnotherPerson) it.peerSpendableBalance else it.spendableShieldedBalance }
        return BalanceWidgetState(
            // F9: the headline counts value locked for a transaction in progress; the checks below do not.
            totalBalance = account?.displayTotalBalance ?: Zatoshi(0),
            exchangeRate = if (args.isExchangeRateButtonEnabled) exchangeRateUsd else null,
            button =
                when {
                    !args.isBalanceButtonEnabled -> null
                    account == null || spendable == null -> null
                    account.isAllSpendable(spendable) && account.lockedShieldedBalance == Zatoshi(0) -> null
                    account.totalBalance > spendable &&
                        account.isShieldedPending &&
                        account.totalShieldedBalance > Zatoshi(0) &&
                        spendable == Zatoshi(0) ->
                        BalanceButtonState(
                            icon = R.drawable.ic_balances_expand,
                            text = stringRes(R.string.widget_balances_button_spendable),
                            amount = null,
                            onClick = ::onBalanceButtonClick
                        )

                    account.totalBalance > spendable &&
                        !account.isShieldedPending &&
                        account.totalShieldedBalance > Zatoshi(0) &&
                        spendable == Zatoshi(0) &&
                        account.totalTransparentBalance == Zatoshi(0) ->
                        BalanceButtonState(
                            icon = R.drawable.ic_balances_expand,
                            text = stringRes(R.string.widget_balances_button_spendable),
                            amount = null,
                            onClick = ::onBalanceButtonClick
                        )

                    account.totalBalance > spendable ->
                        BalanceButtonState(
                            icon = R.drawable.ic_balances_expand,
                            text = stringRes(R.string.widget_balances_button_spendable),
                            amount = spendable,
                            onClick = ::onBalanceButtonClick
                        )

                    // F9: the headline counts locked value that can't be spent now; the Spendable sheet says why.
                    account.lockedShieldedBalance > Zatoshi(0) ->
                        BalanceButtonState(
                            icon = R.drawable.ic_balances_expand,
                            text = stringRes(R.string.widget_balances_button_spendable),
                            amount = spendable,
                            onClick = ::onBalanceButtonClick
                        )

                    else -> null
                },
            showDust = args.showDust
        )
    }

    /**
     * [WalletAccount.isAllShielded], measured against [spendable]: everything is [spendable], or the only rest is
     * transparent dust too small to shield. With [WalletAccount.spendableShieldedBalance] it is exactly isAllShielded.
     */
    private fun WalletAccount.isAllSpendable(spendable: Zatoshi): Boolean =
        totalBalance == spendable ||
            (
                totalBalance > spendable &&
                    spendable == totalShieldedBalance &&
                    totalTransparentBalance > Zatoshi(0) &&
                    !isShieldingAvailable
            )

    private fun onBalanceButtonClick() = navigationRouter.forward(SpendableBalanceArgs)
}

data class BalanceWidgetArgs(
    val showDust: Boolean,
    val isBalanceButtonEnabled: Boolean,
    val isExchangeRateButtonEnabled: Boolean,
    /** Send and Pay: the balance button shows what can pay another person ([WalletAccount.peerSpendableBalance]). */
    val paysAnotherPerson: Boolean = false,
)
