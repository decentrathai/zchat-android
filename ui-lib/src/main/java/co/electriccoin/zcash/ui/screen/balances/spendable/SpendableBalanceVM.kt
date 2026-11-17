package co.electriccoin.zcash.ui.screen.balances.spendable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import cash.z.ecc.sdk.extension.typicalFee
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.isPending
import co.electriccoin.zcash.ui.common.usecase.GetTransactionsUseCase
import co.electriccoin.zcash.ui.common.usecase.ListTransactionData
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.confirmingExplanation
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

class SpendableBalanceVM(
    accountDataSource: AccountDataSource,
    getTransactions: GetTransactionsUseCase,
    private val navigationRouter: NavigationRouter,
    private val shieldFunds: ShieldFundsUseCase,
) : ViewModel() {
    val state =
        combine(
            accountDataSource.selectedAccount,
            getTransactions.observe()
        ) { account, transactions ->
            createState(account, transactions)
        }.filterNotNull()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    createState(
                        account =
                            accountDataSource.allAccounts.value
                                .orEmpty()
                                .firstOrNull { it.isSelected },
                        transactions = null
                    )
            )

    private fun createState(account: WalletAccount?, transactions: List<ListTransactionData>?): SpendableBalanceState? {
        if (account == null) return null
        return SpendableBalanceState(
            title = stringRes(R.string.balance_action_title),
            message = createMessage(account, transactions, hideAmounts = false),
            hiddenMessage = createMessage(account, transactions, hideAmounts = true),
            positive = createPositiveButton(account),
            onBack = ::onBack,
            rows = createInfoRows(account, transactions),
            shieldButton = createShieldButtonState(account)
        )
    }

    private fun createMessage(
        account: WalletAccount,
        transactions: List<ListTransactionData>?,
        hideAmounts: Boolean,
    ): StringResource {
        val pending =
            when {
                // F9: value locked for a transaction in progress is not spendable, so never "all spendable".
                account.isAllShielded && account.lockedShieldedBalance > Zatoshi(0) -> null

                account.isAllShielded -> stringRes(R.string.balance_action_all_shielded)

                account.totalBalance > account.spendableShieldedBalance &&
                    transactions.orEmpty().any { it.transaction.isPending } ->
                    stringRes(R.string.balance_action_pending)

                account.totalBalance > account.spendableShieldedBalance ->
                    stringRes(R.string.balance_action_syncing)

                else -> null
            }

        val shielding =
            stringRes(
                R.string.balance_action_shield_message,
                CURRENCY_TICKER,
                stringRes(Zatoshi.typicalFee, HIDDEN),
                CURRENCY_TICKER
            ).takeIf { account.isShieldingAvailable }

        val message =
            if (pending != null && shielding != null) {
                pending + stringRes("\n\n") + shielding
            } else {
                pending ?: shielding ?: stringRes("")
            }
        val withPeer =
            createPeerExplanation(account, hideAmounts)?.let {
                if (message.isEmpty()) it else message + stringRes("\n\n") + it
            } ?: message
        return createHeldExplanation(account, hideAmounts)?.let {
            if (withPeer.isEmpty()) it else withPeer + stringRes("\n\n") + it
        } ?: withPeer
    }

    /**
     * F9: what the SDK holds locked for a transaction in progress. The headline counts it, nothing spendable does.
     * Null when nothing is locked. It says nothing about when the value is free again: the SDK decides that.
     */
    private fun createHeldExplanation(account: WalletAccount, hideAmounts: Boolean): StringResource? {
        val locked = account.lockedShieldedBalance
        if (locked == Zatoshi(0)) return null
        return stringRes(R.string.balance_action_held, if (hideAmounts) HIDDEN_AMOUNT else stringRes(locked))
    }

    /**
     * Why the balance is larger than what can pay another person: Ironwood still confirming and the
     * sealed Orchard pool are both counted in it. Null when every shielded zatoshi can go to a peer.
     * With [hideAmounts] every figure is the hidden-balance placeholder, as in the rows below it.
     */
    private fun createPeerExplanation(account: WalletAccount, hideAmounts: Boolean): StringResource? {
        val ironwood = account.unified.ironwood
        val confirming = ironwood.changePending + ironwood.valuePending
        val orchard = account.unified.balance.available
        val isOwnChange = ironwood.valuePending == Zatoshi(0)
        return when {
            confirming > Zatoshi(0) && hideAmounts ->
                confirmingExplanation(
                    progress = null,
                    pending = confirming,
                    txAbbrev = null,
                    isOwnChange = isOwnChange,
                    orchardStranded = orchard,
                    zec = { AMOUNT_SLOT },
                ).joinToString(separator = " ").withHiddenAmounts()
            confirming > Zatoshi(0) ->
                stringRes(
                    confirmingExplanation(
                        progress = null,
                        pending = confirming,
                        txAbbrev = null,
                        isOwnChange = isOwnChange,
                        orchardStranded = orchard,
                    ).joinToString(separator = " ")
                )
            orchard > Zatoshi(0) ->
                stringRes(
                    R.string.balance_action_orchard_explanation,
                    if (hideAmounts) HIDDEN_AMOUNT else stringRes(orchard)
                )
            else -> null
        }
    }

    /** This text with each [AMOUNT_SLOT] shown as the hidden-balance placeholder. */
    private fun String.withHiddenAmounts(): StringResource =
        split(AMOUNT_SLOT)
            .map { stringRes(it) }
            .reduce { text, next -> text + HIDDEN_AMOUNT + next }

    private fun createPositiveButton(account: WalletAccount) =
        ButtonState(
            text =
                if (account.isShieldingAvailable) {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_dismiss)
                } else {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok)
                },
            onClick = ::onBack
        )

    private fun createInfoRows(
        account: WalletAccount,
        transactions: List<ListTransactionData>?
    ): List<SpendableBalanceRowState> {
        val hasPendingTransaction = transactions.orEmpty().any { it.transaction.isPending }
        return listOfNotNull(
            // Owner decision 4 (2026-09-10): sealed Orchard can pay nobody else, and at 25,000 zatoshi or
            // less it cannot move at all, so the "Spendable" row never counts it. The Orchard row below
            // shows it on its own.
            SpendableBalanceRowState(
                title = stringRes(R.string.balance_action_info_shielded),
                icon = imageRes(R.drawable.ic_balance_shield),
                value =
                    stringRes(account.spendableShieldedBalance - account.unified.balance.available)
            ),
            when {
                account.totalShieldedBalance > account.spendableShieldedBalance &&
                    account.isShieldedPending &&
                    hasPendingTransaction ->
                    SpendableBalanceRowState(
                        title = stringRes(R.string.balance_action_info_pending),
                        icon = loadingImageRes(),
                        value = stringRes(account.pendingShieldedBalance)
                    )

                account.totalShieldedBalance > account.spendableShieldedBalance && hasPendingTransaction ->
                    SpendableBalanceRowState(
                        title = stringRes(R.string.balance_action_info_pending),
                        icon = loadingImageRes(),
                        value =
                            stringRes(account.totalShieldedBalance - account.spendableShieldedBalance)
                    )

                else -> null
            },
            // F9: value locked for a transaction in progress, counted by the headline and by no row above.
            SpendableBalanceRowState(
                title = stringRes(R.string.balance_action_info_held),
                icon = loadingImageRes(),
                value = stringRes(account.lockedShieldedBalance)
            ).takeIf { account.lockedShieldedBalance > Zatoshi(0) },
        ) + createPeerRows(account)
    }

    /**
     * How the balance splits for paying another person. Shown only when some of it cannot: the rows
     * above keep their meaning, these only add the breakdown.
     */
    private fun createPeerRows(account: WalletAccount): List<SpendableBalanceRowState> {
        val ironwood = account.unified.ironwood
        val confirming = ironwood.changePending + ironwood.valuePending
        val orchard = account.unified.balance.available
        if (confirming == Zatoshi(0) && orchard == Zatoshi(0)) return emptyList()
        return listOfNotNull(
            SpendableBalanceRowState(
                title = stringRes(R.string.balance_action_info_peer),
                icon = imageRes(R.drawable.ic_balance_shield),
                value = stringRes(account.peerSpendableBalance)
            ),
            SpendableBalanceRowState(
                title = stringRes(R.string.balance_action_info_orchard),
                icon = imageRes(""),
                value = stringRes(orchard)
            ).takeIf { orchard > Zatoshi(0) },
            SpendableBalanceRowState(
                title = stringRes(R.string.balance_action_info_confirming),
                icon = loadingImageRes(),
                value = stringRes(confirming)
            ).takeIf { confirming > Zatoshi(0) },
        )
    }

    private fun createShieldButtonState(account: WalletAccount): SpendableBalanceShieldButtonState? =
        SpendableBalanceShieldButtonState(
            amount = account.transparent.balance,
            onShieldClick = ::onShieldClick
        ).takeIf { account.isShieldingAvailable }

    private fun onBack() = navigationRouter.back()

    private fun onShieldClick() = shieldFunds(closeCurrentScreen = true)

    private companion object {
        /** Marks where a figure goes in plain-text copy; never shown (see withHiddenAmounts). */
        const val AMOUNT_SLOT = "\u0000"

        val HIDDEN_AMOUNT = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    }
}
