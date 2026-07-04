package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupSendResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Group chat detail view for viewing and sending messages in a group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailView(
    state: GroupDetailState,
    isSendingMessage: Boolean,
    sendResult: GroupSendResult?,
    onSendResultConsumed: () -> Unit,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSyncKeys: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = chatColors()

    when (state) {
        is GroupDetailState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = colors.primary)
            }
        }
        is GroupDetailState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Error",
                        fontSize = 17.sp,
                        color = colors.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        color = colors.textSecondary
                    )
                }
            }
        }
        is GroupDetailState.Success -> {
            GroupDetailContent(
                conversation = state.conversation,
                currentUserAddress = state.currentUserAddress,
                isSendingMessage = isSendingMessage,
                sendResult = sendResult,
                onSendResultConsumed = onSendResultConsumed,
                onBackClick = onBackClick,
                onSettingsClick = onSettingsClick,
                onSendMessage = onSendMessage,
                onDraftChange = onDraftChange,
                onSyncKeys = onSyncKeys,
                colors = colors,
                modifier = modifier
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailContent(
    conversation: GroupConversation,
    currentUserAddress: String,
    isSendingMessage: Boolean,
    sendResult: GroupSendResult?,
    onSendResultConsumed: () -> Unit,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSyncKeys: () -> Unit,
    colors: ChatColors,
    modifier: Modifier = Modifier
) {
    var messageText by remember(conversation.draft) {
        mutableStateOf(conversation.draft ?: "")
    }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // P0.5 — armed at send-tap; the composer is cleared only when the send completes and something
    // actually went out (success / partial delivery), never optimistically at tap time — a silent
    // failure used to destroy the typed message.
    var awaitingSendOutcome by remember { mutableStateOf(false) }

    // P0.1 — surface the send/invite outcome as a visible banner (was Log.w + Toast-only), and
    // resolve the deferred composer clear above.
    LaunchedEffect(isSendingMessage, sendResult) {
        if (isSendingMessage) return@LaunchedEffect
        val result = sendResult
        // SF-2 — the event flow is process-wide, so an event from ANOTHER group can appear here.
        // Ignore it entirely: don't consume it (the other group still needs it), don't clear this
        // composer, don't show a snackbar for a send that wasn't ours.
        if (result != null && result.groupId != conversation.groupInfo.groupId) return@LaunchedEffect
        if (awaitingSendOutcome) {
            awaitingSendOutcome = false
            val nothingTransmitted =
                result is GroupSendResult.NoActiveRecipients || result is GroupSendResult.AllFailed
            if (!nothingTransmitted) {
                messageText = ""
            }
        }
        if (result != null) {
            onSendResultConsumed()
            snackbarHostState.showSnackbar(
                when (result) {
                    is GroupSendResult.NoActiveRecipients ->
                        "Waiting for members to accept your invite before your message can send"
                    is GroupSendResult.AllFailed ->
                        "Message couldn't be delivered to anyone — check your balance and try again"
                    is GroupSendResult.PartialDelivery ->
                        "Delivered to ${result.sent} of ${result.total} members — the rest failed"
                    is GroupSendResult.InviteFailed ->
                        "Couldn't invite ${result.addresses.size} member(s) — " +
                            "check balance and Resend from group settings"
                }
            )
        }
    }

    // Auto-save draft with debounce. P0.5: paused while a send outcome is pending — the ViewModel
    // owns the draft during a send (it clears it only on success), and a transient composer state
    // must not overwrite a message that may still need to be retried.
    LaunchedEffect(messageText, awaitingSendOutcome) {
        if (awaitingSendOutcome) return@LaunchedEffect
        if (messageText != (conversation.draft ?: "")) {
            kotlinx.coroutines.delay(500L)
            onDraftChange(messageText)
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(conversation.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onSettingsClick)
                    ) {
                        // Group avatar — stored local photo when set (editable by the admin from
                        // Group Settings), else the shared gradient placeholder. Tapping the header
                        // already opens settings, where the admin-gated edit lives.
                        ZchatAvatar(
                            ref = ZchatAvatarRef.Group(conversation.groupInfo.groupId),
                            displayName = conversation.groupInfo.name,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = conversation.groupInfo.name,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = conversation.memberCountLabel,
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Group Settings",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.background,
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Messages list
            if (conversation.messages.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(colors.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No messages yet",
                            fontSize = 17.sp,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Send the first message!",
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                        // #10 — persistent explanation when the group has invitees who haven't accepted
                        // yet (total > active). The send path already shows a transient snackbar
                        // ("Waiting for members to accept…"), but it auto-dismisses, so a creator whose
                        // first message reached nobody saw an empty thread with no lasting reason. This
                        // durable hint keeps the "why nothing sends yet" visible until someone joins.
                        val pendingInvites = conversation.members.size - conversation.activeMemberCount
                        if (pendingInvites > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Waiting for $pendingInvites invited member" +
                                    (if (pendingInvites == 1) "" else "s") +
                                    " to accept — your messages will send once someone joins.",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 40.dp)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = conversation.messages.sortedWith { a, b ->
                            GroupMessage.compareForOrdering(a, b)
                        },
                        key = { it.id }
                    ) { message ->
                        GroupMessageBubble(
                            message = message,
                            isOwnMessage = message.senderAddress == currentUserAddress,
                            onSyncKeys = onSyncKeys,
                            colors = colors
                        )
                    }
                }
            }

            // Message input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("Message...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.background,
                        focusedContainerColor = colors.background,
                        unfocusedContainerColor = colors.background,
                        cursorColor = colors.primary
                    ),
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (messageText.isNotBlank() && !isSendingMessage)
                                colors.primary
                            else
                                colors.primary.copy(alpha = 0.5f)
                        )
                        .clickable(
                            enabled = messageText.isNotBlank() && !isSendingMessage
                        ) {
                            // P0.5 — do NOT clear the composer here: it's cleared only after the
                            // send reports an outcome that transmitted something (see the outcome
                            // LaunchedEffect above), so a failed send can't destroy the message.
                            awaitingSendOutcome = true
                            onSendMessage(messageText)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSendingMessage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    isOwnMessage: Boolean,
    onSyncKeys: () -> Unit,
    colors: ChatColors
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm")
    }
    val isUndecryptable = message.isUndecryptable

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isOwnMessage) colors.outgoingBubble else colors.incomingBubble
                )
                // Tap an undecryptable bubble to reload group state and re-derive missing keys.
                .then(
                    if (isUndecryptable) Modifier.clickable(onClick = onSyncKeys) else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Sender name (for other people's messages)
            if (!isOwnMessage) {
                Text(
                    text = "${message.senderAddress.take(8)}...",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Own message renders ON the outgoing bubble, which is dark-teal in EVERY theme — so its
            // foreground must be LIGHT in every theme. textOnAccent is light only in LIGHT themes (it is
            // near-black 0xFF080B12 in Nightwire dark = the on-bright-accent color, NOT an on-dark-bubble
            // color); textPrimary is light only in DARK themes. Pick per theme (matches ChatDetailView #249).
            val onBubble = if (colors.isLight) colors.textOnAccent else colors.textPrimary

            // Message content
            Text(
                text = message.displayText,
                color = if (isOwnMessage) onBubble else colors.textPrimary,
                fontSize = 15.sp
            )

            // Recovery action for messages this device can't decrypt (missing group key).
            if (isUndecryptable) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap to sync group keys",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary
                )
            }

            // Timestamp and status
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.timestamp
                        .atZone(ZoneId.systemDefault())
                        .format(timeFormatter),
                    fontSize = 10.sp,
                    color = if (isOwnMessage)
                        onBubble.copy(alpha = 0.8f)
                    else
                        colors.textSecondary
                )

                if (isOwnMessage) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            message.isFailed -> "!"
                            message.isPending -> "⏱"
                            else -> "✓"
                        },
                        fontSize = 10.sp,
                        color = when {
                            message.isFailed -> colors.error
                            message.isPending -> onBubble.copy(alpha = 0.8f)
                            else -> onBubble.copy(alpha = 0.8f)
                        }
                    )
                }
            }
        }
    }
}
