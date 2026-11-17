package co.electriccoin.zcash.ui.design.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.scaffoldPadding

@Suppress("LongMethod")
@Composable
fun ZashiBaseSettingsOptIn(
    header: String,
    @DrawableRes image: Int,
    info: String?,
    onDismiss: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    // ZC-1703 (QA2 Q2-93): a settings screen reached from More closes with the back arrow of the other settings
    // screens; the opt-in screens keep the X.
    useBackArrow: Boolean = false,
) {
    Scaffold(
        // ZC-1718 (QA2 Q2-93): the back arrow is the top-bar arrow of the other settings screens, placed by the top
        // app bar, not a button inside the padded content column.
        topBar = {
            if (useBackArrow) {
                // ZC-1720: the bar is drawn over the page colour, so its container is transparent (no band on any
                // theme); the arrow keeps the theme's own tint.
                ZashiSmallTopAppBar(
                    colors = ZcashTheme.colors.topAppBarColors.copyColors(containerColor = Color.Transparent),
                    navigationAction = { ZashiTopAppBarBackNavigation(onBack = onDismiss) },
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(paddingValues)
        ) {
            if (!useBackArrow) {
                Button(
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp),
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = ZashiColors.Btns.Tertiary.btnTertiaryBg
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_settings_opt_int_close),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(ZashiColors.Btns.Tertiary.btnTertiaryFg)
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
            ) {
                Image(painter = painterResource(image), contentDescription = null)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = header,
                    color = ZashiColors.Text.textPrimary,
                    style = ZashiTypography.header6,
                    fontWeight = FontWeight.SemiBold
                )
                content()

                Spacer(modifier = Modifier.weight(1f))

                if (info != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    ZashiInfoText(info)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            footer()
        }
    }
}
