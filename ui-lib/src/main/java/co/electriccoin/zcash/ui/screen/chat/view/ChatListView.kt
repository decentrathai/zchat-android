package co.electriccoin.zcash.ui.screen.chat.view

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.SolidColor
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
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.WalletSyncStatus
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.NavigationRouter
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
    onSetupDestroyPin: suspend (String) -> Unit = {},
    onVerifyDestroyPin: suspend (String) -> Boolean = { false },
    // Recovery for a forgotten destroy PIN — clears the stored PIN so the user can set a fresh one.
    // Invoked only AFTER a successful device-credential confirm (see the verify dialog's "Forgot PIN?").
    onResetDestroyPin: () -> Unit = {},
    onInviteFriendClick: () -> Unit = {},
    // #224 — inbound OPEN ("free NOSTR") contact requests awaiting accept/reject. When > 0 a tappable
    // banner appears atop the list; tapping it opens the requests sheet handled by the caller.
    messageRequestCount: Int = 0,
    onRequestsClick: () -> Unit = {},
    // C1 (UX audit) — seed-backup reminder banner. Shows once the user has received funds and hasn't
    // backed up their recovery phrase yet; tapping opens the backup explainer.
    showBackupReminder: Boolean = false,
    onBackupReminderClick: () -> Unit = {},
    // Fired after the user successfully SETS/CHANGES their self photo, so the caller can propagate it to
    // established contacts over FREE NOSTR (ZPROF). No-op default keeps previews/other callers unaffected.
    onSelfAvatarChanged: () -> Unit = {},
    onSelfAvatarRemoved: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // SECURITY (privacy): the conversation list (contact names, last-message previews) is sensitive —
    // block screenshots / screen-recording / app-switcher thumbnail while foregrounded.
    if (shouldSecureScreen) {
        SecureScreen()
    }

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
    var pinVerifying by remember { mutableStateOf(false) }
    val pinVerifyScope = rememberCoroutineScope()

    // Destroy-PIN recovery (forgotten/mis-set PIN). Gated behind the system device-credential confirm so
    // it can't casually bypass the anti-accidental-wipe PIN — the same factor that already protects app
    // access + Send Funds. On success: clear the stored PIN and route to fresh setup. If the device has no
    // credential at all, there's nothing to authenticate against, so allow the reset directly.
    val destroyResetContext = LocalContext.current
    fun completeDestroyPinReset() {
        onResetDestroyPin()
        showPinVerifyDialog = false
        pinInput = ""
        pinError = null
        pinVerifying = false
        showPinSetupDialog = true
    }
    val destroyPinResetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) completeDestroyPinReset()
    }
    fun launchDestroyPinReset() {
        val km = destroyResetContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        @Suppress("DEPRECATION")
        val intent = km?.createConfirmDeviceCredentialIntent(
            "Reset emergency-wipe PIN",
            "Confirm your device PIN/biometric to reset the ZCHAT destroy PIN."
        )
        if (intent != null) destroyPinResetLauncher.launch(intent) else completeDestroyPinReset()
    }
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

    // Search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Auto-focus search field when search opens
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) focusRequester.requestFocus()
    }

    // Back handler to close search instead of navigating back
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    // Navigation router for bottom nav tabs
    val navigationRouter = koinInject<NavigationRouter>()

    // Self-avatar (Phase 1: local-only) — top-bar identity spot. Tap opens change/remove; the
    // picked image is downscaled + stored via AvatarStore off the main thread.
    val avatarStore = koinInject<AvatarStore>()
    val avatarScope = rememberCoroutineScope()
    var showSelfAvatarDialog by remember { mutableStateOf(false) }
    val selfAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatarScope.launch {
                val bytes = loadAvatarBytesFromUri(context, uri)
                val stored = bytes != null && withContext(Dispatchers.IO) { avatarStore.setSelfAvatar(bytes) }
                if (!stored) {
                    Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
                } else {
                    // Propagate the new self photo to established contacts over FREE NOSTR (ZPROF).
                    onSelfAvatarChanged()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            val colors = chatColors()
            if (isSearchActive) {
                // Search mode top bar
                @Suppress("LongMethod")
                androidx.compose.material3.TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search",
                                tint = chatColors().textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    title = {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 16.sp,
                                color = chatColors().textPrimary,
                                fontFamily = RajdhaniFontFamily,
                            ),
                            cursorBrush = SolidColor(chatColors().primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .background(
                                    chatColors().bgInput,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search conversations...",
                                        fontSize = 16.sp,
                                        fontFamily = RajdhaniFontFamily,
                                        color = chatColors().textSecondary,
                                    )
                                }
                                innerTextField()
                            }
                        )
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = chatColors().textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = chatColors().surface
                    )
                )
            } else {
                // Normal top bar
                androidx.compose.material3.TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Self avatar — the user's own local photo (tap to set/change/remove).
                            ZchatAvatar(
                                ref = ZchatAvatarRef.Self,
                                displayName = null,
                                size = 32.dp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(onClickLabel = "Change my photo") {
                                        showSelfAvatarDialog = true
                                    }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "ZChat",
                                style = TextStyle(
                                    fontSize = 24.sp, // Direction-A: 24sp Rajdhani Bold header
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = RajdhaniFontFamily,
                                ),
                                color = chatColors().primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            // Balance badge. SDK 2.5.2 requires an explicit locale on toZecString;
                            // Locale.getDefault() matches the SDK's pre-2.5.2 internal default.
                            val balanceText =
                                if (balance.value == 0L) {
                                    "0 ZEC"
                                } else {
                                    // Trim trailing zeros so the header balance stays on one line and
                                    // doesn't wrap/crowd the wordmark + icons (e.g. "0.00018 ZEC").
                                    val zec = balance.toZecString(java.util.Locale.getDefault())
                                        .trimEnd('0').trimEnd('.', ',')
                                    "$zec ZEC"
                                }
                            // B12: tapping the balance opens the Wallet tab. replace() (not forward()) to
                            // match the bottom-nav tab semantics — forward() would strand a back-stack entry.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClickLabel = "Open wallet") { navigationRouter.replace(WalletTab) }
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = balanceText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatColors().primary,
                                    maxLines = 1
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
                        }
                    },
                    actions = {
                        // Search
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = chatColors().textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // New Chat
                        IconButton(onClick = onNewChatClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = "New Chat",
                                tint = chatColors().primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // New Group
                        IconButton(onClick = onNewGroupClick) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = "New Group",
                                tint = chatColors().primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // My QR / Receive
                        IconButton(onClick = onQrCodeClick) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "My Address",
                                tint = chatColors().textSecondary,
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
                                    tint = chatColors().textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                containerColor = chatColors().bgElevated,
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Check for Updates",
                                            color = chatColors().textPrimary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        UpdateCheckTrigger.trigger()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = chatColors().primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Invite Friend",
                                            color = chatColors().textPrimary
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
                                            tint = chatColors().primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Settings",
                                            color = chatColors().textPrimary
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
                                            tint = chatColors().textSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = chatColors().surface
                    )
                )
            }
        },
        // Compose-new-chat FAB. Centered so it doesn't collide with the "Destroy All" trash
        // icon on the right side of the sync bar.
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New Chat") },
                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = onNewChatClick,
                containerColor = chatColors().primary,
                contentColor = chatColors().textOnAccent,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            )
        },
        bottomBar = {
            // Sync status bar + bottom nav grouped together so the centered FAB anchors above
            // BOTH and never overlaps the "Synced" text.
            Column {
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
            // Bottom Nav: Chats (active) | Wallet (coming soon) | More (coming soon)
            NightwireBottomNav(
                items = listOf(
                    BottomNavItem(
                        label = "Chats",
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = "Chats",
                                tint = chatColors().primary,
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
                                tint = chatColors().textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = {
                            navigationRouter.replace(WalletTab)
                        }
                    ),
                    BottomNavItem(
                        label = "AI",
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "AI",
                                tint = chatColors().textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = {
                            // replace() (not forward()): AI is a peer content tab and leaves via replace();
                            // forward() here strands a back-stack entry across tab switches (NAV-1).
                            navigationRouter.replace(co.electriccoin.zcash.ui.screen.ai.AiTab)
                        }
                    ),
                    BottomNavItem(
                        label = "More",
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "More",
                                tint = chatColors().textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = {
                            // Route to the settings hub (MoreArgs), not the Advanced Settings leaf —
                            // matches the top-bar gear and keeps the bottom nav reachable.
                            onSettingsClick()
                        }
                    ),
                )
            )
            }  // close Column wrapping SyncStatusBar + NightwireBottomNav
        },
        containerColor = chatColors().background
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

            // C1 (UX audit) — seed-backup reminder atop the list. The single biggest fund-loss path:
            // without the recovery phrase a lost/wiped device means the funds are gone forever.
            if (showBackupReminder) {
                SeedBackupReminderBanner(onClick = onBackupReminderClick)
            }

            // #224 — Message Requests banner. Tapping opens the accept/reject sheet (handled by caller).
            if (messageRequestCount > 0) {
                MessageRequestsBanner(
                    count = messageRequestCount,
                    onClick = onRequestsClick,
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
                            CircularProgressIndicator(color = chatColors().primary)
                        }
                    }
                    is ChatListState.Success -> {
                        // Filter conversations and groups based on search query
                        val filteredConversations = if (searchQuery.isBlank()) {
                            state.conversations
                        } else {
                            val q = searchQuery.lowercase()
                            state.conversations.filter { conv ->
                                conv.displayName.lowercase().contains(q) ||
                                    conv.peerAddress.lowercase().contains(q) ||
                                    (conv.lastMessage?.text?.lowercase()?.contains(q) == true)
                            }
                        }
                        val filteredGroups = if (searchQuery.isBlank()) {
                            state.groups
                        } else {
                            val q = searchQuery.lowercase()
                            state.groups.filter { group ->
                                group.name.lowercase().contains(q)
                            }
                        }

                        if (state.conversations.isEmpty() && state.groups.isEmpty()) {
                            EmptyConversationsView(
                                modifier = Modifier,
                                onNewChatClick = onNewChatClick
                            )
                        } else if (searchQuery.isNotBlank() && filteredConversations.isEmpty() && filteredGroups.isEmpty()) {
                            // No search results
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No conversations found",
                                    color = chatColors().textSecondary,
                                    fontSize = 15.sp,
                                    fontFamily = RajdhaniFontFamily,
                                )
                            }
                        } else {
                            ConversationsAndGroupsList(
                                conversations = filteredConversations,
                                groups = filteredGroups,
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
                                color = chatColors().error
                            )
                        }
                    }
                }
            }

            // SyncStatusBar was moved into the bottomBar slot above (alongside the bottom nav)
            // so the centered FAB anchors above both and never overlaps the "Synced" text.
        }
    }

    // Self-avatar chooser (Phase 1: local-only). hasPhoto is recomputed on every avatar mutation
    // (version bump) so the dialog shows "Set up photo" when none is set and Change/Remove otherwise.
    if (showSelfAvatarDialog) {
        val avatarVersion by avatarStore.version.collectAsState()
        val hasSelfPhoto = remember(avatarVersion) { avatarStore.hasSelfAvatar() }
        AvatarPhotoDialog(
            title = "My photo",
            hasPhoto = hasSelfPhoto,
            onDismiss = { showSelfAvatarDialog = false },
            onPickNew = { selfAvatarPicker.launch("image/*") },
            onRemove = {
                avatarScope.launch(Dispatchers.IO) {
                    avatarStore.removeSelfAvatar()
                    onSelfAvatarRemoved()
                }
            }
        )
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
                        tint = chatColors().destroyRed,
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
                        color = chatColors().textSecondary
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
                            color = chatColors().error,
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
                                // Capture the PIN value BEFORE resetting the fields below. The launch
                                // is async; reading the `pinInput` var inside it would race the
                                // `pinInput = ""` reset on the next lines and hash an EMPTY string —
                                // PBKDF2 throws "password empty" and crashed the app before destroyAll
                                // ever ran (so data survived). runCatching guards the fire-and-forget
                                // launch so a hashing failure can never crash the app.
                                val pinToSet = pinInput
                                pinVerifyScope.launch {
                                    runCatching { onSetupDestroyPin(pinToSet) }
                                        .onFailure { Log.e("ChatListView", "destroy PIN setup failed", it) }
                                }
                                showPinSetupDialog = false
                                showDestroyDialog = true
                                pinInput = ""
                                pinConfirmInput = ""
                                pinError = null
                            }
                        }
                    },
                    enabled = pinInput.length >= 4 && pinInput == pinConfirmInput
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
                        tint = chatColors().destroyRed,
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
                        color = chatColors().destroyRed
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
                            color = chatColors().error,
                            fontSize = 13.sp
                        )
                    }
                    // Recovery: forgot the destroy PIN. Confirm the device credential, then clear the
                    // stored PIN and route to fresh setup — otherwise a forgotten PIN bricks the wipe forever.
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = { launchDestroyPinReset() },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Forgot PIN? Reset with device lock", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pinVerifying) return@TextButton
                        pinVerifying = true
                        pinError = null
                        // PBKDF2 verify is CPU-bound (~300ms). Hop off the main thread so
                        // the dialog stays responsive and Android does not ANR the activity.
                        pinVerifyScope.launch {
                            // onVerifyDestroyPin is now suspend; it dispatches PBKDF2 to
                            // Dispatchers.Default internally — no explicit withContext needed here.
                            // Guard against a throw (corrupted/keystore-invalidated destroyStore data):
                            // an uncaught exception would crash the app AND strand pinVerifying=true.
                            val ok = runCatching { onVerifyDestroyPin(pinInput) }.getOrDefault(false)
                            pinVerifying = false
                            // Guard against the dialog being dismissed mid-verify: PBKDF2 is not
                            // cooperatively cancellable, so the 300ms work completes even if the
                            // user cancelled. Only act on the result if the dialog is still open.
                            if (!showPinVerifyDialog) return@launch
                            if (ok) {
                                showPinVerifyDialog = false
                                showDestroyDialog = true
                                pinInput = ""
                            } else {
                                pinError = "Incorrect PIN"
                            }
                        }
                    },
                    enabled = pinInput.length >= 4 && !pinVerifying
                ) {
                    Text(if (pinVerifying) "Verifying…" else "Verify", color = chatColors().destroyRed)
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
                            .background(chatColors().destroyRed),
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
                    Text("DESTROY ALL", color = chatColors().destroyRed)
                }
            },
            text = {
                Column {
                    Text(
                        text = "This action CANNOT be undone!",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = chatColors().destroyRed
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("The following will be permanently deleted:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• All messages and conversations")
                    Text("• All contacts")
                    Text(
                        text = "• Your wallet — private keys and all funds",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "   Without your recovery phrase, your money cannot be recovered.",
                        fontSize = 13.sp,
                        color = chatColors().destroyRed,
                    )
                    Text("• All app settings")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "After deletion, you will be prompted to uninstall the app.",
                        fontSize = 13.sp,
                        color = chatColors().textSecondary
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
                    Text("🔥 DESTROY EVERYTHING", color = chatColors().destroyRed)
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
                    color = chatColors().textSecondary
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
                        containerColor = chatColors().bgElevated
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
                            color = chatColors().textSecondary
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
    iconTint: Color = chatColors().primary
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
            color = chatColors().textPrimary
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
                    color = chatColors().textSecondary
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
                    color = chatColors().textSecondary,
                    modifier = Modifier.align(Alignment.End)
                )

                // Preset status options
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Quick status:",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
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
                        Text("Clear", color = chatColors().error)
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
    val borderColor = colors.borderDefault
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(chatColors().surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Refresh icon on the LEFT — 48dp hit box (was a bare 16dp clickable, ~1/3 the min target)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = !isRefreshing) { onRefreshClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = if (isRefreshing) colors.primary.copy(alpha = 0.4f) else colors.primary,
                modifier = Modifier.size(16.dp)
            )
        }
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
        // DESTROY ALL button on the RIGHT — destructive, so the hit area is the full 48dp min
        // (was 24dp = half), while the red badge stays visually compact.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onDestroyClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(chatColors().destroyRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = "Destroy All",
                    tint = chatColors().destroyRed,
                    modifier = Modifier.size(14.dp)
                )
            }
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
 * C1 (UX audit) — tappable seed-backup reminder shown atop the chat list once the user has received
 * funds but hasn't backed up their recovery phrase. Losing the phrase (device loss/wipe) = losing all
 * funds, so this is the single highest-value nag in the app. Tapping opens the backup explainer.
 */
