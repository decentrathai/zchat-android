package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.view.chatColors

/**
 * SY-1: the compact catch-up bar on the Wallet tab after an upgrade from 2.12.4 or older (Bridge 2, COPY_DECK
 * CB1/CB2): the same texts as the chat list's bar, with [percent] from [PostUpgradeRules.catchUpBannerPercentOf].
 * Display only.
 */
@Suppress("MagicNumber")
@Composable
fun UpgradeCatchUpBar(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    val cc = chatColors()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cc.secondary.copy(alpha = 0.15f))
                .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.upgrade_catch_up_title, percent),
            color = cc.secondary,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { percent.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = cc.secondary,
            trackColor = cc.bgInput,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.upgrade_catch_up_subtitle),
            color = cc.textSecondary,
            fontSize = 12.sp,
        )
    }
}
