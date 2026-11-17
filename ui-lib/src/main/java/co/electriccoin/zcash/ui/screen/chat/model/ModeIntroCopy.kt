package co.electriccoin.zcash.ui.screen.chat.model

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R

/**
 * ZC-1457: one row of the first-run mode intro. [subtitleRes] is one short line that names the cost; [bodyRes] is the
 * existing long text, shown only when the row is tapped open. Its English is exactly the pinned MODE_INTRO_* text.
 */
data class ModeIntroItem(
    val mode: ConversationMode,
    @StringRes val subtitleRes: Int,
    @StringRes val bodyRes: Int,
)

/** ZC-1457: the three rows of the mode intro (ConversationModeIntroDialog), Shielded, Tunnel and Open, in that order. */
object ModeIntroCopy {
    val rows: List<ModeIntroItem> =
        listOf(
            ModeIntroItem(ConversationMode.VAULT, R.string.chat_mode_intro_short_shielded, R.string.chat_mode_intro_shielded_body),
            ModeIntroItem(ConversationMode.TUNNEL, R.string.chat_mode_intro_short_tunnel, R.string.chat_mode_intro_tunnel_body),
            ModeIntroItem(ConversationMode.OPEN, R.string.chat_mode_intro_short_open, R.string.chat_mode_intro_open_body),
        )
}
