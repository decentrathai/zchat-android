package co.electriccoin.zcash.ui.screen.chat.view

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatListLayout
import co.electriccoin.zcash.ui.screen.chat.model.ChatListRow
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.PaymentBubble
import co.electriccoin.zcash.ui.screen.chat.model.StateOrigin
import co.electriccoin.zcash.ui.screen.chat.model.SyncLine
import co.electriccoin.zcash.ui.screen.chat.model.SyncLineState
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.model.WalletSyncStatus
import co.electriccoin.zcash.ui.screen.chat.model.zecDisplay
import co.electriccoin.zcash.ui.screen.chat.util.DestroyFlowChoice
import co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem
import co.electriccoin.zcash.ui.screen.chat.view.components.NightwireBottomNav
import co.electriccoin.zcash.ui.screen.chat.view.components.UnreadBadge
import co.electriccoin.zcash.ui.screen.update.UpdateCheckTrigger
import co.electriccoin.zcash.ui.screen.update.UpdateRequiredBar
import co.electriccoin.zcash.ui.screen.wallettab.WalletTab
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


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
    // DESTROY-ADV-C (ZC-722): the saved choice, read again from the store when the red button is tapped.
    onDestroyOpened: suspend () -> DestroyFlowChoice = { DestroyFlowChoice() },
    // ZC-1014: a Destroy record was set aside at this start (its seal did not verify): ask once whether to run it again.
    destroyRerunOffered: Boolean = false,
    onDestroyRerunAnswered: () -> Unit = {},
    onVerifyDestroyPin: suspend (String) -> DestroyPinVerifyResult = { DestroyPinVerifyResult.Failed(attemptsRemaining = 0) },
    onInviteFriendClick: () -> Unit = {},
    // #224 — inbound OPEN ("free NOSTR") contact requests awaiting accept/reject. When > 0 a tappable
    // banner appears atop the list; tapping it opens the requests sheet handled by the caller.
    messageRequestCount: Int = 0,
    onRequestsClick: () -> Unit = {},
    // QA2 B-3 (Q2-25, ZC-1512): declined requests, reachable from the ⋮ menu while any exist (Restore lives there).
    declinedRequestCount: Int = 0,
    onDeclinedRequestsClick: () -> Unit = {},
    // C1 (UX audit) — seed-backup reminder banner. Shows once the user has received funds and hasn't
    // backed up their recovery phrase yet; tapping opens the backup explainer.
    showBackupReminder: Boolean = false,
    onBackupReminderClick: () -> Unit = {},
    // Fired after the user successfully SETS/CHANGES their self photo, so the caller can propagate it to
    // established contacts over FREE NOSTR (ZPROF). No-op default keeps previews/other callers unaffected.
    onSelfAvatarChanged: () -> Unit = {},
    onSelfAvatarRemoved: () -> Unit = {},
    // Bridge 2: set after an upgrade from 2.12.4 or older until the SDK reports the wallet fully scanned.
    upgradeCatchUpPending: Boolean = false,
    // ZC-733-B: the contact book or the picture index cannot be read: the banner column says so.
    contactNamesUnreadable: Boolean = false,
    pictureIndexUnreadable: Boolean = false,
    updateRequired: Boolean = false,
    // ZC-1104 (W-79): the Groups row: unread per group, whether it is open, and the tap that opens or folds it.
    groupUnread: Map<String, Int> = emptyMap(),
    groupLastMessageMs: Map<String, Long> = emptyMap(),
    groupsExpanded: Boolean = false,
    onToggleGroupsFolder: () -> Unit = {},
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
    var showPinVerifyDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinVerifying by remember { mutableStateOf(false) }
    var destroyChoice by remember { mutableStateOf(DestroyFlowChoice()) }
    val pinVerifyScope = rememberCoroutineScope()

    // DESTROY-ADV-P (W-52): the phone's screen lock is the proof when no Destroy PIN is set, and stands in for a forgotten
    // PIN. A confirmed screen lock opens the final dialog and does nothing else: no PIN is deleted or set here.
    val destroyResetContext = LocalContext.current
    val phoneHasScreenLock =
        remember { (destroyResetContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true }
    val destroyScreenLockLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                showPinVerifyDialog = false
                pinInput = ""
                pinError = null
                pinVerifying = false
                showDestroyDialog = true
            }
        }

    fun launchDestroyScreenLock() {
        val km = destroyResetContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        @Suppress("DEPRECATION")
        val intent =
            km?.createConfirmDeviceCredentialIntent(
                destroyResetContext.getString(R.string.destroy_heading),
                destroyResetContext.getString(R.string.chat_destroy_screen_lock_prompt)
            )
        if (intent != null) destroyScreenLockLauncher.launch(intent)
    }

    // The red button: the Destroy PIN when one is set; else the phone's screen lock; with neither (B3) the final
    // dialog at once, whose DESTROY ALL tap is the one guard. Nothing is set up and nothing is asked.
    fun onDestroyTapped() {
        when {
            hasDestroyPin -> showPinVerifyDialog = true
            phoneHasScreenLock -> launchDestroyScreenLock()
            else -> showDestroyDialog = true
        }
    }
    val userAddress = when (state) {
        is ChatListState.Success -> state.currentUserAddress
        else -> null
    }

    val balance = when (state) {
        is ChatListState.Success -> state.balance
        else -> Zatoshi(0)
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

    // SY-2: the status line says what the wallet does (the SDK status, the catch-up bar, the last full sync in this
    // process), not whether the 60-second local refresh is running.
    val syncLine =
        SyncLine.state(
            sdkStatus = walletSyncStatus.sdkStatus,
            progressPercent = walletSyncStatus.progress,
            catchUpPending = upgradeCatchUpPending,
            syncedAtMillis = walletSyncStatus.syncedAtMillis,
            nowMillis = System.currentTimeMillis(),
        )

    val context = androidx.compose.ui.platform.LocalContext.current
    // ES-A: the self-avatar's click label, read here in the composable (the modifier takes a plain string).
    val changeMyPhotoLabel = stringResource(R.string.chat_list_change_my_photo)

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
                    Toast
                        .makeText(context, context.getString(R.string.chat_list_image_load_failed), Toast.LENGTH_SHORT)
                        .show()
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
                                contentDescription = stringResource(R.string.chat_list_cd_close_search),
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
                                        text = stringResource(R.string.chat_list_search_placeholder),
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
                                    contentDescription = stringResource(R.string.chat_list_cd_clear_search),
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
                        // P11 (ES-3): the wordmark on line 1 and the whole balance on line 2 (ChatListHeaderTitle).
                        ChatListHeaderTitle(
                            balance = balance,
                            zecPriceUsd = zecPriceUsd,
                            // B12: tapping the balance opens the Wallet tab. replace() (not forward()) to
                            // match the bottom-nav tab semantics — forward() would strand a back-stack entry.
                            onOpenWallet = { navigationRouter.replace(WalletTab) },
                            // R5-4: the Compose-observed locale, so a language change re-formats the balance.
                            formatLocale = rememberDesiredFormatLocale(),
                            leading = {
                                // Self avatar — the user's own local photo (tap to set/change/remove).
                                ZchatAvatar(
                                    ref = ZchatAvatarRef.Self,
                                    displayName = null,
                                    size = 32.dp,
                                    modifier =
                                        Modifier
                                            .clip(CircleShape)
                                            .clickable(onClickLabel = changeMyPhotoLabel) {
                                                showSelfAvatarDialog = true
                                            }
                                )
                            },
                        )
                    },
                    actions = {
                        // Search
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.chat_list_cd_search),
                                tint = chatColors().textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // New Chat
                        IconButton(onClick = onNewChatClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = stringResource(R.string.chat_new_chat),
                                tint = chatColors().primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // New Group
                        IconButton(onClick = onNewGroupClick) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = stringResource(R.string.chat_list_cd_new_group),
                                tint = chatColors().primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // My QR / Receive
                        IconButton(onClick = onQrCodeClick) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = stringResource(R.string.chat_list_cd_my_address),
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
                                    contentDescription = stringResource(R.string.chat_list_cd_menu),
                                    tint = chatColors().textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                containerColor = chatColors().bgElevated,
                            ) {
                                // Store builds get their updates from the app store, and Play's
                                // Device & Network Abuse policy forbids in-app self-update, so the
                                // entry is absent there rather than present and inert.
                                if (BuildConfig.IS_SELF_UPDATE_ENABLED) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.chat_list_menu_check_updates),
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
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.invite_title),
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
                                // QA2 B-3 (ZC-1512): one mis-tap on Reject is undone here; shown only when something
                                // is declined, so it never opens an empty sheet.
                                if (declinedRequestCount > 0) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.message_requests_declined_title),
                                                color = chatColors().textPrimary
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onDeclinedRequestsClick()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Restore,
                                                contentDescription = null,
                                                tint = chatColors().primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.chat_list_menu_settings),
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
        // icon on the right side of the sync bar. OF-1: not on the empty list, whose own "Start a Chat" button is
        // the one call to action (the FAB covered it on the Fold).
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            if (ChatListLayout.showNewChatFab(state)) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.chat_new_chat)) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = onNewChatClick,
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                )
            }
        },
        bottomBar = {
            // Sync status bar + bottom nav grouped together so the centered FAB anchors above
            // BOTH and never overlaps the "Synced" text.
            Column {
                SyncStatusBar(
                    line = syncLine,
                    syncedAt = walletSyncStatus.syncedAtMillis?.let(Instant::ofEpochMilli),
                    secondsUntilNextSync = secondsUntilNextSync,
                    isRefreshing = isRefreshing,
                    onRefreshClick = onRefresh,
                    blockHeight = blockHeight,
                    zecPriceUsd = zecPriceUsd,
                    onDestroyClick = {
                        pinVerifyScope.launch {
                            destroyChoice = runCatching { onDestroyOpened() }.getOrDefault(DestroyFlowChoice())
                            onDestroyTapped()
                        }
                    }
                )
            // Bottom Nav: Chats (active) | Wallet (coming soon) | More (coming soon)
            NightwireBottomNav(
                items = listOf(
                    BottomNavItem(
                        label = stringResource(R.string.tab_chats),
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = stringResource(R.string.tab_chats),
                                tint = chatColors().primary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = true,
                        onClick = { /* Already on chats */ }
                    ),
                    BottomNavItem(
                        label = stringResource(R.string.tab_wallet),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = stringResource(R.string.tab_wallet),
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
                        label = stringResource(R.string.tab_ai),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.tab_ai),
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
                        label = stringResource(R.string.home_button_more),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.home_button_more),
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
            if (updateRequired) {
                UpdateRequiredBar(modifier = Modifier.padding(16.dp))
            }
            // Wallet Sync Progress Banner - shows during restore/sync. After an upgrade from 2.12.4 or older it is the
            // catch-up bar instead (Bridge 2), shown whatever the sync state until the SDK's fully-scanned signal.
            val catchUpPercent = PostUpgradeRules.catchUpBannerPercent(upgradeCatchUpPending, walletSyncStatus.progress)
            if (catchUpPercent != null) {
                WalletSyncProgressBanner(
                    syncStatus = walletSyncStatus,
                    catchUpPercent = catchUpPercent
                )
            } else if (walletSyncStatus.isRestoring || walletSyncStatus.isInitiating || (walletSyncStatus.isSyncing && walletSyncStatus.progress < 98f)) {
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

            // ZC-733-B: saved names or pictures that cannot be read are said, not shown as missing.
            UnreadableStoreNotice(namesUnreadable = contactNamesUnreadable, picturesUnreadable = pictureIndexUnreadable)

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
                                    text = stringResource(R.string.chat_list_no_results),
                                    color = chatColors().textSecondary,
                                    fontSize = 15.sp,
                                    fontFamily = RajdhaniFontFamily,
                                )
                            }
                        } else {
                            // QA2 U5 review F3 (ZC-1653): while a listed group awaits Join the row is shown open
                            // whatever is saved, so a tap there would write the opposite of what is on screen; as
                            // during a search, it changes nothing then.
                            val inviteHoldsRowOpen = filteredGroups.any { it.groupId in state.groupsAwaitingJoin }
                            val toggleSavedRowOpen = { if (!inviteHoldsRowOpen) onToggleGroupsFolder() }
                            // ZC-946 part 2: the kept pass is read-only (no delete, no row menu) until the list's pass ends.
                            androidx.compose.runtime.CompositionLocalProvider(
                                LocalCachedChatList provides (state.origin == StateOrigin.CACHED),
                            ) {
                                ConversationsAndGroupsList(
                                    conversations = filteredConversations,
                                    groups = filteredGroups,
                                    groupsAwaitingJoin = state.groupsAwaitingJoin,
                                    // QA2 Q2-57a (ZC-1653): a group that awaits Join counts as 1 unread.
                                    groupUnread =
                                        ChatListLayout.unreadWithInvitations(
                                            filteredGroups,
                                            groupUnread,
                                            state.groupsAwaitingJoin
                                        ),
                                    groupLastMessageMs = groupLastMessageMs,
                                    // A search shows the groups it finds, folded or not; QA2 Q2-57a: so does a listed
                                    // group that awaits Join.
                                    groupsExpanded =
                                        ChatListLayout.groupsRowOpen(
                                            saved = groupsExpanded,
                                            searching = searchQuery.isNotBlank(),
                                            groups = filteredGroups,
                                            groupsAwaitingJoin = state.groupsAwaitingJoin
                                        ),
                                    // ZC-1104 review F4: during a search the row is shown open whatever is saved; a tap
                                    // there would write the opposite of what is on screen, so it changes nothing.
                                    onToggleGroupsFolder = { if (searchQuery.isBlank()) toggleSavedRowOpen() },
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
            title = stringResource(R.string.chat_list_my_photo),
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

    // PIN Verify Dialog (when PIN already set)
    val keepsContacts = destroyChoice.keepsContacts
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
                    Text(stringResource(R.string.destroy_heading))
                }
            },
            text = {
                Column {
                    Text(
                        text =
                            stringResource(
                                if (keepsContacts) {
                                    R.string.chat_destroy_verify_warning_keep_contacts
                                } else {
                                    R.string.chat_destroy_verify_warning
                                }
                            ),
                        fontSize = 15.sp,
                        color = chatColors().destroyRed
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.destroy_enter_pin_body),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text(stringResource(R.string.destroy_pin_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
                    // A forgotten PIN: the phone's screen lock stands in for it here (nothing is reset).
                    if (phoneHasScreenLock) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { launchDestroyScreenLock() },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.chat_destroy_forgot_pin), fontSize = 13.sp)
                        }
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
                            // FD-8c: the lockout-aware result, so a lockout names its wait instead of
                            // reading as "Incorrect PIN"; a throw counts as a wrong PIN, never a right one.
                            // ZC-1042-X1: a throw is neither a right nor a wrong PIN: the dialog says it could not be
                            // checked (and what to do when that keeps happening); nothing is started.
                            val attempt = runCatching { onVerifyDestroyPin(pinInput) }
                            val result = attempt.getOrDefault(DestroyPinVerifyResult.Failed(attemptsRemaining = 0))
                            pinVerifying = false
                            // Guard against the dialog being dismissed mid-verify: PBKDF2 is not
                            // cooperatively cancellable, so the 300ms work completes even if the
                            // user cancelled. Only act on the result if the dialog is still open.
                            if (!showPinVerifyDialog) return@launch
                            if (result == DestroyPinVerifyResult.Success) {
                                showPinVerifyDialog = false
                                showDestroyDialog = true
                                pinInput = ""
                            } else if (attempt.isFailure) {
                                pinError = destroyResetContext.getString(R.string.destroy_pin_cannot_check)
                            } else {
                                pinError = destroyPinVerifyErrorText(result)?.getString(destroyResetContext)
                            }
                        }
                    },
                    enabled = pinInput.length >= 4 && !pinVerifying
                ) {
                    Text(
                        stringResource(if (pinVerifying) R.string.chat_destroy_verifying else R.string.chat_destroy_verify),
                        color = chatColors().destroyRed
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinVerifyDialog = false
                    pinInput = ""
                    pinError = null
                }) {
                    Text(stringResource(R.string.destroy_cancel))
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
                    Text(stringResource(R.string.chat_destroy_final_title), color = chatColors().destroyRed)
                }
            },
            text = {
                // ZC-692-T: the dialog is longer than a small screen; it scrolls, so no line and no button is cut.
                // ZC-1226: a line of fixed height under the body says there is more below the fold (canScrollForward);
                // its height does not change, so showing or hiding it never moves the body.
                val destroyScroll = rememberScrollState()
                Column {
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(destroyScroll)) {
                        Text(
                            text = stringResource(R.string.chat_destroy_final_cannot_undo),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = chatColors().destroyRed
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.chat_destroy_final_list_intro))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.chat_destroy_final_item_messages))
                        Text(
                            stringResource(
                                if (keepsContacts) {
                                    R.string.chat_destroy_final_kept_contacts
                                } else {
                                    R.string.chat_destroy_final_item_contacts
                                }
                            )
                        )
                        Text(
                            text = stringResource(R.string.chat_destroy_final_item_wallet),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.chat_destroy_final_wallet_note),
                            fontSize = 13.sp,
                            color = chatColors().destroyRed,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        // ZC-886: Destroy does not wait for an exchange (TEX) payment between its two steps.
                        Text(
                            text = stringResource(R.string.destroy_tex_send_not_finished),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        Text(stringResource(R.string.chat_destroy_final_item_settings))
                        Spacer(modifier = Modifier.height(12.dp))
                        // ZC-692-T: what Destroy All does not erase, said before the last tap.
                        Text(
                            text = stringResource(R.string.chat_destroy_final_stays_note),
                            fontSize = 13.sp,
                        )
                        // B5: with the default choice, a list kept by an earlier Destroy is erased too.
                        if (destroyChoice.erasesEarlierList) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = stringResource(R.string.destroy_erases_earlier_list), fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text =
                                if (keepsContacts) {
                                    stringResource(R.string.chat_destroy_final_stays_installed_note)
                                } else {
                                    stringResource(R.string.chat_destroy_final_uninstall_note)
                                },
                            fontSize = 13.sp,
                            color = chatColors().textSecondary
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.BottomStart) {
                        if (destroyScroll.canScrollForward) {
                            Text(
                                text = stringResource(R.string.chat_destroy_final_scroll_hint),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = chatColors().destroyRed,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDestroyDialog = false
                        onDestroyAll()
                    }
                ) {
                    Text(stringResource(R.string.chat_destroy_final_confirm), color = chatColors().destroyRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDestroyDialog = false }) {
                    Text(stringResource(R.string.destroy_cancel))
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

    // ZC-1014: the last Destroy did not finish (its record was set aside, nothing was deleted): asked once; the answer
    // runs the red button's own flow (the proof, then the final dialog).
    if (destroyRerunOffered) {
        AlertDialog(
            onDismissRequest = onDestroyRerunAnswered,
            text = { Text(stringResource(R.string.destroy_rerun_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDestroyRerunAnswered()
                        pinVerifyScope.launch {
                            destroyChoice = runCatching { onDestroyOpened() }.getOrDefault(DestroyFlowChoice())
                            onDestroyTapped()
                        }
                    }
                ) {
                    Text(stringResource(R.string.destroy_rerun_yes), color = chatColors().destroyRed)
                }
            },
            dismissButton = {
                TextButton(onClick = onDestroyRerunAnswered) {
                    Text(stringResource(R.string.destroy_rerun_no))
                }
            }
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
                    text = stringResource(R.string.chat_list_group_soon_title),
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
                                text = stringResource(R.string.chat_list_group_soon_badge),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00D9FF)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.chat_list_group_soon_body),
                    fontSize = 15.sp,
                    color = chatColors().textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Feature highlights
                Text(
                    text = stringResource(R.string.chat_list_group_soon_expect),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                GroupFeatureItem(
                    icon = Icons.Outlined.Lock,
                    text = stringResource(R.string.chat_list_group_soon_e2e)
                )
                GroupFeatureItem(
                    icon = Icons.Filled.Groups,
                    text = stringResource(R.string.chat_list_group_soon_members)
                )
                GroupFeatureItem(
                    icon = Icons.Outlined.VpnKey,
                    text = stringResource(R.string.chat_list_group_soon_rotation)
                )
                GroupFeatureItem(
                    icon = Icons.Outlined.Link,
                    text = stringResource(R.string.chat_list_group_soon_onchain)
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
                            text = stringResource(R.string.chat_list_group_soon_note),
                            fontSize = 13.sp,
                            color = chatColors().textSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_list_group_soon_got_it))
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
        title = { Text(stringResource(R.string.chat_list_status_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.chat_list_status_hint),
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = currentStatus,
                    onValueChange = { if (it.length <= 100) onStatusChange(it) },
                    label = { Text(stringResource(R.string.chat_list_status_label)) },
                    placeholder = { Text(stringResource(R.string.chat_list_status_placeholder)) },
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
                    text = stringResource(R.string.chat_list_status_quick),
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
                Text(stringResource(R.string.chat_list_status_save))
            }
        },
        dismissButton = {
            Row {
                if (currentStatus.isNotBlank()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.chat_list_status_clear), color = chatColors().error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_list_dialog_cancel))
                }
            }
        }
    )
}

