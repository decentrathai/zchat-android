package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily

@Composable
fun OnboardingIdentityView(
    address: String?,
    contactCode: String?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onContinue: () -> Unit,
) {
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
                text = stringResource(R.string.onboarding_identity_title),
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RajdhaniFontFamily,
                ),
                color = NightwireColors.AccentPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_identity_subtitle),
                fontSize = 15.sp,
                color = NightwireColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (address != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(NightwireColors.BgElevated)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Encode the INVITE CODE (address + NOSTR key) so a friend who scans it can chat for
                    // free from message #1. Falls back to the bare address until the code is ready.
                    ZashiQr(
                        state = QrState(qrData = contactCode ?: address),
                        qrSize = 220.dp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Show a short, non-alarming form of the address (a raw 100+ char unified address reads as
                // "something is wrong" to a newcomer). Copy/Share still hand out the FULL invite code.
                Text(
                    text = truncateIdentity(address),
                    fontSize = 13.sp,
                    fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                    color = NightwireColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row {
                    ZashiButton(
                        text = stringResource(R.string.onboarding_identity_copy),
                        onClick = onCopy,
                        colors = ZashiButtonDefaults.tertiaryColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    ZashiButton(
                        text = stringResource(R.string.onboarding_identity_share),
                        onClick = onShare,
                        colors = ZashiButtonDefaults.tertiaryColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // L6 — the mint can take a moment on a slow first sync; a bare label read as "hung." Show a
                // spinner so it's clearly working.
                Spacer(modifier = Modifier.height(160.dp))
                androidx.compose.material3.CircularProgressIndicator(
                    color = NightwireColors.AccentPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.onboarding_identity_loading),
                    color = NightwireColors.TextSecondary,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            ZashiButton(
                onClick = onContinue,
                text = stringResource(R.string.onboarding_identity_continue),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            )
        }
    }
}

/** A short, non-alarming rendering of a long identity string (e.g. u1abcdef…xyz012). Copy/Share still
 *  use the full code — this is display-only. */
private fun truncateIdentity(address: String): String =
    if (address.length > 24) "${address.take(14)}…${address.takeLast(8)}" else address
