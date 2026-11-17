package co.electriccoin.zcash.ui.common.model

import androidx.annotation.DrawableRes
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

sealed interface WalletAccount : Comparable<WalletAccount> {
    val sdkAccount: Account

    val unified: UnifiedInfo
    val sapling: SaplingInfo?
    val transparent: TransparentInfo
    val isSelected: Boolean
    val name: StringResource

    @get:DrawableRes
    val icon: Int

    val hdAccountIndex: Zip32AccountIndex
        get() = sdkAccount.hdAccountIndex ?: Zip32AccountIndex.new(0)

    /**
     * Total transparent + total shielded balance.
     */
    val totalBalance: Zatoshi

    /**
     * Total shielded balance including non-spendable.
     */
    val totalShieldedBalance: Zatoshi

    /**
     * F9: shielded value the SDK reports as locked (`WalletBalance.locked`), held for a transaction in progress.
     * SDK 3.0.0 keeps it in its own bucket and leaves it out of `total` and `available`, so neither [totalBalance]
     * nor [spendableShieldedBalance] counts it. Zero while nothing is locked.
     */
    val lockedShieldedBalance: Zatoshi

    /**
     * F9: what a headline shows: [totalBalance] plus [lockedShieldedBalance], so the balance does not drop while
     * value is locked. Display only. Every pending, "all shielded", spendable or budget check keeps reading
     * [totalBalance] and the spendable values, which leave locked value out.
     */
    val displayTotalBalance: Zatoshi
        get() = totalBalance + lockedShieldedBalance

    /**
     * Total spendable transparent balance.
     */
    val totalTransparentBalance: Zatoshi

    /**
     * Spendable & available shielded balance. Might be smaller than total shielded balance.
     */
    val spendableShieldedBalance: Zatoshi

    /**
     * What can pay ANOTHER person right now: confirmed Ironwood. After NU6.3 an Orchard Action can only
     * pay its own address (ZIP 258) and a Sapling-funded peer send crosses the turnstile with a public
     * amount, so neither belongs in a peer budget. [spendableShieldedBalance] keeps counting them for
     * display; every peer amount check, Send all and pre-check budgets from this instead.
     */
    val peerSpendableBalance: Zatoshi
        get() = unified.ironwood.available

    /**
     * Pending shielded Balance.
     */
    val pendingShieldedBalance: Zatoshi

    /**
     * Change-only pending balance (from our own transactions).
     * Excludes incoming valuePending which does NOT block spends.
     */
    val changePendingShieldedBalance: Zatoshi

    val isShieldedPending: Boolean
        get() = pendingShieldedBalance > Zatoshi(0)

    @Suppress("MagicNumber")
    val isShieldingAvailable: Boolean
        get() = totalTransparentBalance > Zatoshi(100000L)

    val isAllShielded: Boolean
        get() {
            val isAllShielded = totalBalance == spendableShieldedBalance
            val isAllShieldedWithTransparentDustLeft =
                totalBalance > spendableShieldedBalance &&
                    spendableShieldedBalance == totalShieldedBalance &&
                    totalTransparentBalance > Zatoshi(0) &&
                    !isShieldingAvailable

            return isAllShielded || isAllShieldedWithTransparentDustLeft
        }

    fun canSpend(amount: Zatoshi): Boolean = spendableShieldedBalance >= amount
}

data class ZashiAccount(
    override val sdkAccount: Account,
    override val unified: UnifiedInfo,
    override val sapling: SaplingInfo,
    override val transparent: TransparentInfo,
    override val isSelected: Boolean,
) : WalletAccount {
    override val name: StringResource
        get() = stringRes(co.electriccoin.zcash.ui.R.string.zashi_wallet_name)

    override val icon: Int
        get() = R.drawable.ic_item_zashi

    override val totalBalance: Zatoshi
        get() = unified.balance.total + unified.ironwood.total + sapling.balance.total + transparent.balance

    override val totalShieldedBalance: Zatoshi
        get() = unified.balance.total + unified.ironwood.total + sapling.balance.total

    override val lockedShieldedBalance: Zatoshi
        get() = unified.balance.locked + unified.ironwood.locked + sapling.balance.locked

    override val totalTransparentBalance: Zatoshi
        get() = transparent.balance

    // Orchard stays in the spendable total: NU6.3 sealed the pool but did not freeze it. Orchard
    // value is still spendable to your own address, which is exactly what a ZIP 318 crossing does.
    override val spendableShieldedBalance: Zatoshi
        get() = unified.balance.available + unified.ironwood.available + sapling.balance.available

    override val pendingShieldedBalance: Zatoshi
        get() {
            val changePendingShieldedBalance =
                unified.balance.changePending + unified.ironwood.changePending + sapling.balance.changePending
            val valuePendingShieldedBalance =
                unified.balance.valuePending + unified.ironwood.valuePending + sapling.balance.valuePending
            return changePendingShieldedBalance + valuePendingShieldedBalance
        }

    override val changePendingShieldedBalance: Zatoshi
        get() = unified.balance.changePending + unified.ironwood.changePending + sapling.balance.changePending

    override fun compareTo(other: WalletAccount) =
        when (other) {
            is KeystoneAccount -> 1
            is ZashiAccount -> 0
        }
}

