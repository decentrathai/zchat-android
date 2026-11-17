package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R

/**
 * SR-C1 (KI-37) — shown before a photo, file or voice note is uploaded to a contact whose newest relay message came from
 * ZChat 2.12 or older: that version shows the attachment's link as a line of code. [name] is the contact's name as the
 * chat header shows it. Nothing is uploaded, sent or paid until [onSendAnyway]; [onCancel] (also a tap outside or Back)
 * leaves the attachment FAILED with its Retry.
 */
@Composable
fun LegacyContactAttachmentDialog(
    name: String,
    onSendAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.chat_attachment_legacy_title, name)) },
        text = { Text(stringResource(R.string.chat_attachment_legacy_body, name), fontSize = 15.sp) },
        confirmButton = {
            TextButton(onClick = onSendAnyway) {
                Text(stringResource(R.string.chat_attachment_legacy_send_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.chat_attachment_legacy_cancel))
            }
        },
    )
}
