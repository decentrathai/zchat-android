package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupWaiting

@Immutable
data class AdvancedSettingsState(
    val onBack: () -> Unit,
    val items: List<ListItemState>,
    val deleteButton: ButtonState,
    /** F10: the confirm before "Restore old chats" restarts ZCHAT; null while it is not shown. */
    val restoreOldChatsConfirm: RestoreOldChatsConfirmState? = null,
    /** KI-23: the dialog of the row "Automatic setup payments"; null while none is shown. */
    val automaticSetupPaymentsDialog: AutomaticSetupPaymentsDialogState? = null,
)

/**
 * KI-23 (ZC-020): the dialogs of the Advanced Settings row "Automatic setup payments". None of them turns the payments
 * on: that happens only in the question that shows what will be paid.
 */
@Immutable
data class AutomaticSetupPaymentsDialogState(
    val kind: Kind,
    /** Turn off for [Kind.CONFIRM_TURN_OFF], turn on for [Kind.CONFIRM_TURN_ON]. */
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    /**
     * ZC1013-X1, [Kind.CONFIRM_TURN_ON] only: what waits now and what keeps asking, read at the row's tap; null when it
     * could not be read.
     */
    val waiting: AutomaticSetupWaiting? = null,
) {
    enum class Kind {
        /** The payments are on: ask before turning them off. */
        CONFIRM_TURN_OFF,

        /** ZC-1013: the payments are off: ask before turning them back on, with the standing-consent text. */
        CONFIRM_TURN_ON,

        /** Turning them off could not be saved: they are unchanged. */
        NOT_SAVED,
    }
}

/** F10 (fix-all 2.13.0, chat copy #16): the confirm of the Advanced Settings row "Restore old chats". */
@Immutable
data class RestoreOldChatsConfirmState(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)
