package co.electriccoin.zcash.ui.screen.chat.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus
import co.electriccoin.zcash.ui.screen.chat.model.ChatDetailState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MemoTemplate
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaymentDialogState
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.PoolType
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockInfo
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockType
import co.electriccoin.zcash.ui.screen.chat.model.UnknownReason
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ZCHAT Brand Colors - fallback defaults; use chatColors() for theme-awareness
private val ZchatCyan = Color(0xFF00D9FF)
private val ZchatGreen = Color(0xFF00E676)
private val ZchatNavy = Color(0xFF0D1B2A)
private val ZchatNavyLight = Color(0xFF1B2838)
private val ZchatTeal = Color(0xFF00838F)

// Note: chatColors() function is now defined in ChatThemeColors.kt and shared across all chat views

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailView(
    state: ChatDetailState,
    onBackClick: () -> Unit,
    onSendMessage: (message: String, amountZatoshi: Long) -> Boolean,
    onSendReply: (message: String, replyToId: String, replyPreview: String, amountZatoshi: Long) -> Boolean =
        { msg, _, _, amt -> onSendMessage(msg, amt) },
    isKeyChanged: Boolean = false,
    onDismissKeyChanged: () -> Unit = {},
    showRotationReminder: Boolean = false,
    onRotateKeyCta: () -> Unit = {},
    onDismissRotationReminder: () -> Unit = {},
    safetyNumber: String? = null,
    isVerified: Boolean = false,
    onMarkVerified: () -> Unit = {},
    quantumShieldStatus: String = "NONE", // "NONE", "PENDING", "ACTIVE"
    onInitiateQuantumShield: () -> Unit = {},
    onResetQuantumShield: () -> Unit = {},
    onSendImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendViewOnceImage: () -> Unit = {},
    onMarkFileViewed: (fileHash: String) -> Unit = {},
    // Voice messages
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    isRecordingViewOnce: Boolean = false,
    onMicTap: () -> Unit = {},
    onMicLongPress: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    // Per-chat conversation mode (Vault / Tunnel / Open)
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode =
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
    onPickConversationMode: () -> Unit = {},
    onPlaceCall: () -> Unit = {},
    onPlaceVideoCall: () -> Unit = {},
    uploadProgress: Float? = null,
    fileDownloadProgress: Map<String, Float> = emptyMap(),
    fileDownloadFailures: Set<String> = emptySet(),
    onRetryDownload: (zfileContent: String, peerAddress: String) -> Unit = { _, _ -> },
    onDeleteMessage: (String) -> Unit,
    onRetryMessage: (messageId: String) -> Unit = { },
    onSendPayment: (amountZec: Double, memo: String) -> Unit,
    onSendReaction: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onSendReadReceipt: (messageId: String) -> Unit = { },
    // Time-lock callbacks
    onSendScheduledMessage: (message: String, unlockTimestamp: Long) -> Unit = { _, _ -> },
    onSendBlockLockedMessage: (message: String, unlockHeight: Long) -> Unit = { _, _ -> },
    onSendPaymentLockedMessage: (message: String, requiredZatoshi: Long) -> Unit = { _, _ -> },
    onSendConditionalMessage: (message: String, answer: String, hint: String) -> Unit = { _, _, _ -> },
    onUnlockPaymentMessage: (txId: String, senderAddress: String, amount: Long) -> Unit = { _, _, _ -> },
    onUnlockConditionalMessage: (txId: String, senderAddress: String, answer: String, answerHash: String) -> Boolean = { _, _, _, _ -> false },
    // Payment request callbacks
    onSendPaymentRequest: (amountZatoshi: Long, reason: String) -> Unit = { _, _ -> },
    onFulfillPaymentRequest: (amountZatoshi: Long, requestId: String) -> Unit = { _, _ -> },
    // Nickname callback
    onNicknameChange: (address: String, nickname: String) -> Unit = { _, _ -> },
    currentBlockHeight: Long? = null,
    // Draft callback
    onDraftChange: (String) -> Unit = { },
    // E2E encryption callback
    onE2EToggle: (Boolean) -> Unit = { },
    // Mute callback
    onMuteToggle: () -> Unit = { },
    // Welcome ZEC suggestion
    onSendWelcomeZec: (() -> Unit)? = null,
    showWelcomeZecSuggestion: Boolean = false,
    modifier: Modifier = Modifier
) {
    // SECURITY (privacy): the open conversation is the most sensitive screen (plaintext messages,
    // attachments, view-once media) — block screenshots / screen-recording / app-switcher thumbnail
    // while foregrounded.
    if (shouldSecureScreen) {
        SecureScreen()
    }
    when (state) {
        is ChatDetailState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is ChatDetailState.Success -> {
            ChatDetailContent(
                conversation = state.conversation,
                balance = state.balance,
                zecPriceUsd = state.zecPriceUsd,
                privacyStatus = state.privacyStatus,
                isKeyChanged = isKeyChanged,
                onDismissKeyChanged = onDismissKeyChanged,
                showRotationReminder = showRotationReminder,
                onRotateKeyCta = onRotateKeyCta,
                onDismissRotationReminder = onDismissRotationReminder,
                safetyNumber = safetyNumber,
                isVerified = isVerified,
                onMarkVerified = onMarkVerified,
                quantumShieldStatus = quantumShieldStatus,
                onInitiateQuantumShield = onInitiateQuantumShield,
                onResetQuantumShield = onResetQuantumShield,
                onSendImage = onSendImage,
                onTakePhoto = onTakePhoto,
                onSendFile = onSendFile,
                onSendViewOnceImage = onSendViewOnceImage,
                onMarkFileViewed = onMarkFileViewed,
                isRecording = isRecording,
                recordingSeconds = recordingSeconds,
                isRecordingViewOnce = isRecordingViewOnce,
                onMicTap = onMicTap,
                onMicLongPress = onMicLongPress,
                onSendRecording = onSendRecording,
                onCancelRecording = onCancelRecording,
                conversationMode = conversationMode,
                onPickConversationMode = onPickConversationMode,
                onPlaceCall = onPlaceCall,
                onPlaceVideoCall = onPlaceVideoCall,
                uploadProgress = uploadProgress,
                fileDownloadProgress = fileDownloadProgress,
                fileDownloadFailures = fileDownloadFailures,
                onRetryDownload = onRetryDownload,
                onBackClick = onBackClick,
                onSendMessage = { msg, amt -> onSendMessage(msg, amt) },
                onSendReply = { msg, replyToId, replyPreview, amt -> onSendReply(msg, replyToId, replyPreview, amt) },
                onDeleteMessage = onDeleteMessage,
                onRetryMessage = onRetryMessage,
                onSendPayment = onSendPayment,
                onSendReaction = onSendReaction,
                onSendReadReceipt = onSendReadReceipt,
                onSendScheduledMessage = onSendScheduledMessage,
                onSendBlockLockedMessage = onSendBlockLockedMessage,
                onSendPaymentLockedMessage = onSendPaymentLockedMessage,
                onSendConditionalMessage = onSendConditionalMessage,
                onSendPaymentRequest = onSendPaymentRequest,
                onFulfillPaymentRequest = onFulfillPaymentRequest,
                onNicknameChange = onNicknameChange,
                currentBlockHeight = currentBlockHeight,
                onDraftChange = onDraftChange,
                onE2EToggle = onE2EToggle,
                onMuteToggle = onMuteToggle,
                onSendWelcomeZec = onSendWelcomeZec,
                showWelcomeZecSuggestion = showWelcomeZecSuggestion,
                modifier = modifier
            )
        }
        is ChatDetailState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailContent(
    conversation: Conversation,
    balance: Zatoshi,
    zecPriceUsd: Double?,
    privacyStatus: PrivacyStatus,
    isKeyChanged: Boolean = false,
    onDismissKeyChanged: () -> Unit = {},
    showRotationReminder: Boolean = false,
    onRotateKeyCta: () -> Unit = {},
    onDismissRotationReminder: () -> Unit = {},
    safetyNumber: String? = null,
    isVerified: Boolean = false,
    onMarkVerified: () -> Unit = {},
    quantumShieldStatus: String = "NONE",
    onInitiateQuantumShield: () -> Unit = {},
    onResetQuantumShield: () -> Unit = {},
    onSendImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendViewOnceImage: () -> Unit = {},
    onMarkFileViewed: (fileHash: String) -> Unit = {},
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    isRecordingViewOnce: Boolean = false,
    onMicTap: () -> Unit = {},
    onMicLongPress: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode =
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
    onPickConversationMode: () -> Unit = {},
    onPlaceCall: () -> Unit = {},
    onPlaceVideoCall: () -> Unit = {},
    uploadProgress: Float? = null,
    fileDownloadProgress: Map<String, Float> = emptyMap(),
    fileDownloadFailures: Set<String> = emptySet(),
    onRetryDownload: (zfileContent: String, peerAddress: String) -> Unit = { _, _ -> },
    onBackClick: () -> Unit,
    onSendMessage: (message: String, amountZatoshi: Long) -> Boolean,
    onSendReply: (message: String, replyToId: String, replyPreview: String, amountZatoshi: Long) -> Boolean,
    onDeleteMessage: (String) -> Unit,
    onRetryMessage: (messageId: String) -> Unit,
    onSendPayment: (amountZec: Double, memo: String) -> Unit,
    onSendReaction: (messageId: String, emoji: String) -> Unit,
    onSendReadReceipt: (messageId: String) -> Unit,
    // Time-lock callbacks
    onSendScheduledMessage: (message: String, unlockTimestamp: Long) -> Unit,
    onSendBlockLockedMessage: (message: String, unlockHeight: Long) -> Unit,
    onSendPaymentLockedMessage: (message: String, requiredZatoshi: Long) -> Unit,
    onSendConditionalMessage: (message: String, answer: String, hint: String) -> Unit,
    // Payment request callbacks
    onSendPaymentRequest: (amountZatoshi: Long, reason: String) -> Unit,
    onFulfillPaymentRequest: (amountZatoshi: Long, requestId: String) -> Unit,
    // Nickname callback
    onNicknameChange: (address: String, nickname: String) -> Unit,
    currentBlockHeight: Long?,
    // Draft callback
    onDraftChange: (String) -> Unit,
    // E2E encryption callback
    onE2EToggle: (Boolean) -> Unit,
    // Mute callback
    onMuteToggle: () -> Unit,
    // Welcome ZEC suggestion
    onSendWelcomeZec: (() -> Unit)? = null,
    showWelcomeZecSuggestion: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

    // Live NOSTR outbound readiness. A TUNNEL send only goes free over NOSTR when the publisher is
    // registered (isOutboundReady); during the cold-launch window the peer pubkey may already be known
    // (hasNostrCallChannel) while the publisher isn't, and a send then falls back to a CHARGED on-chain
    // memo. Gating the "Free" label on this prevents a false "Free — sent over NOSTR" that actually spends.
    val nostrOutboundReady by co.electriccoin.zcash.ui.nostr.NostrChatBridge.outboundReady.collectAsState()

    // Initialize with draft if available
    var messageText by remember { mutableStateOf(conversation.draft ?: "") }
    val listState = rememberLazyListState()
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showTimeLockDialog by remember { mutableStateOf(false) }
    var showPaymentRequestDialog by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<MemoTemplate?>(null) }
    var showAmountPicker by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameText by remember { mutableStateOf(conversation.contactName ?: "") }
    var showPrivacyStatus by remember { mutableStateOf(false) }
    var showSafetyNumberDialog by remember { mutableStateOf(false) }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

    // Clipboard and context for copy functionality
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Message amount state (in zatoshi)
    // Default amount options: 1000, 5000, 10000, 50000, 100000 zatoshi
    val amountOptions = listOf(1000L, 5000L, 10000L, 50000L, 100000L)
    var selectedAmountIndex by remember { mutableIntStateOf(0) }
    val selectedAmount = amountOptions[selectedAmountIndex]

    // Reply state
    var replyToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // Back gesture clears an active reply first (otherwise the X on the ReplyPreview is the only,
    // less-discoverable, way out). Only intercepts back while a reply is being composed.
    BackHandler(enabled = replyToMessage != null) {
        replyToMessage = null
    }

    // Auto-save draft with debounce (500ms delay)
    LaunchedEffect(messageText) {
        if (messageText != (conversation.draft ?: "")) {
            kotlinx.coroutines.delay(500L)
            onDraftChange(messageText)
        }
    }
    // Flush the draft synchronously when leaving the screen so the last keystroke inside the 500ms
    // debounce window isn't lost on back-navigate / fast background.
    val latestDraft = rememberUpdatedState(messageText)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDraft.value != (conversation.draft ?: "")) {
                onDraftChange(latestDraft.value)
            }
        }
    }

    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Overflow ("⋮") menu for secondary top-bar actions, to keep the action row uncrowded.
    var showTopBarMenu by remember { mutableStateOf(false) }

    // Normalize any message still carrying a raw protocol payload (raw "ZFILE|…"/"ZBOOT|…") so it
    // renders as the rich file bubble / friendly note instead of leaking the raw string to either side.
    val normalizedMessages = remember(conversation.messages) { conversation.messages.map { it.forDisplay() } }

    // Filter messages based on search
    val filteredMessages = remember(normalizedMessages, searchQuery) {
        if (searchQuery.isBlank()) {
            normalizedMessages
        } else {
            normalizedMessages.filter {
                it.text.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val displayMessages = remember(filteredMessages) { filteredMessages.asReversed() }
    // Pre-compute message lookup map for O(1) reply-to resolution instead of O(n) linear search
    val messageById = remember(normalizedMessages) { normalizedMessages.associateBy { it.id } }

    // Check if peer address is a valid Zcash address
    // Unified addresses start with "u1" and are 200+ chars, Sapling starts with "zs" and is 78+ chars
    val isValidAddress = (conversation.peerAddress.startsWith("u1") && conversation.peerAddress.length > 100) ||
            (conversation.peerAddress.startsWith("zs") && conversation.peerAddress.length > 70)

    // Scroll to bottom when new messages arrive
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search messages...") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            }
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    // Tap to edit nickname
                                    nicknameText = conversation.contactName ?: ""
                                    showNicknameDialog = true
                                },
                                onLongClick = {
                                    // Long press to copy address
                                    clipboardManager.setText(AnnotatedString(conversation.peerAddress))
                                    Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                                }
                            )
                        ) {
                            val avatarAccent = avatarColorForAddress(conversation.peerAddress)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(avatarAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                // Show contact name initials if available, otherwise address prefix
                                val initials = if (conversation.hasContactName) {
                                    conversation.contactName.orEmpty().split(" ")
                                        .take(2)
                                        .map { it.firstOrNull()?.uppercaseChar() ?: '?' }
                                        .joinToString("")
                                } else {
                                    conversation.peerAddress.take(2).uppercase()
                                }
                                Text(
                                    text = initials,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = avatarAccent
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conversation.displayName,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Direction-A: "● shielded" status subline — every ZCHAT chat is
                                // end-to-end encrypted/shielded. Green dot + mono "shielded" + the
                                // peer address (if named) or the set-nickname hint.
                                val subText = if (conversation.hasContactName) {
                                    Conversation.truncateAddress(conversation.peerAddress)
                                } else {
                                    "Tap to set nickname"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(colors.success)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "shielded",
                                        fontSize = 11.sp,
                                        color = colors.success,
                                        fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    // weight(1f) so the address ellipsizes and the fixed "shielded"
                                    // label never gets clipped in the icon-crowded header.
                                    Text(
                                        text = " · $subText",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isSearching) "Close search" else "Back"
                        )
                    }
                },
                actions = {
                    if (!isSearching) {
                        // End-to-end encryption toggle. Three distinct states, each with its own
                        // glyph + label (an enabled-but-not-yet-ready session must not look identical
                        // to "off"), plus a Toast on tap so the user gets feedback for this otherwise
                        // silent, security-relevant control.
                        // While E2E is enabled but the key exchange hasn't completed (LockClock /
                        // "pending") the toggle is locked: each tap would re-fire sendKEXMessage()
                        // and race the in-flight handshake. Re-enabled once ready (or back to off).
                        val e2ePending = conversation.e2eEnabled && !conversation.isE2EReady
                        IconButton(
                            enabled = !e2ePending,
                            onClick = {
                                val now = !conversation.e2eEnabled
                                onE2EToggle(now)
                                Toast.makeText(
                                    context,
                                    if (now) "End-to-end encryption on" else "End-to-end encryption off",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        ) {
                            Icon(
                                imageVector = when {
                                    conversation.isE2EReady -> Icons.Default.Lock
                                    conversation.e2eEnabled -> Icons.Default.LockClock
                                    else -> Icons.Default.LockOpen
                                },
                                contentDescription = when {
                                    conversation.isE2EReady -> "End-to-end encrypted"
                                    conversation.e2eEnabled -> "End-to-end encryption pending key exchange"
                                    else -> "End-to-end encryption off"
                                },
                                tint = when {
                                    conversation.isE2EReady -> chatColors().primary
                                    conversation.e2eEnabled -> chatColors().warning
                                    else -> chatColors().textTertiary
                                }
                            )
                        }
                        // (Safety-number verification moved into the overflow menu below.)
                        // Search — de-emphasized (neutral tint) so the state-colored E2E lock and
                        // the call buttons stand out instead of competing in the same accent cyan.
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search messages",
                                tint = chatColors().textSecondary,
                            )
                        }
                        // Voice/video call. A call routes over the peer's NOSTR identity on the free
                        // relay (startCall → placeCall(peerNostrPubkey)) — it NEVER spends on-chain — so
                        // it's placeable whenever we hold that pubkey (hasNostrCallChannel), EVEN in a
                        // VAULT message conversation. We therefore offer the buttons when either the
                        // message mode is call-capable (Tunnel/Open, which will bootstrap on tap) OR a
                        // NOSTR call channel already exists. Gating on the message mode alone wrongly hid
                        // calls on an already-established VAULT chat where the free relay was right there.
                        //
                        // callsReady (= channel exists) drives only the TINT, not the tap: bright when a
                        // call connects now, muted as a "not ready yet" cue otherwise. On a muted tap we
                        // STILL invoke the place-call path — startCall detects the missing peer key, kicks
                        // the ZBOOT/KEX handshake, and shows an informative toast. So we never swallow the
                        // tap; the call path owns both the not-ready UX and the handshake kick.
                        if (conversationMode.supportsCalls || conversation.hasNostrCallChannel) {
                            val callsReady = conversation.hasNostrCallChannel
                            val callTint = if (callsReady) chatColors().primary else chatColors().textTertiary
                            IconButton(onClick = { onPlaceCall() }) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = if (callsReady) "Voice call" else "Voice call (exchanges secure keys, then connects)",
                                    tint = callTint,
                                )
                            }
                            IconButton(onClick = { onPlaceVideoCall() }) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = if (callsReady) "Video call" else "Video call (exchanges secure keys, then connects)",
                                    tint = callTint,
                                )
                            }
                        }
                        // Overflow menu: secondary/occasional actions (mute, verify safety number,
                        // conversation-mode picker) moved off the cramped row. The mode-picker lock
                        // glyph no longer sits next to the E2E lock, removing the two-locks confusion.
                        IconButton(onClick = { showTopBarMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More actions",
                                tint = chatColors().textSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = showTopBarMenu,
                            onDismissRequest = { showTopBarMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (conversation.isMuted) "Unmute" else "Mute") },
                                onClick = { showTopBarMenu = false; onMuteToggle() },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (conversation.isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = if (conversation.isMuted) chatColors().error else chatColors().textSecondary,
                                    )
                                },
                            )
                            if (safetyNumber != null && conversation.isE2EReady) {
                                DropdownMenuItem(
                                    text = { Text("Verify safety number") },
                                    onClick = { showTopBarMenu = false; showSafetyNumberDialog = true },
                                    leadingIcon = {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = chatColors().primary)
                                    },
                                )
                            }
                            // Extra Security (Post-Quantum) entry point. Moved out of the always-on
                            // body banner (which used to stack a second shield glyph under the header)
                            // and into this menu. Only offered when E2E is ready and PQ is not yet on;
                            // the ACTIVE/PENDING states still surface their own compact banner below.
                            if (conversation.isE2EReady &&
                                runCatching { QuantumShieldStatus.valueOf(quantumShieldStatus) }
                                    .getOrDefault(QuantumShieldStatus.NONE) == QuantumShieldStatus.NONE) {
                                DropdownMenuItem(
                                    text = { Text("Enable Extra Security (Post-Quantum)") },
                                    onClick = { showTopBarMenu = false; onInitiateQuantumShield() },
                                    leadingIcon = {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = chatColors().textSecondary)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (conversationMode) {
                                            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT -> "Conversation mode: Vault"
                                            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL -> "Conversation mode: Tunnel"
                                            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN -> "Conversation mode: Open"
                                        }
                                    )
                                },
                                onClick = { showTopBarMenu = false; onPickConversationMode() },
                                leadingIcon = {
                                    val ic = when (conversationMode) {
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT -> Icons.Default.Shield
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL -> Icons.Default.Lock
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN -> Icons.Default.LockOpen
                                    }
                                    val tint = if (conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN) chatColors().textSecondary else chatColors().primary
                                    Icon(ic, contentDescription = null, tint = tint)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.primary,
                    actionIconContentColor = colors.primary
                )
            )
        },
        bottomBar = {
            Column {
                // Image upload progress indicator
                if (uploadProgress != null) {
                    ImageUploadProgressBar(progress = uploadProgress)
                }
                // Template picker row (shown when templates button is tapped)
                if (showTemplates) {
                    TemplatePickerRow(
                        templates = MemoTemplate.BUILT_IN_TEMPLATES,
                        zecPriceUsd = zecPriceUsd,
                        onTemplateSelected = { template ->
                            selectedTemplate = template
                            showTemplates = false
                            showPaymentDialog = true
                        },
                        onDismiss = { showTemplates = false }
                    )
                }
                // Reply preview
                if (replyToMessage != null) {
                    ReplyPreview(
                        message = replyToMessage!!,
                        onDismiss = { replyToMessage = null }
                    )
                }
                MessageInput(
                    value = messageText,
                    onValueChange = { messageText = it },
                    onSend = {
                        if (messageText.isNotBlank() && isValidAddress) {
                            // Clear the input only if the send was ACCEPTED. A pre-queue rejection
                            // (key changed / funds not yet shielded / another send in flight) returns
                            // false and we KEEP the typed text (B1-msg-lost-on-blocked-send).
                            val accepted = if (replyToMessage != null) {
                                // Pass the tapped message's displayText as the authoritative quote preview
                                // (displayText keeps locked-message plaintext out). This is what fixes both
                                // sender echo and receiver from rendering a reply with no quote.
                                onSendReply(
                                    messageText,
                                    replyToMessage!!.id,
                                    replyToMessage!!.displayText.take(50),
                                    selectedAmount,
                                )
                            } else {
                                onSendMessage(messageText, selectedAmount)
                            }
                            if (accepted) {
                                replyToMessage = null
                                messageText = ""
                            }
                        }
                    },
                    onPayClick = { showPaymentDialog = true },
                    onTemplatesClick = { showTemplates = !showTemplates },
                    onLockClick = { showTimeLockDialog = true },
                    onRequestClick = { showPaymentRequestDialog = true },
                    onSendImage = onSendImage,
                    onTakePhoto = onTakePhoto,
                    onSendFile = onSendFile,
                    onSendViewOnceImage = onSendViewOnceImage,
                    onAmountClick = { showAmountPicker = true },
                    selectedAmount = selectedAmount,
                    conversationMode = conversationMode,
                    // A TUNNEL chat only spends ZEC for the one-time on-chain ZBOOT handshake; once the
                    // peer's NOSTR pubkey is known (hasNostrCallChannel) AND the NOSTR publisher is ready
                    // (nostrOutboundReady) every further send is free over NOSTR. BOTH are required:
                    // the actual send routing (handleNostrRouteIfApplicable) only goes free when
                    // isOutboundReady(), else it falls back to a charged on-chain memo — so the label
                    // must not claim "Free" until outbound is live, or it would mislead during the
                    // cold-launch window and a send there would silently spend ZEC.
                    tunnelSendIsFree = conversation.hasNostrCallChannel && nostrOutboundReady,
                    isEnabled = isValidAddress,
                    disabledMessage = if (!isValidAddress) "Cannot reply - sender address unknown" else null,
                    isRecording = isRecording,
                    recordingSeconds = recordingSeconds,
                    isRecordingViewOnce = isRecordingViewOnce,
                    onMicTap = onMicTap,
                    onMicLongPress = onMicLongPress,
                    onSendRecording = onSendRecording,
                    onCancelRecording = onCancelRecording,
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Show explanation banner for unknown senders
            if (!isValidAddress) {
                UnknownSenderBanner(
                    reason = conversation.messages.firstOrNull()?.unknownReason
                )
            }

            // Handshake-waiting banner: on a call-capable transport (Tunnel/Open) the call channel
            // only exists once the peer's NOSTR pubkey is known (hasNostrCallChannel) — i.e. after the
            // contact replies. Match the call BUTTON's "ready" signal exactly so a muted button and the
            // banner agree. Once the channel exists (including a VAULT chat that already established one)
            // calls are ready, so the banner hides. Surface it so a locked call doesn't read as a broken
            // app (Fable 5 feedback).
            if (isValidAddress && conversationMode.supportsCalls && !conversation.hasNostrCallChannel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(chatColors().warning.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.LockClock,
                        contentDescription = null,
                        tint = chatColors().warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Waiting for your contact to reply — calls unlock after their first message.",
                        fontSize = 12.sp,
                        color = chatColors().warning,
                    )
                }
            }

            // Key-Changed Warning Banner
            if (isKeyChanged) {
                androidx.compose.animation.AnimatedVisibility(visible = true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFF2D78).copy(alpha = 0.15f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Key changed",
                            tint = Color(0xFFFF2D78),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Security key changed — verify with your contact",
                            color = Color(0xFFFF2D78),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissKeyChanged) {
                            Text("OK", color = Color(0xFFFF2D78), fontSize = 13.sp)
                        }
                    }
                }
            }

            // Unverified-contact banner: with open-inbox TOFU first-contact, a new peer is trusted on
            // first use but NOT cryptographically proven. Surface that clearly (the main mitigation for
            // inbound spoofing) with a one-tap path to compare the safety number. Hidden once verified,
            // and suppressed while the louder key-changed banner is showing.
            if (conversation.isE2EReady && !isVerified && !isKeyChanged && safetyNumber != null) {
                androidx.compose.animation.AnimatedVisibility(visible = true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFB800).copy(alpha = 0.12f))
                            .clickable { showSafetyNumberDialog = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFFFB800),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unverified contact — tap to check the safety number and confirm it's really them.",
                            color = Color(0xFFFFB800),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Key-rotation reminder (#178 Part B). Shown at most once/week in NOSTR chats.
            if (showRotationReminder) {
                androidx.compose.animation.AnimatedVisibility(visible = true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(chatColors().primary.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = chatColors().primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Rotate your key for stronger privacy. Refreshing it regularly limits " +
                                "what an old key could ever expose.",
                            color = chatColors().primary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRotateKeyCta) {
                            Text("Rotate", color = chatColors().primary, fontSize = 13.sp)
                        }
                        TextButton(onClick = onDismissRotationReminder) {
                            Text("Later", color = chatColors().primary, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Extra Security (Post-Quantum) status banner.
            // User-facing name is "Extra Security (Post-Quantum)"; internal status enum
            // remains QuantumShieldStatus. Parse the String status into the enum so we can
            // reuse its plain-language displayLabel().
            val extraSecurityStatus = remember(quantumShieldStatus) {
                runCatching { QuantumShieldStatus.valueOf(quantumShieldStatus) }
                    .getOrDefault(QuantumShieldStatus.NONE)
            }
            when (extraSecurityStatus) {
                QuantumShieldStatus.ACTIVE -> {
                    var showResetDialog by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF7C4DFF).copy(alpha = 0.12f))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showResetDialog = true },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF7C4DFF),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Extra Security (Post-Quantum): ${extraSecurityStatus.displayLabel()}",
                                color = Color(0xFF7C4DFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Adds a post-quantum key on top of E2E encryption for this chat.",
                                color = Color(0xFF7C4DFF).copy(alpha = 0.75f),
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "long-press to turn off",
                            color = Color(0xFF7C4DFF).copy(alpha = 0.6f),
                            fontSize = 10.sp,
                        )
                    }
                    if (showResetDialog) {
                        AlertDialog(
                            onDismissRequest = { showResetDialog = false },
                            title = { Text("Turn off Extra Security?", color = Color(0xFFE8EDF5)) },
                            text = {
                                Text(
                                    "This turns off the extra post-quantum key and returns to standard end-to-end " +
                                        "encryption. You can turn it back on later.",
                                    color = Color(0xFFE8EDF5),
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResetDialog = false
                                    onResetQuantumShield()
                                }) {
                                    Text("Turn off", color = Color(0xFFFF3344))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetDialog = false }) {
                                    Text("Cancel", color = Color(0xFF00E5FF))
                                }
                            },
                            containerColor = Color(0xFF0D1117),
                        )
                    }
                }
                QuantumShieldStatus.PENDING -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFB800).copy(alpha = 0.10f))
                            .clickable(onClick = onInitiateQuantumShield)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFFFFB800),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Extra Security (Post-Quantum): ${extraSecurityStatus.displayLabel()}",
                                color = Color(0xFFFFB800),
                                fontSize = 13.sp,
                            )
                            Text(
                                text = "Adds a post-quantum key on top of E2E encryption for this chat.",
                                color = Color(0xFFFFB800).copy(alpha = 0.75f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                QuantumShieldStatus.NONE -> {
                    // OFF: no banner here — this state previously rendered an always-on second
                    // shield row that cluttered the top of every chat. The 'Enable Extra Security'
                    // entry point now lives in the top-bar overflow menu (shown only when E2E is
                    // ready). When E2E is not yet ready there is nothing to offer, so render nothing.
                }
            }

            // Privacy Status Card (collapsible)
            PrivacyStatusCard(
                privacyStatus = privacyStatus,
                conversationMode = conversationMode,
                isExpanded = showPrivacyStatus,
                onToggle = { showPrivacyStatus = !showPrivacyStatus },
                // For NOSTR conversations the on-chain pool/anonymity-set panel is irrelevant
                // (messages are off-chain NIP-17 DMs). Route the tap to E2E safety-number
                // verification instead, when a safety number is available.
                onTapNostr = {
                    if (safetyNumber != null) {
                        showSafetyNumberDialog = true
                    } else {
                        // No safety number yet (handshake not complete) — avoid a dead tap by
                        // expanding the privacy panel so the user still gets feedback + context.
                        showPrivacyStatus = !showPrivacyStatus
                    }
                }
            )

            // Search results count
            if (isSearching && searchQuery.isNotBlank()) {
                Text(
                    text = "${filteredMessages.size} results found",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true
            ) {
                // Empty conversation — show a friendly start state instead of a blank screen (notably
                // right after first contact, before the first on-chain message confirms).
                if (displayMessages.isEmpty() && !(showWelcomeZecSuggestion && onSendWelcomeZec != null)) {
                    item(key = "empty_state") {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // When the empty area is the result of a 0-match search, say so
                            // explicitly — the generic "No messages yet" implies the chat is empty.
                            val searchingEmpty = isSearching && searchQuery.isNotBlank()
                            Icon(
                                imageVector = if (searchingEmpty) Icons.Default.Search else Icons.Default.Lock,
                                contentDescription = if (searchingEmpty) "No search results" else "End-to-end encrypted",
                                tint = chatColors().textSecondary,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchingEmpty) "No messages match your search" else "No messages yet",
                                color = chatColors().textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                            )
                            if (!searchingEmpty) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Say hello — messages here are end-to-end encrypted.",
                                    color = chatColors().textSecondary,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                items(displayMessages.size, key = { displayMessages[it].id }) { index ->
                    val message = displayMessages[index]
                    // Date separator: compare with previous message (next in list since reversed)
                    if (index < displayMessages.size - 1) {
                        val nextMsg = displayMessages[index + 1]
                        val msgDate = message.timestamp
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        val nextDate = nextMsg.timestamp
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        if (msgDate != nextDate) {
                            DateSeparator(msgDate, currentBlockHeight)
                        }
                    } else if (displayMessages.size > 1) {
                        // First message in conversation — show its date
                        val msgDate = message.timestamp
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        DateSeparator(msgDate, currentBlockHeight)
                    }
                    // Call-log entries render as a centered pill, not a sender bubble.
                    if (message.isCallLog) {
                        CallLogPill(message.callLog!!)
                        return@items
                    }
                    // System notes (e.g. "contact rotated their key") render as a centered info pill (#225).
                    if (message.isSystemNote) {
                        SystemNotePill(message.text)
                        return@items
                    }
                    val fileProgress = message.fileHash?.let { fileDownloadProgress[it] }
                    val fileDownloadFailed = message.fileHash?.let { it in fileDownloadFailures } ?: false
                    val isOutgoingInFlight = message.isOutgoing && message.isPending &&
                        message.fileHash != null && uploadProgress != null
                    MessageBubble(
                        message = message,
                        messageById = messageById,
                        zecPriceUsd = zecPriceUsd,
                        onDeleteMessage = onDeleteMessage,
                        onReplyClick = { replyToMessage = message },
                        onReactionClick = { emoji -> onSendReaction(message.id, emoji) },
                        onPayRequest = { amountZatoshi, requestId ->
                            onFulfillPaymentRequest(amountZatoshi, requestId)
                        },
                        onImageClick = { path -> fullscreenImagePath = path },
                        onMarkFileViewed = onMarkFileViewed,
                        onRetryMessage = onRetryMessage,
                        // Receiver-side download failed: bubble shows a tap-to-retry affordance that
                        // re-fetches using the message's own serialized ZFILE + peer address.
                        downloadFailed = fileDownloadFailed,
                        onRetryDownload = {
                            message.fileZfileContent?.let { onRetryDownload(it, message.peerAddress) }
                        },
                        highlightSearch = searchQuery.takeIf { it.isNotBlank() },
                        // Sender bubble shows uploadProgress; receiver bubble shows download fraction.
                        bubbleProgress = if (isOutgoingInFlight) uploadProgress else fileProgress,
                    )
                }

                // Welcome ZEC suggestion — shown at the top (last in reversed list)
                if (showWelcomeZecSuggestion && onSendWelcomeZec != null) {
                    item(key = "welcome_zec") {
                        WelcomeZecCard(onSend = onSendWelcomeZec)
                    }
                }
            }
        }
    }

    // Payment Dialog
    if (showPaymentDialog) {
        PaymentDialog(
            balance = balance,
            zecPriceUsd = zecPriceUsd,
            recipientName = conversation.displayName,
            prefilledTemplate = selectedTemplate,
            onDismiss = {
                showPaymentDialog = false
                selectedTemplate = null
            },
            onSendPayment = { amount, memo ->
                onSendPayment(amount, memo)
                showPaymentDialog = false
                selectedTemplate = null
            }
        )
    }

    // Safety Number Verification Dialog
    // Fullscreen image viewer
    if (fullscreenImagePath != null) {
        val path = fullscreenImagePath!!
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullscreenImagePath = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            val fhash = remember(path) { java.io.File(path).name }
            var bitmap by remember(path) {
                mutableStateOf<android.graphics.Bitmap?>(null)
            }
            // Re-decode when the download state for this file changes (progress ticks/clears on
            // completion, or the failure flag flips) — otherwise a retry that succeeds while the
            // fullscreen viewer is open would never repaint the image (the effect was keyed only on
            // the invariant path).
            androidx.compose.runtime.LaunchedEffect(path, fileDownloadProgress[fhash], fhash in fileDownloadFailures) {
                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { decodeSampledBitmap(path, reqPx = 2048) }.getOrNull()
                }
            }
            androidx.compose.runtime.DisposableEffect(path) {
                onDispose {
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap = null
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullscreenImagePath = null },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Fullscreen image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Cache miss OR decode failure: show a progress + status so the user
                    // doesn't see a silent black screen and assume the app froze.
                    val pathFile = remember(path) { java.io.File(path) }
                    val exists = pathFile.exists()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Resolve the message backing this fullscreen path so we can detect a failed
                        // download (by fileHash) and offer retry instead of a perpetual spinner.
                        val fullscreenMsg = remember(path, fileDownloadFailures) {
                            conversation.messages
                                .firstOrNull { it.fileHash != null && pathFile.name == it.fileHash }
                        }
                        val fullscreenFailed = fullscreenMsg?.fileHash?.let { it in fileDownloadFailures } ?: false
                        // Retry needs the serialized ZFILE to re-fetch. Older messages keep the
                        // fileHash but not the ZFILE payload — for those a retry button would be a
                        // dead tap, so show an explanatory line instead of a fake affordance.
                        val fullscreenZfile = fullscreenMsg?.fileZfileContent
                        if (!exists && fullscreenFailed && fullscreenZfile != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onRetryDownload(fullscreenZfile, fullscreenMsg.peerAddress)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry download",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Download failed — tap to retry",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                            }
                        } else if (!exists && fullscreenFailed) {
                            Text(
                                text = "Download failed — message data missing, cannot retry",
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        } else if (!exists) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = chatColors().primary
                            )
                            Text(
                                text = "Downloading…",
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                        } else {
                            Text(
                                text = "Cannot preview this file",
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { fullscreenImagePath = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }

    if (showSafetyNumberDialog && safetyNumber != null) {
        AlertDialog(
            onDismissRequest = { showSafetyNumberDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = chatColors().primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Safety Number", color = chatColors().textPrimary)
                }
            },
            text = {
                Column {
                    // #190: surface a CHANGED key right where the user re-verifies. A key change on an
                    // established contact is a MITM/rotation signal that invalidates the safety number
                    // they previously compared — so the verify dialog (not just the inline banner) must
                    // tell them the old number is stale and to re-compare the NEW one before trusting.
                    if (isKeyChanged) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = chatColors().error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "This contact's security key CHANGED. Your previous verification is no longer valid — re-compare this new number with them before trusting it.",
                                color = chatColors().error,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(
                        text = "Compare this number with your contact. If they match, your conversation is secure.",
                        color = chatColors().textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // Display safety number in groups of 4
                    Text(
                        text = safetyNumber.chunked(4).joinToString(" "),
                        color = chatColors().primary,
                        fontSize = 18.sp,
                        fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF101828),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "If the numbers don't match, someone may be intercepting your messages.",
                        color = chatColors().error.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                    )
                    if (isVerified) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "✓ You marked this contact as verified.",
                            color = chatColors().primary,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyNumberDialog = false }) {
                    Text("Done", color = chatColors().primary)
                }
            },
            dismissButton = {
                // Only offer to mark as verified once; a key change clears the flag and the
                // option reappears. Marking is the user's out-of-band confirmation, not a
                // wire-protocol change, so it is fully backward-compatible.
                if (!isVerified) {
                    TextButton(onClick = {
                        onMarkVerified()
                        showSafetyNumberDialog = false
                    }) {
                        Text("Mark as verified", color = chatColors().primary)
                    }
                }
            },
            containerColor = Color(0xFF0D1117),
        )
    }

    // Nickname Edit Dialog
    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text("Edit Contact Name") },
            text = {
                Column {
                    Text(
                        text = "Set a nickname for this contact:",
                        fontSize = 15.sp,
                        color = chatColors().textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        // Cap nickname length so a pathological input can't break list/header layouts.
                        onValueChange = { if (it.length <= 40) nicknameText = it },
                        placeholder = { Text("Enter nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = Conversation.truncateAddress(conversation.peerAddress),
                        fontSize = 13.sp,
                        color = chatColors().textSecondary.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    // Save is always enabled: saving a BLANK field intentionally REMOVES the nickname
                    // (ZchatPreferences.setNickname treats blank as delete — see nickname_clearBySettingBlank
                    // test). Disabling on blank would remove the only way to clear a nickname.
                    onClick = {
                        onNicknameChange(conversation.peerAddress, nicknameText.trim())
                        showNicknameDialog = false
                    }
                ) {
                    Text(if (nicknameText.isBlank()) "Remove" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    nicknameText = conversation.contactName ?: ""
                    showNicknameDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Amount Picker Dialog
    if (showAmountPicker) {
        AlertDialog(
            onDismissRequest = { showAmountPicker = false },
            title = { Text("Message Amount") },
            text = {
                Column {
                    Text(
                        text = "Select the amount to send with each message:",
                        fontSize = 15.sp,
                        color = chatColors().textSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    amountOptions.forEachIndexed { index, amount ->
                        val zecAmount = amount / 100_000_000.0
                        val isSelected = index == selectedAmountIndex
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    selectedAmountIndex = index
                                    showAmountPicker = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    chatColors().primary.copy(alpha = 0.15f)
                                else chatColors().bgElevated
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format("%.5f ZEC", zecAmount),
                                    fontSize = 17.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "$amount zatoshi",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAmountPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Time-Lock Composer Dialog
    if (showTimeLockDialog) {
        TimeLockComposerDialog(
            currentBlockHeight = currentBlockHeight,
            onDismiss = { showTimeLockDialog = false },
            onSendScheduledMessage = { message, timestamp ->
                onSendScheduledMessage(message, timestamp)
                showTimeLockDialog = false
            },
            onSendBlockLockedMessage = { message, height ->
                onSendBlockLockedMessage(message, height)
                showTimeLockDialog = false
            },
            onSendPaymentLockedMessage = { message, zatoshi ->
                onSendPaymentLockedMessage(message, zatoshi)
                showTimeLockDialog = false
            },
            onSendConditionalMessage = { message, answer, hint ->
                onSendConditionalMessage(message, answer, hint)
                showTimeLockDialog = false
            }
        )
    }

    // Payment Request Composer Dialog
    if (showPaymentRequestDialog) {
        PaymentRequestComposerDialog(
            zecPriceUsd = zecPriceUsd,
            onDismiss = { showPaymentRequestDialog = false },
            onSendRequest = { amountZatoshi, reason ->
                onSendPaymentRequest(amountZatoshi, reason)
                showPaymentRequestDialog = false
            }
        )
    }
}

// Common reaction emojis
private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    messageById: Map<String, ChatMessage>,
    zecPriceUsd: Double? = null,
    onDeleteMessage: (String) -> Unit,
    onReplyClick: () -> Unit,
    onReactionClick: (emoji: String) -> Unit,
    onPayRequest: (amountZatoshi: Long, requestId: String) -> Unit = { _, _ -> },
    onImageClick: (imagePath: String) -> Unit = {},
    onMarkFileViewed: (fileHash: String) -> Unit = {},
    onRetryMessage: (messageId: String) -> Unit = {},
    downloadFailed: Boolean = false,
    onRetryDownload: () -> Unit = {},
    highlightSearch: String? = null,
    bubbleProgress: Float? = null,
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

    // Clipboard for the "Copy" affordance — non-deprecated Compose API. We copy the human-readable
    // displayText, never the raw ZMSG/ZFILE/ZBOOT memo.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val copyContext = LocalContext.current

    val isOutgoing = message.isOutgoing
    val isPending = message.isPending
    var showMenu by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Find quoted message if this is a reply (O(1) map lookup instead of O(n) linear search)
    val quotedMessage = message.replyToId?.let { replyId ->
        messageById[replyId]
    }

    // Theme-aware bubble colors
    val bubbleColor = when {
        isOutgoing && isPending -> colors.outgoingBubble.copy(alpha = 0.6f) // Lighter for pending
        isOutgoing -> colors.outgoingBubble
        else -> colors.incomingBubble
    }

    // Bubble foreground: dark themes use textPrimary/textSecondary (readable on dark bubbles).
    // In LIGHT theme the OUTGOING bubble is teal (BubbleSent ~#006B78) — dark textPrimary (~3:1)
    // and textSecondary (~1.26:1, invisible) fail there — so use white (textOnAccent) for
    // outgoing-light text + a subtle-white timestamp. Incoming + all dark cases unchanged.
    val cc = chatColors()
    val onLightSentBubble = isOutgoing && cc.isLight
    val textColor = if (onLightSentBubble) cc.textOnAccent else cc.textPrimary
    val timeColor = if (onLightSentBubble) cc.textOnAccent.copy(alpha = 0.8f) else cc.textSecondary

    // Check if we're in Deep Cyber mode by checking if background is near-black
    val isZypherpunkMode = colors.background == chatColors().background

    Column(modifier = modifier) {

        Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .combinedClickable(
                            onClick = { },
                            onLongClick = { showMenu = true }
                        )
                        .then(
                            if (isZypherpunkMode) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = if (isOutgoing) chatColors().bubbleSentBorder
                                    else chatColors().bubbleReceivedBorder,
                                    shape = RoundedCornerShape(
                                        topStart = if (isOutgoing) 20.dp else 4.dp,
                                        topEnd = if (isOutgoing) 20.dp else 20.dp,
                                        bottomStart = if (isOutgoing) 20.dp else 20.dp,
                                        bottomEnd = if (isOutgoing) 4.dp else 20.dp
                                    )
                                )
                            } else Modifier
                        ),
                    shape = RoundedCornerShape(
                        topStart = if (isZypherpunkMode) (if (isOutgoing) 20.dp else 4.dp) else 16.dp,
                        topEnd = if (isZypherpunkMode) 20.dp else 16.dp,
                        bottomStart = if (isZypherpunkMode) 20.dp else (if (isOutgoing) 16.dp else 4.dp),
                        bottomEnd = if (isZypherpunkMode) (if (isOutgoing) 4.dp else 20.dp) else (if (isOutgoing) 4.dp else 16.dp)
                    ),
                    colors = CardDefaults.cardColors(containerColor = bubbleColor)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        // Quoted message preview (if this is a reply). Guard on non-blank so a reply whose
                        // preview couldn't be resolved doesn't render an empty quote bar (use isNotBlank,
                        // not != null — the old default was "" which is non-null and drew a blank box).
                        val quoteText = quotedMessage?.displayText?.takeIf { it.isNotBlank() }
                            ?: message.replyToPreview?.takeIf { it.isNotBlank() }
                        if (quoteText != null) {
                            QuotedMessagePreview(
                                previewText = quoteText,
                                isOutgoing = isOutgoing
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Time-locked message display
                        if (message.isLocked && message.timeLock != null) {
                            LockedMessageContent(
                                timeLock = message.timeLock,
                                isOutgoing = isOutgoing
                            )
                        } else if (message.isPaymentRequest && message.paymentRequest != null) {
                            // Payment request display
                            PaymentRequestContent(
                                paymentRequest = message.paymentRequest,
                                zecPriceUsd = zecPriceUsd,
                                isOutgoing = isOutgoing,
                                onPayClick = {
                                    onPayRequest(message.paymentRequest.amountZatoshi, message.id)
                                }
                            )
                        } else if (message.fileHash != null && message.fileViewOnce && message.fileViewed) {
                            // View-once already consumed — render the locked placeholder so the
                            // user can see that something WAS there without being able to recover it.
                            ViewOnceConsumedPlaceholder(
                                isAudio = message.fileType == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A,
                                isOutgoing = isOutgoing,
                            )
                        } else if (message.fileHash != null && message.fileViewOnce && !message.fileViewed) {
                            // View-once not yet consumed — sealed bubble. Tap reveals the media,
                            // then marks the file viewed (wipes cache, flips bubble to the
                            // "consumed" placeholder above on next render).
                            ViewOnceRevealBubble(
                                message = message,
                                bubbleProgress = bubbleProgress,
                                isOutgoing = isOutgoing,
                                onImageClick = onImageClick,
                                onMarkViewed = { onMarkFileViewed(message.fileHash) },
                                downloadFailed = downloadFailed,
                                onRetryDownload = onRetryDownload,
                            )
                        } else if (message.fileHash != null && message.fileType == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A) {
                            // Voice message bubble — play/pause + duration + transfer progress.
                            VoiceMessageBubble(
                                message = message,
                                bubbleProgress = bubbleProgress,
                                isOutgoing = isOutgoing,
                            )
                        } else if (message.fileHash != null) {
                            // File message — try to render cached decrypted image
                            val context = LocalContext.current
                            val cacheFile = remember(message.fileHash) {
                                java.io.File(context.cacheDir, "zchat_files/${message.fileHash}")
                            }
                            // Stack the upload/download progress bar above the image preview
                            // when a transfer is in flight. The bar is hidden once the bubble
                            // settles to the decoded image.
                            val activeProgress = bubbleProgress
                            if (activeProgress != null && activeProgress < 1f) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (message.isOutgoing) "Uploading…" else "Downloading…",
                                            fontSize = 11.sp,
                                            color = chatColors().textSecondary,
                                        )
                                        Text(
                                            text = "${(activeProgress * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            color = chatColors().primary,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { activeProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = chatColors().primary,
                                        trackColor = chatColors().bgInput,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                            // Decode bitmap off the main thread to avoid UI jank
                            var bitmap by remember(message.fileHash) {
                                mutableStateOf<android.graphics.Bitmap?>(null)
                            }
                            androidx.compose.runtime.LaunchedEffect(message.fileHash) {
                                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (cacheFile.exists()) {
                                        runCatching {
                                            decodeSampledBitmap(cacheFile.absolutePath, reqPx = 800)
                                        }.getOrNull()
                                    } else {
                                        null
                                    }
                                }
                            }
                            androidx.compose.runtime.DisposableEffect(message.fileHash) {
                                onDispose {
                                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                                    bitmap = null
                                }
                            }
                            val bmp = bitmap
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Shared image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        // Use combinedClickable so a long-press routes to the
                                        // bubble menu (Save / Share / Forward) instead of being
                                        // swallowed by the tap-to-fullscreen handler.
                                        .combinedClickable(
                                            onClick = { onImageClick(cacheFile.absolutePath) },
                                            onLongClick = { showMenu = true },
                                        ),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                // Cache miss — render blurhash placeholder if available. Decode
                                // off the composition thread (was blocking main with N decodes for
                                // long lists). Empty blurhash short-circuits without scheduling.
                                val blurhashKey = message.fileBlurhash.takeIf { !it.isNullOrEmpty() }
                                var blurhashBitmap by remember(blurhashKey) {
                                    mutableStateOf<android.graphics.Bitmap?>(null)
                                }
                                androidx.compose.runtime.LaunchedEffect(blurhashKey) {
                                    blurhashBitmap = blurhashKey?.let { hash ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                            runCatching {
                                                val pixels = co.electriccoin.zcash.ui.screen.chat.filesharing
                                                    .BlurhashDecoder.decode(hash, 32, 32)
                                                if (pixels != null) {
                                                    android.graphics.Bitmap.createBitmap(
                                                        pixels, 32, 32,
                                                        android.graphics.Bitmap.Config.ARGB_8888
                                                    )
                                                } else null
                                            }.getOrNull()
                                        }
                                    }
                                }
                                androidx.compose.runtime.DisposableEffect(blurhashKey) {
                                    onDispose {
                                        blurhashBitmap?.takeIf { !it.isRecycled }?.recycle()
                                        blurhashBitmap = null
                                    }
                                }
                                val blurBmp = blurhashBitmap
                                if (blurBmp != null) {
                                    // Placeholder tap forwards to the same fullscreen-opener path
                                    // as the loaded-image branch — the dialog itself decodes the
                                    // cache file or shows a download spinner if it's not there yet.
                                    Box(modifier = Modifier.clickable { onImageClick(cacheFile.absolutePath) }) {
                                        androidx.compose.foundation.Image(
                                            bitmap = blurBmp.asImageBitmap(),
                                            contentDescription = "Loading image",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Text(
                                            text = message.text,
                                            fontSize = 13.sp,
                                            color = Color.White,
                                            // Cap the caption so a long one can't overflow the 200dp
                                            // image bounds on small screens.
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .padding(8.dp)
                                                .fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                        )
                                        // Image ZFILEs almost always carry a blurhash, so the
                                        // download-failed branch below would never show. Overlay a
                                        // tap-to-retry chip on the blur preview instead. Retry needs
                                        // the serialized ZFILE — if it's missing (old messages with
                                        // only a fileHash), show a non-interactive note, not a dead tap.
                                        if (downloadFailed) {
                                            val canRetry = message.fileZfileContent != null
                                            Row(
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.Black.copy(alpha = 0.6f))
                                                    .then(
                                                        if (canRetry) Modifier.clickable { onRetryDownload() }
                                                        else Modifier
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (canRetry) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "Retry download",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = if (canRetry) "Tap to retry" else "Data missing",
                                                    fontSize = 13.sp,
                                                    color = Color.White,
                                                )
                                            }
                                        }
                                    }
                                } else if (downloadFailed) {
                                    // Download failed and nothing is cached: offer an explicit retry
                                    // instead of a dead placeholder. Mirrors the failed-send affordance.
                                    // Retry needs the serialized ZFILE; if it's missing, say so rather
                                    // than render a button that silently does nothing.
                                    val canRetry = message.fileZfileContent != null
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .then(
                                                if (canRetry) Modifier.clickable { onRetryDownload() }
                                                else Modifier
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (canRetry) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Retry download",
                                                tint = chatColors().error,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = if (canRetry) "Download failed — tap to retry"
                                            else "Download failed — message data missing",
                                            fontSize = 13.sp,
                                            color = chatColors().error,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = message.text,
                                        fontSize = 15.sp,
                                        color = chatColors().primary,
                                        modifier = Modifier.clickable { onImageClick(cacheFile.absolutePath) }
                                    )
                                }
                            }
                        } else if (message.text.startsWith("\uD83D\uDCCE ")) {
                            // File message without cached image — show placeholder
                            Text(
                                text = message.text,
                                fontSize = 15.sp,
                                color = chatColors().primary
                            )
                        } else {
                            // Message text with optional search highlighting
                            if (highlightSearch != null && message.text.contains(highlightSearch, ignoreCase = true)) {
                                HighlightedText(
                                    text = message.text,
                                    highlight = highlightSearch,
                                    textColor = textColor
                                )
                            } else {
                                Text(
                                    text = message.text,
                                    fontSize = 15.sp,
                                    color = textColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatMessageTime(message.timestamp),
                                fontSize = 11.sp,
                                color = timeColor
                            )
                            // Show status indicator for outgoing messages
                            if (isOutgoing) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageStatusIndicator(
                                    status = message.effectiveStatus,
                                    isOutgoing = true
                                )
                            }
                        }
                    }
                }
            }

            // Long-press dropdown menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = chatColors().bgElevated
            ) {
                // Reply option
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        showMenu = false
                        onReplyClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null
                        )
                    }
                )
                // React option
                DropdownMenuItem(
                    text = { Text("React") },
                    onClick = {
                        showMenu = false
                        showReactionPicker = true
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.EmojiEmotions,
                            contentDescription = null
                        )
                    }
                )
                // Copy option — available on every bubble (sent and received). Copies the decoded
                // displayText so the clipboard never holds a raw protocol memo. File/locked bubbles
                // have no plain text to copy, so the item is hidden for them.
                val copyText = message.displayText
                val isCopyable = message.fileHash == null && !message.isLocked &&
                    !message.isPaymentRequest && copyText.isNotBlank()
                if (isCopyable) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            showMenu = false
                            clipboardManager.setText(AnnotatedString(copyText))
                            Toast.makeText(copyContext, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null
                            )
                        }
                    )
                }
                // Save / Forward / Share — only for file bubbles whose bytes are still on
                // disk. We skip view-once (the bytes are gone) and skip messages whose
                // cache hasn't been downloaded yet.
                val context = LocalContext.current
                val fileHash = message.fileHash
                if (fileHash != null && !message.fileViewOnce) {
                    val cacheFile = remember(fileHash) {
                        java.io.File(context.cacheDir, "zchat_files/$fileHash")
                    }
                    if (cacheFile.exists()) {
                        DropdownMenuItem(
                            text = { Text("Save") },
                            onClick = {
                                showMenu = false
                                saveFileToDownloads(context, cacheFile, message.fileType)
                            },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                showMenu = false
                                shareFile(context, cacheFile, message.fileType)
                            },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Forward") },
                            onClick = {
                                showMenu = false
                                // No in-app chat picker yet — Android Share is the cleanest
                                // way to forward the bytes elsewhere without losing privacy
                                // (the encrypted blob has already been published, so reusing
                                // its URL is fine; the wrappedKey is per-conversation though,
                                // so we share the decrypted bytes instead of the ZFILE memo).
                                shareFile(context, cacheFile, message.fileType, asForward = true)
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        )
                    }
                }
                // Delete option
                DropdownMenuItem(
                    text = { Text("Delete Message") },
                    onClick = {
                        showMenu = false
                        showDeleteConfirm = true
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

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete message?") },
                    text = {
                        Text(
                            "This hides the message from your chat. The transaction remains on the " +
                                "Zcash blockchain and will reappear if you restore from seed.",
                            fontSize = 13.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                onDeleteMessage(message.id)
                            }
                        ) {
                            Text("Delete", color = chatColors().error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            // Reaction picker
            DropdownMenu(
                expanded = showReactionPicker,
                onDismissRequest = { showReactionPicker = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            // 44dp tappable area (was ~32dp — below the comfortable touch-target size).
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    showReactionPicker = false
                                    onReactionClick(emoji)
                                }
                                .wrapContentSize(Alignment.Center)
                        )
                    }
                }
            }
        }

        // Pending/queued send affordance (Bug 8b): a message waiting for the previous tx's change
        // notes to confirm on-chain. Shows a slim progress bar + coarse ETA. Outgoing-only; a
        // genuine "in flight" file transfer already renders its own bar inside the bubble, so we
        // skip those (bubbleProgress != null) to avoid a double indicator.
        if (isOutgoing && message.effectiveStatus == MessageStatus.SENDING && bubbleProgress == null) {
            // A NOSTR/TUNNEL outgoing message is delivered instantly over a relay — it does NOT wait
            // for a Zcash block, so it must NOT show the on-chain ~75s block-time ETA / countdown.
            // Two cases: "nostr-out-…" = published over NOSTR; "tunnel-wait-…" = queued, waiting for
            // the secure connection (handshake) before it flushes over NOSTR. Both are off-chain and
            // free. Only on-chain pending sends (id "pending_…") get the block-based estimate.
            val isTunnelWaiting = message.txId == null && message.id.startsWith("tunnel-wait-")
            val isNostrPending = message.txId == null &&
                (message.id.startsWith("nostr-out-") || isTunnelWaiting)
            val queuedAtMillis = message.timestamp.toEpochMilli()
            // Recompute roughly once a second so the label/bar advance while waiting.
            var nowMillis by remember(message.id) { mutableStateOf(System.currentTimeMillis()) }
            androidx.compose.runtime.LaunchedEffect(message.id) {
                while (true) {
                    nowMillis = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1000L)
                }
            }
            val elapsedSeconds = ((nowMillis - queuedAtMillis) / 1000L).coerceAtLeast(0L)
            // NOSTR sends are instant → indeterminate spinner, no block-ETA progress fraction.
            val progress = if (isNostrPending) {
                null
            } else {
                co.electriccoin.zcash.ui.screen.chat.model.PendingSendEstimate
                    .progressFor(elapsedSeconds)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, end = 4.dp)
                    .widthIn(max = 220.dp)
            ) {
                Text(
                    text = when {
                        isTunnelWaiting -> "Waiting for secure connection…"
                        isNostrPending -> "Sending…"
                        else ->
                            co.electriccoin.zcash.ui.screen.chat.model.PendingSendEstimate
                                .label(elapsedSeconds)
                    },
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = chatColors().primary,
                        trackColor = chatColors().bgInput,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = chatColors().primary,
                        trackColor = chatColors().bgInput,
                    )
                }
            }
        }

        // Retry affordance (Bug 8b): a FAILED outgoing send can be re-queued with one tap.
        if (isOutgoing && message.effectiveStatus == MessageStatus.FAILED) {
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, end = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRetryMessage(message.id) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry sending",
                    tint = chatColors().error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Retry",
                    fontSize = 12.sp,
                    color = chatColors().error,
                )
            }
        }

        // Reactions display
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(start = if (isOutgoing) 0.dp else 8.dp, end = if (isOutgoing) 8.dp else 0.dp)
                    .align(if (isOutgoing) Alignment.End else Alignment.Start),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Group reactions by emoji
                val groupedReactions = message.reactions.groupBy { it.emoji }
                groupedReactions.forEach { (emoji, reactions) ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = chatColors().bgElevated
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = emoji, fontSize = 14.sp)
                            if (reactions.size > 1) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${reactions.size}",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sealed view-once bubble. Tap to reveal the underlying image or play the audio. Consumption is
 * immediate: an image fires [onMarkViewed] as soon as it renders, audio as soon as playback starts
 * — [onMarkViewed] wipes the local cache and flips the bubble into the consumed state. A reveal
 * the user never "finishes" is still burned.
 */
