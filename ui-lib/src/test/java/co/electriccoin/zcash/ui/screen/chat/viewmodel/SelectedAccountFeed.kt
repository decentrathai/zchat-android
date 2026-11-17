package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * C5 — the selected account of one phone, which a test can change between two chain tips: all value still in the
 * sealed Orchard pool, the first Ironwood deposit still confirming, Ironwood spendable, or a Keystone hardware
 * wallet selected. The ViewModel and the send chokepoint read the same instance.
 *
 * Balances are synthetic; the Keystone account uses the SDK's own public test fixture.
 */
internal class SelectedAccountFeed private constructor(
    private val zashi: ZashiAccount,
    private val keystone: KeystoneAccount,
) : AccountDataSource {
    private val selected = MutableStateFlow<WalletAccount>(zashi)

    /** Every value in Orchard, no Ironwood at all: the chokepoint refuses with the sealed-Orchard reason. */
    fun sealedOrchard() = select(zashiWith(orchard = ORCHARD, ironwood = WalletBalance(ZERO, ZERO, ZERO)))

    /** Orchard value plus an Ironwood deposit that has landed but not confirmed ("wait for the confirmation"). */
    fun ironwoodConfirming() = select(zashiWith(orchard = ORCHARD, ironwood = WalletBalance(ZERO, ZERO, PENDING)))

    /** Nothing spendable yet and only a confirming Ironwood deposit: a send meets the transient shortfall. */
    fun onlyConfirmingIronwood() = select(zashiWith(orchard = ZERO, ironwood = WalletBalance(ZERO, ZERO, PENDING)))

    /** The Ironwood deposit confirmed: a peer-addressed memo can be paid from Ironwood. */
    fun ironwoodSpendable() = select(zashiWith(orchard = ORCHARD, ironwood = WalletBalance(SPENDABLE, ZERO, ZERO)))

    /**
     * M3 (ZC-110) — only dust in Ironwood (2,111 zatoshi available, the Fold's two dust receipts of 28 Sep) and nothing
     * else: less than one automatic handshake (17,000) can be paid.
     */
    fun ironwoodDust() = select(zashiWith(orchard = ZERO, ironwood = WalletBalance(DUST, ZERO, ZERO)))

    /** A Keystone hardware wallet is the selected account: no automatic send can be signed. */
    fun keystoneSelected() = select(keystone)

    private fun select(account: WalletAccount) {
        selected.value = account
    }

    private fun zashiWith(
        orchard: Zatoshi,
        ironwood: WalletBalance,
    ): ZashiAccount =
        zashi.copy(
            unified = zashi.unified.copy(balance = WalletBalance(orchard, ZERO, ZERO), ironwood = ironwood),
        )

    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(zashi))
    override val selectedAccount: Flow<WalletAccount?> = selected
    override val zashiAccount: Flow<ZashiAccount?> = selected.map { it as? ZashiAccount }

    override suspend fun getAllAccounts(): List<WalletAccount> = listOf(selected.value)

    override suspend fun getSelectedAccount(): WalletAccount = selected.value

    override suspend fun getZashiAccount(): ZashiAccount = selected.value as? ZashiAccount ?: zashi

    override suspend fun selectAccount(account: Account) = Unit

    override suspend fun selectAccount(account: WalletAccount) = Unit

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit

    companion object {
        private val ZERO = Zatoshi(0)
        private val ORCHARD = Zatoshi(300_000)
        private val PENDING = Zatoshi(200_000)
        private val SPENDABLE = Zatoshi(500_000)
        private val DUST = Zatoshi(2_111)

        /** Starts with Ironwood spendable; call one of the selectors to change it. */
        suspend fun create(): SelectedAccountFeed {
            val zeroBalance = WalletBalance(ZERO, ZERO, ZERO)
            val keystone =
                KeystoneAccount(
                    sdkAccount = AccountFixture.new(),
                    unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone-self"), zeroBalance),
                    transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone-self"), ZERO),
                    isSelected = true,
                )
            return SelectedAccountFeed(syntheticZashiAccount(), keystone).apply { ironwoodSpendable() }
        }
    }
}
