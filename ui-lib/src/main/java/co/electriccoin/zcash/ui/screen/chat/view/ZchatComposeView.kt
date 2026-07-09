package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.cyberpunk.CyberButtonFullWidth
import co.electriccoin.zcash.ui.design.component.cyberpunk.CyberButtonType
import co.electriccoin.zcash.ui.design.component.cyberpunk.GlassSurface
import co.electriccoin.zcash.ui.design.theme.modifiers.cyanGlow
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZchatComposeView(state: ZchatComposeState) {
    // SECURITY (privacy): New Chat exposes the recipient address, the typed first message, and the
    // contact list — block screenshots / screen-recording / app-switcher thumbnail while foregrounded
    // (same as ChatListView / ChatDetailView).
    if (shouldSecureScreen) {
        SecureScreen()
    }
    when (state) {
        is ZchatComposeState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = chatColors().primary)
            }
        }
        is ZchatComposeState.Error -> {
            ComposeErrorView(state)
        }
        is ZchatComposeState.Ready -> {
            ComposeReadyView(state)
        }
        is ZchatComposeState.SendSuccess -> {
            SendSuccessView(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeErrorView(state: ZchatComposeState.Error) {
    Scaffold(
        containerColor = chatColors().background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "New Chat",
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = chatColors().textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = state.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = chatColors().textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = chatColors().surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = state.message,
                color = chatColors().error,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = state.onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent
                ),
                shape = RoundedCornerShape(NightwireColors.RadiusButton)
            ) {
                Text("Retry", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeReadyView(state: ZchatComposeState.Ready) {
    Scaffold(
        containerColor = chatColors().background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "New Chat",
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = chatColors().textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = state.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = chatColors().textPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = state.onScanQrClick) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = chatColors().primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = chatColors().surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Recipient Address Input
            OutlinedTextField(
                value = state.recipientAddress,
                onValueChange = state.onRecipientChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Recipient Address", color = chatColors().textSecondary) },
                placeholder = { Text("Paste or scan Zcash address...", color = chatColors().textSecondary) },
                singleLine = true,
                isError = state.recipientAddress.isNotEmpty() && !state.isValidAddress,
                supportingText = if (state.recipientAddress.isNotEmpty() && !state.isValidAddress) {
                    { Text("Invalid Zcash address", color = chatColors().error) }
                } else if (state.selectedContact != null) {
                    { Text("Contact: ${state.selectedContact.name}", color = chatColors().primary) }
                } else null,
                trailingIcon = {
                    if (state.isValidAddress && state.selectedContact == null) {
                        IconButton(onClick = state.onShowAddContactDialog) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add to contacts",
                                tint = chatColors().primary
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = chatColors().bgInput,
                    unfocusedContainerColor = chatColors().bgInput,
                    focusedTextColor = chatColors().textPrimary,
                    unfocusedTextColor = chatColors().textPrimary,
                    cursorColor = chatColors().primary,
                    focusedBorderColor = chatColors().borderActive,
                    unfocusedBorderColor = chatColors().borderDefault,
                    errorBorderColor = chatColors().error
                )
            )

            // Conversation-mode selector — shown once a valid recipient is entered, BEFORE the
            // first message is sent. Defaults to Vault (most private). Writes through to the same
            // persisted per-peer value that the chat overflow picker reads/writes.
            // Always visible so the MODE control doesn't surprise users by popping in mid-compose;
            // it's disabled (dimmed, non-interactive) until a valid recipient is entered.
            ConversationModeSelector(
                selected = state.selectedMode,
                enabled = state.isValidAddress,
                openAvailable = state.openAvailable,
                onSelect = state.onModeSelect
            )

            // Contacts Section
            if (state.contacts.isNotEmpty()) {
                Text(
                    text = "CONTACTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = RajdhaniFontFamily,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = chatColors().primary
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(state.contacts, key = { it.address }) { contact ->
                        ContactItem(
                            contact = contact,
                            isSelected = state.selectedContact?.address == contact.address,
                            onClick = { state.onContactSelect(contact) }
                        )
                    }
                }
            } else {
                // Empty contacts message
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = chatColors().textTertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No contacts yet",
                            fontSize = 15.sp,
                            color = chatColors().textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Paste an address or scan QR code",
                            fontSize = 13.sp,
                            color = chatColors().textTertiary
                        )
                    }
                }
            }

            // Message Input Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = chatColors().surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.message,
                        onValueChange = state.onMessageChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        label = { Text("Message", color = chatColors().textSecondary) },
                        placeholder = { Text("Type your message...", color = chatColors().textTertiary) },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = chatColors().bgInput,
                            unfocusedContainerColor = chatColors().bgInput,
                            focusedTextColor = chatColors().textPrimary,
                            unfocusedTextColor = chatColors().textPrimary,
                            cursorColor = chatColors().primary,
                            focusedBorderColor = chatColors().borderActive,
                            unfocusedBorderColor = chatColors().borderDefault
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Message info row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val isOverLimit = state.message.length > state.maxMessageLength
                            Text(
                                text = "${state.message.length} / ${state.maxMessageLength} chars",
                                fontSize = 11.sp,
                                color = if (isOverLimit) chatColors().error else chatColors().textSecondary
                            )
                            if (state.chunkCount > 1) {
                                Text(
                                    text = "${state.chunkCount} chunks",
                                    fontSize = 11.sp,
                                    color = chatColors().primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Available balance — always shown (including "0 ZEC") so a user with an empty
                    // wallet understands why sends / Send All fail. Highlighted red at zero.
                    // Hidden for a free OPEN send: there's no on-chain spend, so balance is irrelevant.
                    if (!state.isFreeOpenSend && state.availableBalanceDisplay.isNotEmpty()) {
                        Text(
                            text = "Available: ${state.availableBalanceDisplay}",
                            fontSize = 11.sp,
                            color = if (state.spendableBalanceZatoshi == 0L)
                                chatColors().error
                            else
                                chatColors().primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (state.isFreeOpenSend) {
                        // Free OPEN send: a NIP-17 gift-wrapped NOSTR DM with NO on-chain spend. Replace
                        // the ZEC amount/adjust card with an honest "Free" indicator (no Adjust action,
                        // since there's nothing to charge).
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = chatColors().success.copy(alpha = 0.12f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Free — sent over NOSTR",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatColors().success
                                )
                                Text(
                                    text = "End-to-end encrypted relay DM. No ZEC, no on-chain transaction.",
                                    fontSize = 11.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {

                    // Amount adjustment row
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = state.onShowAmountDialog),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.isZeroAmount)
                                chatColors().error.copy(alpha = 0.1f)
                            else
                                chatColors().bgElevated
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Amount: ${state.totalAmountDisplay}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (state.isZeroAmount)
                                        chatColors().error
                                    else
                                        chatColors().textPrimary
                                )
                                Text(
                                    text = "Fee: ${state.feeDisplay}",
                                    fontSize = 11.sp,
                                    color = chatColors().textSecondary
                                )
                                if (state.isZeroAmount) {
                                    Text(
                                        text = "Zero amount may be delayed by miners",
                                        fontSize = 11.sp,
                                        color = chatColors().error
                                    )
                                }
                            }
                            TextButton(onClick = state.onShowAmountDialog) {
                                Text("Adjust", color = chatColors().primary)
                            }
                        }
                    }
                    } // end non-free amount card

                    Spacer(modifier = Modifier.height(12.dp))

                    // Send Button — disabled unless the action is actually valid: valid recipient,
                    // non-blank message within the chunk limit, and not already sending. NOTE: a zero
                    // amount is NOT disqualifying — "0 ZEC (Free, may be delayed by miners)" is an
                    // intentional MessageAmount option, so the button stays enabled for it (the red
                    // "may be delayed" warning above already informs the user). The ONLY invalid zero
                    // is Send All on an empty/insufficient wallet, where there is literally nothing to
                    // send — that case is blocked here.
                    val isSendAllWithoutFunds =
                        state.selectedAmount == MessageAmount.SEND_ALL && state.isZeroAmount
                    val sendEnabled = state.isValidAddress &&
                        state.message.isNotBlank() &&
                        state.message.length <= state.maxMessageLength &&
                        !isSendAllWithoutFunds &&
                        !state.isSending
                    Button(
                        onClick = state.onSendClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (sendEnabled) Modifier.shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(NightwireColors.RadiusButton),
                                    ambientColor = chatColors().accentPrimaryGlow,
                                    spotColor = chatColors().accentPrimaryGlow
                                ) else Modifier
                            ),
                        enabled = sendEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = chatColors().primary,
                            contentColor = chatColors().textOnAccent,
                            disabledContainerColor = chatColors().primary.copy(alpha = 0.3f),
                            disabledContentColor = chatColors().textOnAccent.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(NightwireColors.RadiusButton)
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = chatColors().textOnAccent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sending...", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Message", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Add Contact Dialog
        if (state.showAddContactDialog) {
            AddContactDialog(
                address = state.recipientAddress,
                name = state.contactName,
                onNameChange = state.onContactNameChange,
                onConfirm = { state.onAddContact(state.recipientAddress, state.contactName) },
                onDismiss = state.onDismissAddContactDialog
            )
        }

        // Amount Selection Dialog
        if (state.showAmountDialog) {
            AmountSelectionDialog(
                selectedAmount = state.selectedAmount,
                customAmountZatoshi = state.customAmountZatoshi,
                customAmountText = state.customAmountText,
                availableBalanceDisplay = state.availableBalanceDisplay,
                isBalanceZero = state.spendableBalanceZatoshi == 0L,
                sendAllAmountDisplay = state.sendAllAmountDisplay,
                onAmountSelect = state.onAmountSelect,
                onCustomAmountChange = state.onCustomAmountChange,
                onDismiss = state.onDismissAmountDialog
            )
        }
    }
}

