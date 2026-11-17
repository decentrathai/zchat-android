package co.electriccoin.zcash.ui.screen.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.view.chatColors

/** Passive notice shared by the Chats and Wallet tabs. The update check runs only on the button's tap. */
@Composable
fun UpdateRequiredBar(modifier: Modifier = Modifier) {
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
            text = stringResource(R.string.update_required_title),
            color = cc.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.update_required_banner_body), color = cc.textSecondary)
        if (BuildConfig.IS_SELF_UPDATE_ENABLED) {
            TextButton(onClick = { UpdateCheckTrigger.trigger() }) {
                Text(text = stringResource(R.string.update_required_button), color = cc.secondary)
            }
        }
    }
}
