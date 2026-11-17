package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RekeyUnkeyableConfirmation

/**
 * Round 8 P5 (PHONE_TRIAGE F5a) — asked before a removal or a key rotation when some key holders can't be sent the new
 * key (no secure session with them). It names them and says what happens to them. Nothing is quoted, sent or changed
 * unless the admin taps "Remove anyway" / "Rotate anyway" ([onConfirm]); Cancel ([onDismiss]) changes nothing.
 */
@Composable
fun RekeyUnkeyableDialog(
    confirmation: RekeyUnkeyableConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (confirmation.removing) R.string.group_remove_member_title else R.string.group_rotate_confirm_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(R.string.group_rekey_unkeyable_body, confirmation.names.joinToString(", ")),
                fontSize = 15.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (confirmation.removing) R.string.group_rekey_remove_anyway else R.string.group_rekey_rotate_anyway,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(co.electriccoin.zcash.ui.design.R.string.general_cancel))
            }
        },
    )
}
