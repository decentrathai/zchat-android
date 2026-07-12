package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.stringRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsufficientFundsView(
    state: InsufficientFundsState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        sheetState = sheetState,
        state = state,
    ) {
        Content(modifier = Modifier.weight(1f, false), state = it)
    }
}

@Composable
private fun Content(state: InsufficientFundsState, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_swap_quote_error),
            contentDescription = null,
        )
        Spacer(12.dp)
        Text(
            text = stringResource(state.titleRes),
            color = ZashiColors.Text.textPrimary,
            style = ZashiTypography.header6,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(8.dp)
        Text(
            text = stringResource(state.descriptionRes),
            color = ZashiColors.Text.textTertiary,
            style = ZashiTypography.textMd
        )
        Spacer(28.dp)
        // OK stays always-enabled. Rapid-tap duplicate nav pops are already debounced centrally by
        // NavigationRouter.navigateWithBackoff (0.5s); a local one-shot latch would instead trap the
        // user in the sheet if a back() is ever delayed/dropped.
        ZashiButton(
            modifier = Modifier.fillMaxWidth(),
            state =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = state.onBack
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        InsufficientFundsView(
            state =
                InsufficientFundsState(
                    titleRes = R.string.insufficient_funds_payment_title,
                    descriptionRes = R.string.insufficient_funds_payment_description,
                    onBack = {}
                )
        )
    }
