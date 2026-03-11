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
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Icon
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
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily

@Composable
fun OnboardingGetZecView(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
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
                text = stringResource(R.string.onboarding_getzec_title),
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
                text = stringResource(R.string.onboarding_getzec_subtitle),
                fontSize = 14.sp,
                color = NightwireColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            GetZecOption(
                icon = { Icon(Icons.Default.Group, null, tint = NightwireColors.AccentPrimary, modifier = Modifier.size(28.dp)) },
                title = stringResource(R.string.onboarding_getzec_friend_title),
                description = stringResource(R.string.onboarding_getzec_friend_desc)
            )

            Spacer(modifier = Modifier.height(16.dp))

            GetZecOption(
                icon = { Icon(Icons.Default.SwapHoriz, null, tint = NightwireColors.AccentPrimary, modifier = Modifier.size(28.dp)) },
                title = stringResource(R.string.onboarding_getzec_exchange_title),
                description = stringResource(R.string.onboarding_getzec_exchange_desc)
            )

            Spacer(modifier = Modifier.height(16.dp))

            GetZecOption(
                icon = { Icon(Icons.Default.Wallet, null, tint = NightwireColors.AccentPrimary, modifier = Modifier.size(28.dp)) },
                title = stringResource(R.string.onboarding_getzec_swap_title),
                description = stringResource(R.string.onboarding_getzec_swap_desc)
            )

            Spacer(modifier = Modifier.weight(1f))

            ZashiButton(
                onClick = onContinue,
                text = stringResource(R.string.onboarding_getzec_continue),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            ZashiButton(
                onClick = onSkip,
                text = stringResource(R.string.onboarding_getzec_skip),
                colors = ZashiButtonDefaults.tertiaryColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GetZecOption(
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
