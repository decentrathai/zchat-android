package co.electriccoin.zcash.ui.screen.send.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.WalletAccount

/** What the "Send all" link above the amount field shows. It never fills 0. */
sealed interface SendAllState {
    /** Nothing shielded at all: there is nothing to explain. */
    data object Hidden : SendAllState

    /** Tapping asks the SDK for the exact maximum. */
    data object Ready : SendAllState

    /** The exact maximum is being priced; the link is inert meanwhile. */
    data object Calculating : SendAllState

    /** The fee depends on the kind of recipient, so a valid one is needed before pricing. */
    data object NeedsRecipient : SendAllState

    /** Disabled, with the reason and its numbers shown under the field. */
    data class Unavailable(
        val reason: SendMaxUnavailable
    ) : SendAllState

    companion object {
        /**
         * The link for [account] and [recipient] (null: no valid recipient yet). [search] is the outcome
         * of the last tap, [Calculating] or [Unavailable], and wins until the form is edited.
         */
        fun of(
            account: WalletAccount?,
            recipient: SendMaxRecipient?,
            search: SendAllState? = null,
        ): SendAllState {
            // Wallet-level reasons (confirming, sealed Orchard, below the fee) do not depend on the
            // recipient, so they are shown before one is entered; a shielded recipient is the cheapest.
            val reason = account?.let { SendMaxSearch.precheck(it, recipient ?: SendMaxRecipient.SHIELDED) }
            return when {
                account == null || account.totalShieldedBalance == Zatoshi(0) -> Hidden
                search != null -> search
                reason != null -> Unavailable(reason)
                recipient == null -> NeedsRecipient
                else -> Ready
            }
        }
    }
}
