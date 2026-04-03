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
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VpnKey
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
import co.electriccoin.zcash.ui.screen.chat.view.components.NightwireBottomNav
import co.electriccoin.zcash.ui.screen.update.UpdateCheckTrigger
import co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem
import co.electriccoin.zcash.ui.screen.wallettab.WalletTab
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit


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
    onInviteFriendClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Status edit dialog state
    var showStatusDialog by remember { mutableStateOf(false) }
    var statusText by remember(userStatus) { mutableStateOf(userStatus.text) }

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

    val context = androidx.compose.ui.platform.LocalContext.current

    // Navigation router for bottom nav tabs
    val navigationRouter = koinInject<NavigationRouter>()

    Scaffold(
        topBar = {
            val colors = chatColors()
            // Nightwire Top Bar: "ZChat" in Rajdhani/AccentPrimary + action icons
            androidx.compose.material3.TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ZChat",
                            style = TextStyle(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = RajdhaniFontFamily,
                            ),
                            color = NightwireColors.AccentPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // Balance badge
                        val balanceText = if (balance.value == 0L) "0 ZEC" else "${balance.toZecString()} ZEC"
                        Text(
                            text = balanceText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NightwireColors.AccentPrimary
                        )
                        zecPriceUsd?.let { price ->
                            val balanceZec = balance.value / 100_000_000.0
                            val usdValue = balanceZec * price
                            Text(
                                text = " ($${String.format("%.2f", usdValue)})",
                                fontSize = 11.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                },
                actions = {
                    // Search
                    IconButton(onClick = {
                        android.widget.Toast.makeText(
                            context, "Search coming soon", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = NightwireColors.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // New Chat
                    IconButton(onClick = onNewChatClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Message,
                            contentDescription = "New Chat",
                            tint = NightwireColors.AccentPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // New Group
                    IconButton(onClick = onNewGroupClick) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = "New Group",
                            tint = NightwireColors.AccentPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // My QR / Receive
                    IconButton(onClick = onQrCodeClick) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "My Address",
                            tint = NightwireColors.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // Settings / More menu
                    Box {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Menu",
                                tint = NightwireColors.TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = NightwireColors.BgElevated,
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Check for Updates",
                                        color = NightwireColors.TextPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    UpdateCheckTrigger.manualCheck.value = true
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = NightwireColors.AccentPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Invite Friend",
                                        color = NightwireColors.TextPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onInviteFriendClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        tint = NightwireColors.AccentPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Settings",
                                        color = NightwireColors.TextPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onSettingsClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = NightwireColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightwireColors.BgSurface
                )
            )
        },
        bottomBar = {
            // Bottom Nav: Chats (active) | Wallet (coming soon) | More (coming soon)
            NightwireBottomNav(
                items = listOf(
                    BottomNavItem(
                        label = "Chats",
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = "Chats",
                                tint = NightwireColors.AccentPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = true,
                        onClick = { /* Already on chats */ }
                    ),
                    BottomNavItem(
                        label = "Wallet",
                        icon = {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "Wallet",
                                tint = NightwireColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = {
                            navigationRouter.replace(WalletTab)
                        }
                    ),
                    BottomNavItem(
                        label = "More",
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "More",
                                tint = NightwireColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = {
                            navigationRouter.forward(AdvancedSettingsArgs)
                        }
                    ),
                )
            )
        },
        containerColor = NightwireColors.BgBase
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Wallet Sync Progress Banner - shows during restore/sync
            if (walletSyncStatus.isRestoring || walletSyncStatus.isInitiating || (walletSyncStatus.isSyncing && walletSyncStatus.progress < 98f)) {
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
                            CircularProgressIndicator(color = NightwireColors.AccentPrimary)
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
                                color = NightwireColors.ColorDanger
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
                        fontSize = 15.sp,
                        color = NightwireColors.TextSecondary
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
                            color = NightwireColors.ColorDanger,
                            fontSize = 13.sp
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
                        fontSize = 15.sp,
                        color = Color(0xFFFF1744)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Enter your PIN to confirm destruction:",
                        fontSize = 15.sp
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
                            color = NightwireColors.ColorDanger,
                            fontSize = 13.sp
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
                        fontSize = 17.sp,
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
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary
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
                    fontSize = 20.sp,
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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00D9FF)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Private group messaging is being built using the ZMSG-GROUP protocol.",
                    fontSize = 15.sp,
                    color = NightwireColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Feature highlights
                Text(
                    text = "What to expect:",
                    fontSize = 15.sp,
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
                        containerColor = NightwireColors.BgElevated
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
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
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
    iconTint: Color = NightwireColors.AccentPrimary
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
            fontSize = 15.sp,
            color = NightwireColors.TextPrimary
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
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary
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
                    fontSize = 11.sp,
                    color = NightwireColors.TextSecondary,
                    modifier = Modifier.align(Alignment.End)
                )

                // Preset status options
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Quick status:",
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary
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
                                fontSize = 11.sp,
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
                                fontSize = 11.sp,
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
                        Text("Clear", color = NightwireColors.ColorDanger)
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
                    color = NightwireColors.BorderDefault,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(NightwireColors.BgSurface)
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
                    fontSize = 11.sp,
                    color = colors.primary
                )
            } else {
                // Format: "HH:mm:ss · 45s · Synced · $42.15"
                val statusParts = mutableListOf<String>()
                statusParts.add(formatSyncTime(lastSyncTime))
                statusParts.add("${secondsUntilNextSync}s")
                statusParts.add("Synced")
                zecPriceUsd?.let { price ->
                    statusParts.add("$${String.format("%.2f", price)}")
                }
                Text(
                    text = statusParts.joinToString(" · "),
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // DESTROY ALL button on the RIGHT
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(NightwireColors.DestroyRed.copy(alpha = 0.15f))
                .clickable { onDestroyClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = "Destroy All",
                tint = NightwireColors.DestroyRed,
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
    val isInitiating = syncStatus.isInitiating
    val isRestoringOrInitiating = isRestoring || isInitiating
    val progress = syncStatus.progress

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRestoringOrInitiating) {
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
                        color = if (isRestoringOrInitiating) colors.primary else colors.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            isInitiating -> "Setting Up Wallet"
                            isRestoring -> "Restoring Wallet"
                            else -> "Syncing"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRestoringOrInitiating) colors.primary else colors.secondary
                    )
                }
                // Big percentage
                Text(
                    text = "${progress.toInt()}%",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRestoringOrInitiating) colors.primary else colors.secondary
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
                                colors = if (isRestoringOrInitiating) {
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
                    fontSize = 15.sp,
                    color = colors.textSecondary
                )
            }

            // Block range (if available)
            syncStatus.scanningRange?.let { range ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = range,
                    fontSize = 13.sp,
                    color = colors.textSecondary.copy(alpha = 0.7f)
                )
            }

            // Warning for restoring/initiating
            if (isRestoringOrInitiating) {
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
                        text = if (isInitiating) "Keep app open while wallet is being set up" else "Keep app open • Older wallets may take longer",
                        fontSize = 11.sp,
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
    var showPrivacyDialog by remember { mutableStateOf(false) }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("What makes it private?") },
            text = {
                Text(
                    "Every message in ZCHAT is sent as an encrypted transaction on the Zcash blockchain. " +
                        "No server stores your messages. No one can read them except you and the recipient — not even us."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NightwireColors.BgBase)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_lock_shield),
            contentDescription = "Privacy",
            modifier = Modifier.size(80.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No conversations yet",
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = RajdhaniFontFamily,
            ),
            color = NightwireColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val annotatedText = buildAnnotatedString {
            withStyle(SpanStyle(color = NightwireColors.TextSecondary, fontSize = 15.sp)) {
                append("Send a ")
            }
            withLink(
                LinkAnnotation.Clickable("private") {
                    showPrivacyDialog = true
                }
            ) {
                withStyle(
                    SpanStyle(
                        color = NightwireColors.AccentPrimary,
                        fontSize = 15.sp,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append("private")
                }
            }
            withStyle(SpanStyle(color = NightwireColors.TextSecondary, fontSize = 15.sp)) {
                append(" message to get started")
            }
        }
        Text(text = annotatedText)

        Spacer(modifier = Modifier.height(24.dp))
        co.electriccoin.zcash.ui.screen.chat.view.components.ZChatButton(
            text = "Start a Chat",
            onClick = onNewChatClick,
        )
    }
}

@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    text: String,
    iconTint: Color = NightwireColors.AccentPrimary
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
            fontSize = 13.sp,
            color = NightwireColors.TextSecondary,
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
                co.electriccoin.zcash.ui.screen.chat.view.components.SectionHeader(title = "Groups")
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
                    co.electriccoin.zcash.ui.screen.chat.view.components.SectionHeader(title = "Chats")
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

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .background(NightwireColors.BgBase)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group avatar (48dp) with cyan accent
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NightwireColors.AccentPrimaryBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Groups,
                    contentDescription = "Group",
                    tint = NightwireColors.AccentPrimary,
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
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = NightwireColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatTimestamp(group.createdAt),
                        color = NightwireColors.TextTertiary,
                        fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Group chat",
                        fontSize = 14.sp,
                        color = NightwireColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                            tint = NightwireColors.ColorDanger
                        )
                    }
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 76.dp)
                    .height(1.dp)
                    .background(NightwireColors.BorderDefault)
            )
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
        conversation.contactName.orEmpty().split(" ")
            .take(2)
            .map { it.firstOrNull()?.uppercaseChar() ?: '?' }
            .joinToString("")
    } else {
        contact?.name?.take(2)?.uppercase() ?: conversation.peerAddress.take(2).uppercase()
    }

    val colors = chatColors()
    val hasPayment = conversation.lastMessage?.isPaymentRequest == true
    Box(modifier = modifier) {
        // Left edge indicator (3dp)
        if (hasPayment) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(72.dp)
                    .background(NightwireColors.AccentSuccess.copy(alpha = 0.4f))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .background(NightwireColors.BgBase)
                .padding(start = if (hasPayment) 19.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar (48dp) — unique color per contact
            val avatarAccent = NightwireColors.avatarColorForAddress(conversation.peerAddress)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(avatarAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatarText,
                    fontWeight = FontWeight.Bold,
                    color = avatarAccent,
                    fontSize = 16.sp
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            fontWeight = FontWeight.Medium,
                            fontSize = 17.sp,
                            color = NightwireColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (conversation.isMuted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.NotificationsOff,
                                contentDescription = "Muted",
                                modifier = Modifier.size(14.dp),
                                tint = NightwireColors.TextTertiary
                            )
                        }
                    }
                    conversation.lastMessage?.let { msg ->
                        Text(
                            text = formatTimestamp(msg.timestamp),
                            color = NightwireColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Message preview
                if (conversation.hasDraft) {
                    Row {
                        Text(
                            text = "Draft: ",
                            fontSize = 13.sp,
                            color = NightwireColors.ColorDanger,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = conversation.draft?.take(80) ?: "",
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else conversation.lastMessage?.let { msg ->
                    val previewText = msg.displayText.take(100)
                    Text(
                        text = if (msg.isOutgoing) "You: $previewText" else previewText,
                        fontSize = 13.sp,
                        color = if (conversation.unreadCount > 0) NightwireColors.TextPrimary else NightwireColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Peer status
                conversation.peerStatus?.let { status ->
                    if (status.text.isNotBlank()) {
                        Text(
                            text = status.text,
                            color = NightwireColors.AccentSuccess.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Unread badge
            if (conversation.unreadCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                co.electriccoin.zcash.ui.screen.chat.view.components.UnreadBadge(
                    count = conversation.unreadCount
                )
            }
        }

        // Long-press dropdown menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
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
                        tint = NightwireColors.ColorDanger
                    )
                }
            )
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
        // Divider — indented past avatar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 76.dp)
                .height(1.dp)
                .background(NightwireColors.BorderDefault)
        )
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