@Composable
private fun ViewOnceRevealBubble(
    message: co.electriccoin.zcash.ui.screen.chat.model.ChatMessage,
    bubbleProgress: Float?,
    isOutgoing: Boolean,
    onImageClick: (imagePath: String) -> Unit,
    onMarkViewed: () -> Unit,
    downloadFailed: Boolean = false,
    onRetryDownload: () -> Unit = {},
) {
    val context = LocalContext.current
    val cacheFile = remember(message.fileHash) {
        java.io.File(context.cacheDir, "zchat_files/${message.fileHash}")
    }
    var revealed by remember(message.fileHash) { mutableStateOf(false) }
    val isAudio = message.fileType == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A
    val ready = cacheFile.exists()

    // Transfer progress bar (image bubble pattern).
    Column(modifier = Modifier.fillMaxWidth()) {
        if (bubbleProgress != null && bubbleProgress < 1f) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isOutgoing) "Uploading…" else "Downloading…",
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
                Text(
                    text = "${(bubbleProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = chatColors().primary,
                )
            }
            LinearProgressIndicator(
                progress = { bubbleProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = chatColors().primary,
                trackColor = chatColors().bgInput,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (!revealed) {
            // A failed download must NOT keep the bubble stuck on "Downloading…": when the file
            // isn't cached and the fetch failed, the row becomes a tap-to-retry affordance instead
            // (mirrors the regular file bubble). Tapping reveal stays disabled until the file lands.
            val sealedFailed = !ready && downloadFailed
            // Sealed placeholder — tap to reveal (or, on failure, tap to retry).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(chatColors().bgInput)
                    .clickable(enabled = ready || sealedFailed) {
                        if (sealedFailed) onRetryDownload() else if (ready) revealed = true
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (sealedFailed) Icons.Default.Refresh else Icons.Default.Lock,
                    contentDescription = if (sealedFailed) "Retry download" else null,
                    tint = if (sealedFailed) chatColors().error else chatColors().primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAudio) "View once voice message" else "View once photo",
                        color = chatColors().textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = when {
                            sealedFailed -> "Download failed — tap to retry"
                            ready -> "Tap to ${if (isAudio) "listen" else "view"} — opens once only"
                            else -> "Downloading…"
                        },
                        color = if (sealedFailed) chatColors().error else chatColors().textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else if (isAudio) {
            // SECURITY (view-once): consume as soon as playback STARTS, not only on natural
            // completion — a paused/aborted listen must still burn the message. EphemeralAudioPlayer
            // fires onConsume once when player.start() is first called.
            EphemeralAudioPlayer(cacheFile = cacheFile, onConsume = onMarkViewed)
        } else {
            // Decode + render image inline. SECURITY (view-once): the reveal IS the single view, so
            // we consume (wipe + mark viewed) the moment the bitmap is on screen — see the
            // LaunchedEffect below — rather than waiting for a second tap into fullscreen (which the
            // user may never make). The in-memory bitmap keeps rendering after the file is wiped.
            var bitmap by remember(message.fileHash) {
                mutableStateOf<android.graphics.Bitmap?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(message.fileHash) {
                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (cacheFile.exists()) {
                        runCatching { decodeSampledBitmap(cacheFile.absolutePath, reqPx = 800) }.getOrNull()
                    } else null
                }
            }
            androidx.compose.runtime.DisposableEffect(message.fileHash) {
                onDispose {
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap = null
                }
            }
            val bmp = bitmap
            // Consume on reveal: fires once when the bitmap first appears. After this the on-disk
            // cache is wiped, so re-opening the conversation shows the consumed placeholder.
            androidx.compose.runtime.LaunchedEffect(message.fileHash, bmp != null) {
                if (bmp != null) onMarkViewed()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "View once photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        // Fullscreen is best-effort: the file is already wiped, so the viewer
                        // shows a cache-miss once consumed. The inline image above is the view.
                        .clickable { onImageClick(cacheFile.absolutePath) },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = "Loading…",
                    color = chatColors().textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Compact "this view-once is gone" placeholder shown after consumption. */
@Composable
private fun ViewOnceConsumedPlaceholder(isAudio: Boolean, isOutgoing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(chatColors().bgInput)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.LockOpen,
            contentDescription = null,
            tint = chatColors().textSecondary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (isAudio) "Voice message — listened" else "Photo — viewed",
            color = chatColors().textSecondary,
            fontSize = 13.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        )
    }
}

/**
 * One-shot audio player used inside a view-once bubble. Auto-starts when composed and fires
 * [onConsume] exactly once the moment playback first STARTS — a view-once voice message must be
 * burned even if the listener pauses or leaves before it finishes. Releases the player on dispose.
 */
@Composable
private fun EphemeralAudioPlayer(cacheFile: java.io.File, onConsume: () -> Unit) {
    val player = remember { android.media.MediaPlayer() }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var consumed by remember { mutableStateOf(false) }
    val onConsumeRef = androidx.compose.runtime.rememberUpdatedState(onConsume)
    // Play from a private temp copy: consuming on start securely WIPES the original in place, which
    // would otherwise corrupt the bytes MediaPlayer is still streaming. Copy first, wipe the
    // original, stream from the copy, delete the copy on dispose.
    val playbackFile = remember { mutableStateOf<java.io.File?>(null) }

    androidx.compose.runtime.LaunchedEffect(cacheFile.absolutePath) {
        runCatching {
            val tmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File.createTempFile("vo_aud_", ".tmp", cacheFile.parentFile).also { t ->
                    cacheFile.copyTo(t, overwrite = true)
                }
            }
            playbackFile.value = tmp
            player.reset()
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            durationMs = player.duration.toLong()
            player.start()
            isPlaying = true
        }.onSuccess {
            // SECURITY (view-once): consume on first start, not on completion.
            if (!consumed) {
                consumed = true
                onConsumeRef.value()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            kotlinx.coroutines.delay(100)
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = android.media.MediaPlayer.OnCompletionListener {
            isPlaying = false
        }
        player.setOnCompletionListener(listener)
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
            // Remove the plaintext temp copy used for playback.
            playbackFile.value?.let { f -> runCatching { f.delete() } }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = chatColors().primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        val totalSec = (durationMs / 1000).coerceAtLeast(1).toInt()
        val posSec = (positionMs / 1000).toInt().coerceAtMost(totalSec)
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (totalSec > 0) posSec.toFloat() / totalSec else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = chatColors().primary,
                trackColor = chatColors().bgInput,
            )
            Text(
                text = "%d:%02d / %d:%02d".format(posSec / 60, posSec % 60, totalSec / 60, totalSec % 60),
                fontSize = 11.sp,
                color = chatColors().textSecondary,
            )
        }
    }
}

/**
 * Voice-message playback bubble. Backed by [MediaPlayer]; releases its resources via
 * DisposableEffect when the bubble leaves composition so we don't leak handles when
 * the user scrolls.
 *
 * The bar above the player echoes the same Uploading/Downloading copy as the image
 * bubble so the two paths feel consistent.
 */
@Composable
private fun VoiceMessageBubble(
    message: co.electriccoin.zcash.ui.screen.chat.model.ChatMessage,
    bubbleProgress: Float?,
    isOutgoing: Boolean,
) {
    val context = LocalContext.current
    val cacheFile = remember(message.fileHash) {
        java.io.File(context.cacheDir, "zchat_files/${message.fileHash}")
    }
    val player = remember { android.media.MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember(message.fileHash) {
        mutableStateOf(message.fileDurationMs ?: 0L)
    }
    var positionMs by remember(message.fileHash) { mutableStateOf(0L) }

    // Whenever the cache file becomes available, prepare the player. We re-prepare on every
    // fileHash change so swapping conversations doesn't keep a stale source attached.
    androidx.compose.runtime.LaunchedEffect(message.fileHash, cacheFile.exists()) {
        if (!cacheFile.exists()) return@LaunchedEffect
        runCatching {
            player.reset()
            player.setDataSource(cacheFile.absolutePath)
            player.prepare()
            val real = player.duration.toLong()
            if (real > 0) durationMs = real
        }
    }

    // Position-poller — only runs while playback is active.
    androidx.compose.runtime.LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            kotlinx.coroutines.delay(100)
        }
    }

    // Stop-on-complete listener — install once.
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = android.media.MediaPlayer.OnCompletionListener {
            isPlaying = false
            positionMs = 0L
        }
        player.setOnCompletionListener(listener)
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (bubbleProgress != null && bubbleProgress < 1f) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isOutgoing) "Uploading…" else "Downloading…",
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
                Text(
                    text = "${(bubbleProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = chatColors().primary,
                )
            }
            LinearProgressIndicator(
                progress = { bubbleProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = chatColors().primary,
                trackColor = chatColors().bgInput,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val ready = cacheFile.exists()
            IconButton(
                onClick = {
                    if (!ready) return@IconButton
                    if (isPlaying) {
                        runCatching { player.pause() }
                        isPlaying = false
                    } else {
                        runCatching { player.start() }
                        isPlaying = true
                    }
                },
                enabled = ready,
                modifier = Modifier
                    .size(48.dp) // 48dp meets the comfortable touch-target size (was 40dp)
                    .clip(CircleShape)
                    // While downloading, the disabled bg used to be bgInput — only ~3% off the
                    // bubble background in some themes, so a dead button looked tappable. Use a
                    // clearly muted textTertiary tint and a download glyph until the file lands.
                    .background(if (ready) chatColors().primary else chatColors().textTertiary.copy(alpha = 0.35f)),
            ) {
                Icon(
                    imageVector = when {
                        !ready -> Icons.Default.Download
                        isPlaying -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        !ready -> "Downloading voice message"
                        isPlaying -> "Pause"
                        else -> "Play"
                    },
                    tint = if (ready) chatColors().textOnAccent else chatColors().textSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val totalSec = (durationMs / 1000).coerceAtLeast(1).toInt()
                val posSec = (positionMs / 1000).toInt().coerceAtMost(totalSec)
                LinearProgressIndicator(
                    progress = { if (totalSec > 0) posSec.toFloat() / totalSec else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = chatColors().primary,
                    trackColor = chatColors().bgInput,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "%d:%02d / %d:%02d".format(posSec / 60, posSec % 60, totalSec / 60, totalSec % 60),
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
            }
        }
    }
}

/**
 * Preview of a quoted message inside a reply bubble
 */
@Composable
private fun QuotedMessagePreview(
    previewText: String,
    isOutgoing: Boolean
) {
    // #249 convention: the OUTGOING bubble is dark-teal in EVERY theme, so its foreground must be LIGHT
    // in every theme. textOnAccent is light only in LIGHT themes (it is near-black 0xFF080B12 in
    // Nightwire dark — i.e. it's the on-bright-accent color, NOT an on-dark-bubble color); textPrimary is
    // light only in DARK themes. So pick per theme, exactly like the main bubble at the top of this file.
    val cc = chatColors()
    val onBubble = if (cc.isLight) cc.textOnAccent else cc.textPrimary
    val bgColor = if (isOutgoing) {
        onBubble.copy(alpha = 0.15f)
    } else {
        cc.primary.copy(alpha = 0.1f)
    }
    val textColor = if (isOutgoing) {
        onBubble.copy(alpha = 0.9f)
    } else {
        cc.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .background(
                    if (isOutgoing) onBubble.copy(alpha = 0.6f)
                    else cc.primary
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = previewText.take(80) + if (previewText.length > 80) "..." else "",
            fontSize = 13.sp,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Text with highlighted search matches
 */
@Composable
private fun HighlightedText(
    text: String,
    highlight: String,
    textColor: Color
) {
    if (highlight.isBlank()) {
        Text(text = text, fontSize = 15.sp, color = textColor)
        return
    }
    // Actually highlight every case-insensitive occurrence of the query (previously this was a stub
    // that rendered plain text, so search "matches" were never visually marked).
    val highlightBg = Color.Yellow.copy(alpha = 0.5f)
    val lower = text.lowercase()
    val query = highlight.lowercase()
    val annotated =
        buildAnnotatedString {
            var start = 0
            while (true) {
                val idx = lower.indexOf(query, start)
                if (idx < 0) {
                    append(text.substring(start))
                    break
                }
                append(text.substring(start, idx))
                withStyle(SpanStyle(background = highlightBg, color = textColor)) {
                    append(text.substring(idx, idx + query.length))
                }
                start = idx + query.length
            }
        }
    Text(text = annotated, fontSize = 15.sp, color = textColor)
}

/**
 * Reply preview component shown above the message input
 */
@Composable
private fun ReplyPreview(
    message: ChatMessage,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().primary.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reply icon and indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(chatColors().primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isOutgoing) "Replying to yourself" else "Replying to message",
                    fontSize = 11.sp,
                    color = chatColors().primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = message.text.take(50) + if (message.text.length > 50) "..." else "",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = chatColors().textSecondary
                )
            }
        }
    }
}

/**
 * Message status indicator showing delivery state.
 *
 * Status icons:
 * - SENDING: Clock icon (animated/pulsing)
 * - SENT: Single checkmark (gray)
 * - CONFIRMED: Double checkmark (white)
 * - READ: Double checkmark (blue)
 * - FAILED: Error icon (red)
 */
@Composable
private fun MessageStatusIndicator(
    status: MessageStatus,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    val iconSize = 14.dp
    // Status icons render INSIDE the outgoing (dark-teal-in-every-theme) bubble, so they need a LIGHT
    // tint in every theme. textOnAccent is near-black in Nightwire dark (it's the on-bright-accent
    // color) — use the #249 per-theme split so the checks aren't invisible on the dark bubble.
    val cc = chatColors()
    val onBubble = if (cc.isLight) cc.textOnAccent else cc.textPrimary

    when (status) {
        MessageStatus.SENDING -> {
            // Clock icon for sending state
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.6f)
            )
        }
        MessageStatus.SENT -> {
            // Single checkmark for sent (in mempool)
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = "Sent",
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.6f)
            )
        }
        MessageStatus.CONFIRMED -> {
            // Double checkmark for confirmed on blockchain
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Confirmed",
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.8f)
            )
        }
        MessageStatus.READ -> {
            // Blue double checkmark for read
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                modifier = modifier.size(iconSize),
                tint = Color(0xFF4FC3F7) // Light blue
            )
        }
        MessageStatus.FAILED -> {
            // Red error icon for failed
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Failed",
                modifier = modifier.size(iconSize),
                tint = chatColors().error // theme-aware error red
            )
        }
    }
}

