package co.electriccoin.zcash.ui.screen.wallettab

import androidx.annotation.DrawableRes
import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * K0-3 (2.13.0) — the account chip on the Wallet tab: the selected account's name and icon. Shown only when the phone
 * holds more than one account (a Keystone account connected earlier next to the ZChat account); a tap opens the
 * account list, the only account switcher ZChat can reach.
 */
data class WalletAccountChipState(
    val name: StringResource,
    @DrawableRes val icon: Int,
    val isKeystone: Boolean,
    val onClick: () -> Unit,
)
