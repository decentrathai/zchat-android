package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ZCHAT Brand Colors
private val ZchatCyan = Color(0xFF00D9FF)
private val ZchatGreen = Color(0xFF00E676)
private val ZchatNavy = Color(0xFF0D1B2A)
private val ZchatNavyLight = Color(0xFF1B2838)
private val ZchatTeal = Color(0xFF00838F)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTextApi::class)
@Composable
fun ChatListView(
    state: ChatListState,
    userStatus: UserStatus,
    onConversationClick: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCopyAddressClick: () -> Unit,
    onQrCodeClick: () -> Unit,
    onContactsClick: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteChat: (String) -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Gradient ZCHAT title
                            Text(
                                text = "ZCHAT",
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(ZchatCyan, ZchatGreen)
                                    )
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${balance.toZecString()} ZEC",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ZchatCyan
                            )
                            // Show USD equivalent if price available
                            zecPriceUsd?.let { price ->
                                val balanceZec = balance.value / 100_000_000.0
                                val usdValue = balanceZec * price
                                Text(
                                    text = " ($${String.format("%.2f", usdValue)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ZchatGreen
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
            FloatingActionButton(
                onClick = onNewChatClick,
                containerColor = ZchatCyan,
                contentColor = ZchatNavy
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Chat",
                    tint = ZchatNavy
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                        if (state.conversations.isEmpty()) {
                            EmptyConversationsView(
                                modifier = Modifier,
                                onNewChatClick = onNewChatClick
                            )
                        } else {
                            ConversationsList(
                                conversations = state.conversations,
                                onConversationClick = onConversationClick,
                                onDeleteChat = onDeleteChat,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZchatNavyLight)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Refresh icon on the LEFT
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Refresh",
            tint = if (isRefreshing) ZchatCyan.copy(alpha = 0.4f) else ZchatCyan,
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
                    color = ZchatCyan
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Syncing...",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZchatCyan
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
                    color = Color(0xFF8892A0)  // Muted text
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
        Text(
            text = "🔐",
            fontSize = 64.sp
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

        // Privacy explanation card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                PrivacyPoint(
                    emoji = "🔒",
                    text = "Messages encrypted on blockchain — only you hold the keys"
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrivacyPoint(
                    emoji = "🚫",
                    text = "No servers, no data collection — we can't read your messages"
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrivacyPoint(
                    emoji = "📱",
                    text = "No phone number, no email — just download and chat"
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrivacyPoint(
                    emoji = "🤖",
                    text = "No AI, no clouds, no tracking — nothing to leak"
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
    emoji: String,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = emoji,
            fontSize = 14.sp
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
            ConversationItem(
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
    val displayName = contact?.name ?: conversation.displayName
    val avatarText = contact?.name?.take(2)?.uppercase()
        ?: conversation.peerAddress.take(2).uppercase()

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
                containerColor = ZchatNavyLight
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
                        .background(
                            Brush.linearGradient(
                                colors = listOf(ZchatCyan, ZchatGreen)
                            )
                        )
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(ZchatNavy),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ZchatCyan
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

                    conversation.lastMessage?.let { msg ->
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
                                color = ZchatGreen.copy(alpha = 0.8f),
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
