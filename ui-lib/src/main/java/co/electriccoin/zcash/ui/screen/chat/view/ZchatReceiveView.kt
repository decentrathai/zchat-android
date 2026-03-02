package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily
import co.electriccoin.zcash.ui.screen.chat.model.ZchatReceiveState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZchatReceiveView(
    state: ZchatReceiveState,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "My Address",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = NightwireColors.TextPrimary
                    )
                },
                navigationIcon = {
                    if (state is ZchatReceiveState.Success) {
                        IconButton(onClick = state.onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = NightwireColors.TextPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightwireColors.BgSurface
                )
            )
        },
        containerColor = NightwireColors.BgBase,
        modifier = modifier
    ) { paddingValues ->
        when (state) {
            is ZchatReceiveState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = NightwireColors.AccentPrimary)
                }
            }
            is ZchatReceiveState.Success -> {
                ZchatReceiveContent(
                    state = state,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZchatReceiveContent(
    state: ZchatReceiveState.Success,
    modifier: Modifier = Modifier
) {
    var expandedAddress by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Address type badge
        AddressTypeBadge(isShielded = !state.showingTransparent)

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = if (state.showingTransparent) "Transparent Address" else "Shielded Address",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RajdhaniFontFamily,
            color = NightwireColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Subtitle description
        Text(
            text = if (state.showingTransparent) {
                "For receiving from exchanges"
            } else {
                "Private messaging address"
            },
            fontSize = 15.sp,
            color = NightwireColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // QR Code
        val currentAddress = if (state.showingTransparent) {
            state.transparentAddress
        } else {
            state.shieldedAddress
        }

        ZashiQr(
            state = QrState(
                qrData = currentAddress,
                centerImage = if (state.showingTransparent) {
                    R.drawable.ic_zec_qr_transparent
                } else {
                    R.drawable.ic_zec_qr_shielded
                }
            ),
            modifier = Modifier
                .size(240.dp)
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Address text (expandable)
        Text(
            text = currentAddress,
            fontSize = 13.sp,
            fontFamily = JetBrainsMonoFontFamily,
            color = NightwireColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = if (expandedAddress) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .animateContentSize()
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { expandedAddress = !expandedAddress },
                    onLongClick = state.onCopyAddress
                )
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Copy Address button
        Button(
            onClick = state.onCopyAddress,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(NightwireColors.RadiusButton),
                    ambientColor = NightwireColors.AccentPrimaryGlow,
                    spotColor = NightwireColors.AccentPrimaryGlow
                ),
            shape = RoundedCornerShape(NightwireColors.RadiusButton),
            colors = ButtonDefaults.buttonColors(
                containerColor = NightwireColors.AccentPrimary,
                contentColor = NightwireColors.TextOnAccent
            )
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Copy Address")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Share Address button
        val shareContext = androidx.compose.ui.platform.LocalContext.current
        OutlinedButton(
            onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, currentAddress)
                }
                shareContext.startActivity(android.content.Intent.createChooser(intent, "Share Address"))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(NightwireColors.RadiusButton),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = NightwireColors.AccentPrimary,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, NightwireColors.AccentPrimary)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Share")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Toggle button for showing transparent/shielded
        OutlinedButton(
            onClick = if (state.showingTransparent) state.onShowShielded else state.onShowTransparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.showingTransparent) {
                    "Show Shielded Address"
                } else {
                    "Show Transparent Address"
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Info card for transparent address
        AnimatedVisibility(
            visible = state.showingTransparent,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            TransparentAddressWarning()
        }

        // Info card for shielded address
        AnimatedVisibility(
            visible = !state.showingTransparent,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            ShieldedAddressInfo()
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AddressTypeBadge(isShielded: Boolean) {
    val backgroundColor = if (isShielded) {
        NightwireColors.AccentPrimaryBg
    } else {
        NightwireColors.ColorWarning.copy(alpha = 0.15f)
    }

    val textColor = if (isShielded) {
        NightwireColors.AccentPrimary
    } else {
        NightwireColors.ColorWarning
    }

    val iconRes = if (isShielded) {
        R.drawable.ic_solid_check
    } else {
        R.drawable.ic_alert_circle
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = if (isShielded) "Shielded" else "Transparent",
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TransparentAddressWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NightwireColors.RadiusModal),
        colors = CardDefaults.cardColors(
            containerColor = NightwireColors.ColorWarning.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NightwireColors.ColorWarning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Important",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NightwireColors.ColorWarning
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Transparent addresses are only for receiving ZEC from exchanges or services that don't support shielded addresses.",
                fontSize = 13.sp,
                color = NightwireColors.ColorWarning.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You cannot send ZCHAT messages from transparent funds. Received ZEC will need to be shielded before you can use it for private messaging.",
                fontSize = 13.sp,
                color = NightwireColors.ColorWarning.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ShieldedAddressInfo() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NightwireColors.RadiusModal),
        colors = CardDefaults.cardColors(
            containerColor = NightwireColors.AccentPrimaryBg
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NightwireColors.AccentPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Recommended",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NightwireColors.AccentPrimary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This is your unified shielded address for ZCHAT. Share this address to receive private messages and ZEC transactions.",
                fontSize = 13.sp,
                color = NightwireColors.AccentPrimaryDim,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All funds received at this address can be used for private ZCHAT messaging.",
                fontSize = 13.sp,
                color = NightwireColors.AccentPrimaryDim,
                lineHeight = 18.sp
            )
        }
    }
}
