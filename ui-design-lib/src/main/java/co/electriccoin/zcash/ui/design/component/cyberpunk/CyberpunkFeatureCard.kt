package co.electriccoin.zcash.ui.design.component.cyberpunk

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A cyberpunk-styled feature card displaying a custom icon with text
 * Used for Welcome screen feature highlights
 *
 * @param iconRes Drawable resource ID for the cyberpunk icon
 * @param text Description text for the feature
 * @param modifier Modifier for the card
 * @param iconSize Size of the icon (default 100dp for readability with text)
 */
@Composable
fun CyberpunkFeatureCard(
    @DrawableRes iconRes: Int,
    text: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 100.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = text,
            modifier = Modifier.size(iconSize),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
