package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZecString
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.WalletSyncStatus
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.design.theme.modifiers.neonGlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ZCHAT Brand Colors - now theme-aware via LocalChatColors
// These are fallback defaults; composables should use chatColors() which is theme-aware
private val ZchatCyan = Color(0xFF00D9FF)
private val ZchatGreen = Color(0xFF00E676)
private val ZchatNavy = Color(0xFF0D1B2A)
private val ZchatNavyLight = Color(0xFF1B2838)
private val ZchatTeal = Color(0xFF00838F)

// Note: chatColors() function is now defined in ChatThemeColors.kt and shared across all chat views

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTextApi::class)
@Composable
fun ChatListView(
    state: ChatListState,
    userStatus: UserStatus,
    onConversationClick: (String) -> Unit,
    onGroupClick: (String) -> Unit = {},
    onNewChatClick: () -> Unit,
    onNewGroupClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onCopyAddressClick: () -> Unit,
    onQrCodeClick: () -> Unit,
    onContactsClick: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onDeleteGroup: (String) -> Unit = {},
    onAddContact: (String) -> Unit,
    onEditContact: (String) -> Unit,
    onSetUserStatus: (String, Boolean) -> Unit,
    getContact: (String) -> Contact?,
    // Destroy All functionality
    onDestroyAll: () -> Unit = {},
    hasDestroyPin: Boolean = false,
    onSetupDestroyPin: (String) -> Unit = {},
    onVerifyDestroyPin: (String) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    // Status edit dialog state
    var showStatusDialog by remember { mutableStateOf(false) }
    var statusText by remember(userStatus) { mutableStateOf(userStatus.text) }

    // Expandable FAB state
    var isFabExpanded by remember { mutableStateOf(false) }
    val fabRotation by animateFloatAsState(
        targetValue = if (isFabExpanded) 45f else 0f,
        label = "FAB rotation"
    )

    // Group Chat Coming Soon dialog
    var showGroupComingSoonDialog by remember { mutableStateOf(false) }

    // Destroy dialog states
    var showDestroyDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showPinVerifyDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinConfirmInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val userAddress = when (state) {
        is ChatListState.Success -> state.currentUserAddress
        else -> null
    }

    val balance = when (state) {
        is ChatListState.Success -> state.balance
        else -> Zatoshi(0)
    }

    val lastSyncTime = when (state) {
        is ChatListState.Success -> state.lastSyncTime
        else -> null
    }

    val isRefreshing = when (state) {
        is ChatListState.Success -> state.isRefreshing
        else -> false
    }

    val secondsUntilNextSync = when (state) {
        is ChatListState.Success -> state.secondsUntilNextSync
        else -> 0
    }

    val blockHeight = when (state) {
        is ChatListState.Success -> state.blockHeight
        else -> null
    }

    val zecPriceUsd = when (state) {
        is ChatListState.Success -> state.zecPriceUsd
        else -> null
    }

    val walletSyncStatus = when (state) {
        is ChatListState.Success -> state.syncStatus
        else -> WalletSyncStatus()
    }

    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val colors = chatColors()
                            // Gradient ZCHAT title using theme colors + Orbitron font
                            Text(
                                text = "ZCHAT",
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = co.electriccoin.zcash.ui.design.theme.typography.OrbitronFontFamily,
                                    brush = colors.titleGradient
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${balance.toZecString()} ZEC",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary
                            )
                            // Show USD equivalent if price available
                            zecPriceUsd?.let { price ->
                                val balanceZec = balance.value / 100_000_000.0
                                val usdValue = balanceZec * price
                                Text(
                                    text = " ($${String.format("%.2f", usdValue)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.secondary
                                )
                            }
                        }
                        userAddress?.let { address ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${address.take(8)}...${address.takeLast(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy Address",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onCopyAddressClick() },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // User status - tap to edit
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showStatusDialog = true }
                        ) {
                            Text(
                                text = if (userStatus.text.isNotBlank()) "📍 ${userStatus.text}" else "Set status...",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (userStatus.text.isNotBlank())
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onContactsClick) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Contacts",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onQrCodeClick) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "Show QR Code",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            val colors = chatColors()
            val infiniteTransition = rememberInfiniteTransition(label = "fab-pulse")
            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glow-pulse"
            )
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 56.dp)  // Position above SyncStatusBar
            ) {
                // Mini FABs - visible when expanded
                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // New Group option
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(colors.surface)
                                .clickable {
                                    isFabExpanded = false
                                    onNewGroupClick()
                                }
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = "New Group",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(colors.secondary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = "New Group",
                                    tint = colors.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // New Chat option
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(colors.surface)
                                .clickable {
                                    isFabExpanded = false
                                    onNewChatClick()
                                }
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = "New Chat",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Message,
                                    contentDescription = "New Chat",
                                    tint = colors.fabForeground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Main FAB
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = colors.fabBackground,
                    contentColor = colors.fabForeground,
                    modifier = Modifier.neonGlow(
                        color = Color(0xFF00FFFF),
                        radius = 20.dp,
                        alpha = glowAlpha,
                        cornerRadius = 16.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isFabExpanded) "Close menu" else "New Chat",
                        tint = colors.fabForeground,
                        modifier = Modifier.rotate(fabRotation)
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Wallet Sync Progress Banner - shows during restore/sync
            if (walletSyncStatus.isRestoring || (walletSyncStatus.isSyncing && walletSyncStatus.progress < 98f)) {
                WalletSyncProgressBanner(
                    syncStatus = walletSyncStatus
                )
            }

            // Main content with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                when (state) {
                    is ChatListState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    is ChatListState.Success -> {
                        if (state.conversations.isEmpty() && state.groups.isEmpty()) {
                            EmptyConversationsView(
                                modifier = Modifier,
                                onNewChatClick = onNewChatClick
                            )
                        } else {
                            ConversationsAndGroupsList(
                                conversations = state.conversations,
                                groups = state.groups,
                                onConversationClick = onConversationClick,
                                onGroupClick = onGroupClick,
                                onDeleteChat = onDeleteChat,
                                onDeleteGroup = onDeleteGroup,
                                onAddContact = onAddContact,
                                onEditContact = onEditContact,
                                getContact = getContact,
                                modifier = Modifier
                            )
                        }
                    }
                    is ChatListState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Sync Status Bar at the bottom
            SyncStatusBar(
                lastSyncTime = lastSyncTime,
                secondsUntilNextSync = secondsUntilNextSync,
                isRefreshing = isRefreshing,
                onRefreshClick = onRefresh,
                blockHeight = blockHeight,
                zecPriceUsd = zecPriceUsd,
                onDestroyClick = {
                    if (hasDestroyPin) {
                        showPinVerifyDialog = true
                    } else {
                        showPinSetupDialog = true
                    }
                }
            )
        }
    }

    // Status Edit Dialog
    if (showStatusDialog) {
        StatusEditDialog(
            currentStatus = statusText,
            onStatusChange = { statusText = it },
            onDismiss = { showStatusDialog = false },
            onConfirm = { broadcast ->
                onSetUserStatus(statusText, broadcast)
                showStatusDialog = false
            },
            onClear = {
                statusText = ""
                onSetUserStatus("", false)
                showStatusDialog = false
            }
        )
    }

    // PIN Setup Dialog (first time using Destroy All)
    if (showPinSetupDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinSetupDialog = false
                pinInput = ""
                pinConfirmInput = ""
                pinError = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF1744),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set Destroy PIN")
                }
            },
            text = {
                Column {
                    Text(
                        text = "Create a PIN to protect the Destroy All feature. This PIN will be required to wipe all app data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text("Enter PIN (4-8 digits)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinConfirmInput,
                        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pinConfirmInput = it },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    pinError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            pinInput.length < 4 -> pinError = "PIN must be at least 4 digits"
                            pinInput != pinConfirmInput -> pinError = "PINs do not match"
                            else -> {
                                onSetupDestroyPin(pinInput)
                                showPinSetupDialog = false
                                showDestroyDialog = true
                                pinInput = ""
                                pinConfirmInput = ""
                                pinError = null
                            }
                        }
                    },
                    enabled = pinInput.length >= 4 && pinConfirmInput.isNotEmpty()
                ) {
                    Text("Set PIN & Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinSetupDialog = false
                    pinInput = ""
                    pinConfirmInput = ""
                    pinError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // PIN Verify Dialog (when PIN already set)
    if (showPinVerifyDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinVerifyDialog = false
                pinInput = ""
                pinError = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = Color(0xFFFF1744),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Destroy All Data")
                }
            },
            text = {
                Column {
                    Text(
                        text = "⚠️ WARNING: This will permanently delete ALL app data including messages, contacts, and wallet information.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF1744)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Enter your PIN to confirm destruction:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text("Enter PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    pinError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onVerifyDestroyPin(pinInput)) {
                            showPinVerifyDialog = false
                            showDestroyDialog = true
                            pinInput = ""
                            pinError = null
                        } else {
                            pinError = "Incorrect PIN"
                        }
                    },
                    enabled = pinInput.length >= 4
                ) {
                    Text("Verify", color = Color(0xFFFF1744))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinVerifyDialog = false
                    pinInput = ""
                    pinError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Final Destroy Confirmation Dialog
    if (showDestroyDialog) {
        AlertDialog(
            onDismissRequest = { showDestroyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF1744)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("DESTROY ALL", color = Color(0xFFFF1744))
                }
            },
            text = {
                Column {
                    Text(
                        text = "This action CANNOT be undone!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF1744)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("The following will be permanently deleted:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• All messages and conversations")
                    Text("• All contacts")
                    Text("• Wallet cache and data")
                    Text("• All app settings")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "After deletion, you will be prompted to uninstall the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDestroyDialog = false
                        onDestroyAll()
                    }
                ) {
                    Text("🔥 DESTROY EVERYTHING", color = Color(0xFFFF1744))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDestroyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Group Chat Coming Soon Dialog
    if (showGroupComingSoonDialog) {
        GroupComingSoonDialog(
            onDismiss = { showGroupComingSoonDialog = false }
        )
    }
}

/**
 * Coming Soon dialog for Group Chat feature
 */
@Composable
private fun GroupComingSoonDialog(
    onDismiss: () -> Unit
) {
    val colors = chatColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF00D9FF),
                                    Color(0xFF00E676)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Group Chats",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                // Coming Soon badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF00D9FF).copy(alpha = 0.2f),
                                        Color(0xFF00E676).copy(alpha = 0.2f)
                                    )
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RocketLaunch,
                                contentDescription = null,
                                tint = Color(0xFF00D9FF),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "COMING SOON",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00D9FF)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Private group messaging is being built using the ZMSG-GROUP protocol.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Feature highlights
                Text(
                    text = "What to expect:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                GroupFeatureItem(
                    icon = Icons.Outlined.Lock,
                    text = "End-to-end encrypted group chats"
                )
                GroupFeatureItem(
                    icon = Icons.Filled.Groups,
                    text = "Up to 20 members per group"
                )
                GroupFeatureItem(
                    icon = Icons.Outlined.VpnKey,
                    text = "Secure key rotation on member changes"
                )
                GroupFeatureItem(
                    icon = Icons.Outlined.Link,
                    text = "Fully on-chain, no servers"
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Cost note
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "💡",
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Note: Group messages cost ~0.0001 ZEC per member (e.g., 10 members = 0.001 ZEC per message)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it!")
            }
        }
    )
}