@Composable
private fun SyncStatusBar(
    line: SyncLineState,
    syncedAt: Instant?,
    secondsUntilNextSync: Int,
    isRefreshing: Boolean,
    onRefreshClick: () -> Unit,
    blockHeight: Long?,
    zecPriceUsd: Double?,
    onDestroyClick: () -> Unit = {}
) {
    val colors = chatColors()
    val borderColor = colors.borderDefault
    // ZC-1266: "Catching up N%" explains itself on a tap.
    var showCatchingUpExplanation by remember { mutableStateOf(false) }
    if (showCatchingUpExplanation) {
        AlertDialog(
            onDismissRequest = { showCatchingUpExplanation = false },
            title = { Text(stringResource(R.string.chat_list_sync_catching_up_title)) },
            text = { Text(stringResource(R.string.chat_list_sync_catching_up_explained)) },
            confirmButton = {
                TextButton(onClick = { showCatchingUpExplanation = false }) {
                    Text(stringResource(R.string.ai_notice_ok))
                }
            },
        )
    }
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
                contentDescription = stringResource(R.string.chat_list_cd_refresh),
                tint = if (isRefreshing) colors.primary.copy(alpha = 0.4f) else colors.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(enabled = SyncLine.explainsOnTap(line)) { showCatchingUpExplanation = true }
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.chat_list_status_syncing),
                    fontSize = 11.sp,
                    color = colors.primary
                )
            } else {
                // Synced: "HH:mm:ss · 45s · Synced · $42.15", the time being the wallet's last full sync in this
                // process. SY-2: otherwise "Catching up N% · $42.15", "Offline", "Sync stopped" or "Connecting…".
                val statusParts = mutableListOf<String>()
                when (line) {
                    SyncLineState.Synced -> {
                        syncedAt?.let { statusParts.add(formatSyncTime(it)) }
                        statusParts.add("${secondsUntilNextSync}s")
                        statusParts.add(stringResource(R.string.chat_list_status_synced))
                    }
                    is SyncLineState.CatchingUp ->
                        statusParts.add(stringResource(R.string.chat_list_sync_line_catching_up, line.percent))
                    SyncLineState.Offline -> statusParts.add(stringResource(R.string.chat_list_sync_line_offline))
                    SyncLineState.Stopped -> statusParts.add(stringResource(R.string.chat_list_sync_line_stopped))
                    SyncLineState.Connecting ->
                        statusParts.add(stringResource(R.string.chat_list_sync_line_connecting))
                }
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
                    contentDescription = stringResource(R.string.chat_list_cd_destroy_all),
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
                        text = stringResource(R.string.chat_backup_banner_title),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = RajdhaniFontFamily,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = stringResource(R.string.chat_backup_banner_body),
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
                    text = stringResource(R.string.chat_backup_banner_button),
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
                        text = pluralStringResource(R.plurals.chat_list_message_requests, count, count),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = RajdhaniFontFamily,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = stringResource(R.string.chat_list_message_requests_hint),
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
                    text = stringResource(R.string.chat_list_message_requests_review),
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
    modifier: Modifier = Modifier,
    // Bridge 2: the catch-up bar after an upgrade (COPY_DECK CB1/CB2, EN + ES).
    catchUpPercent: Int? = null
) {
    val colors = chatColors()
    val isCatchUp = catchUpPercent != null
    val isRestoring = syncStatus.isRestoring && !isCatchUp
    val isInitiating = syncStatus.isInitiating && !isCatchUp
    val isRestoringOrInitiating = isRestoring || isInitiating
    val progress = catchUpPercent?.toFloat() ?: syncStatus.progress

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
                Row(
                    modifier = if (isCatchUp) Modifier.weight(1f) else Modifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Animated sync icon
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = if (isRestoringOrInitiating) colors.primary else colors.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            catchUpPercent != null ->
                                stringResource(R.string.upgrade_catch_up_title, catchUpPercent)
                            isInitiating -> stringResource(R.string.chat_list_sync_setting_up)
                            isRestoring -> stringResource(R.string.chat_list_sync_restoring)
                            else -> stringResource(R.string.chat_list_sync_syncing)
                        },
                        modifier = Modifier.weight(1f, fill = false),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRestoringOrInitiating) colors.primary else colors.secondary
                    )
                }
                // Big percentage (the catch-up title already says it)
                if (!isCatchUp) {
                    Text(
                        text = "${progress.toInt()}%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRestoringOrInitiating) colors.primary else colors.secondary
                    )
                }
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
            if (isCatchUp) {
                Text(
                    text = stringResource(R.string.upgrade_catch_up_subtitle),
                    fontSize = 15.sp,
                    color = colors.textSecondary
                )
            } else if (syncStatus.statusMessage.isNotEmpty()) {
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
                        // QA2 Q2-106: the theme's warning colour (a fixed amber read about 2:1 on light themes).
                        tint = chatColors().warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text =
                            if (isInitiating) {
                                stringResource(R.string.chat_list_sync_keep_open_setup)
                            } else {
                                stringResource(R.string.chat_list_sync_keep_open_older)
                            },
                        fontSize = 12.sp,
                        color = chatColors().warning
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
            title = { Text(stringResource(R.string.chat_list_privacy_title)) },
            text = { Text(stringResource(R.string.chat_list_privacy_body)) },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(stringResource(R.string.chat_list_privacy_got_it))
                }
            }
        )
    }

    // OF-1: centred when it fits (min height = the viewport), scrolls when the banners and a large font cut it.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(chatColors().background)
    ) {
        val viewportHeight = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_lock_shield),
                contentDescription = stringResource(R.string.chat_list_empty_cd_privacy),
                modifier = Modifier.size(80.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.chat_list_empty_title),
                style =
                    TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                    ),
                color = chatColors().textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ES-A: one sentence with one link slot (%1$s), so each language keeps its own word order.
            val sentence = stringResource(R.string.chat_list_empty_body)
            val linkWord = stringResource(R.string.chat_list_empty_body_link)
            val slot = "%1\$s"
            val before = if (slot in sentence) sentence.substringBefore(slot) else "$sentence "
            val after = if (slot in sentence) sentence.substringAfter(slot) else ""
            val annotatedText =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = chatColors().textSecondary, fontSize = 15.sp)) {
                        append(before)
                    }
                    withLink(
                        LinkAnnotation.Clickable("privacy_link") {
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
                            append(linkWord)
                        }
                    }
                    withStyle(SpanStyle(color = chatColors().textSecondary, fontSize = 15.sp)) {
                        append(after)
                    }
                }
            Text(text = annotatedText)

            Spacer(modifier = Modifier.height(24.dp))
            co.electriccoin.zcash.ui.screen.chat.view.components.ZChatButton(
                text = stringResource(R.string.chat_list_empty_start),
                onClick = onNewChatClick,
            )
        }
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
        // OF-1: room below the last row so it scrolls clear of the centred New Chat FAB.
        contentPadding = PaddingValues(top = 8.dp, bottom = ChatListLayout.FAB_CLEARANCE)
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
 * ZC-1104 (W-79): one Groups row on top (ChatListLayout.rows), its groups inline only when open, then the chats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsAndGroupsList(
    conversations: List<Conversation>,
    groups: List<GroupInfo>,
    groupsAwaitingJoin: Set<String>,
    groupUnread: Map<String, Int>,
    groupLastMessageMs: Map<String, Long>,
    groupsExpanded: Boolean,
    onToggleGroupsFolder: () -> Unit,
    onConversationClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onEditContact: (String) -> Unit,
    getContact: (String) -> Contact?,
    modifier: Modifier = Modifier
) {
    val rows = ChatListLayout.rows(conversations, groups, groupUnread, groupsExpanded)
    // The "Chats" header separates the open groups from the chats; a folded Groups row needs none.
    val chatsHeaderBefore =
        rows.indexOfFirst { it is ChatListRow.Conversation }.takeIf { it > 0 && rows[it - 1] is ChatListRow.Group }
    // ZC-1140: the keys in the order the LazyColumn below gives them.
    val rowKeys: List<Any> =
        buildList {
            rows.forEachIndexed { index, row ->
                if (index == chatsHeaderBefore) add(CHATS_HEADER_KEY)
                add(
                    when (row) {
                        is ChatListRow.GroupsFolder -> GROUPS_FOLDER_KEY
                        is ChatListRow.Group -> "group_${row.group.groupId}"
                        is ChatListRow.Conversation -> row.conversation.peerAddress
                    }
                )
            }
        }
    val listState = rememberLazyListState()
    // ZC-1140: the keyed list keeps its first visible row by key, so rows inserted above it (the live pass's Groups
    // row over the kept pass's first chat) stay out of view. Read before the new rows are laid out: the previous keys
    // and whether the list was at the top; scroll back to the top when rows appeared above the first one.
    val previousRowKeys = remember { arrayOfNulls<List<Any>>(1) }
    val lastKeys = previousRowKeys[0]
    if (lastKeys != null && lastKeys != rowKeys) {
        val (firstIndex, firstOffset) =
            Snapshot.withoutReadObservation {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
        val wasAtTop = firstIndex == 0 && firstOffset == 0
        if (ChatListLayout.shouldScrollToTop(lastKeys.getOrNull(firstIndex), wasAtTop, rowKeys)) {
            listState.requestScrollToItem(0)
        }
    }
    SideEffect { previousRowKeys[0] = rowKeys }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        // OF-1: room below the last row so it scrolls clear of the centred New Chat FAB.
        contentPadding = PaddingValues(top = 8.dp, bottom = ChatListLayout.FAB_CLEARANCE)
    ) {
        rows.forEachIndexed { index, row ->
            if (index == chatsHeaderBefore) {
                item(key = CHATS_HEADER_KEY) {
                    co.electriccoin.zcash.ui.screen.chat.view.components.SectionHeader(
                        title = stringResource(R.string.chat_list_section_chats)
                    )
                }
            }
            when (row) {
                is ChatListRow.GroupsFolder ->
                    item(key = GROUPS_FOLDER_KEY) {
                        GroupsFolderRow(
                            count = row.count,
                            unread = row.unread,
                            expanded = groupsExpanded,
                            onToggle = onToggleGroupsFolder
                        )
                    }
                is ChatListRow.Group -> {
                    val group = row.group
                    item(key = "group_${group.groupId}") {
                        GroupItem(
                            group = group,
                            awaitingJoin = group.groupId in groupsAwaitingJoin,
                            unread = groupUnread[group.groupId] ?: 0,
                            lastMessageMs = groupLastMessageMs[group.groupId],
                            onClick = { onGroupClick(group.groupId) },
                            onDeleteGroup = { onDeleteGroup(group.groupId) }
                        )
                    }
                }
                is ChatListRow.Conversation -> {
                    val conversation = row.conversation
                    item(key = conversation.peerAddress) {
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
        }
    }
}

// ZC-1104: the keys of the Groups row and the "Chats" header; Int, so never equal to a chat's or a group's String key.
private const val GROUPS_FOLDER_KEY = 1
private const val CHATS_HEADER_KEY = 2

/**
 * ZC-1104 (W-79): the compact Groups row: the group icon, "Groups", the number of groups, the unread badge (none at 0)
 * and the arrow that opens or folds the groups below it. Read out as "N groups, M unread" with the action named.
 */
@Composable
private fun GroupsFolderRow(
    count: Int,
    unread: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = pluralStringResource(R.plurals.chat_list_groups_folder_description, count, count, unread)
    val action =
        stringResource(
            if (expanded) R.string.chat_list_groups_folder_collapse else R.string.chat_list_groups_folder_expand
        )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClickLabel = action, onClick = onToggle)
                .background(chatColors().background)
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Groups,
            contentDescription = null,
            tint = chatColors().primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.chat_list_section_groups),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = chatColors().textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = count.toString(),
            fontSize = 14.sp,
            color = chatColors().textSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.weight(1f))
        if (unread > 0) {
            UnreadBadge(count = unread)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            // The row already names its action (onClickLabel); the arrow adds nothing to read out.
            contentDescription = null,
            tint = chatColors().textSecondary
        )
    }
}

