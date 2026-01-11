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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.MaterialTheme
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
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
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
                        text = "Receive ZEC",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (state is ZchatReceiveState.Success) {
                        IconButton(onClick = state.onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
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
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Subtitle description
        Text(
            text = if (state.showingTransparent) {
                "For receiving from exchanges"
            } else {
                "Private messaging address"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Address text (expandable)
        Text(
            text = currentAddress,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
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
        ZashiColors.Utility.Purple.utilityPurple100
    } else {
        ZashiColors.Utility.WarningYellow.utilityOrange100
    }

    val textColor = if (isShielded) {
        ZashiColors.Utility.Purple.utilityPurple700
    } else {
        ZashiColors.Utility.WarningYellow.utilityOrange700
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
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TransparentAddressWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = ZashiColors.Utility.WarningYellow.utilityOrange50
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
                    tint = ZashiColors.Utility.WarningYellow.utilityOrange600,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Important",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ZashiColors.Utility.WarningYellow.utilityOrange700
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Transparent addresses are only for receiving ZEC from exchanges or services that don't support shielded addresses.",
                style = MaterialTheme.typography.bodySmall,
                color = ZashiColors.Utility.WarningYellow.utilityOrange700,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You cannot send ZCHAT messages from transparent funds. Received ZEC will need to be shielded before you can use it for private messaging.",
                style = MaterialTheme.typography.bodySmall,
                color = ZashiColors.Utility.WarningYellow.utilityOrange700,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ShieldedAddressInfo() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = ZashiColors.Utility.Purple.utilityPurple50
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
                    tint = ZashiColors.Utility.Purple.utilityPurple600,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Recommended",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ZashiColors.Utility.Purple.utilityPurple700
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This is your unified shielded address for ZCHAT. Share this address to receive private messages and ZEC transactions.",
                style = MaterialTheme.typography.bodySmall,
                color = ZashiColors.Utility.Purple.utilityPurple700,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All funds received at this address can be used for private ZCHAT messaging.",
                style = MaterialTheme.typography.bodySmall,
                color = ZashiColors.Utility.Purple.utilityPurple700,
                lineHeight = 18.sp
            )
        }
    }
}
