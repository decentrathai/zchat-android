package co.electriccoin.zcash.ui.screen.home.tex

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.home.HomeMessageState
import co.electriccoin.zcash.ui.screen.home.HomeMessageWrapper

/**
 * F3 (fix-all 2.13.0): the Home bar about a TEX send that is being checked or moved back, needs support, or was moved
 * back and not yet read. A tap opens the payment's detail, which says it in full. It asks nothing.
 */
@Suppress("ModifierNaming")
@Composable
fun TexRecoveryMessage(
    state: TexRecoveryMessageState,
    contentPadding: PaddingValues,
    innerModifier: Modifier = Modifier,
) {
    HomeMessageWrapper(
        innerModifier = innerModifier,
        contentPadding = contentPadding,
        onClick = state.onClick,
        start = {
            Image(
                painter = painterResource(R.drawable.ic_message_transparent_balance),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LocalContentColor.current)
            )
        },
        title = { Text(text = state.title.getValue()) },
        subtitle = { Text(text = state.subtitle.getValue()) },
        end = null
    )
}

@Immutable
data class TexRecoveryMessageState(
    val title: StringResource,
    val subtitle: StringResource,
    val onClick: () -> Unit,
) : HomeMessageState

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            TexRecoveryMessage(
                state =
                    TexRecoveryMessageState(
                        title = stringRes(R.string.tex_status_shielded_title),
                        subtitle = stringRes(R.string.tex_home_shielded_subtitle, "0.0099", "0.0001"),
                        onClick = {},
                    ),
                contentPadding = PaddingValues()
            )
        }
    }