data class KeystoneAccount(
    override val sdkAccount: Account,
    override val unified: UnifiedInfo,
    override val transparent: TransparentInfo,
    override val isSelected: Boolean,
) : WalletAccount {
    override val icon: Int
        get() = R.drawable.ic_item_keystone

    override val name: StringResource
        get() = stringRes(co.electriccoin.zcash.ui.R.string.keystone_wallet_name)

    override val sapling: SaplingInfo? = null

    override val totalBalance: Zatoshi
        get() = unified.balance.total + unified.ironwood.total + transparent.balance

    override val totalShieldedBalance: Zatoshi
        get() = unified.balance.total + unified.ironwood.total

    override val lockedShieldedBalance: Zatoshi
        get() = unified.balance.locked + unified.ironwood.locked

    override val totalTransparentBalance: Zatoshi
        get() = transparent.balance

    override val spendableShieldedBalance: Zatoshi
        get() = unified.balance.available + unified.ironwood.available

    override val pendingShieldedBalance: Zatoshi
        get() =
            unified.balance.changePending + unified.ironwood.changePending +
                unified.balance.valuePending + unified.ironwood.valuePending

    override val changePendingShieldedBalance: Zatoshi
        get() = unified.balance.changePending + unified.ironwood.changePending

    override fun compareTo(other: WalletAccount) =
        when (other) {
            is KeystoneAccount -> 0
            is ZashiAccount -> -1
        }
}

/**
 * ZC-602 — whether one of [accounts] (own, imported or Keystone) holds [address] now as its unified, Sapling or
 * transparent address. The account list holds each account's current addresses only: an address the wallet handed out
 * earlier is not in it (the chat's self registry keeps the ones it saw). No list yet, or a blank address: false.
 */
fun walletHoldsAddress(
    accounts: List<WalletAccount>?,
    address: String,
): Boolean {
    val asked = address.trim()
    if (asked.isEmpty()) return false
    return accounts.orEmpty().any { account ->
        account.unified.address.address == asked ||
            account.sapling?.address?.address == asked ||
            account.transparent.address.address == asked
    }
}

data class UnifiedInfo(
    val address: WalletAddress.Unified,
    /** The Orchard-pool balance. Sealed by NU6.3: spendable, but only to your own address. */
    val balance: WalletBalance,
    /**
     * The Ironwood-pool balance, added by NU6.3 (mainnet height 3,428,143, 2026-07-28).
     *
     * Ironwood is a second value pool of the same Orchard protocol — same curves, same keys, same
     * receivers — so one unified address covers both and no contact migration is needed. It is
     * where value now lives: ZIP 258 forbids adding value back to Orchard, and an Orchard Action
     * must set `enableCrossAddress = 0`, meaning it can only pay its own address. Anything sent to
     * a peer is therefore an Ironwood output.
     *
     * Defaulted so that construction sites predating the pool keep compiling and keep their
     * meaning.
     */
    val ironwood: WalletBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0)),
    /**
     * ZC-1834 — false while the synchronizer has not published this account's balances (its walletBalances is still
     * null, or the published map holds no entry for the account). [balance] and [ironwood] are then empty placeholders,
     * not a zero the wallet holds, and a funds check must treat the account as not known yet. Defaulted to true so
     * construction sites that state their balances keep their meaning.
     */
    val balancesPublished: Boolean = true,
)

data class TransparentInfo(
    val address: WalletAddress.Transparent,
    val balance: Zatoshi
)

data class SaplingInfo(
    val address: WalletAddress.Sapling,
    val balance: WalletBalance
)
