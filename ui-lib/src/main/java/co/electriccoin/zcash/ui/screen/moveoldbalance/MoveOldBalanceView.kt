// One small composable per piece of the sheet (title, body, bullet, figure, button) keeps each state readable.
@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.moveoldbalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.LottieProgress
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveOldBalanceView(state: MoveOldBalanceState?) {
    val current by rememberUpdatedState(state)
    // While the move is being sent the sheet cannot be swiped away (Back is ignored by the state too).
    val sheetState =
        rememberScreenModalBottomSheetState(
            confirmValueChange = { current !is MoveOldBalanceState.Sending || it != SheetValue.Hidden }
        )
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
    ) {
        Content(state = it, modifier = Modifier.weight(1f, false))
    }
}

@Composable
private fun Content(
    state: MoveOldBalanceState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
    ) {
        when (state) {
            is MoveOldBalanceState.Preparing -> Progress(stringRes(R.string.move_old_balance_preparing), null)
            is MoveOldBalanceState.Consent -> ConsentContent(state)
            is MoveOldBalanceState.Review -> ReviewContent(state)
            is MoveOldBalanceState.Sending ->
                Progress(
                    stringRes(R.string.move_old_balance_sending_title),
                    stringRes(R.string.move_old_balance_sending_body)
                )
            is MoveOldBalanceState.Success -> SuccessContent(state)
            is MoveOldBalanceState.Failed -> FailedContent(state)
            is MoveOldBalanceState.Blocked -> BlockedContent(state)
        }
    }
}

@Composable
private fun ColumnScope.Progress(
    title: StringResource,
    body: StringResource?
) {
    Title(title)
    body?.let {
        Spacer(8.dp)
        Body(it)
    }
    Spacer(24.dp)
    LottieProgress(modifier = Modifier.align(Alignment.CenterHorizontally), size = 32.dp)
}

@Composable
private fun ColumnScope.ConsentContent(state: MoveOldBalanceState.Consent) {
    Title(stringRes(R.string.move_old_balance_consent_title))
    Spacer(8.dp)
    Body(stringRes(R.string.move_old_balance_consent_intro, stringRes(state.amount)))
    Spacer(16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bullet(stringRes(R.string.move_old_balance_consent_public, stringRes(state.amount)), isWarning = true)
        Bullet(stringRes(R.string.move_old_balance_consent_linkable), isWarning = true)
        Bullet(stringRes(R.string.move_old_balance_consent_private))
        if (state.isTorOff) Bullet(stringRes(R.string.move_old_balance_consent_tor), isWarning = true)
        state.handshakes?.let { HandshakeBullet(it) }
        Bullet(stringRes(R.string.move_old_balance_consent_one_tx))
        Bullet(stringRes(R.string.move_old_balance_consent_fee, stringRes(state.fee), stringRes(state.arrives)))
        // The only true dust disclosure: the SDK does not export the dust it leaves out of the balance.
        Bullet(stringRes(R.string.move_old_balance_consent_dust, stringRes(Zatoshi(Zip317.MARGINAL_FEE_ZATOSHI))))
    }
    Spacer(24.dp)
    Button(stringRes(R.string.move_old_balance_consent_continue), state.onContinue)
    Spacer(8.dp)
    Button(stringRes(R.string.move_old_balance_not_now), state.onNotNow, ButtonStyle.SECONDARY)
}

@Composable
private fun ColumnScope.ReviewContent(state: MoveOldBalanceState.Review) {
    Title(stringRes(R.string.move_old_balance_review_title))
    Spacer(16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Figure(stringRes(R.string.move_old_balance_review_moving), stringRes(state.amount))
        Figure(stringRes(R.string.move_old_balance_review_fee), stringRes(state.fee))
        Figure(stringRes(R.string.move_old_balance_review_arrives), stringRes(state.arrives))
        Figure(stringRes(R.string.move_old_balance_review_to), stringRes(R.string.move_old_balance_review_to_value))
        Body(stringRes(R.string.move_old_balance_review_one_tx))
    }
    Spacer(16.dp)
    if (state.isFeeHigh) {
        Warning(stringRes(R.string.move_old_balance_review_high_fee, stringRes(state.fee)))
        Spacer(8.dp)
    }
    Warning(stringRes(R.string.move_old_balance_review_public_note, stringRes(state.amount)))
    Spacer(24.dp)
    Button(stringRes(R.string.move_old_balance_review_confirm), state.onConfirm)
    Spacer(8.dp)
    Button(stringRes(R.string.move_old_balance_review_back), state.onBackToConsent, ButtonStyle.SECONDARY)
}

@Composable
private fun ColumnScope.SuccessContent(state: MoveOldBalanceState.Success) {
    Title(stringRes(R.string.move_old_balance_success_title))
    Spacer(8.dp)
    Body(stringRes(R.string.move_old_balance_success_body))
    state.handshakes?.let {
        Spacer(8.dp)
        Body(handshakeText(it))
    }
    Spacer(24.dp)
    state.onViewTransaction?.let {
        Button(stringRes(R.string.move_old_balance_view_tx), it, ButtonStyle.SECONDARY)
        Spacer(8.dp)
    }
    Button(stringRes(R.string.move_old_balance_done), state.onDone)
}