/** ZC-946 part 2: true while the list shows the kept pass (StateOrigin.CACHED): rows offer no delete and no menu. */
private val LocalCachedChatList = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Single group item in the chat list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupItem(
    group: GroupInfo,
    awaitingJoin: Boolean,
    // ZC-1104: the group's unread messages (GroupUnread); no badge at 0.
    unread: Int,
    // ZC-1227: the newest message's time; null (no message) shows the creation time.
    lastMessageMs: Long?,
    onClick: () -> Unit,
    onDeleteGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    // Round 8 D4: an invite from a new address waits for Join; nothing was sent for it.
    val subtitle = if (awaitingJoin) R.string.group_invite_request_list_subtitle else R.string.chat_list_group_chat

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
                        text = rememberChatListTimestamp(lastMessageMs?.let { Instant.ofEpochMilli(it) } ?: group.createdAt),
                        color = chatColors().textTertiary,
                        fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(subtitle),
                        fontSize = 14.sp,
                        color = if (awaitingJoin) chatColors().warning else chatColors().textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            if (unread > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                UnreadBadge(count = unread)
            }
            }

            // Long-press dropdown menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_leave)) },
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
        enableDismissFromEndToStart = !LocalCachedChatList.current,
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
                            contentDescription = stringResource(R.string.chat_list_row_cd_delete),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.chat_list_row_swipe_delete),
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
    // Round 8 P10 (ES-7): the old-Team label in the phone's language; the stored name is not changed.
    val displayName = contactDisplayName(conversation.contactName ?: contact?.name ?: conversation.displayName)

    val colors = chatColors()
    val hasPayment = conversation.lastMessage?.isPaymentRequest == true
    val cachedList = LocalCachedChatList.current
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
                    onLongClick = { if (!cachedList) showMenu = true }
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
                                contentDescription = stringResource(R.string.chat_list_row_cd_muted),
                                modifier = Modifier.size(14.dp),
                                tint = chatColors().textTertiary
                            )
                        }
                        // W-72 (SPEC_F R8): this chat's key carries a pre-update mark ("Not confirmed" in the chat).
                        if (conversation.keyNotConfirmed) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.chat_legacy_key_list_cd),
                                modifier = Modifier.size(14.dp),
                                tint = chatColors().warning
                            )
                        }
                    }
                    conversation.lastMessage?.let { msg ->
                        Text(
                            text = rememberChatListTimestamp(msg.timestamp),
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
                            text = stringResource(R.string.chat_list_row_draft_prefix) + " ",
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
                    // ZC-1356: a payment says here what its bubble says.
                    val paymentLine = PaymentBubble.of(msg, msg.paymentAmountZatoshi)
                    val previewText =
                        if (msg.bodyChecking) {
                            stringResource(R.string.chat_cached_checking)
                        } else if (paymentLine != null) {
                            val paidAmount = zecDisplay(Zatoshi(paymentLine.amountZatoshi), rememberDesiredFormatLocale())
                            val line =
                                if (paymentLine.isSent) {
                                    stringResource(R.string.chat_payment_bubble_sent, paidAmount)
                                } else {
                                    stringResource(R.string.chat_payment_bubble_received, paidAmount)
                                }
                            listOfNotNull(line, paymentLine.memo).joinToString(" · ").take(100)
                        } else if (msg.callLog != null) {
                            // ZC-1577: a call previews as its pill does, in the phone's language.
                            callLogLine(msg.callLog, separator = " ")
                        } else {
                            msg.forDisplay().displayText.take(100)
                        }
                    Text(
                        text =
                            if (msg.isOutgoing) {
                                stringResource(R.string.chat_list_you_prefix, previewText)
                            } else {
                                previewText
                            },
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
                text = { Text(stringResource(R.string.chat_list_row_menu_delete_chat)) },
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
                    text = { Text(stringResource(R.string.chat_list_row_menu_edit_contact)) },
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
                    text = { Text(stringResource(R.string.chat_list_row_menu_add_contact)) },
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

/**
 * ES-A: the row time in the language the app shows ("Ayer", "3 sept"). The month-day order comes from the phone
 * (getBestDateTimePattern with the "MMMd" skeleton, so en-US reads "Sep 3" and es-ES "3 sept"); the pure
 * chatListTimestamp (ChatListCopy.kt) does the rest. Calendar days are counted in the phone's zone, as before.
 */
@Composable
private fun rememberChatListTimestamp(timestamp: Instant): String {
    val locale = rememberDesiredFormatLocale()
    val monthDay = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMd") }
    val yesterday = stringResource(R.string.chat_list_yesterday)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return chatListTimestamp(timestamp, ZonedDateTime.now(), locale, yesterday, monthDay, is24Hour)
}