/**
 * Composable for displaying locked message content
 */
@Composable
private fun LockedMessageContent(
    timeLock: TimeLockInfo,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    // Locked content renders inside the outgoing (dark-teal-in-every-theme) bubble → needs a LIGHT
    // foreground in every theme; textOnAccent is near-black in Nightwire dark, so use the #249 split.
    val cc = chatColors()
    val onBubble = if (cc.isLight) cc.textOnAccent else cc.textPrimary
    val textColor = if (isOutgoing) onBubble else cc.textSecondary
    val iconColor = if (isOutgoing) onBubble.copy(alpha = 0.8f) else cc.primary

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(24.dp),
                tint = iconColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeLock.lockIcon,
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = timeLock.lockDescription,
            fontSize = 15.sp,
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // Show type-specific info
        when (timeLock.lockType) {
            TimeLockType.SCHEDULED -> {
                if (!timeLock.isUnlocked) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Message will appear automatically",
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            TimeLockType.BLOCK_HEIGHT -> {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Block-locked for trustless delivery",
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeLockType.PAYMENT -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap to pay and reveal",
                    fontSize = 13.sp,
                    color = if (isOutgoing) onBubble.copy(alpha = 0.9f) else cc.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeLockType.CONDITIONAL -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap to answer and reveal",
                    fontSize = 13.sp,
                    color = if (isOutgoing) onBubble.copy(alpha = 0.9f) else cc.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Hard cap on chat input length — prevents paste-bomb jank + 1000-output transactions. */
private const val MAX_MESSAGE_INPUT_CHARS = 5000

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPayClick: () -> Unit,
    onTemplatesClick: () -> Unit,
    onLockClick: () -> Unit,
    onRequestClick: () -> Unit,
    onSendImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendViewOnceImage: () -> Unit = {},
    onAmountClick: () -> Unit = {},
    selectedAmount: Long = 1000L,
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode =
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
    // True when this is a TUNNEL chat whose one-time on-chain handshake is already complete (peer
    // NOSTR pubkey known) — so further sends are free over NOSTR and the cost row should say "Free".
    tunnelSendIsFree: Boolean = false,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    disabledMessage: String? = null,
    // Voice messages: parent owns the AudioRecorder lifecycle + RECORD_AUDIO permission;
    // this row only renders the mic icon (when text is empty) and the recording-state UI.
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    isRecordingViewOnce: Boolean = false,
    onMicTap: () -> Unit = {},
    onMicLongPress: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
) {
    // Theme-aware colors
    val colors = chatColors()
    val inputContext = LocalContext.current
    var showFeatureMenu by remember { mutableStateOf(false) }

    // Check if we're in Deep Cyber mode for neon effects (declared once at function level)
    val isZypherpunkMode = colors.background == chatColors().background
    val borderColor = chatColors().borderDefault
    val surfaceColor = chatColors().surface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Top border (BorderDefault)
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(surfaceColor)
            .navigationBarsPadding() // Prevents being covered by nav bar on Fold 3
    ) {
        // Show disabled message if address is invalid
        if (disabledMessage != null) {
            Text(
                text = disabledMessage,
                fontSize = 13.sp,
                color = chatColors().error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Cost row — mode-aware. VAULT (every message on-chain) actually spends ZEC per message, so
        // show the per-message amount. TUNNEL spends only for the one-time on-chain ZBOOT handshake:
        // show the cost while the handshake is still pending, but once it's complete (tunnelSendIsFree)
        // sends are free over NOSTR. OPEN sends free NIP-17 NOSTR DMs from message #1. In the free
        // cases, showing/charging a ZEC amount would be misleading.
        val showCost = conversationMode.isShieldedOnlyTransport ||
            (conversationMode.needsBootstrap && !tunnelSendIsFree)
        if (showCost) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp) // meet the 48dp Material touch-target minimum (was ~28dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable(enabled = isEnabled) { onAmountClick() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val zecAmount = selectedAmount / 100_000_000.0
                Text(
                    text = "⚡ ",
                    fontSize = 12.sp
                )
                Text(
                    text = "Message cost: ${String.format("%.5f", zecAmount)} ZEC",
                    fontSize = 13.sp,
                    color = if (isEnabled) chatColors().primary
                    else chatColors().textTertiary
                )
                Text(
                    text = " (tap to change)",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⚡ ", fontSize = 12.sp)
                Text(
                    text = "Free — sent over NOSTR",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
            }
        }

        // Recording mode: replaces the normal input row with a recording bar so the
        // user can see the elapsed seconds and tap Send or Cancel. View-once recordings
        // tint the mic + label in the danger color so the user notices the mode.
        if (isRecording) {
            // View-once recordings use the danger color so the destructive "burns after one play"
            // mode is unmistakable; a regular recording uses the neutral accent.
            val micTint = if (isRecordingViewOnce) chatColors().error else chatColors().primary
            val label = if (isRecordingViewOnce) "View-once  %d:%02d" else "Recording  %d:%02d"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancelRecording, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel recording",
                        tint = chatColors().error,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isRecordingViewOnce) Icons.Default.Lock else Icons.Default.Mic,
                        contentDescription = null,
                        tint = micTint,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label.format(recordingSeconds / 60, recordingSeconds % 60),
                        color = chatColors().textPrimary,
                        fontSize = 14.sp,
                    )
                }
                IconButton(
                    onClick = onSendRecording,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(chatColors().primary),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send recording",
                        tint = chatColors().textOnAccent,
                    )
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Single + button for all special features
            Box(contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { showFeatureMenu = true },
                    enabled = isEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isZypherpunkMode && isEnabled) chatColors().bgElevated
                            else if (isEnabled) colors.primary else colors.backgroundLight
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Special features",
                        // Disabled must read as disabled in EVERY theme. The old Nightwire branch kept
                        // the primary accent even when disabled, making the read-only "+" look live.
                        tint = when {
                            !isEnabled -> chatColors().textTertiary.copy(alpha = 0.5f)
                            isZypherpunkMode -> chatColors().primary
                            else -> colors.background
                        }
                    )
                }

                // Feature menu dropdown
                DropdownMenu(
                    expanded = showFeatureMenu,
                    onDismissRequest = { showFeatureMenu = false },
                    containerColor = chatColors().bgElevated
                ) {
                    // Send Payment option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Send Payment",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Send ZEC to this contact",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onPayClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.secondary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachMoney,
                                    contentDescription = null,
                                    tint = colors.background,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Time-Locked Message option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Time-Locked Message",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Message unlocked by payment or block height",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onLockClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = colors.background,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Quick Pay Templates option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Quick Pay Templates",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Pre-saved payment shortcuts",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onTemplatesClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(ZchatTeal),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "☕",
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    )

                    // Take Photo — capture via system camera
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Take Photo",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Capture with camera (encrypted)",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onTakePhoto()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00C2FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Send Image option (Phase 2 file sharing) — pick from gallery
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Send Image",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Share a photo from gallery (encrypted)",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onSendImage()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF7C4DFF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Send view-once Photo — gallery picker but the recipient (and we)
                    // can only see it once.
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Send view-once photo",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Recipient sees it once, then it's gone",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary,
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onSendViewOnceImage()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(chatColors().error),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )

                    // Send File — PDF / ZIP / TXT / image via document picker
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Send File",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "PDF, ZIP, TXT, image (encrypted, max 25 MB)",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onSendFile()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFB300)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Request Payment option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Request Payment",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Ask contact to pay you",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onRequestClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00BFA5)),  // Teal variant
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "💸",
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = {
                    // Hard-cap input; if a paste exceeds the cap, tell the user it was trimmed
                    // instead of silently dropping the overflow.
                    if (it.length > MAX_MESSAGE_INPUT_CHARS) {
                        Toast.makeText(
                            inputContext,
                            "Message trimmed to $MAX_MESSAGE_INPUT_CHARS characters",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    onValueChange(it.take(MAX_MESSAGE_INPUT_CHARS))
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (isEnabled) "Message..." else "Replies not available",
                        color = chatColors().textTertiary
                    )
                },
                shape = RoundedCornerShape(NightwireColors.RadiusInput),
                maxLines = 4,
                enabled = isEnabled,
                // The composer hard-caps input at MAX_MESSAGE_INPUT_CHARS (a paste past the limit is
                // silently truncated). Surface a counter once the user nears the cap so the trim
                // isn't invisible; turn it red at the limit.
                supportingText = if (value.length >= MAX_MESSAGE_INPUT_CHARS - 1000) {
                    {
                        val atLimit = value.length >= MAX_MESSAGE_INPUT_CHARS
                        Text(
                            text = "${value.length} / $MAX_MESSAGE_INPUT_CHARS",
                            fontSize = 11.sp,
                            color = if (atLimit) chatColors().error else chatColors().textSecondary,
                        )
                    }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (isEnabled) onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = chatColors().bgInput,
                    unfocusedContainerColor = chatColors().bgInput,
                    focusedTextColor = chatColors().textPrimary,
                    unfocusedTextColor = chatColors().textPrimary,
                    cursorColor = chatColors().primary,
                    focusedBorderColor = chatColors().borderActive,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            val sendEnabled = value.isNotBlank() && isEnabled

            // Send button: only visible when text is entered (per spec)
            if (sendEnabled) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp)
                ) {
                    // Cyan glow for Nightwire mode
                    if (isZypherpunkMode) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(chatColors().accentPrimaryGlow)
                        )
                    }

                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .size(if (isZypherpunkMode) 44.dp else 48.dp)
                            .clip(CircleShape)
                            .then(
                                if (isZypherpunkMode) {
                                    Modifier.background(
                                        chatColors().primary,
                                        CircleShape
                                    )
                                } else {
                                    Modifier.background(colors.secondary)
                                }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (isZypherpunkMode) chatColors().textOnAccent
                                else colors.background
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
                // Mic button — only visible when the text field is empty.
                //   Tap        → regular voice message.
                //   Long-press → view-once voice message (deletes after one playback).
                // We use combinedClickable on a Box because IconButton doesn't expose a
                // long-press hook.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) chatColors().bgElevated else chatColors().backgroundLight
                        )
                        .combinedClickable(
                            enabled = isEnabled,
                            onClick = onMicTap,
                            onLongClick = onMicLongPress,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record voice message — long-press for view-once",
                        // Clearly muted when read-only so it doesn't read as a live mic in Nightwire.
                        tint = if (isEnabled) chatColors().primary
                            else chatColors().textTertiary.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/**
 * Best-effort save of a decrypted-locally file blob into the public Downloads (images
 * use the Pictures collection so the gallery picks them up). Uses MediaStore on Android Q+
 * so we don't need WRITE_EXTERNAL_STORAGE. Falls back to a Toast on failure.
 */
private fun saveFileToDownloads(
    context: android.content.Context,
    cacheFile: java.io.File,
    fileType: co.electriccoin.zcash.ui.screen.chat.model.ZFILEType?,
) {
    if (!cacheFile.exists()) {
        Toast.makeText(context, "File not available", Toast.LENGTH_SHORT).show()
        return
    }
    val mime = fileType?.mimeType ?: "application/octet-stream"
    val isImage = mime.startsWith("image/")
    val isAudio = mime.startsWith("audio/")
    val ext = when (fileType) {
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.JPEG -> "jpg"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.PNG -> "png"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.GIF -> "gif"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.WEBP -> "webp"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.PDF -> "pdf"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.ZIP -> "zip"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.TXT -> "txt"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A -> "m4a"
        null -> "bin"
    }
    val filename = "zchat_${System.currentTimeMillis()}.$ext"
    val targetCollection = when {
        isImage -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        isAudio -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
    val relativePath = when {
        isImage -> android.os.Environment.DIRECTORY_PICTURES + "/ZCHAT"
        isAudio -> android.os.Environment.DIRECTORY_MUSIC + "/ZCHAT"
        else -> android.os.Environment.DIRECTORY_DOWNLOADS + "/ZCHAT"
    }
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
    }
    val uri = context.contentResolver.insert(targetCollection, values)
    if (uri == null) {
        Toast.makeText(context, "Could not create destination", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            cacheFile.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open destination stream")
        Toast.makeText(context, "Saved to $relativePath/$filename", Toast.LENGTH_LONG).show()
    }.onFailure {
        runCatching { context.contentResolver.delete(uri, null, null) }
        Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Share a decrypted file via Android's share sheet. Uses the existing ShareFileProvider
 * authority so receiving apps get a content:// URI with read permission instead of a
 * file:// path.
 */
private fun shareFile(
    context: android.content.Context,
    cacheFile: java.io.File,
    fileType: co.electriccoin.zcash.ui.screen.chat.model.ZFILEType?,
    asForward: Boolean = false,
) {
    if (!cacheFile.exists()) {
        Toast.makeText(context, "File not available", Toast.LENGTH_SHORT).show()
        return
    }
    val authority = "xyz.zsend.zchat.provider"
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, cacheFile)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = fileType?.mimeType ?: "*/*"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooserTitle = if (asForward) "Forward via…" else "Share via…"
    context.startActivity(android.content.Intent.createChooser(intent, chooserTitle).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun formatMessageTime(timestamp: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
    return formatter.format(timestamp)
}

@Composable
private fun UnknownSenderBanner(
    reason: UnknownReason?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().success.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "Unknown sender",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = chatColors().textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This message was not sent using ZCHAT, so we cannot recognize the sender. You cannot reply to this message.",
                fontSize = 13.sp,
                color = chatColors().textPrimary.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Privacy Status Bar — slim inline indicator for shielded status.
 * Tappable to expand details. Minimal footprint to maximize chat area.
 */
@Composable
private fun PrivacyStatusCard(
    privacyStatus: PrivacyStatus,
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onTapNostr: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The wallet-pool shielding indicator (isFullyShielded) only describes the VAULT (on-chain)
    // transport. In Tunnel/Open the messages travel as NIP-17 gift-wrapped NOSTR DMs — E2E
    // encrypted but NOT on-chain — so claiming "shielded on-chain via Zcash" there is false.
    val isNostr = conversationMode.isNostrTransport
    val isShielded = privacyStatus.isFullyShielded
    val accentColor = if (isNostr || isShielded) {
        chatColors().success
    } else {
        chatColors().warning
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // NOSTR (Tunnel/Open): the expandable panel below shows on-chain pool /
            // anonymity-set details, which are meaningless for off-chain relay chats.
            // Route the tap to E2E safety-number verification instead. On-chain/vault
            // conversations keep the original expand-to-details behaviour.
            .clickable { if (isNostr) onTapNostr() else onToggle() }
    ) {
        // Slim bar — always visible (single row, ~32dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Privacy Status",
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when {
                    // Tunnel/Open: NIP-17 E2E-encrypted NOSTR DMs, off-chain.
                    isNostr -> "End-to-end encrypted over NOSTR — not on-chain"
                    // Vault: on-chain shielded. "on-chain" (not "end-to-end") — the phrase
                    // "end-to-end encrypted" is reserved for the E2E lock in the action bar.
                    isShielded -> "Messages are shielded on-chain via Zcash"
                    else -> "Funds need shielding for full privacy"
                },
                fontSize = 11.sp,
                color = accentColor.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = if (isExpanded) "Collapse" else "Details",
                tint = accentColor.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
        }

        // Expanded detail panel
        androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(chatColors().bgElevated)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pool:", fontSize = 12.sp, color = chatColors().textSecondary)
                    Text(
                        privacyStatus.poolDisplayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = chatColors().textPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Anonymity Set:", fontSize = 12.sp, color = chatColors().textSecondary)
                    Text(
                        privacyStatus.anonymitySetEstimate,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = chatColors().textPrimary
                    )
                }
                if (privacyStatus.needsShielding) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = chatColors().warning,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Shield your funds to the Orchard pool for maximum privacy.",
                            fontSize = 11.sp,
                            color = chatColors().warning
                        )
                    }
                }
            }
        }
    }
}

