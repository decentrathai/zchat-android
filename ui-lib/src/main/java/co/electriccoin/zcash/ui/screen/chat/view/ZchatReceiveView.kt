package co.electriccoin.zcash.ui.screen.chat.view

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.model.MyCodeTab
import co.electriccoin.zcash.ui.screen.chat.model.MyCodeTabs
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
                        text = stringResource(R.string.my_code_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = chatColors().textPrimary
                    )
                },
                navigationIcon = {
                    val onBack: (() -> Unit)? = when (state) {
                        is ZchatReceiveState.Success -> state.onBack
                        is ZchatReceiveState.Error -> state.onBack
                        else -> null
                    }
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back_navigation_content_description),
                                tint = chatColors().textPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = chatColors().surface
                )
            )
        },
        containerColor = chatColors().background,
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
                    CircularProgressIndicator(color = chatColors().primary)
                }
            }
            is ZchatReceiveState.Success -> {
                ZchatReceiveContent(
                    state = state,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is ZchatReceiveState.Error -> {
                ZchatReceiveError(
                    state = state,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@Composable
private fun ZchatReceiveError(
    state: ZchatReceiveState.Error,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = stringResource(R.string.my_code_error_icon),
            tint = chatColors().warning,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(state.message),
            fontSize = 15.sp,
            color = chatColors().textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = state.onRetry,
            shape = RoundedCornerShape(NightwireColors.RadiusButton),
            colors = ButtonDefaults.buttonColors(
                containerColor = chatColors().primary,
                contentColor = chatColors().textOnAccent
            )
        ) {
            Text(stringResource(R.string.my_code_retry))
        }
    }
}

@Composable
private fun ZchatReceiveContent(
    state: ZchatReceiveState.Success,
    modifier: Modifier = Modifier
) {
    // ZC-1463: system Back from the transparent address returns to the shielded address (the state's onBack).
    BackHandler(enabled = state.showingTransparent, onBack = state.onBack)

    // ZC-1461: two tabs, one QR each, so nothing hides below the fold. The free chat code comes first when our relay
    // key is derived; without it only the shielded address shows, with no tab row.
    val tabs = MyCodeTabs.available(state.supportsOpen)
    var selected by rememberSaveable(state.supportsOpen) { mutableStateOf(MyCodeTabs.defaultTab(state.supportsOpen)) }
    val shown = if (selected in tabs) selected else tabs.first()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (tabs.size > 1) {
            TabRow(
                selectedTabIndex = tabs.indexOf(shown),
                containerColor = chatColors().surface,
                contentColor = chatColors().primary
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == shown,
                        onClick = {
                            // Leaving the address tab leaves the transparent view too, so Back is plain again.
                            if (tab == MyCodeTab.CHAT && state.showingTransparent) state.onShowShielded()
                            selected = tab
                        },
                        text = {
                            Text(
                                text = stringResource(tab.label()),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                color = if (tab == shown) chatColors().primary else chatColors().textSecondary
                            )
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Task A — the owner's OWN photo (self avatar), so the user sees their own identity here.
            ZchatAvatar(ref = ZchatAvatarRef.Self, displayName = null, size = 72.dp)

            Spacer(modifier = Modifier.height(16.dp))

            when (shown) {
                MyCodeTab.CHAT -> FreeChatCodeTab(state)
                MyCodeTab.ADDRESS -> AddressTab(state)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun MyCodeTab.label(): Int =
    when (this) {
        MyCodeTab.CHAT -> R.string.my_code_tab_chat
        MyCodeTab.ADDRESS -> R.string.my_code_tab_address
    }

/**
 * ZC-1461: the ZCHAT chat invite. Encodes the zchat: contact code (shielded address PLUS our NOSTR messaging key),
 * NOT a payment address. A peer who scans THIS gets our NOSTR key persisted and can start a FREE NOSTR ("Open") chat
 * from message #1, no on-chain handshake.
 * The address tab's QR stays the bare, universally-payable Zcash address (#256).
 */
@Composable
private fun FreeChatCodeTab(state: ZchatReceiveState.Success) {
    Text(
        text = stringResource(R.string.my_code_tab_chat),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = RajdhaniFontFamily,
        color = chatColors().textPrimary
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.my_code_chat_helper),
        fontSize = 15.sp,
        color = chatColors().textSecondary,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(24.dp))
    ZashiQr(
        state = QrState(qrData = state.contactCodeText),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = state.onCopyContactCode,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NightwireColors.RadiusButton),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = chatColors().primary,
                contentColor = chatColors().textOnAccent
            )
    ) {
        Icon(
            imageVector = Icons.Default.PersonAdd,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(stringResource(R.string.my_code_copy_invite))
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.my_code_invite_disclaimer),
        fontSize = 13.sp,
        color = chatColors().textSecondary,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddressTab(state: ZchatReceiveState.Success) {
    var expandedAddress by rememberSaveable { mutableStateOf(false) }
    val transparent = state.showingTransparent
    val headingRes = if (transparent) R.string.my_code_heading_transparent else R.string.my_code_heading_shielded
    val subtitleRes = if (transparent) R.string.my_code_subtitle_transparent else R.string.my_code_subtitle_shielded
    val toggleRes = if (transparent) R.string.my_code_show_shielded else R.string.my_code_show_transparent

    // Address type badge
    AddressTypeBadge(isShielded = !transparent)

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(headingRes),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = RajdhaniFontFamily,
        color = chatColors().textPrimary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(subtitleRes),
        fontSize = 15.sp,
        color = chatColors().textSecondary,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    val currentAddress = if (transparent) state.transparentAddress else state.shieldedAddress
    val qrCenter = if (transparent) R.drawable.ic_zec_qr_transparent else R.drawable.ic_zec_qr_shielded

    // QR ALWAYS carries the PLAIN Zcash address (shielded or transparent) so ANY wallet — not just ZCHAT — can scan it
    // to send you ZEC. The ZCHAT free-Open chat invite (address + NOSTR key) is the other tab. No fixed .size():
    // ZashiQr's default width (screenWidth * 0.74) keeps the dense unified-address QR scannable.
    ZashiQr(
        state = QrState(qrData = currentAddress, centerImage = qrCenter),
        modifier = Modifier.padding(horizontal = 16.dp)
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.my_code_qr_hint),
        fontSize = 12.sp,
        color = chatColors().textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Address text (expandable; long press copies)
    val addressInteraction = remember { MutableInteractionSource() }
    Text(
        text = currentAddress,
        fontSize = 14.sp,
        fontFamily = JetBrainsMonoFontFamily,
        color = chatColors().textSecondary,
        textAlign = TextAlign.Center,
        maxLines = if (expandedAddress) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .animateContentSize()
                .combinedClickable(
                    indication = null,
                    interactionSource = addressInteraction,
                    onClick = { expandedAddress = !expandedAddress },
                    onLongClick = state.onCopyAddress
                ).padding(horizontal = 16.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Copy button — ALWAYS copies the plain Zcash address (shielded or transparent) so it pastes into any wallet.
    val buttonShape = RoundedCornerShape(NightwireColors.RadiusButton)
    Button(
        onClick = state.onCopyAddress,
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = buttonShape,
                    ambientColor = chatColors().accentPrimaryGlow,
                    spotColor = chatColors().accentPrimaryGlow
                ),
        shape = buttonShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = chatColors().primary,
                contentColor = chatColors().textOnAccent
            )
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(stringResource(R.string.my_code_copy_address))
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Share Address button: shares the plain, universally-payable address.
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    val shareTitle = stringResource(R.string.my_code_share)
    OutlinedButton(
        onClick = { shareAddress(shareContext, currentAddress, shareTitle) },
        modifier = Modifier.fillMaxWidth(),
        shape = buttonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = chatColors().primary),
        border = androidx.compose.foundation.BorderStroke(1.dp, chatColors().primary)
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(shareTitle)
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Toggle button for showing transparent/shielded
    OutlinedButton(
        onClick = if (transparent) state.onShowShielded else state.onShowTransparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(toggleRes))
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Info card for transparent address
    AnimatedVisibility(
        visible = transparent,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        TransparentAddressWarning()
    }

    // Info card for shielded address
    AnimatedVisibility(
        visible = !transparent,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        ShieldedAddressInfo()
    }
}

private fun shareAddress(
    context: android.content.Context,
    address: String,
    title: String,
) {
    val intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, address)
        }
    context.startActivity(android.content.Intent.createChooser(intent, title))
}

@Composable
private fun AddressTypeBadge(isShielded: Boolean) {
    val backgroundColor = if (isShielded) {
        chatColors().bgInput
    } else {
        chatColors().warning.copy(alpha = 0.15f)
    }

    val textColor = if (isShielded) {
        chatColors().primary
    } else {
        chatColors().warning
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
            text =
                stringResource(
                    if (isShielded) R.string.my_code_badge_shielded else R.string.my_code_badge_transparent
                ),
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
            containerColor = chatColors().warning.copy(alpha = 0.1f)
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
                    contentDescription = stringResource(R.string.my_code_info_icon),
                    tint = chatColors().warning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.my_code_important),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = chatColors().warning
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.my_code_transparent_warning_use),
                fontSize = 13.sp,
                color = chatColors().warning.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.my_code_transparent_warning_shield),
                fontSize = 13.sp,
                color = chatColors().warning.copy(alpha = 0.85f),
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
            containerColor = chatColors().bgInput
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
                    contentDescription = stringResource(R.string.my_code_info_icon),
                    tint = chatColors().primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.my_code_recommended),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = chatColors().primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.my_code_shielded_info_share),
                fontSize = 13.sp,
                color = chatColors().primary,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.my_code_shielded_info_use),
                fontSize = 13.sp,
                color = chatColors().primary,
                lineHeight = 18.sp
            )
        }
    }
}