@Composable
private fun ColumnScope.FailedContent(state: MoveOldBalanceState.Failed) {
    if (state.isOutcomeUnknown) {
        Title(stringRes(R.string.move_old_balance_unknown_title))
        Spacer(8.dp)
        Body(stringRes(R.string.move_old_balance_unknown_body))
    } else if (state.isNetworkFailure) {
        Title(stringRes(R.string.move_old_balance_grpc_title))
        Spacer(8.dp)
        Body(stringRes(R.string.move_old_balance_grpc_body))
    } else {
        Title(stringRes(R.string.move_old_balance_failure_title))
        Spacer(8.dp)
        Body(stringRes(R.string.move_old_balance_failure_body))
    }
    Spacer(24.dp)
    state.onViewTransaction?.let {
        Button(stringRes(R.string.move_old_balance_view_tx), it, ButtonStyle.SECONDARY)
        Spacer(8.dp)
    }
    Button(stringRes(R.string.move_old_balance_done), state.onDone)
}

@Composable
private fun ColumnScope.BlockedContent(state: MoveOldBalanceState.Blocked) {
    val (title, body) = blockedCopy(state.reason)
    Title(title)
    Spacer(8.dp)
    Body(body)
    Spacer(24.dp)
    Button(stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok), state.onOk)
}

private fun blockedCopy(reason: MoveOldBalanceBlock): Pair<StringResource, StringResource> =
    when (reason) {
        is MoveOldBalanceBlock.TooSmall ->
            stringRes(R.string.move_old_balance_too_small_title) to
                stringRes(R.string.move_old_balance_too_small_body, stringRes(reason.orchard), stringRes(reason.fee))
        MoveOldBalanceBlock.WaitForSync ->
            stringRes(R.string.move_old_balance_wait_sync_title) to stringRes(R.string.move_old_balance_wait_sync_body)
        MoveOldBalanceBlock.Pending ->
            stringRes(R.string.move_old_balance_pending_title) to stringRes(R.string.move_old_balance_pending_body)
        MoveOldBalanceBlock.Keystone ->
            stringRes(R.string.move_old_balance_keystone_title) to stringRes(R.string.move_old_balance_keystone_body)
        MoveOldBalanceBlock.NothingToMove ->
            stringRes(R.string.move_old_balance_nothing_title) to stringRes(R.string.move_old_balance_nothing_body)
        MoveOldBalanceBlock.UpdateRequired ->
            stringRes(R.string.update_required_title) to stringRes(R.string.update_required_refusal)
        MoveOldBalanceBlock.Error ->
            stringRes(R.string.move_old_balance_error_title) to stringRes(R.string.move_old_balance_error_body)
    }

private fun handshakeText(handshakes: PendingHandshakes): StringResource =
    stringRes(R.string.move_old_balance_consent_handshakes, handshakes.count, stringRes(handshakes.total))

@Composable
private fun HandshakeBullet(handshakes: PendingHandshakes) = Bullet(handshakeText(handshakes), isWarning = true)

@Composable
private fun Title(text: StringResource) {
    Text(
        text = text.getValue(),
        color = ZashiColors.Text.textPrimary,
        style = ZashiTypography.header6,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun Body(text: StringResource) {
    Text(
        text = text.getValue(),
        color = ZashiColors.Text.textTertiary,
        style = ZashiTypography.textMd
    )
}

@Composable
private fun Warning(text: StringResource) {
    Text(
        text = text.getValue(),
        color = ZashiColors.Text.textPrimary,
        style = ZashiTypography.textMd,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun Bullet(
    text: StringResource,
    isWarning: Boolean = false
) {
    Row {
        Text(text = "•", color = ZashiColors.Text.textTertiary, style = ZashiTypography.textSm)
        Spacer(8.dp)
        Text(
            text = text.getValue(),
            color = if (isWarning) ZashiColors.Text.textPrimary else ZashiColors.Text.textTertiary,
            style = ZashiTypography.textSm,
        )
    }
}

@Composable
private fun Figure(
    label: StringResource,
    value: StringResource
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.getValue(),
            color = ZashiColors.Text.textTertiary,
            style = ZashiTypography.textSm,
            modifier = Modifier.weight(1f)
        )
        Spacer(8.dp)
        Text(
            text = value.getValue(),
            color = ZashiColors.Text.textPrimary,
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Button(
    text: StringResource,
    onClick: () -> Unit,
    style: ButtonStyle = ButtonStyle.PRIMARY
) {
    ZashiButton(
        modifier = Modifier.fillMaxWidth(),
        state = ButtonState(text = text, style = style, onClick = onClick)
    )
}

@PreviewScreens
@Composable
private fun ConsentPreview() =
    ZcashTheme {
        MoveOldBalanceView(
            state =
                MoveOldBalanceState.Consent(
                    amount = Zatoshi(139_000),
                    fee = Zatoshi(20_000),
                    arrives = Zatoshi(119_000),
                    isTorOff = true,
                    handshakes = PendingHandshakes(1, Zatoshi(17_000)),
                    onContinue = {},
                    onNotNow = {},
                    onBack = {},
                )
        )
    }