/** Centered info pill for a local system note (e.g. "contact rotated their key") — #225. */
@Suppress("MagicNumber")
@Composable
private fun SystemNotePill(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(chatColors().bgElevated)
                .border(1.dp, chatColors().borderDefault, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = text,
                color = chatColors().textSecondary,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Centered pill for a local call-log entry (incoming / outgoing / missed call). */
@Suppress("MagicNumber")
@Composable
private fun CallLogPill(info: co.electriccoin.zcash.ui.screen.chat.model.CallLogInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(chatColors().bgElevated)
                .border(1.dp, chatColors().borderDefault, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "${info.icon}  ${info.label}",
                color = chatColors().textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Suppress("MagicNumber")
@Composable
private fun DateSeparator(date: LocalDate, currentBlockHeight: Long? = null) {
    val cc = chatColors()
    val today = LocalDate.now()
    val yesterday = today.minus(1, ChronoUnit.DAYS)
    val dateLabel = when (date) {
        today -> "TODAY"
        yesterday -> "YESTERDAY"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")).uppercase()
    }
    // Direction-A cypherpunk date chip: on today's divider, surface the live Zcash block height
    // ("TODAY · BLOCK 2,841,204") so the thread feels anchored to the chain. Centered mono chip.
    val label = if (date == today && currentBlockHeight != null && currentBlockHeight > 0) {
        "$dateLabel · BLOCK ${String.format(java.util.Locale.US, "%,d", currentBlockHeight)}"
    } else {
        dateLabel
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(cc.bgElevated)
                .border(1.dp, cc.borderDefault, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                color = cc.textSecondary,
                fontSize = 10.sp,
                fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun WelcomeZecCard(onSend: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgElevated
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = null,
                    tint = chatColors().primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Send Welcome ZEC",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = chatColors().textPrimary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Send ~0.005 ZEC so they can start messaging (~50 messages)",
                fontSize = 12.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = onSend,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(NightwireColors.RadiusButton),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent,
                ),
            ) {
                Text("Send 0.005 ZEC", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// PaymentDialog, TimeLockComposerDialog, TemplatePickerRow, and PaymentRequestComposerDialog
// have been extracted to ChatDialogs.kt for better organization

// Decode an image file to a Bitmap downscaled so its dimensions stay near reqPx.
// Uses the canonical Android pattern (see co.electriccoin.zcash.ui.screen.chat.filesharing.BitmapSampling).
private fun decodeSampledBitmap(path: String, reqPx: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val sample = co.electriccoin.zcash.ui.screen.chat.filesharing
        .BitmapSampling.calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqPx)
        ?: return null
    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(path, decodeOpts)
}

@Composable
private fun ImageUploadProgressBar(progress: Float) {
    val label = when {
        progress < 0.15f -> "Preparing image…"
        progress < 0.25f -> "Compressing…"
        progress < 0.9f -> "Encrypting & uploading… ${(progress * 100).toInt()}%"
        progress < 1.0f -> "Finalizing…"
        else -> "Sent"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(chatColors().bgElevated)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = chatColors().textSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = chatColors().primary,
            trackColor = chatColors().bgInput,
        )
    }
}
