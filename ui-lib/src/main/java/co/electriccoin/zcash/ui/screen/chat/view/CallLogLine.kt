package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.screen.chat.model.CallLogInfo

/**
 * QA2 Q2-76 (ZC-1577): the text of a call-log entry, "📞 Outgoing call · 2:13", in the phone's language. The pill in a
 * chat and the chat list's preview both show it; [separator] goes between the icon and the label.
 */
@Composable
internal fun callLogLine(
    info: CallLogInfo,
    separator: String,
): String {
    val label = stringResource(info.labelRes)
    val withDuration = info.durationText?.let { "$label · $it" } ?: label
    return "${info.icon}$separator$withDuration"
}
