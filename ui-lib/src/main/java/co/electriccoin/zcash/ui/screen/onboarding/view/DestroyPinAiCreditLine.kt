package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiDestroyPinLine
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * AI-KEY-T (W-52; spec 5.4): what fills the place on step 1 of the Destroy PIN set-up. The stored state when the screen
 * opens, no call; each action calls on its tap only. The set-up's own files name no AI code: this one does.
 */
@Composable
internal fun DestroyPinCreditLine() {
    val access = koinInject<AiAccountAccess>()
    val scope = rememberCoroutineScope()
    var line by remember { mutableStateOf<AiDestroyPinLine?>(null) }
    var busy by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { line = access.destroyPinLine() }
    DestroyPinAiCreditLine(
        line = line,
        linkFailed = linkFailed,
        busy = busy,
        onLinkNow = {
            busy = true
            scope.launch {
                try {
                    linkFailed = !access.linkNow()
                    line = access.destroyPinLine()
                } finally {
                    busy = false
                }
            }
        },
        onCheckNow = {
            busy = true
            scope.launch {
                try {
                    access.checkNow()
                    line = access.destroyPinLine()
                } finally {
                    busy = false
                }
            }
        }
    )
}

/** The line itself (spec 7.3), with its action. Draws nothing while the store was not read yet and for the row with no line. */
@Composable
private fun DestroyPinAiCreditLine(
    line: AiDestroyPinLine?,
    linkFailed: Boolean,
    busy: Boolean,
    onLinkNow: () -> Unit,
    onCheckNow: () -> Unit
) {
    val text =
        when (line) {
            AiDestroyPinLine.UNREADABLE -> R.string.destroy_pin_ai_unreadable
            AiDestroyPinLine.CANNOT -> R.string.destroy_pin_ai_cannot
            AiDestroyPinLine.NOT_YET -> R.string.destroy_pin_ai_not_yet
            AiDestroyPinLine.SAFE -> R.string.destroy_pin_ai_safe
            AiDestroyPinLine.NONE, null -> return
        }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(text),
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textSecondary
    )
    if (line == AiDestroyPinLine.NOT_YET) {
        if (linkFailed) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.destroy_pin_ai_link_failed),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textSecondary
            )
        }
        TextButton(onClick = onLinkNow, enabled = !busy) {
            Text(text = stringResource(R.string.destroy_pin_ai_link_now))
        }
    }
    if (line == AiDestroyPinLine.SAFE) {
        TextButton(onClick = onCheckNow, enabled = !busy) {
            Text(text = stringResource(R.string.destroy_pin_ai_check_now))
        }
    }
}
