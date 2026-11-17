package co.electriccoin.zcash.ui.screen.chat.view

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentBlockAction
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentBlockReason
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.action
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote

/**
 * SR-C2 — shown instead of the picker, the camera or the recorder (and after a refused share or Retry) when the chat has
 * no end-to-end key to lock a photo, file or voice note with. Nothing was prepared, uploaded or paid.
 *
 * ZC-1474: one body and one action per [AttachmentBlockReason] ([AttachmentBlockReason.action]); every action is an entry
 * point the chat already has, and nothing here pays by itself:
 * - SWITCH_TO_SHIELDED: [onSwitchToShielded], the mode picker's own switch (it asks first whenever it would pay);
 * - SEND_KEY_EXCHANGE: [onSendKeyExchange], the ⋮ menu's Reconnect (a failed setup's Retry asks its price; otherwise the
 *   handshake consent); its words say it may cost ZEC;
 * - ACCEPT_KEY_EXCHANGE: [onAcceptKeyExchange], the chat-request banner's Accept, with that banner's own price
 *   ([acceptTransactions], ChatViewModel.chatRequestAcceptTransactions); null where that banner is not offered (an Open
 *   chat answers through its Tunnel offer), and then the notice offers OK only;
 * - SEND_A_MESSAGE and WAIT: OK only (the composer is the way, or there is nothing to do but wait).
 */
@Composable
fun AttachmentKeyExchangeDialog(
    reason: AttachmentBlockReason,
    onSwitchToShielded: () -> Unit,
    onSendKeyExchange: () -> Unit,
    onAcceptKeyExchange: () -> Unit,
    onDismiss: () -> Unit,
    acceptTransactions: Int? = null,
) {
    val action = reason.action
    val offersAccept = action == AttachmentBlockAction.ACCEPT_KEY_EXCHANGE && acceptTransactions != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_attachment_needs_kex_title)) },
        text = {
            Column {
                Text(stringResource(attachmentKeyExchangeBody(reason)), fontSize = 15.sp)
                when {
                    action == AttachmentBlockAction.SEND_KEY_EXCHANGE -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        // ZC-1681: its own cost line; the Reconnect subtitle promised a relay path that is not known here.
                        Text(stringResource(R.string.chat_attachment_kex_not_sent_cost), fontSize = 13.sp)
                    }
                    action == AttachmentBlockAction.ACCEPT_KEY_EXCHANGE && acceptTransactions != null -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.chat_request_banner_count_known,
                                acceptTransactions,
                                acceptTransactions,
                                PaidActionCopy.zec(handshakeSpendQuote(acceptTransactions), rememberDesiredFormatLocale()),
                            ),
                            fontSize = 13.sp,
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (action) {
                AttachmentBlockAction.SWITCH_TO_SHIELDED ->
                    TextButton(onClick = onSwitchToShielded) {
                        Text(stringResource(R.string.chat_attachment_needs_kex_switch_shielded))
                    }
                AttachmentBlockAction.SEND_KEY_EXCHANGE ->
                    TextButton(onClick = onSendKeyExchange) {
                        Text(stringResource(R.string.chat_menu_reconnect_title))
                    }
                AttachmentBlockAction.ACCEPT_KEY_EXCHANGE ->
                    if (offersAccept) {
                        TextButton(onClick = onAcceptKeyExchange) {
                            Text(stringResource(R.string.chat_request_accept))
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_dialog_ok)) }
                    }
                AttachmentBlockAction.SEND_A_MESSAGE, AttachmentBlockAction.WAIT ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_dialog_ok)) }
            }
        },
        dismissButton =
            if (action == AttachmentBlockAction.SWITCH_TO_SHIELDED ||
                action == AttachmentBlockAction.SEND_KEY_EXCHANGE ||
                offersAccept
            ) {
                {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.chat_attachment_needs_kex_not_now))
                    }
                }
            } else {
                null
            },
    )
}

/** The notice's body for [reason]; the history wait is a plain notice (ChatSendCopy.ATTACHMENT_STILL_READING_HISTORY). */
@StringRes
internal fun attachmentKeyExchangeBody(reason: AttachmentBlockReason): Int =
    when (reason) {
        AttachmentBlockReason.NEEDS_KEY_EXCHANGE -> R.string.chat_attachment_needs_kex_body
        AttachmentBlockReason.NEEDS_KEY_EXCHANGE_SHIELDED -> R.string.chat_attachment_needs_kex_shielded_body
        AttachmentBlockReason.KEY_EXCHANGE_UNANSWERED -> R.string.chat_attachment_needs_kex_unanswered_body
        AttachmentBlockReason.STILL_READING_HISTORY -> R.string.chat_attachment_still_reading_history
        AttachmentBlockReason.KEX_NOT_SENT -> R.string.chat_attachment_kex_not_sent_body
        AttachmentBlockReason.PEER_MUST_ACCEPT -> R.string.chat_attachment_peer_must_accept_body
    }
