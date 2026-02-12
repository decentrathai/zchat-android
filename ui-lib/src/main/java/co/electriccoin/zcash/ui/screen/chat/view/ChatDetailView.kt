package co.electriccoin.zcash.ui.screen.chat.view

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    onSendMessage: (message: String, amountZatoshi: Long) -> Unit,
    onSendReply: (message: String, replyToId: String, amountZatoshi: Long) -> Unit = { msg, _, amt -> onSendMessage(msg, amt) },
    onDeleteMessage: (String) -> Unit,
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
    modifier: Modifier = Modifier
) {
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
                onBackClick = onBackClick,
                onSendMessage = { msg, amt -> onSendMessage(msg, amt) },
                onSendReply = { msg, replyToId, amt -> onSendReply(msg, replyToId, amt) },
                onDeleteMessage = onDeleteMessage,
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
                    color = MaterialTheme.colorScheme.error
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
    onBackClick: () -> Unit,
    onSendMessage: (message: String, amountZatoshi: Long) -> Unit,
    onSendReply: (message: String, replyToId: String, amountZatoshi: Long) -> Unit,
    onDeleteMessage: (String) -> Unit,
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
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

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

    // Auto-save draft with debounce (500ms delay)
    LaunchedEffect(messageText) {
        if (messageText != (conversation.draft ?: "")) {
            kotlinx.coroutines.delay(500L)
            onDraftChange(messageText)
        }
    }

    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Filter messages based on search
    val filteredMessages = remember(conversation.messages, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messages
        } else {
            conversation.messages.filter {
                it.text.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val displayMessages = remember(filteredMessages) { filteredMessages.asReversed() }

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
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                // Show contact name initials if available, otherwise address prefix
                                val initials = if (conversation.hasContactName) {
                                    conversation.contactName!!.split(" ")
                                        .take(2)
                                        .map { it.firstOrNull()?.uppercaseChar() ?: '?' }
                                        .joinToString("")
                                } else {
                                    conversation.peerAddress.take(2).uppercase()
                                }
                                Text(
                                    text = initials,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = conversation.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Show truncated address below if contact name is displayed
                                if (conversation.hasContactName) {
                                    Text(
                                        text = Conversation.truncateAddress(conversation.peerAddress),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.primary.copy(alpha = 0.7f)
                                    )
                                } else {
                                    // Show hint to tap for nickname
                                    Text(
                                        text = "Tap to set nickname",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                        // E2E encryption toggle
                        IconButton(
                            onClick = { onE2EToggle(!conversation.e2eEnabled) }
                        ) {
                            Icon(
                                imageVector = if (conversation.isE2EReady) {
                                    Icons.Default.Lock
                                } else if (conversation.e2eEnabled) {
                                    Icons.Default.LockOpen
                                } else {
                                    Icons.Default.LockOpen
                                },
                                contentDescription = if (conversation.e2eEnabled) "E2E Enabled" else "E2E Disabled",
                                tint = if (conversation.isE2EReady) {
                                    MaterialTheme.colorScheme.primary
                                } else if (conversation.e2eEnabled) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    colors.textSecondary
                                }
                            )
                        }
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search messages"
                            )
                        }
                        // Mute toggle
                        IconButton(onClick = onMuteToggle) {
                            Icon(
                                imageVector = if (conversation.isMuted) {
                                    Icons.Default.NotificationsOff
                                } else {
                                    Icons.Default.Notifications
                                },
                                contentDescription = if (conversation.isMuted) "Unmute" else "Mute",
                                tint = if (conversation.isMuted) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    colors.primary
                                }
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
                            if (replyToMessage != null) {
                                onSendReply(messageText, replyToMessage!!.id, selectedAmount)
                                replyToMessage = null
                            } else {
                                onSendMessage(messageText, selectedAmount)
                            }
                            messageText = ""
                        }
                    },
                    onPayClick = { showPaymentDialog = true },
                    onTemplatesClick = { showTemplates = !showTemplates },
                    onLockClick = { showTimeLockDialog = true },
                    onRequestClick = { showPaymentRequestDialog = true },
                    onAmountClick = { showAmountPicker = true },
                    selectedAmount = selectedAmount,
                    isEnabled = isValidAddress,
                    disabledMessage = if (!isValidAddress) "Cannot reply - sender address unknown" else null
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

            // Privacy Status Card (collapsible)
            PrivacyStatusCard(
                privacyStatus = privacyStatus,
                isExpanded = showPrivacyStatus,
                onToggle = { showPrivacyStatus = !showPrivacyStatus }
            )

            // Search results count
            if (isSearching && searchQuery.isNotBlank()) {
                Text(
                    text = "${filteredMessages.size} results found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                items(displayMessages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        allMessages = conversation.messages,
                        zecPriceUsd = zecPriceUsd,
                        onDeleteMessage = onDeleteMessage,
                        onReplyClick = { replyToMessage = message },
                        onReactionClick = { emoji -> onSendReaction(message.id, emoji) },
                        onPayRequest = { amountZatoshi, requestId ->
                            onFulfillPaymentRequest(amountZatoshi, requestId)
                        },
                        highlightSearch = searchQuery.takeIf { it.isNotBlank() }
                    )
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

    // Nickname Edit Dialog
    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text("Edit Contact Name") },
            text = {
                Column {
                    Text(
                        text = "Set a nickname for this contact:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        onValueChange = { nicknameText = it },
                        placeholder = { Text("Enter nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = Conversation.truncateAddress(conversation.peerAddress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onNicknameChange(conversation.peerAddress, nicknameText)
                        showNicknameDialog = false
                    }
                ) {
                    Text("Save")
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
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
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "$amount zatoshi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    allMessages: List<ChatMessage>,
    zecPriceUsd: Double? = null,
    onDeleteMessage: (String) -> Unit,
    onReplyClick: () -> Unit,
    onReactionClick: (emoji: String) -> Unit,
    onPayRequest: (amountZatoshi: Long, requestId: String) -> Unit = { _, _ -> },
    highlightSearch: String? = null,
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

    val isOutgoing = message.isOutgoing
    val isPending = message.isPending
    var showMenu by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }

    // Find quoted message if this is a reply
    val quotedMessage = message.replyToId?.let { replyId ->
        allMessages.find { it.id == replyId }
    }

    // Theme-aware bubble colors
    val bubbleColor = when {
        isOutgoing && isPending -> colors.outgoingBubble.copy(alpha = 0.6f) // Lighter for pending
        isOutgoing -> colors.outgoingBubble
        else -> colors.incomingBubble
    }

    val textColor = when {
        isOutgoing -> colors.fabForeground // Use contrasting color for outgoing
        else -> colors.textPrimary
    }

    val timeColor = when {
        isOutgoing -> colors.primary.copy(alpha = 0.8f)
        else -> colors.textSecondary
    }

    // Check if we're in Deep Cyber mode by checking if background is near-black
    val isZypherpunkMode = colors.background == Color(0xFF050510)

    Column(modifier = modifier) {
        // Transmission header for Deep Cyber theme
        if (isZypherpunkMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
            ) {
                Text(
                    text = if (isOutgoing) "◈ OUTGOING TRANSMISSION" else "◈ TRANSMISSION RECEIVED",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (isOutgoing) Color(0xFF00FFAA) else Color(0xFF00FF88),
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

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
                                    color = if (isOutgoing) Color(0xFF00FFFF).copy(alpha = 0.3f)
                                    else Color(0xFFFF00FF).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(
                                        topStart = if (isOutgoing) 12.dp else 4.dp,
                                        topEnd = if (isOutgoing) 4.dp else 12.dp,
                                        bottomStart = if (isOutgoing) 12.dp else 4.dp,
                                        bottomEnd = if (isOutgoing) 4.dp else 12.dp
                                    )
                                )
                            } else Modifier
                        ),
                    shape = RoundedCornerShape(
                        topStart = if (isZypherpunkMode && isOutgoing) 12.dp else 16.dp,
                        topEnd = if (isZypherpunkMode && !isOutgoing) 12.dp else 16.dp,
                        bottomStart = if (isOutgoing) (if (isZypherpunkMode) 12.dp else 16.dp) else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else (if (isZypherpunkMode) 12.dp else 16.dp)
                    ),
                    colors = CardDefaults.cardColors(containerColor = bubbleColor)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        // Quoted message preview (if this is a reply)
                        if (quotedMessage != null || message.replyToPreview != null) {
                            QuotedMessagePreview(
                                previewText = quotedMessage?.text ?: message.replyToPreview ?: "",
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
                                    style = MaterialTheme.typography.bodyMedium,
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
                                style = MaterialTheme.typography.bodySmall,
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
                onDismissRequest = { showMenu = false }
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
                // Delete option
                DropdownMenuItem(
                    text = { Text("Delete Message") },
                    onClick = {
                        showMenu = false
                        onDeleteMessage(message.id)
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
                            modifier = Modifier
                                .clickable {
                                    showReactionPicker = false
                                    onReactionClick(emoji)
                                }
                                .padding(4.dp)
                        )
                    }
                }
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
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
 * Preview of a quoted message inside a reply bubble
 */
@Composable
private fun QuotedMessagePreview(
    previewText: String,
    isOutgoing: Boolean
) {
    val bgColor = if (isOutgoing) {
        Color.White.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    }
    val textColor = if (isOutgoing) {
        Color.White.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
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
                    if (isOutgoing) Color.White.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.primary
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = previewText.take(80) + if (previewText.length > 80) "..." else "",
            style = MaterialTheme.typography.bodySmall,
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
    val highlightColor = Color.Yellow.copy(alpha = 0.5f)

    // Simple approach: just show the text with style
    // A more complex approach would use AnnotatedString to highlight matches
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor
    )
    // Note: For proper highlighting, you'd use buildAnnotatedString
    // but that adds complexity. The yellow background works well visually.
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
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
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
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isOutgoing) "Replying to yourself" else "Replying to message",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = message.text.take(50) + if (message.text.length > 50) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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

    when (status) {
        MessageStatus.SENDING -> {
            // Clock icon for sending state
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                modifier = modifier.size(iconSize),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
        MessageStatus.SENT -> {
            // Single checkmark for sent (in mempool)
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = "Sent",
                modifier = modifier.size(iconSize),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
        MessageStatus.CONFIRMED -> {
            // Double checkmark for confirmed on blockchain
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Confirmed",
                modifier = modifier.size(iconSize),
                tint = Color.White.copy(alpha = 0.8f)
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
                tint = Color(0xFFFF5252) // Red
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
    val textColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor = if (isOutgoing) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary

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
            style = MaterialTheme.typography.bodyMedium,
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
                        style = MaterialTheme.typography.bodySmall,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeLockType.PAYMENT -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap to pay and reveal",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOutgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeLockType.CONDITIONAL -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap to answer and reveal",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOutgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPayClick: () -> Unit,
    onTemplatesClick: () -> Unit,
    onLockClick: () -> Unit,
    onRequestClick: () -> Unit,
    onAmountClick: () -> Unit = {},
    selectedAmount: Long = 1000L,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    disabledMessage: String? = null
) {
    // Theme-aware colors
    val colors = chatColors()
    var showFeatureMenu by remember { mutableStateOf(false) }

    // Check if we're in Deep Cyber mode for neon effects (declared once at function level)
    val isZypherpunkMode = colors.background == Color(0xFF050510)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding() // Prevents being covered by nav bar on Fold 3
    ) {
        // Show disabled message if address is invalid
        if (disabledMessage != null) {
            Text(
                text = disabledMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Amount selector row
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                style = MaterialTheme.typography.bodySmall,
                color = if (isEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = " (tap to change)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
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
                // Neon glow for Deep Cyber mode
                if (isZypherpunkMode && isEnabled) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FFFF).copy(alpha = 0.25f))
                    )
                }

                IconButton(
                    onClick = { showFeatureMenu = true },
                    enabled = isEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .then(
                            if (isZypherpunkMode && isEnabled) {
                                Modifier.background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF00FFFF), Color(0xFF00AAAA))
                                    ),
                                    CircleShape
                                )
                            } else {
                                Modifier.background(
                                    if (isEnabled) colors.primary else colors.backgroundLight
                                )
                            }
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Special features",
                        tint = if (isEnabled) colors.background else Color.Gray
                    )
                }

                // Feature menu dropdown
                DropdownMenu(
                    expanded = showFeatureMenu,
                    onDismissRequest = { showFeatureMenu = false }
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (isEnabled) "Type a message..." else "Replies not available")
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                enabled = isEnabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (isEnabled) onSend() })
            )
            Spacer(modifier = Modifier.width(8.dp))

            val sendEnabled = value.isNotBlank() && isEnabled

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp)
            ) {
                // Outer glow for Deep Cyber mode
                if (isZypherpunkMode && sendEnabled) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF00FF).copy(alpha = 0.3f))
                    )
                }

                // Magenta ring for Deep Cyber mode
                if (isZypherpunkMode && sendEnabled) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF00FF))
                    )
                }

                IconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier
                        .size(if (isZypherpunkMode && sendEnabled) 44.dp else 48.dp)
                        .clip(CircleShape)
                        .then(
                            if (isZypherpunkMode && sendEnabled) {
                                Modifier.background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF00FFFF), Color(0xFF00BBBB))
                                    ),
                                    CircleShape
                                )
                            } else {
                                Modifier.background(
                                    if (sendEnabled) colors.secondary else colors.backgroundLight
                                )
                            }
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (sendEnabled) colors.background else Color.Gray
                    )
                }
            }
        }
    }
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
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "Unknown sender",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This message was not sent using ZCHAT, so we cannot recognize the sender. You cannot reply to this message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Privacy Status Card - shows pool type, anonymity set, and shielded status.
 * Collapsible to minimize screen real estate when not needed.
 */
@Composable
private fun PrivacyStatusCard(
    privacyStatus: PrivacyStatus,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isShielded = privacyStatus.isFullyShielded
    val cardColor = if (isShielded) {
        Color(0xFF1A3A1A) // Dark green for shielded
    } else {
        Color(0xFF3A2A1A) // Dark amber for needs attention
    }
    val accentColor = if (isShielded) {
        Color(0xFF4CAF50) // Green
    } else {
        Color(0xFFFF9800) // Amber warning
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row - always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Privacy Status",
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isShielded) "SHIELDED" else "NEEDS ATTENTION",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))

                // Pool type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Pool:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = privacyStatus.poolDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Anonymity set
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Anonymity Set:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = privacyStatus.anonymitySetEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Explanation text
                Text(
                    text = if (isShielded) {
                        "Your messages hide among millions of shielded transactions."
                    } else {
                        "Some funds are in less private pools. Consider shielding for maximum privacy."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )

                // Warning if needs shielding
                if (privacyStatus.needsShielding) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Shield your funds to the Orchard pool for maximum privacy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }
        }
    }
}

// PaymentDialog, TimeLockComposerDialog, TemplatePickerRow, and PaymentRequestComposerDialog
// have been extracted to ChatDialogs.kt for better organization
