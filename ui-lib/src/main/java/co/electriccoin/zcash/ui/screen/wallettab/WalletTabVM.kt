package co.electriccoin.zcash.ui.screen.wallettab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.OrchardSweepDecision
import co.electriccoin.zcash.ui.common.model.decideOrchardSweep
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsFromMessageUseCase
import co.electriccoin.zcash.ui.screen.accountlist.AccountList
import co.electriccoin.zcash.ui.screen.ai.AiTab
import co.electriccoin.zcash.ui.screen.chat.ChatList
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import co.electriccoin.zcash.ui.screen.moveoldbalance.MoveOldBalanceArgs
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeNotice
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalletTabVM(
    private val navigationRouter: NavigationRouter,
    private val navigateToReceive: NavigateToReceiveUseCase,
    private val navigateToSwap: NavigateToSwapUseCase,
    private val observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val shieldFundsFromMessage: ShieldFundsFromMessageUseCase,
    private val zchatPreferences: ZchatPreferences,
    getWalletAccounts: GetWalletAccountsUseCase,
    postUpgradeNotice: PostUpgradeNotice,
    walletSnapshotDataSource: WalletSnapshotDataSource,
) : ViewModel() {
    val updateRequired = NetworkUpgradeGuard.state

    /**
     * SY-1: the catch-up bar after an upgrade from 2.12.4 or older (Bridge 2), as on the chat list: the same flag, the
     * same wallet snapshot and the same rule, so both show the same N and both go when the flag is cleared (only
     * MainActivity clears it, on the SDK's fully-scanned signal). Display only. Null when no bar is shown.
     */
    val catchUpPercent: StateFlow<Int?> =
        combine(postUpgradeNotice.catchUpPending, walletSnapshotDataSource.observe()) { pending, snapshot ->
            PostUpgradeRules.catchUpBannerPercentOf(pending, snapshot)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            PostUpgradeRules.catchUpBannerPercentOf(
                postUpgradeNotice.catchUpPending.value,
                walletSnapshotDataSource.observe().value,
            ),
        )

    /**
     * K0-3 (2.13.0): the account chip, only when the phone holds more than one account. Without it nobody could switch:
     * the only switcher (AccountList) opened from the Home screen, which no ZChat build navigates to. So a user with a
     * Keystone account selected could not get back to the ZChat account to chat, and a Keystone account whose
     * selection the Keystore lost stayed hidden. A phone with only the ZChat account sees no change.
     */
    val accountChip: StateFlow<WalletAccountChipState?> =
        getWalletAccounts
            .observe()
            .map { accounts ->
                val selected = accounts?.takeIf { it.size > 1 }?.firstOrNull { it.isSelected }
                selected?.let {
                    WalletAccountChipState(
                        name = it.name,
                        icon = it.icon,
                        isKeystone = it is KeystoneAccount,
                        onClick = ::onAccountChip,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    /** Opens the account list; choosing an account there selects it and comes back here. */
    fun onAccountChip() = navigationRouter.forward(AccountList)

    // Transparent balance available to shield (null when there's nothing to shield / below the SDK
    // threshold). Drives the "Shield transparent funds" banner — the 1-click entry point Zashi shows on
    // its home screen but ZCHAT's custom wallet tab never surfaced. Shielding is what makes transparent
    // funds spendable + private; the button reuses the SAME reviewed shield flow (ShieldFundsFromMessage).
    val shieldableTransparent: StateFlow<Zatoshi?> =
        observeSelectedWalletAccount()
            .map { account -> account?.takeIf { it.isShieldingAvailable }?.totalTransparentBalance }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Bumped when the user dismisses the card, so it is recomputed from the stored dismissal. */
    private val dismissals = MutableStateFlow(0)

    /** The "Old balance" card (sweep spec E1); null when the sealed Orchard pool holds nothing. */
    val oldBalanceCard: StateFlow<OldBalanceCardState?> =
        combine(observeSelectedWalletAccount(), dismissals) { account, _ ->
            account?.let {
                oldBalanceCard(
                    decision = decideOrchardSweep(it),
                    dismissedAtZatoshi = zchatPreferences.getDismissedOldBalance(oldBalanceDismissalKey(it)),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    fun onMoveOldBalance() = navigationRouter.forward(MoveOldBalanceArgs)

    /** Only a balance too small to move can be dismissed; it is remembered at today's figure. */
    fun onDismissOldBalance() =
        viewModelScope.launch {
            val account = observeSelectedWalletAccount().first() ?: return@launch
            val decision = decideOrchardSweep(account)
            if (decision is OrchardSweepDecision.TooSmall) {
                zchatPreferences.setDismissedOldBalance(oldBalanceDismissalKey(account), decision.orchard.value)
                dismissals.update { it + 1 }
            }
        }

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
