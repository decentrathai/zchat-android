package co.electriccoin.zcash.ui.screen.chat.model

import androidx.annotation.StringRes
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import java.util.Locale

/** R2-2 — figures and text the chat's money prompts show (res/ui/common chat_money_strings.xml, English and Spanish). */
object PaidActionCopy {
    /**
     * Exact ZEC, no rounding and no trailing zeros, written in [locale] the way the balances are ("0.00017" in English,
     * "0,00017" in Spanish). A cost is never rounded down. Round 8 P9 (ES-2): callers pass rememberDesiredFormatLocale().
     */
    fun zec(
        amount: Zatoshi,
        locale: Locale
    ): String = zecDisplay(amount, locale)

    /**
     * R8-fixup F8 — the least paying a payment request of [amount] takes from the wallet: the amount, the platform fee
     * and the ZIP 317 fee of the one-memo payment, from the sizer the payment itself is built with. A wallet whose
     * balance sits in several notes pays a larger network fee, so this is a floor. D-6 (fix-all 2.13.0): the pay-request
     * confirm no longer shows it; it shows the exact figure of the payment's quote (ChatViewModel.quotePayment).
     */
    fun payRequestTotal(amount: Zatoshi): Zatoshi = onChainSendMinimumCost(1, amount)

    /**
     * What [confirmation] says the tap does. Round 10b R10B-1: a first message whose count includes our on-chain ZBOOT
     * names that third transaction. T3b-1: a mode switch that also pays for the chat setup says so. SR-A: a Shielded
     * attachment of more than one memo names the count ([actionArgs]). ZC-535: a key-change reconnect with a contact
     * known to be on ZChat 2.12.4 or older says they won't see these on-chain messages until they update.
     */
    @StringRes
    fun actionText(confirmation: PaidActionConfirmation): Int =
        when {
            // ZC-1272: one question for the whole chat; the price line names each leg.
            confirmation.setupLegCost != null && confirmation.waitsForFunds -> R.string.paid_action_chat_setup_waits
            confirmation.setupLegCost != null -> R.string.paid_action_chat_setup
            confirmation.action == PaidAction.FIRST_MESSAGE && confirmation.includesRelayIdentity ->
                R.string.paid_action_first_message_relay
            confirmation.action == PaidAction.MODE_CHANGE && confirmation.includesChatSetup ->
                R.string.paid_action_mode_change_setup
            // SR-A: a link of more than one memo names the memo count (the text takes it as %1$d).
            confirmation.action == PaidAction.SHIELDED_ATTACHMENT && confirmation.memos > 1 ->
                R.string.paid_action_shielded_attachment_memos
            // T5b X-06: the earlier try paid its key exchange; "The earlier try was not paid" would be false.
            confirmation.action == PaidAction.SHIELDED_ATTACHMENT_RETRY && confirmation.keyExchangeAlreadyPaid ->
                R.string.paid_action_shielded_attachment_retry_kex_paid
            // ZC-535: the same question plus what a contact on 2.12.4 or older sees.
            confirmation.action == PaidAction.KEY_CHANGE_RECONNECT && confirmation.contactIsOlder ->
                R.string.paid_action_key_change_reconnect_older
            // ZC-545: the Shielded accept's question for a contact on 2.12.4 or older.
            confirmation.action == PaidAction.KEY_CHANGE_ACCEPT && confirmation.contactIsOlder ->
                R.string.paid_action_key_change_accept_older
            else -> actionText(confirmation.action)
        }

    /** SR-A — the format arguments of [actionText] for [confirmation] (only the memo count of a multi-memo link). */
    fun actionArgs(confirmation: PaidActionConfirmation): Array<Any> =
        if (actionText(confirmation) == R.string.paid_action_shielded_attachment_memos) arrayOf(confirmation.memos) else emptyArray()

    /** What the confirmation says the tap does. */
    @StringRes
    fun actionText(action: PaidAction): Int =
        when (action) {
            PaidAction.DISAPPEARING_TIMER -> R.string.paid_action_disappearing_timer
            PaidAction.MODE_CHANGE -> R.string.paid_action_mode_change
            PaidAction.KEY_CHANGE_RECONNECT -> R.string.paid_action_key_change_reconnect
            PaidAction.KEY_CHANGE_ACCEPT -> R.string.paid_action_key_change_accept
            PaidAction.REACTION -> R.string.paid_action_reaction
            PaidAction.FIRST_MESSAGE -> R.string.paid_action_first_message
            PaidAction.STATUS_BROADCAST -> R.string.paid_action_status_broadcast
            PaidAction.STATUS_BROADCAST_REST -> R.string.paid_action_status_broadcast_rest
            PaidAction.PAYMENT_REQUEST_RETRY -> R.string.paid_action_payment_request_retry
            PaidAction.GROUP_CREATE -> R.string.paid_action_group_create
            PaidAction.GROUP_ADD_MEMBER -> R.string.paid_action_group_add_member
            PaidAction.GROUP_RESEND_INVITE -> R.string.paid_action_group_resend_invite
            PaidAction.GROUP_REMOVE_MEMBER -> R.string.paid_action_group_remove_member
            PaidAction.GROUP_ROTATE_KEY -> R.string.paid_action_group_rotate_key
            PaidAction.GROUP_KEY_SYNC -> R.string.paid_action_group_key_sync
            PaidAction.GROUP_LEAVE -> R.string.paid_action_group_leave
            PaidAction.GROUP_MESSAGE -> R.string.paid_action_group_message
            PaidAction.RESET_ENCRYPTION -> R.string.paid_action_reset_encryption
            PaidAction.GROUP_INTRODUCE -> R.string.paid_action_group_introduce
            PaidAction.LONG_MESSAGE -> R.string.paid_action_long_message
            PaidAction.PAYMENT_REQUEST -> R.string.paid_action_payment_request
            PaidAction.SHIELDED_ATTACHMENT -> R.string.paid_action_shielded_attachment
            PaidAction.SHIELDED_ATTACHMENT_RETRY -> R.string.paid_action_shielded_attachment_retry
            PaidAction.NEW_CHAT_FIRST_MESSAGE -> R.string.paid_action_new_chat_first_message
            PaidAction.NEW_CHAT_FIRST_MESSAGE_TUNNEL -> R.string.paid_action_new_chat_first_message_tunnel
            PaidAction.HANDSHAKE_RETRY -> R.string.paid_action_handshake_retry
            PaidAction.KEX_ANSWER_TRUE_PRICE -> R.string.paid_action_kex_answer_true_price
            PaidAction.OWN_SETUP_TRUE_PRICE -> R.string.paid_action_own_setup_true_price
            PaidAction.PEER_MOVED_TO_SHIELDED -> R.string.paid_action_peer_moved_to_shielded
        }
}