@Composable
private fun GroupFeatureItem(
    icon: ImageVector,
    text: String,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusEditDialog(
    currentStatus: String,
    onStatusChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    var broadcastToContacts by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Your Status") },
        text = {
            Column {
                Text(
                    text = "Your status will be visible to contacts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = currentStatus,
                    onValueChange = { if (it.length <= 100) onStatusChange(it) },
                    label = { Text("Status") },
                    placeholder = { Text("What's your status?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${currentStatus.length}/100",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )

                // Preset status options
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Quick status:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    UserStatus.PRESETS.take(3).forEach { preset ->
                        TextButton(
                            onClick = { onStatusChange(preset) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    UserStatus.PRESETS.drop(3).take(3).forEach { preset ->
                        TextButton(
                            onClick = { onStatusChange(preset) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Broadcast option (hidden for now - expensive)
                // This would send status to all contacts which costs ZEC
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(broadcastToContacts) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (currentStatus.isNotBlank()) {
                    TextButton(onClick = onClear) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun SyncStatusBar(
    lastSyncTime: Instant?,
    secondsUntilNextSync: Int,
    isRefreshing: Boolean,
    onRefreshClick: () -> Unit,
    blockHeight: Long?,
    zecPriceUsd: Double?,
    onDestroyClick: () -> Unit = {}
) {
    val colors = chatColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = Color(0xFF00FFFF).copy(alpha = 0.3f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(colors.backgroundLight)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Refresh icon on the LEFT
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Refresh",
            tint = if (isRefreshing) colors.primary.copy(alpha = 0.4f) else colors.primary,
            modifier = Modifier
                .size(16.dp)
                .clickable(enabled = !isRefreshing) { onRefreshClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Syncing...",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary
                )
            } else {
                // Format: "HH:mm:ss · 45s · #2,847,123 · $42.15"
                val statusParts = mutableListOf<String>()
                statusParts.add(formatSyncTime(lastSyncTime))
                statusParts.add("${secondsUntilNextSync}s")
                blockHeight?.let { height ->
                    statusParts.add("#${formatNumber(height)}")
                }
                zecPriceUsd?.let { price ->
                    statusParts.add("$${String.format("%.2f", price)}")
                }
                Text(
                    text = statusParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // DESTROY ALL button on the RIGHT - nuclear icon style
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF1744).copy(alpha = 0.15f))
                .clickable { onDestroyClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = "Destroy All",
                tint = Color(0xFFFF1744),  // Red danger color
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatNumber(number: Long): String {
    return String.format("%,d", number)
}

private fun formatSyncTime(instant: Instant?): String {
    if (instant == null) return "--:--:--"
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

/**
 * Prominent banner showing wallet sync/restore progress.
 * Displays percentage, status message, and block range.
 */
@Composable
private fun WalletSyncProgressBanner(
    syncStatus: WalletSyncStatus,
    modifier: Modifier = Modifier
) {
    val colors = chatColors()
    val isRestoring = syncStatus.isRestoring
    val progress = syncStatus.progress

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRestoring) {
                colors.primary.copy(alpha = 0.15f)
            } else {
                colors.secondary.copy(alpha = 0.15f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Title row with icon and percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Animated sync icon
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = if (isRestoring) colors.primary else colors.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isRestoring) "Restoring Wallet" else "Syncing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isRestoring) colors.primary else colors.secondary
                    )
                }
                // Big percentage
                Text(
                    text = "${progress.toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isRestoring) colors.primary else colors.secondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.backgroundLight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (progress / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = if (isRestoring) {
                                    listOf(colors.primary, colors.secondary)
                                } else {
                                    listOf(colors.secondary, colors.primary)
                                }
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status message
            if (syncStatus.statusMessage.isNotEmpty()) {
                Text(
                    text = syncStatus.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }

            // Block range (if available)
            syncStatus.scanningRange?.let { range ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = range,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary.copy(alpha = 0.7f)
                )
            }

            // Warning for restoring
            if (isRestoring) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Keep app open • Older wallets may take longer",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB300)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationsView(
    modifier: Modifier = Modifier,
    onNewChatClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_lock_shield),
            contentDescription = "Privacy",
            modifier = Modifier.size(96.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Welcome to ZCHAT",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "True Privacy. Zero Compromise.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Cyberpunk feature icons - 2x2 grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Row 1: Lock Shield + No Server
                Image(
                    painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_lock_shield),
                    contentDescription = "End-to-end encrypted",
                    modifier = Modifier.size(140.dp),
                    contentScale = ContentScale.Fit
                )
                Image(
                    painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_no_server),
                    contentDescription = "No servers needed",
                    modifier = Modifier.size(140.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Row 2: Anonymous + No Tracking
                Image(
                    painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_anonymous),
                    contentDescription = "No identity needed",
                    modifier = Modifier.size(140.dp),
                    contentScale = ContentScale.Fit
                )
                Image(
                    painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_no_tracking),
                    contentDescription = "No tracking",
                    modifier = Modifier.size(140.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Tap + to send your first private message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    text: String,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsList(
    conversations: List<Conversation>,
    onConversationClick: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onEditContact: (String) -> Unit,
    getContact: (String) -> Contact?,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(conversations, key = { it.peerAddress }) { conversation ->
            val contact = getContact(conversation.peerAddress)
            SwipeableConversationItem(
                conversation = conversation,
                contact = contact,
                onClick = { onConversationClick(conversation.peerAddress) },
                onDeleteChat = { onDeleteChat(conversation.peerAddress) },
                onAddContact = { onAddContact(conversation.peerAddress) },
                onEditContact = { onEditContact(conversation.peerAddress) }
            )
        }
    }
}

/**
 * Combined list showing both groups and conversations.
 * Groups appear at the top, followed by conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsAndGroupsList(
    conversations: List<Conversation>,
    groups: List<GroupInfo>,
    onConversationClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onEditContact: (String) -> Unit,
    getContact: (String) -> Contact?,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Groups section (if any groups exist)
        if (groups.isNotEmpty()) {
            item {
                Text(
                    text = "Groups",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            items(groups, key = { "group_${it.groupId}" }) { group ->
                GroupItem(
                    group = group,
                    onClick = { onGroupClick(group.groupId) },
                    onDeleteGroup = { onDeleteGroup(group.groupId) }
                )
            }

            // Separator between groups and conversations
            if (conversations.isNotEmpty()) {
                item {
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Conversations section
        items(conversations, key = { it.peerAddress }) { conversation ->
            val contact = getContact(conversation.peerAddress)
            SwipeableConversationItem(
                conversation = conversation,
                contact = contact,
                onClick = { onConversationClick(conversation.peerAddress) },
                onDeleteChat = { onDeleteChat(conversation.peerAddress) },
                onAddContact = { onAddContact(conversation.peerAddress) },
                onEditContact = { onEditContact(conversation.peerAddress) }
            )
        }
    }
}

/**
 * Single group item in the chat list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupItem(
    group: GroupInfo,
    onClick: () -> Unit,
    onDeleteGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val colors = chatColors()

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.backgroundLight
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Group avatar with gradient border
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF00D9FF),
                                    Color(0xFF00E676)
                                )
                            )
                        )
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Group",
                        tint = colors.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatTimestamp(group.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8892A0),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Group chat",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        color = Color(0xFFB0BEC5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Long-press dropdown menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Leave Group") },
                onClick = {
                    showMenu = false
                    onDeleteGroup()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    contact: Contact?,
    onClick: () -> Unit,
    onDeleteChat: () -> Unit,
    onAddContact: () -> Unit,
    onEditContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = chatColors()
    val coroutineScope = rememberCoroutineScope()

    // Track if we should show delete (swiped state)
    var isRevealed by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    // Allow the swipe to settle - show delete button
                    isRevealed = true
                    true
                }
                SwipeToDismissBoxValue.Settled -> {
                    isRevealed = false
                    true
                }
                else -> false
            }
        },
        positionalThreshold = { totalDistance ->
            // Trigger at 20% swipe
            totalDistance * 0.2f
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,  // Only allow right-to-left swipe
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // Delete button revealed on swipe
            val isEndToStart = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart || isRevealed

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isEndToStart) colors.error else Color.Transparent),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isEndToStart) {
                    Row(
                        modifier = Modifier
                            .clickable {
                                onDeleteChat()
                                coroutineScope.launch {
                                    isRevealed = false
                                    dismissState.reset()
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DELETE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        content = {
            ConversationItem(
                conversation = conversation,
                contact = contact,
                onClick = {
                    // If revealed, first reset the swipe, then handle click
                    if (isRevealed) {
                        coroutineScope.launch {
                            isRevealed = false
                            dismissState.reset()
                        }
                    } else {
                        onClick()
                    }
                },
                onDeleteChat = onDeleteChat,
                onAddContact = onAddContact,
                onEditContact = onEditContact
            )
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    contact: Contact?,
    onClick: () -> Unit,
    onDeleteChat: () -> Unit,
    onAddContact: () -> Unit,
    onEditContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    // Use contactName from conversation (populated by ViewModel), fallback to contact param, then displayName
    val displayName = conversation.contactName ?: contact?.name ?: conversation.displayName
    val avatarText = if (conversation.hasContactName) {
        conversation.contactName!!.split(" ")
            .take(2)
            .map { it.firstOrNull()?.uppercaseChar() ?: '?' }
            .joinToString("")
    } else {
        contact?.name?.take(2)?.uppercase() ?: conversation.peerAddress.take(2).uppercase()
    }

    val colors = chatColors()
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.backgroundLight
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with gradient border
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(colors.titleGradient)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp, // 10% bigger than default ~14sp
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        conversation.lastMessage?.let { msg ->
                            Text(
                                text = formatTimestamp(msg.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8892A0),
                                fontSize = 13.sp // 10% bigger than 12sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Show draft if available, otherwise show last message
                    if (conversation.hasDraft) {
                        Row {
                            Text(
                                text = "Draft: ",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = conversation.draft?.take(80) ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 15.sp,
                                color = Color(0xFFB0BEC5),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else conversation.lastMessage?.let { msg ->
                        // Use displayText for better preview of locked/request messages
                        val previewText = msg.displayText.take(100)
                        Text(
                            text = if (msg.isOutgoing) "You: $previewText" else previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 15.sp, // 10% bigger than default ~14sp
                            color = Color(0xFFB0BEC5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Display peer status if available
                    conversation.peerStatus?.let { status ->
                        if (status.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "📍 ${status.text}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondary.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Long-press dropdown menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            // Delete Chat option
            DropdownMenuItem(
                text = { Text("Delete Chat") },
                onClick = {
                    showMenu = false
                    onDeleteChat()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )

            // Add/Edit Contact option
            if (contact != null) {
                DropdownMenuItem(
                    text = { Text("Edit Contact") },
                    onClick = {
                        showMenu = false
                        onEditContact()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null
                        )
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Add to Contacts") },
                    onClick = {
                        showMenu = false
                        onAddContact()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Instant): String {
    val now = Instant.now()
    val daysBetween = ChronoUnit.DAYS.between(timestamp, now)

    return when {
        daysBetween == 0L -> {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
            formatter.format(timestamp)
        }
        daysBetween == 1L -> "Yesterday"
        daysBetween < 7L -> {
            val formatter = DateTimeFormatter.ofPattern("EEE")
                .withZone(ZoneId.systemDefault())
            formatter.format(timestamp)
        }
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
                .withZone(ZoneId.systemDefault())
            formatter.format(timestamp)
        }
    }
}