/**
 * One-line plain-language description per transport mode (compose / New-Chat context).
 *
 * IMPORTANT: this screen is reached by scanning a peer's ZCASH address, so we do NOT yet have their
 * NOSTR key. Every FIRST message sent from here is therefore an on-chain shielded transaction that
 * costs ZEC — including Open (which has no key to go free over NOSTR, so it sends a plain on-chain
 * memo) and Tunnel (whose first message is the on-chain bootstrap). Only Tunnel becomes free, and
 * only AFTER that bootstrap. The copy below must not claim "free" for the first message.
 */
private fun ConversationMode.composeBlurb(): String = when (this) {
    ConversationMode.VAULT -> "Max privacy. E2E + Quantum Shield, every message on-chain (costs ZEC)."
    // OPEN is only offered here once we hold the peer's NOSTR key (scanned from their ZCHAT contact QR),
    // so it delivers a FREE, end-to-end-encrypted (NIP-17 gift-wrapped) NOSTR DM from the first message.
    // The recipient accepts the request once, then the chat is free both ways.
    ConversationMode.OPEN -> "Free from message #1 — end-to-end encrypted NOSTR DM, no ZEC. Recipient accepts your request once."
    ConversationMode.TUNNEL -> "First message is a one-time on-chain handshake (small ZEC fee); replies + calls are then free over a NOSTR relay."
}

