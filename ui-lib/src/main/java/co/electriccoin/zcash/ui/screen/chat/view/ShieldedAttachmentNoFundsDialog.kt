package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy

/**
 * SR-A (S-A5) — a photo, file or voice note in a Shielded chat was refused before anything was uploaded or paid: its link
 * goes inside a Zcash transaction that costs at least [floor], and the wallet can spend [spendable] now. [onSwitchToTunnel]
 * is the mode picker's own switch (which asks its own question when the switch is paid); [onNotNow] (also a tap outside
 * or Back) only closes the notice. The attachment stays FAILED with its Retry.
 */
@Composable
fun ShieldedAttachmentNoFundsDialog(
    floor: Zatoshi,
    spendable: Zatoshi,
    onSwitchToTunnel: () -> Unit,
    onNotNow: () -> Unit,
) {
    val locale = rememberDesiredFormatLocale()
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.chat_attachment_no_funds_title)) },
        text = {
            Text(
                stringResource(
                    R.string.chat_attachment_no_funds_body,
                    PaidActionCopy.zec(floor, locale),
                    PaidActionCopy.zec(spendable, locale),
                ),
                fontSize = 15.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onSwitchToTunnel) {
                Text(stringResource(R.string.chat_attachment_no_funds_switch_tunnel))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.chat_attachment_no_funds_not_now))
            }
        },
    )
}
