package co.electriccoin.zcash.ui.screen.invite.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendView(
    address: String?,
    inviteText: String,
    onShareInvite: () -> Unit,
    onShareQr: () -> Unit,
    onCopyAddress: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = NightwireColors.BgBase,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.invite_title),
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = RajdhaniFontFamily,
                        ),
                        color = NightwireColors.AccentPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NightwireColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightwireColors.BgSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            if (address != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(NightwireColors.BgElevated)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ZashiQr(
                        state = QrState(qrData = address),
                        qrSize = 200.dp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = address,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = NightwireColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Invite text preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NightwireColors.BgElevated)
                        .padding(12.dp)
                ) {
                    Text(
                        text = inviteText,
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                ZashiButton(
                    onClick = onShareInvite,
                    text = stringResource(R.string.invite_share_invite),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                ZashiButton(
                    onClick = onShareQr,
                    text = stringResource(R.string.invite_share_qr),
                    colors = ZashiButtonDefaults.tertiaryColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                ZashiButton(
                    onClick = onCopyAddress,
                    text = stringResource(R.string.invite_copy_address),
                    colors = ZashiButtonDefaults.tertiaryColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.invite_zec_note),
                    fontSize = 12.sp,
                    color = NightwireColors.TextSecondary.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(modifier = Modifier.height(200.dp))
                Text(
                    text = stringResource(R.string.invite_loading),
                    color = NightwireColors.TextSecondary,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