/** Two-to-three-word function tag shown UNDER each mode name on its button (plain-language cue). */
private fun ConversationMode.shortTag(): String = when (this) {
    ConversationMode.VAULT -> "On-chain"
    ConversationMode.OPEN -> "Free / NOSTR"
    ConversationMode.TUNNEL -> "1× on-chain, then free"
}

/**
 * Inline segmented selector for the conversation transport mode. Lets the user choose how the
 * chat travels before the first message is sent. The currently-selected mode shows its one-line
 * description below the chips. Selection is persisted per-peer by the view model.
 */
@Composable
private fun ConversationModeSelector(
    selected: ConversationMode,
    enabled: Boolean,
    // True once we hold the peer's NOSTR key (scanned from their contact QR). OPEN is offered only then,
    // because only then can it deliver a free NOSTR DM from message #1.
    openAvailable: Boolean,
    onSelect: (ConversationMode) -> Unit
) {
    // First-use note before switching to Open: it travels over a public NOSTR relay and the recipient
    // must accept the request once. Shown once per screen visit (no nag once acknowledged or if Open is
    // already the active mode).
    var showOpenWarning by remember { mutableStateOf(false) }
    var openAcknowledged by remember { mutableStateOf(selected == ConversationMode.OPEN) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Text(
            text = "MODE",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = RajdhaniFontFamily,
            letterSpacing = 1.sp,
            color = chatColors().primary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // VAULT (private on-chain) and TUNNEL (one on-chain bootstrap, then free NOSTR + calls) work
            // from any Zcash address. OPEN delivers a FREE NOSTR DM from message #1, but ONLY when we
            // already hold the peer's NOSTR key — which we do when the user scanned the peer's ZCHAT
            // contact QR ([openAvailable]). Without that key OPEN has nowhere to route, so it's omitted
            // here (and the New-Chat default coerces OPEN→TUNNEL). When the key IS known we offer OPEN so
            // the first message goes out free immediately.
            val modes = if (openAvailable) {
                listOf(ConversationMode.VAULT, ConversationMode.TUNNEL, ConversationMode.OPEN)
            } else {
                listOf(ConversationMode.VAULT, ConversationMode.TUNNEL)
            }
            modes.forEach { mode ->
                val isSelected = mode == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected)
                                chatColors().primary.copy(alpha = 0.15f)
                            else
                                chatColors().bgElevated
                        )
                        .clickable(enabled = enabled) {
                            // Intercept the switch TO Open the first time to warn about the privacy
                            // trade-off; every other selection applies immediately.
                            if (mode == ConversationMode.OPEN && !openAcknowledged) {
                                showOpenWarning = true
                            } else {
                                onSelect(mode)
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = mode.label(),
                            fontSize = 14.sp,
                            fontFamily = RajdhaniFontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) chatColors().primary else chatColors().textSecondary
                        )
                        // Plain-language function cue under every mode name (not just the selected one).
                        Text(
                            text = mode.shortTag(),
                            fontSize = 10.sp,
                            fontFamily = RajdhaniFontFamily,
                            color = if (isSelected)
                                chatColors().primary.copy(alpha = 0.8f)
                            else
                                chatColors().textSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        if (showOpenWarning) {
            AlertDialog(
                onDismissRequest = { showOpenWarning = false },
                title = { Text("Open mode: free over a public relay") },
                text = {
                    Text(
                        "Open sends a free, end-to-end-encrypted (gift-wrapped) NOSTR DM from the first " +
                            "message — no ZEC, no on-chain transaction. It travels over a public NOSTR " +
                            "relay, so delivery depends on that relay staying online, and your contact " +
                            "must accept your message request once before the chat is live both ways.\n\n" +
                            "Use Vault or Tunnel if you prefer on-chain metadata privacy."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        openAcknowledged = true
                        showOpenWarning = false
                        onSelect(ConversationMode.OPEN)
                    }) { Text("Use Open") }
                },
                dismissButton = {
                    TextButton(onClick = { showOpenWarning = false }) { Text("Cancel") }
                }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (enabled) {
                selected.composeBlurb()
            } else {
                "Enter a recipient to choose how this chat is delivered."
            },
            fontSize = 12.sp,
            color = chatColors().textSecondary
        )
    }
}

@Composable
private fun ContactItem(
    contact: Contact,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                chatColors().primary.copy(alpha = 0.15f)
            else
                chatColors().surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar — stored local photo when set; else initials on the deterministic per-address
            // color so two contacts whose addresses share the same leading/trailing characters are
            // still visually distinguishable, reducing the risk of tapping the wrong recipient and
            // sending funds to the wrong address. solid=isSelected keeps the selected state visible.
            ZchatAvatar(
                ref = ZchatAvatarRef.Contact(contact.address),
                displayName = contact.name,
                size = 40.dp,
                solid = isSelected
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = chatColors().textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Wider leading/trailing window than the old take(8)/takeLast(6): two distinct
                // unified addresses sharing only the first 8 + last 6 chars would otherwise render
                // identically. More disambiguating chars + the per-address avatar color above.
                Text(
                    text = "${contact.address.take(16)}...${contact.address.takeLast(12)}",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AddContactDialog(
    address: String,
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chatColors().bgElevated,
        titleContentColor = chatColors().textPrimary,
        textContentColor = chatColors().textSecondary,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Add to Contacts", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // Show a wide leading/trailing window so the user can verify they are adding the
                // intended address — confirming a near-identical-looking address to contacts is a
                // money-movement footgun. Allowed to wrap to a second line.
                Text(
                    text = "Address: ${address.take(20)}...${address.takeLast(16)}",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Contact Name", color = chatColors().textSecondary) },
                    placeholder = { Text("Enter name...", color = chatColors().textTertiary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = chatColors().bgInput,
                        unfocusedContainerColor = chatColors().bgInput,
                        focusedTextColor = chatColors().textPrimary,
                        unfocusedTextColor = chatColors().textPrimary,
                        cursorColor = chatColors().primary,
                        focusedBorderColor = chatColors().borderActive,
                        unfocusedBorderColor = chatColors().borderDefault
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Add", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = chatColors().textSecondary)
            }
        }
    )
}

@Composable
private fun AmountSelectionDialog(
    selectedAmount: MessageAmount,
    customAmountZatoshi: Long,
    customAmountText: String,
    availableBalanceDisplay: String,
    isBalanceZero: Boolean,
    sendAllAmountDisplay: String,
    onAmountSelect: (MessageAmount) -> Unit,
    onCustomAmountChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Local text state for custom amount to prevent glitching from round-trip conversion
    var localCustomText by remember { mutableStateOf(customAmountText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chatColors().bgElevated,
        titleContentColor = chatColors().textPrimary,
        textContentColor = chatColors().textSecondary,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Message Amount", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "Amount of ZEC to send with each message chunk",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
                if (availableBalanceDisplay.isNotEmpty()) {
                    Text(
                        text = "Available: $availableBalanceDisplay",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBalanceZero) chatColors().error else chatColors().primary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Amount options
                MessageAmount.entries.forEach { amount ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onAmountSelect(amount) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedAmount == amount)
                                chatColors().primary.copy(alpha = 0.15f)
                            else if (amount == MessageAmount.ZERO)
                                chatColors().error.copy(alpha = 0.1f)
                            else if (amount == MessageAmount.SEND_ALL)
                                chatColors().success.copy(alpha = 0.1f)
                            else
                                chatColors().surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = amount.label,
                                    fontSize = 15.sp,
                                    fontWeight = if (selectedAmount == amount)
                                        FontWeight.Bold
                                    else
                                        FontWeight.Normal,
                                    color = if (amount == MessageAmount.ZERO)
                                        chatColors().error
                                    else
                                        chatColors().textPrimary
                                )
                                Text(
                                    text = amount.description,
                                    fontSize = 11.sp,
                                    color = if (amount == MessageAmount.ZERO)
                                        chatColors().error.copy(alpha = 0.7f)
                                    else
                                        chatColors().textSecondary
                                )
                                // Show recipient amount for Send All
                                if (amount == MessageAmount.SEND_ALL &&
                                    selectedAmount == MessageAmount.SEND_ALL &&
                                    sendAllAmountDisplay.isNotEmpty()
                                ) {
                                    Text(
                                        text = sendAllAmountDisplay,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = chatColors().primary
                                    )
                                }
                            }
                            if (selectedAmount == amount) {
                                Text(
                                    text = "\u2713",
                                    color = chatColors().primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Custom amount input (shown when CUSTOM is selected)
                if (selectedAmount == MessageAmount.CUSTOM) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = localCustomText,
                        onValueChange = { newText ->
                            localCustomText = newText
                            onCustomAmountChange(newText)
                        },
                        label = { Text("Custom Amount (ZEC)", color = chatColors().textSecondary) },
                        placeholder = { Text("0.00001", color = chatColors().textTertiary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = chatColors().bgInput,
                            unfocusedContainerColor = chatColors().bgInput,
                            focusedTextColor = chatColors().textPrimary,
                            unfocusedTextColor = chatColors().textPrimary,
                            cursorColor = chatColors().primary,
                            focusedBorderColor = chatColors().borderActive,
                            unfocusedBorderColor = chatColors().borderDefault
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Done", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun SendSuccessView(state: ZchatComposeState.SendSuccess) {
    // Scale animation for the icon
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val iconScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.3f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "icon_scale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 600, delayMillis = 200),
        label = "content_alpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(chatColors().background)) {
        // Circuit pattern background at low opacity
        Image(
            painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.bg_cyber_circuit_pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(0.15f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Neon send icon with glow + scale animation
            Image(
                painter = painterResource(id = co.electriccoin.zcash.ui.design.R.drawable.ic_cyber_send),
                contentDescription = "Message Sent",
                modifier = Modifier
                    .size(160.dp)
                    .scale(iconScale)
                    .cyanGlow(radius = 24.dp, alpha = 0.5f, cornerRadius = 80.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(24.dp))

            // "MESSAGE SENT" in Orbitron with gradient
            Text(
                text = "MESSAGE SENT",
                style = TextStyle(
                    fontFamily = RajdhaniFontFamily,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(chatColors().primary, chatColors().accentSecondary)
                    )
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Encrypted & delivered to the blockchain",
                style = TextStyle(fontSize = 14.sp, color = chatColors().textSecondary)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Recipient address pill in glass surface
            GlassSurface(
                cornerRadius = 20.dp,
                contentPadding = 12.dp,
                borderColor = chatColors().primary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${state.recipientAddress.take(10)}...${state.recipientAddress.takeLast(10)}",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = chatColors().primary,
                        fontFamily = RajdhaniFontFamily,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Action buttons
            if (state.isNewContact) {
                CyberButtonFullWidth(
                    text = "ADD TO CONTACTS",
                    onClick = state.onAddToContacts,
                    type = CyberButtonType.Primary
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            CyberButtonFullWidth(
                text = "DONE",
                onClick = state.onDone,
                type = CyberButtonType.Ghost
            )
        }
    }
}