@Composable
private fun SeedBackupReminderBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = chatColors()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.warning.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colors.warning,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Back up your recovery phrase",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = RajdhaniFontFamily,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "It's the only way to recover your funds if this device is lost or reset.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.warning)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Back Up",
                    color = colors.background,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * #224 — tappable banner shown atop the chat list when there are pending inbound OPEN contact requests
 * (someone messaged you free over NOSTR and you haven't accepted/rejected yet). Tapping opens the
 * accept/reject sheet.
 */
@Composable
private fun MessageRequestsBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = chatColors()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // weight(1f) bounds the left content so the subtitle wraps WITHIN this column instead of
            // stealing the whole row — previously it pushed the "Review" label down to ~1 char wide,
            // so it rendered vertically (R-e-v-i-e-w). The Review pill below keeps its natural width.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = colors.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (count == 1) "1 message request" else "$count message requests",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = RajdhaniFontFamily,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "Tap Review to accept or decline — free, encrypted over NOSTR",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Filled pill — fixed natural width, single line, never wraps vertically.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Review",
                    color = colors.background,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RajdhaniFontFamily,
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
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
            .background(chatColors().background)
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
            color = chatColors().textPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val annotatedText = buildAnnotatedString {
            withStyle(SpanStyle(color = chatColors().textSecondary, fontSize = 15.sp)) {
                append("Send a ")
            }
            withLink(
                LinkAnnotation.Clickable("private") {
                    showPrivacyDialog = true
                }
            ) {
                withStyle(
                    SpanStyle(
                        color = chatColors().primary,
                        fontSize = 15.sp,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append("private")
                }
            }
            withStyle(SpanStyle(color = chatColors().textSecondary, fontSize = 15.sp)) {
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
    iconTint: Color = chatColors().primary
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
            color = chatColors().textSecondary,
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
                .background(chatColors().background)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group avatar (48dp) — stored local photo when set (admin-editable in group settings),
            // else the shared gradient group placeholder.
            ZchatAvatar(
                ref = ZchatAvatarRef.Group(group.groupId),
                displayName = group.name,
                size = 48.dp
            )

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
                        color = chatColors().textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatTimestamp(group.createdAt),
                        color = chatColors().textTertiary,
                        fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Group chat",
                        fontSize = 14.sp,
                        color = chatColors().textSecondary,
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
                            tint = chatColors().error
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
                    .background(chatColors().borderDefault)
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

    val colors = chatColors()
    val hasPayment = conversation.lastMessage?.isPaymentRequest == true
    Box(modifier = modifier) {
        // Left edge indicator (3dp)
        if (hasPayment) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(72.dp)
                    .background(chatColors().success.copy(alpha = 0.4f))
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
                .background(chatColors().background)
                .padding(start = if (hasPayment) 19.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar (36dp, Direction-A) — stored local photo when set, else initials on the
            // unique per-contact color (real names only; address-derived pair otherwise).
            ZchatAvatar(
                ref = ZchatAvatarRef.Contact(conversation.peerAddress),
                displayName = if (conversation.hasContactName) conversation.contactName else contact?.name,
                size = 36.dp
            )

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
                            color = chatColors().textPrimary,
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
                                tint = chatColors().textTertiary
                            )
                        }
                    }
                    conversation.lastMessage?.let { msg ->
                        Text(
                            text = formatTimestamp(msg.timestamp),
                            color = chatColors().textSecondary,
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
                            color = chatColors().error,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = conversation.draft?.take(80) ?: "",
                            fontSize = 13.sp,
                            color = chatColors().textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // weight so the draft ellipsizes within the row instead of pushing past it
                            // on narrow screens / long drafts.
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                } else conversation.lastMessage?.let { msg ->
                    // Normalize so a file/handshake last-message previews as "📎 Image · 149 KB" /
                    // "🔐 Secure connection request" instead of the raw "ZFILE|…"/"ZBOOT|…" string.
                    val previewText = msg.forDisplay().displayText.take(100)
                    Text(
                        text = if (msg.isOutgoing) "You: $previewText" else previewText,
                        fontSize = 13.sp,
                        color = if (conversation.unreadCount > 0) chatColors().textPrimary else chatColors().textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Peer status
                conversation.peerStatus?.let { status ->
                    if (status.text.isNotBlank()) {
                        Text(
                            text = status.text,
                            color = chatColors().success.copy(alpha = 0.7f),
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
                        tint = chatColors().error
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
                .background(chatColors().borderDefault)
        )
    }
}

private fun formatTimestamp(timestamp: Instant): String {
    // Compare CALENDAR days in the system zone, not elapsed 24h periods — otherwise a message from
    // 22:30 yesterday reads as "today" at 09:00 (only 10.5h elapsed) instead of "Yesterday".
    val zone = ZoneId.systemDefault()
    val daysBetween = ChronoUnit.DAYS.between(
        timestamp.atZone(zone).toLocalDate(),
        java.time.LocalDate.now(zone),
    )

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
