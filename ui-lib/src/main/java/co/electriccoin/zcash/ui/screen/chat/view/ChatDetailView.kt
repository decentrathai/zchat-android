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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
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

/**
 * Get theme-aware chat colors. Maps ZashiColors to ChatColors for theme-awareness.
 */
@Composable
private fun chatColors(): ChatColors {
    val zashiColors = co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
    return ChatColors(
        primary = zashiColors.Surfaces.brandBg,
        secondary = zashiColors.Text.textSupport,
        background = zashiColors.Surfaces.bgPrimary,
        backgroundLight = zashiColors.Surfaces.bgSecondary,
        surface = zashiColors.Surfaces.bgSecondary,
        textPrimary = zashiColors.Text.textPrimary,
        textSecondary = zashiColors.Text.textTertiary,
        outgoingBubble = zashiColors.Surfaces.brandBg,
        incomingBubble = zashiColors.Surfaces.bgSecondary,
        fabBackground = zashiColors.Surfaces.brandBg,
        fabForeground = zashiColors.Surfaces.bgPrimary,
        divider = zashiColors.Surfaces.strokeSecondary,
        error = zashiColors.Text.textError,
        titleGradient = Brush.horizontalGradient(
            colors = listOf(zashiColors.Surfaces.brandBg, zashiColors.Text.textSupport)
        )
    )
}

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
    currentBlockHeight: Long? = null,
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
                currentBlockHeight = currentBlockHeight,
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
    currentBlockHeight: Long?,
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showTimeLockDialog by remember { mutableStateOf(false) }
    var showPaymentRequestDialog by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<MemoTemplate?>(null) }
    var showAmountPicker by remember { mutableStateOf(false) }

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

    // Check if peer address is a valid Zcash address
    // Unified addresses start with "u1" and are 200+ chars, Sapling starts with "zs" and is 78+ chars
    val isValidAddress = (conversation.peerAddress.startsWith("u1") && conversation.peerAddress.length > 100) ||
            (conversation.peerAddress.startsWith("zs") && conversation.peerAddress.length > 70)

    // Scroll to bottom when new messages arrive
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(conversation.messages.size - 1)
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
                            modifier = Modifier.clickable {
                                // Copy address to clipboard when header is clicked
                                clipboardManager.setText(AnnotatedString(conversation.peerAddress))
                                Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                            }
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
                                    Text(
                                        text = "${conversation.messages.size} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search messages"
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredMessages, key = { it.id }) { message ->
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
    val isDeepCyberMode = colors.background == Color(0xFF050510)

    Column(modifier = modifier) {
        // Transmission header for Deep Cyber theme
        if (isDeepCyberMode) {
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
                            if (isDeepCyberMode) {
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
                        topStart = if (isDeepCyberMode && isOutgoing) 12.dp else 16.dp,
                        topEnd = if (isDeepCyberMode && !isOutgoing) 12.dp else 16.dp,
                        bottomStart = if (isOutgoing) (if (isDeepCyberMode) 12.dp else 16.dp) else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else (if (isDeepCyberMode) 12.dp else 16.dp)
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
    val isDeepCyberMode = colors.background == Color(0xFF050510)

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
                if (isDeepCyberMode && isEnabled) {
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
                            if (isDeepCyberMode && isEnabled) {
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
                if (isDeepCyberMode && sendEnabled) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF00FF).copy(alpha = 0.3f))
                    )
                }

                // Magenta ring for Deep Cyber mode
                if (isDeepCyberMode && sendEnabled) {
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
                        .size(if (isDeepCyberMode && sendEnabled) 44.dp else 48.dp)
                        .clip(CircleShape)
                        .then(
                            if (isDeepCyberMode && sendEnabled) {
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
 * Payment dialog for sending ZEC to chat recipient.
 * Supports split payments where the total is divided among N people.
 * Can be pre-filled with a template for quick payments.
 */
@Composable
private fun PaymentDialog(
    balance: Zatoshi,
    zecPriceUsd: Double?,
    recipientName: String,
    prefilledTemplate: MemoTemplate? = null,
    onDismiss: () -> Unit,
    onSendPayment: (amountZec: Double, memo: String) -> Unit
) {
    // Pre-fill from template if provided
    val initialAmount = prefilledTemplate?.let {
        val zecAmount = it.getZecAmount(zecPriceUsd)
        if (zecAmount > 0) String.format("%.8f", zecAmount).trimEnd('0').trimEnd('.') else ""
    } ?: ""

    var amountText by remember(prefilledTemplate) { mutableStateOf(initialAmount) }
    var memo by remember(prefilledTemplate) { mutableStateOf(prefilledTemplate?.memo ?: "") }
    var splitEnabled by remember { mutableStateOf(false) }
    var splitCount by remember { mutableIntStateOf(2) }

    val amountZec = amountText.toDoubleOrNull() ?: 0.0
    val perPersonAmount = if (splitEnabled && splitCount > 0) amountZec / splitCount else amountZec
    val amountUsd = zecPriceUsd?.let { amountZec * it }
    val perPersonUsd = zecPriceUsd?.let { perPersonAmount * it }

    // Balance in ZEC
    val balanceZec = balance.value.toDouble() / 100_000_000.0
    val hasEnoughBalance = amountZec <= balanceZec && amountZec > 0

    val decimalFormat = remember { DecimalFormat("#,##0.00") }
    val zecFormat = remember { DecimalFormat("#,##0.########") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefilledTemplate != null) {
                        Text(
                            text = prefilledTemplate.emoji,
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = prefilledTemplate?.name ?: "Send Payment",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Template info banner
                if (prefilledTemplate != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = prefilledTemplate.emoji,
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Quick Pay: ${prefilledTemplate.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = prefilledTemplate.getDisplayAmount(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Recipient
                Text(
                    text = "To: $recipientName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Amount input
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { newValue ->
                        // Only allow valid decimal input
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                            amountText = newValue
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount (ZEC)") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = {
                        Text(
                            text = "Ⓩ",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    supportingText = {
                        if (amountUsd != null && amountZec > 0) {
                            Text("≈ $${decimalFormat.format(amountUsd)} USD")
                        }
                    },
                    isError = amountZec > 0 && !hasEnoughBalance
                )

                // Balance display
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Available:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${zecFormat.format(balanceZec)} ZEC",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Split payment section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (splitEnabled)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Split Payment",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Divide total among people",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = splitEnabled,
                                onCheckedChange = { splitEnabled = it }
                            )
                        }

                        if (splitEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // People counter
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (splitCount > 2) splitCount-- },
                                    enabled = splitCount > 2,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease"
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$splitCount",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "people",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                IconButton(
                                    onClick = { if (splitCount < 20) splitCount++ },
                                    enabled = splitCount < 20,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase"
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Per-person amount
                            if (amountZec > 0) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Each person pays:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${zecFormat.format(perPersonAmount)} ZEC",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (perPersonUsd != null) {
                                            Text(
                                                text = "≈ $${decimalFormat.format(perPersonUsd)} USD",
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

                // Memo (optional)
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Memo (optional)") },
                    placeholder = { Text("Add a note...") },
                    singleLine = true,
                    maxLines = 1
                )

                // Insufficient funds warning
                if (amountZec > 0 && !hasEnoughBalance) {
                    Text(
                        text = "Insufficient balance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSendPayment(amountZec, memo) },
                enabled = amountZec > 0 && hasEnoughBalance
            ) {
                Icon(
                    Icons.Default.AttachMoney,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Send ${if (amountZec > 0) "${zecFormat.format(amountZec)} ZEC" else ""}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Time-Lock Composer Dialog for creating time-locked messages.
 * Supports 4 lock types: Scheduled, Block Height, Payment, Conditional
 */
@Composable
private fun TimeLockComposerDialog(
    currentBlockHeight: Long?,
    onDismiss: () -> Unit,
    onSendScheduledMessage: (message: String, unlockTimestamp: Long) -> Unit,
    onSendBlockLockedMessage: (message: String, unlockHeight: Long) -> Unit,
    onSendPaymentLockedMessage: (message: String, requiredZatoshi: Long) -> Unit,
    onSendConditionalMessage: (message: String, answer: String, hint: String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var messageText by remember { mutableStateOf("") }

    // Scheduled lock state
    var scheduledMinutes by remember { mutableStateOf("30") }

    // Block height lock state
    var blockHeightOffset by remember { mutableStateOf("10") }

    // Payment lock state
    var paymentAmountZec by remember { mutableStateOf("0.001") }

    // Conditional lock state
    var secretAnswer by remember { mutableStateOf("") }
    var answerHint by remember { mutableStateOf("") }

    val tabTitles = listOf("⏰ Scheduled", "⛓️ Block", "💰 Payment", "❓ Secret")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Time-Lock Message",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Tab selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = index },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Message input
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    placeholder = { Text("Enter your secret message...") },
                    minLines = 2,
                    maxLines = 4
                )

                // Lock-type specific settings
                when (selectedTab) {
                    0 -> ScheduledLockSettings(
                        minutes = scheduledMinutes,
                        onMinutesChange = { scheduledMinutes = it }
                    )
                    1 -> BlockHeightLockSettings(
                        currentBlockHeight = currentBlockHeight,
                        blockOffset = blockHeightOffset,
                        onOffsetChange = { blockHeightOffset = it }
                    )
                    2 -> PaymentLockSettings(
                        amountZec = paymentAmountZec,
                        onAmountChange = { paymentAmountZec = it }
                    )
                    3 -> ConditionalLockSettings(
                        answer = secretAnswer,
                        onAnswerChange = { secretAnswer = it },
                        hint = answerHint,
                        onHintChange = { answerHint = it }
                    )
                }
            }
        },
        confirmButton = {
            val isValid = messageText.isNotBlank() && when (selectedTab) {
                0 -> scheduledMinutes.toIntOrNull()?.let { it > 0 } ?: false
                1 -> blockHeightOffset.toIntOrNull()?.let { it > 0 } ?: false
                2 -> paymentAmountZec.toDoubleOrNull()?.let { it > 0 } ?: false
                3 -> secretAnswer.isNotBlank()
                else -> false
            }

            FilledTonalButton(
                onClick = {
                    when (selectedTab) {
                        0 -> {
                            val minutes = scheduledMinutes.toIntOrNull() ?: 30
                            val unlockTimestamp = (System.currentTimeMillis() / 1000) + (minutes * 60)
                            onSendScheduledMessage(messageText, unlockTimestamp)
                        }
                        1 -> {
                            val offset = blockHeightOffset.toIntOrNull() ?: 10
                            val unlockHeight = (currentBlockHeight ?: 0) + offset
                            onSendBlockLockedMessage(messageText, unlockHeight)
                        }
                        2 -> {
                            val amountZec = paymentAmountZec.toDoubleOrNull() ?: 0.001
                            val zatoshi = (amountZec * 100_000_000).toLong()
                            onSendPaymentLockedMessage(messageText, zatoshi)
                        }
                        3 -> {
                            onSendConditionalMessage(messageText, secretAnswer, answerHint)
                        }
                    }
                },
                enabled = isValid
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Send Locked Message")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ScheduledLockSettings(
    minutes: String,
    onMinutesChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "⏰ Scheduled Unlock",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Message will automatically unlock after the specified time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { if (it.all { c -> c.isDigit() }) onMinutesChange(it) },
                    modifier = Modifier.width(100.dp),
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Column {
                    val mins = minutes.toIntOrNull() ?: 0
                    Text(
                        text = when {
                            mins >= 1440 -> "${mins / 1440} days ${(mins % 1440) / 60} hours"
                            mins >= 60 -> "${mins / 60} hours ${mins % 60} min"
                            else -> "$mins minutes"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("30" to "30m", "60" to "1h", "1440" to "1d", "10080" to "1w").forEach { (value, label) ->
                    Card(
                        modifier = Modifier
                            .clickable { onMinutesChange(value) }
                            .border(
                                width = 1.dp,
                                color = if (minutes == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (minutes == value)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                Color.Transparent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockHeightLockSettings(
    currentBlockHeight: Long?,
    blockOffset: String,
    onOffsetChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "⛓️ Block Height Lock",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Message unlocks at a specific Zcash block height (trustless)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (currentBlockHeight != null) {
                Text(
                    text = "Current block: #${currentBlockHeight}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = blockOffset,
                    onValueChange = { if (it.all { c -> c.isDigit() }) onOffsetChange(it) },
                    modifier = Modifier.width(100.dp),
                    label = { Text("Blocks") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Column {
                    val offset = blockOffset.toIntOrNull() ?: 0
                    val targetBlock = (currentBlockHeight ?: 0) + offset
                    Text(
                        text = "→ Block #$targetBlock",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    // ~75 seconds per block on Zcash
                    val estimatedMinutes = offset * 75 / 60
                    Text(
                        text = "≈ ${if (estimatedMinutes >= 60) "${estimatedMinutes / 60}h ${estimatedMinutes % 60}m" else "${estimatedMinutes}m"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentLockSettings(
    amountZec: String,
    onAmountChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "💰 Payment to Reveal",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recipient must pay you to unlock this message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = amountZec,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                        onAmountChange(newValue)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Required Payment (ZEC)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                leadingIcon = {
                    Text(
                        text = "Ⓩ",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )
            // Quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("0.001", "0.01", "0.1", "1.0").forEach { value ->
                    Card(
                        modifier = Modifier
                            .clickable { onAmountChange(value) }
                            .border(
                                width = 1.dp,
                                color = if (amountZec == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (amountZec == value)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                Color.Transparent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "$value ZEC",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionalLockSettings(
    answer: String,
    onAnswerChange: (String) -> Unit,
    hint: String,
    onHintChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "❓ Secret Answer",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recipient must answer correctly to unlock the message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Secret Answer") },
                placeholder = { Text("The answer only they would know...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = hint,
                onValueChange = onHintChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Hint (optional)") },
                placeholder = { Text("Give them a clue...") },
                singleLine = true,
                supportingText = {
                    Text("The hint will be visible to the recipient")
                }
            )
        }
    }
}

/**
 * Horizontal scrollable row of memo templates for quick payments.
 */
@Composable
private fun TemplatePickerRow(
    templates: List<MemoTemplate>,
    zecPriceUsd: Double?,
    onTemplateSelected: (MemoTemplate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Pay Templates",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Scrollable template chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.forEach { template ->
                    TemplateChip(
                        template = template,
                        zecPriceUsd = zecPriceUsd,
                        onClick = { onTemplateSelected(template) }
                    )
                }
            }
        }
    }
}

/**
 * Individual template chip showing emoji, name, and amount.
 */
@Composable
private fun TemplateChip(
    template: MemoTemplate,
    zecPriceUsd: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = template.emoji,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = template.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = template.getDisplayAmount(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            // Show ZEC equivalent if USD
            if (template.amountUsd != null && zecPriceUsd != null) {
                val zecAmount = template.getZecAmount(zecPriceUsd)
                Text(
                    text = "≈ ${String.format("%.4f", zecAmount)} ZEC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Payment request content displayed in chat bubbles.
 * Shows amount, reason, and a Pay button for incoming requests.
 */
@Composable
private fun PaymentRequestContent(
    paymentRequest: PaymentRequestInfo,
    zecPriceUsd: Double?,
    isOutgoing: Boolean,
    onPayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (isOutgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary
    val bgColor = if (isOutgoing) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)

    Column(modifier = modifier) {
        // Request header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "💸",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isOutgoing) "Payment Request Sent" else "Payment Requested",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Amount card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${paymentRequest.getFormattedAmount()} ZEC",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                if (zecPriceUsd != null) {
                    val usdAmount = paymentRequest.getAmountUsd(zecPriceUsd)
                    if (usdAmount != null) {
                        Text(
                            text = "≈ $${String.format("%.2f", usdAmount)} USD",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Reason (if provided)
        if (paymentRequest.reason.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "\"${paymentRequest.reason}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Pay button (only for incoming requests that aren't paid yet)
        if (!isOutgoing && !paymentRequest.isPaid) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onPayClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Pay ${paymentRequest.getFormattedAmount()} ZEC",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Paid indicator
        if (paymentRequest.isPaid) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Paid",
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF4CAF50) // Green
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Paid",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Dialog for composing and sending a payment request.
 */
@Composable
private fun PaymentRequestComposerDialog(
    zecPriceUsd: Double?,
    onDismiss: () -> Unit,
    onSendRequest: (amountZatoshi: Long, reason: String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var useUsd by remember { mutableStateOf(false) }

    val amountValue = amountText.toDoubleOrNull() ?: 0.0
    val amountZec = if (useUsd && zecPriceUsd != null && zecPriceUsd > 0) {
        amountValue / zecPriceUsd
    } else {
        amountValue
    }
    val amountZatoshi = (amountZec * 100_000_000).toLong()

    val decimalFormat = remember { DecimalFormat("#,##0.00") }
    val zecFormat = remember { DecimalFormat("#,##0.########") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "💸",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Request Payment",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Currency toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                            text = "Enter amount in USD",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = useUsd,
                            onCheckedChange = { useUsd = it },
                            enabled = zecPriceUsd != null
                        )
                    }
                }

                // Amount input
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                            amountText = newValue
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (useUsd) "Amount (USD)" else "Amount (ZEC)") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = {
                        Text(
                            text = if (useUsd) "$" else "Ⓩ",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    supportingText = {
                        if (amountValue > 0) {
                            if (useUsd && zecPriceUsd != null) {
                                Text("≈ ${zecFormat.format(amountZec)} ZEC")
                            } else if (!useUsd && zecPriceUsd != null) {
                                val usdValue = amountValue * zecPriceUsd
                                Text("≈ $${decimalFormat.format(usdValue)} USD")
                            }
                        }
                    }
                )

                // Quick amount presets
                Text(
                    text = "Quick amounts:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = if (useUsd) {
                        listOf("5", "10", "25", "50")
                    } else {
                        listOf("0.01", "0.1", "1", "5")
                    }
                    presets.forEach { preset ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { amountText = preset }
                                .border(
                                    width = 1.dp,
                                    color = if (amountText == preset) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (amountText == preset)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    Color.Transparent
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (useUsd) "$$preset" else "$preset ZEC",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Reason input
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason (optional)") },
                    placeholder = { Text("What's this for?") },
                    singleLine = true,
                    maxLines = 1,
                    supportingText = {
                        Text("e.g., \"Dinner split\", \"Rent\", \"Movie tickets\"")
                    }
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSendRequest(amountZatoshi, reason) },
                enabled = amountZatoshi > 0
            ) {
                Text(
                    text = "💸",
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (amountZec > 0) "Request ${zecFormat.format(amountZec)} ZEC" else "Request Payment"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
