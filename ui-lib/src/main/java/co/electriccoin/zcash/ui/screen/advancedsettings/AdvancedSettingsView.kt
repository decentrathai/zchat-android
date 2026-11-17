package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiHorizontalDivider
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.component.listitem.ZashiListItem
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.util.scaffoldScrollPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote
import kotlinx.collections.immutable.persistentListOf

// TODO [#1271]: Add AdvancedSettingsView Tests
// TODO [#1271]: https://github.com/Electric-Coin-Company/zashi-android/issues/1271
@Composable
fun AdvancedSettings(
    state: AdvancedSettingsState,
) {
    BlankBgScaffold(
        topBar = {
            AdvancedSettingsTopAppBar(
                onBack = state.onBack,
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // ZC-1707, ZC-1717 (QA2): the Scaffold's bottom padding already holds the navigation bar, so the
                    // footnote and Reset end above it and stay reachable by scrolling; no second inset is added.
                    .scaffoldScrollPadding(paddingValues),
        ) {
            state.items.fastForEachIndexed { index, item ->
                ZashiListItem(
                    state = item,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                if (index != state.items.lastIndex) {
                    ZashiHorizontalDivider(
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(ZashiDimensions.Spacing.spacingXl))
            Spacer(modifier = Modifier.weight(1f))
            Info()
            Spacer(modifier = Modifier.height(20.dp))
            ZashiButton(
                modifier =
                    Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                defaultPrimaryColors = ZashiButtonDefaults.destructive1Colors(),
                state = state.deleteButton
            )
        }
    }
    // F10: the confirm before "Restore old chats" restarts ZCHAT.
    state.restoreOldChatsConfirm?.let { confirm ->
        AppAlertDialog(
            title = stringResource(R.string.advanced_settings_restore_old_chats_confirm_title),
            text = stringResource(R.string.advanced_settings_restore_old_chats_confirm_text),
            confirmButtonText = stringResource(R.string.advanced_settings_restore_old_chats_confirm_button),
            onConfirmButtonClick = confirm.onConfirm,
            dismissButtonText = stringResource(R.string.advanced_settings_restore_old_chats_cancel_button),
            onDismissButtonClick = confirm.onDismiss,
            onDismissRequest = confirm.onDismiss,
        )
    }
    // KI-23 (ZC-020): the dialogs of "Automatic setup payments"; ZC-1013: the turn-on confirm shows the standing consent
    // with the price of one setup message (the handshake quote the question uses).
    state.automaticSetupPaymentsDialog?.let { dialog ->
        when (dialog.kind) {
            AutomaticSetupPaymentsDialogState.Kind.CONFIRM_TURN_OFF ->
                AppAlertDialog(
                    title = stringResource(R.string.advanced_settings_auto_setup_payments_off_title),
                    text = stringResource(R.string.advanced_settings_auto_setup_payments_off_text),
                    confirmButtonText = stringResource(R.string.advanced_settings_auto_setup_payments_off_button),
                    onConfirmButtonClick = dialog.onConfirm,
                    dismissButtonText = stringResource(R.string.advanced_settings_auto_setup_payments_cancel_button),
                    onDismissButtonClick = dialog.onDismiss,
                    onDismissRequest = dialog.onDismiss,
                )
            AutomaticSetupPaymentsDialogState.Kind.CONFIRM_TURN_ON -> {
                // ZC1013-X1: what waits now (the in-chat question's count and price) and what keeps asking.
                val locale = rememberDesiredFormatLocale()
                val waiting = dialog.waiting
                val waitingLine =
                    waiting?.takeIf { it.transactions > 0 }?.let {
                        pluralStringResource(
                            R.plurals.advanced_settings_auto_setup_payments_waiting,
                            it.transactions,
                            it.transactions,
                            PaidActionCopy.zec(it.cost, locale)
                        )
                    }
                val declinedLine =
                    waiting?.takeIf { it.declined > 0 }?.let {
                        pluralStringResource(R.plurals.advanced_settings_auto_setup_payments_declined, it.declined, it.declined)
                    }
                AppAlertDialog(
                    title = stringResource(R.string.advanced_settings_auto_setup_payments_on_title),
                    text =
                        listOfNotNull(
                            stringResource(
                                R.string.advanced_settings_auto_setup_payments_on_text,
                                PaidActionCopy.zec(handshakeSpendQuote(1), locale)
                            ),
                            waitingLine,
                            declinedLine,
                        ).joinToString("\n\n"),
                    confirmButtonText = stringResource(R.string.advanced_settings_auto_setup_payments_on_button),
                    onConfirmButtonClick = dialog.onConfirm,
                    dismissButtonText = stringResource(R.string.advanced_settings_auto_setup_payments_cancel_button),
                    onDismissButtonClick = dialog.onDismiss,
                    onDismissRequest = dialog.onDismiss,
                )
            }
            AutomaticSetupPaymentsDialogState.Kind.NOT_SAVED ->
                AppAlertDialog(
                    title = stringResource(R.string.advanced_settings_auto_setup_payments),
                    text = stringResource(R.string.advanced_settings_auto_setup_payments_not_saved),
                    confirmButtonText = stringResource(R.string.advanced_settings_auto_setup_payments_ok_button),
                    onConfirmButtonClick = dialog.onDismiss,
                    onDismissRequest = dialog.onDismiss,
                )
        }
    }
}

@Composable
private fun Info() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_advanced_settings_info),
            contentDescription = null,
            // textSecondary not textTertiary: this hint sits above the destructive Reset button on
            // BgBase where tertiary measured 4.47:1 (sub-AA). Secondary is ~7.8:1.
            colorFilter = ColorFilter.tint(ZashiColors.Text.textSecondary)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(id = R.string.advanced_settings_info),
            fontSize = 12.sp,
            color = ZashiColors.Text.textSecondary,
        )
    }
}

@Composable
private fun AdvancedSettingsTopAppBar(
    onBack: () -> Unit
) {
    ZashiSmallTopAppBar(
        title = stringResource(id = R.string.advanced_settings_title),
        modifier = Modifier.testTag(AdvancedSettingsTag.ADVANCED_SETTINGS_TOP_APP_BAR),
        showTitleLogo = true,
        navigationAction = {
            ZashiTopAppBarBackNavigation(onBack = onBack)
        },
    )
}

@PreviewScreens
@Composable
private fun AdvancedSettingsPreview() =
    ZcashTheme {
        AdvancedSettings(
            state =
                AdvancedSettingsState(
                    onBack = {},
                    items =
                        persistentListOf(
                            ListItemState(
                                title = stringRes(R.string.advanced_settings_recovery),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings_recovery),
                                onClick = {}
                            ),
                            ListItemState(
                                title = stringRes(R.string.advanced_settings_export),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings_export),
                                onClick = {}
                            ),
                            ListItemState(
                                title = stringRes(R.string.advanced_settings_choose_server),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings_choose_server),
                                onClick = {}
                            ),
                            ListItemState(
                                title = stringRes(R.string.advanced_settings_currency_conversion),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings_currency_conversion),
                                onClick = {}
                            ),
                            ListItemState(
                                title = stringRes(R.string.advanced_settings_crash_reporting),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings_crash_reporting),
                                onClick = {}
                            )
                        ),
                    deleteButton =
                        ButtonState(
                            text = stringRes(R.string.advanced_settings_delete_button),
                            onClick = {}
                        )
                ),
        )
    }
