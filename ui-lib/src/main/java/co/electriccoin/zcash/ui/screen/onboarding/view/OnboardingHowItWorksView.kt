package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily

@Composable
fun OnboardingHowItWorksView(
    onIHaveZec: () -> Unit,
    onINeedZec: () -> Unit,
    onWhatIsZec: () -> Unit,
) {
    var showWhatIsZecDialog by remember { mutableStateOf(false) }

    if (showWhatIsZecDialog) {
        AlertDialog(
            onDismissRequest = { showWhatIsZecDialog = false },
            title = { Text("What is ZEC?") },
            text = {
                Text(
                    "ZEC is the currency of the Zcash blockchain — the technology that powers ZCHAT.\n\n" +
                        "You can chat for FREE over the private network — no ZEC required. ZEC is only " +
                        "needed if you want to send a message fully on-chain (Shielded mode) for maximum " +
                        "privacy, which costs about \$0.004.\n\n" +
                        "To add ZEC, a friend can send you some, or you can buy a small amount on a crypto " +
                        "exchange or swap in-app."
                )
            },
            confirmButton = {
                TextButton(onClick = { showWhatIsZecDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    Scaffold(
        containerColor = NightwireColors.BgBase
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.onboarding_how_title),
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RajdhaniFontFamily,
                ),
                color = NightwireColors.AccentPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            InfoItem(
                icon = { Icon(Icons.Default.Lock, null, tint = NightwireColors.AccentPrimary, modifier = Modifier.size(28.dp)) },
                title = stringResource(R.string.onboarding_how_encrypted_title),
                description = stringResource(R.string.onboarding_how_encrypted_desc)
            )

            Spacer(modifier = Modifier.height(20.dp))

            InfoItem(
                icon = { Icon(Icons.Default.Shield, null, tint = NightwireColors.AccentPrimary, modifier = Modifier.size(28.dp)) },
                title = stringResource(R.string.onboarding_how_no_server_title),
                description = stringResource(R.string.onboarding_how_no_server_desc)
            )

            Spacer(modifier = Modifier.height(20.dp))

            InfoItem(
                icon = { Icon(Icons.Default.Bolt, null, tint = NightwireColors.AccentPrimary, modifier = Modifier.size(28.dp)) },
                title = stringResource(R.string.onboarding_how_latency_title),
                description = stringResource(R.string.onboarding_how_latency_desc)
            )

            Spacer(modifier = Modifier.weight(1f))

            ZashiButton(
                onClick = onIHaveZec,
                text = stringResource(R.string.onboarding_how_have_zec),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            ZashiButton(
                onClick = onINeedZec,
                text = stringResource(R.string.onboarding_how_need_zec),
                colors = ZashiButtonDefaults.tertiaryColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            ZashiButton(
                onClick = { showWhatIsZecDialog = true },
                text = stringResource(R.string.onboarding_how_what_is_zec),
                colors = ZashiButtonDefaults.tertiaryColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun InfoItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NightwireColors.BgElevated)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = NightwireColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = NightwireColors.TextSecondary
            )
        }
    }
}
