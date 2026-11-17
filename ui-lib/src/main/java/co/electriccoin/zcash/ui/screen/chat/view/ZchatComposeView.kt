package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactCodeNotice
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.model.UpgradeReminders
import co.electriccoin.zcash.ui.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.component.AmountReadingChooser
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.component.rememberAmountTextFieldSelection
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.screen.chat.util.MAX_AMOUNT_DECIMALS
import co.electriccoin.zcash.ui.screen.chat.util.acceptAmountInputResult
import co.electriccoin.zcash.ui.screen.chat.util.amountFieldTransformation
import co.electriccoin.zcash.ui.screen.chat.util.decimalSeparatorOf
import co.electriccoin.zcash.ui.screen.chat.model.zecDisplay

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
        is ZchatComposeState.MayHaveBeenSent -> {
            MayHaveBeenSentView(state)
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
                        stringResource(R.string.chat_new_chat),
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = chatColors().textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = state.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.new_chat_back),
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
                text = ChatSendCopy.localizedResource(state.message)?.let { stringResource(it) } ?: state.message,
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
                Text(stringResource(R.string.new_chat_retry), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** ZC-979: the most the contacts list takes of the New Chat form before it scrolls on its own. */
private val CONTACTS_MAX_HEIGHT = 240.dp

/** QA2 Q2-23 (ZC-1622): the room under the form, one button height, so Send Message clears the bottom edge. */
private val SEND_BUTTON_BOTTOM_ROOM = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeReadyView(state: ZchatComposeState.Ready) {
    // "Paste code" entry for a ZCHAT contact code (zchat:c1?z=…) — the free-OPEN path when the user
    // received the code as text (chat forward, email) rather than a scannable QR. Confirm feeds the
    // existing onRecipientChange parser, which stores the peer's NOSTR key + fills the address.
    var showPasteCodeDialog by remember { mutableStateOf(false) }
    var pastedCode by remember { mutableStateOf("") }
    Scaffold(
        containerColor = chatColors().background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.chat_new_chat),
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = chatColors().textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = state.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.new_chat_back),
                            tint = chatColors().textPrimary
                        )
                    }
                },
                // QA2 Q2-36 (ZC-1631): no Scan icon here; "Scan a code" below is the one scan entry.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = chatColors().surface
                )
            )
        }
    ) { paddingValues ->
        // ZC-979 / ZC-632: the form scrolls, so at 360 dp and a large font the Send button below the composer card can
        // always be reached. imePadding stays before the scroll: the keyboard still pushes the form up, and a focused
        // field is brought into view inside it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // ZC-1461: the two ways to start: scan a friend's code, or show them mine. Always shown.
            NewChatStartButtons(onScanClick = state.onScanQrClick, onMyCodeClick = state.onMyCodeClick)

            // Recipient Address Input
            OutlinedTextField(
                value = state.recipientAddress,
                onValueChange = state.onRecipientChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.new_chat_recipient_label), color = chatColors().textSecondary) },
                placeholder = { Text(stringResource(R.string.new_chat_recipient_placeholder), color = chatColors().textSecondary) },
                singleLine = true,
                isError = state.recipientAddress.isNotEmpty() && !state.isValidAddress,
                supportingText = if (state.codeNotice != null) {
                    // M1: why the pasted or scanned code bound nothing (replaces the plain "invalid address" line).
                    { Text(contactCodeNoticeText(state.codeNotice), color = chatColors().error) }
                } else if (state.isOwnAddress) {
                    { Text(stringResource(R.string.new_chat_own_address), color = chatColors().error) }
                } else if (state.recipientAddress.isNotEmpty() && !state.isValidAddress) {
                    { Text(stringResource(R.string.new_chat_invalid_address), color = chatColors().error) }
                } else if (state.selectedContact != null) {
                    {
                        Text(
                            stringResource(R.string.new_chat_selected_contact, contactDisplayName(state.selectedContact.name)),
                            color = chatColors().primary
                        )
                    }
                } else null,
                trailingIcon = {
                    if (state.isValidAddress && state.selectedContact == null) {
                        IconButton(onClick = state.onShowAddContactDialog) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.new_chat_add_to_contacts),
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

            // M1: the "already belongs to" notice opens the chat that holds the code's messaging key.
            val keyAlreadyBound = state.codeNotice as? ContactCodeNotice.KeyAlreadyBound
            if (keyAlreadyBound != null) {
                TextButton(
                    onClick = { state.onOpenExistingChat(keyAlreadyBound.existingPeer) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.new_chat_code_open_existing_chat), color = chatColors().primary)
                }
            }

            // Conversation-mode selector — shown once a valid recipient is entered, BEFORE the
            // first message is sent. Smart default: Open when the peer's contact-code key is known,
            // else Tunnel (see ZchatComposeVM.syncModeForRecipient). Writes through to the same
            // persisted per-peer value that the chat overflow picker reads/writes.
            // Always visible so the MODE control doesn't surprise users by popping in mid-compose;
            // it's disabled (dimmed, non-interactive) until a valid recipient is entered.
            if (state.opensExistingChat) {
                // Round 8 P4 (F6): the recipient already has a chat. Nothing is sent from here and its mode is its own,
                // so the picker gives way to a note saying where the message goes.
                Text(
                    text = stringResource(R.string.new_chat_existing_chat_note),
                    fontSize = 12.sp,
                    color = chatColors().textSecondary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                ConversationModeSelector(
                    selected = state.selectedMode,
                    enabled = state.isValidAddress,
                    openAvailable = state.openAvailable,
                    // Contact-code acquisition (#35): the scan flow already parses ZCHAT contact QRs and
                    // persists the peer's NOSTR key; paste opens the local code dialog below.
                    onScanContactCode = state.onScanQrClick,
                    onPasteContactCode = { showPasteCodeDialog = true },
                    onSelect = state.onModeSelect
                )
            }

            // ZC-733-B: saved names that cannot be read are said above the list.
            UnreadableStoreNotice(namesUnreadable = state.contactNamesUnreadable)

            // Contacts Section
            if (state.contacts.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.new_chat_contacts_header),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = RajdhaniFontFamily,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = chatColors().primary
                )

                // ZC-979: bounded, so the list can live inside the scrolling form and scroll on its own.
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = CONTACTS_MAX_HEIGHT)
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
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
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
                            text = stringResource(R.string.new_chat_no_contacts),
                            fontSize = 15.sp,
                            color = chatColors().textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.new_chat_no_contacts_hint),
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
                        label = { Text(stringResource(R.string.new_chat_message_label), color = chatColors().textSecondary) },
                        placeholder = { Text(stringResource(R.string.new_chat_message_placeholder), color = chatColors().textTertiary) },
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
                                text = stringResource(R.string.new_chat_char_count, state.message.length, state.maxMessageLength),
                                fontSize = 11.sp,
                                color = if (isOverLimit) chatColors().error else chatColors().textSecondary
                            )
                            if (state.chunkCount > 1) {
                                Text(
                                    text = stringResource(R.string.new_chat_chunk_count, state.chunkCount),
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
                    if (!state.opensExistingChat && !state.isFreeOpenSend && state.availableBalanceDisplay.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.new_chat_available, state.availableBalanceDisplay),
                            fontSize = 11.sp,
                            color = if (state.spendableBalanceZatoshi == 0L)
                                chatColors().error
                            else
                                chatColors().primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (state.opensExistingChat) {
                        // Round 8 P4: nothing is sent or spent from here, so there is no amount to show.
                    } else if (state.isFreeOpenSend) {
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
                                    text = stringResource(R.string.new_chat_free_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatColors().success
                                )
                                Text(
                                    text = stringResource(R.string.new_chat_free_desc),
                                    fontSize = 11.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    } else if (state.isValidAddress) {
                        // QA2 Q2-36 (ZC-1631): the amount and fee are shown once there is a recipient to pay.
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
                                        // QA2 Q2-91 (ZC-1664): the amount without the ZChat fee; the text names it.
                                        text = stringResource(R.string.new_chat_amount, state.amountDisplay),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (state.isZeroAmount)
                                            chatColors().error
                                        else
                                            chatColors().textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.new_chat_fee, state.feeDisplay),
                                        fontSize = 11.sp,
                                        color = chatColors().textSecondary
                                    )
                                    if (state.isAmountOverBalance) {
                                        // QA2 Q2-84 (ZC-1660): the custom amount is more than the available balance.
                                        Text(
                                            text =
                                                stringResource(
                                                    R.string.new_chat_amount_over_balance,
                                                    state.availableBalanceDisplay
                                                ),
                                            fontSize = 11.sp,
                                            color = chatColors().warning
                                        )
                                    }
                                    if (state.isZeroAmount) {
                                        // Post dust-coerce, a zero per-output amount is only reachable via
                                        // "Send All" before it has a priced amount — there is nothing to send
                                        // yet (the Send button is disabled for this case below). The VM says
                                        // why: pricing, no recipient yet, or the reason and its number.
                                        Text(
                                            text =
                                                ChatSendCopy.localizedResource(state.sendAllNote)?.let { stringResource(it) }
                                                    ?: state.sendAllNote.ifEmpty {
                                                        stringResource(R.string.new_chat_send_all_not_enough)
                                                    },
                                            fontSize = 11.sp,
                                            color = chatColors().error
                                        )
                                    } else if (state.sendAllNote.isNotEmpty()) {
                                        // A priced Send All: the amount kept for the secure handshake (C-8) and/or
                                        // "Amount updated – tap Send again" (C-9).
                                        Text(
                                            text = state.sendAllNote,
                                            fontSize = 11.sp,
                                            color = chatColors().primary
                                        )
                                    }
                                }
                                TextButton(onClick = state.onShowAmountDialog) {
                                    Text(stringResource(R.string.new_chat_adjust), color = chatColors().primary)
                                }
                            }
                        }
                    } // end non-free amount card

                    Spacer(modifier = Modifier.height(12.dp))

                    // Send Button — disabled unless the action is actually valid: valid recipient,
                    // non-blank message within the chunk limit, and not already sending. On-chain
                    // amounts are dust-coerced at the source, so a normal tier can never be zero; the
                    // ONLY invalid zero is Send All on an empty/insufficient wallet, where there is
                    // literally nothing to send — that case is blocked here.
                    val isSendAllWithoutFunds =
                        state.selectedAmount == MessageAmount.SEND_ALL && state.isZeroAmount
                    // Round 8 P4: for an existing chat the button only opens it (with the text as its draft).
                    // ZC-601: while the exact price is worked out the button waits; it says so and never says "Sending".
                    val sendEnabled = state.isValidAddress &&
                        !state.isSending &&
                        !state.isAskingPrice &&
                        (
                            state.opensExistingChat ||
                                (
                                    state.message.isNotBlank() &&
                                        state.message.length <= state.maxMessageLength &&
                                        !isSendAllWithoutFunds
                                )
                        )
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
                            // QA2 Q2-36 (ZC-1631, ZC-1706): the disabled label stays readable in light and dark
                            // (white on a faded teal was 1.5:1 on Nightwire Light).
                            disabledContainerColor = chatColors().bgElevated,
                            disabledContentColor = chatColors().textSecondary
                        ),
                        // ZC-1719 (QA2): the disabled container is 1.05:1 on Nightwire Light, so the disabled button
                        // keeps its shape with an edge in the label colour (at least 3:1 on the light themes).
                        border = if (sendEnabled) null else BorderStroke(1.dp, chatColors().textSecondary),
                        shape = RoundedCornerShape(NightwireColors.RadiusButton)
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = chatColors().textOnAccent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.new_chat_sending), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
                        } else if (state.isAskingPrice) {
                            Text(
                                stringResource(R.string.payment_quote_working),
                                fontFamily = RajdhaniFontFamily,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (state.opensExistingChat) {
                                    stringResource(R.string.new_chat_open_chat_button)
                                } else {
                                    stringResource(R.string.new_chat_send_message)
                                },
                                fontFamily = RajdhaniFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // QA2 Q2-23 (ZC-1622): room of one button height under the form, so "Send Message" can always be scrolled
            // fully above the screen's bottom edge.
            Spacer(modifier = Modifier.height(SEND_BUTTON_BOTTOM_ROOM))
        }

        // Add Contact Dialog
        if (state.showAddContactDialog) {
            AddContactDialog(
                address = state.recipientAddress,
                name = state.contactName,
                notSaved = state.contactNotSaved,
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
                isAmountOverBalance = state.isAmountOverBalance,
                sendAllAmountDisplay = state.sendAllAmountDisplay,
                onAmountSelect = state.onAmountSelect,
                onCustomAmountChange = state.onCustomAmountChange,
                onDismiss = state.onDismissAmountDialog
            )
        }

        // Paste-a-contact-code dialog (#35). Only a "zchat:" code can be confirmed — a bare address
        // belongs in the recipient field (which also accepts pasted codes; this dialog just makes the
        // free-OPEN path explicit and discoverable).
        if (showPasteCodeDialog) {
            val trimmedCode = pastedCode.trim()
            val looksLikeCode = trimmedCode.startsWith("zchat:")
            AlertDialog(
                onDismissRequest = { showPasteCodeDialog = false },
                containerColor = chatColors().bgElevated,
                titleContentColor = chatColors().textPrimary,
                textContentColor = chatColors().textSecondary,
                shape = RoundedCornerShape(12.dp),
                title = {
                    Text(stringResource(R.string.new_chat_paste_code_title), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.new_chat_paste_code_body),
                            fontSize = 13.sp,
                            color = chatColors().textSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pastedCode,
                            onValueChange = { pastedCode = it },
                            label = { Text(stringResource(R.string.new_chat_paste_code_label), color = chatColors().textSecondary) },
                            placeholder = { Text("zchat:c1?z=…", color = chatColors().textTertiary) },
                            singleLine = true,
                            isError = pastedCode.isNotBlank() && !looksLikeCode,
                            supportingText = if (pastedCode.isNotBlank() && !looksLikeCode) {
                                { Text(stringResource(R.string.new_chat_paste_code_not_code), color = chatColors().error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
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
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            state.onRecipientChange(trimmedCode)
                            pastedCode = ""
                            showPasteCodeDialog = false
                        },
                        enabled = looksLikeCode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = chatColors().primary,
                            contentColor = chatColors().textOnAccent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.new_chat_use_code), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPasteCodeDialog = false }) {
                        Text(stringResource(R.string.new_chat_cancel), color = chatColors().textSecondary)
                    }
                }
            )
        }
    }
}

/**
 * One-line plain-language description per transport mode (compose / New-Chat context).
 *
 * With only a bare Zcash address the FIRST message costs ZEC: Shielded is on-chain per message, and
 * Tunnel pays the first message as its own transaction and then the handshake as one or two more (our key exchange;
 * our relay identity on-chain when it cannot go over the relays), each at least 0.00017 ZEC; messages are free only
 * after the handshake (ZC-632). The Tunnel text must stay equal to the English of new_chat_mode_blurb_tunnel. Open is
 * selectable ONLY once the peer's ZCHAT contact code was scanned/pasted (it carries their NOSTR key) — then, and only then,
 * the copy may claim "free from message #1". A keyless OPEN send does not exist (#224 no-spend gate).
 */
internal fun ConversationMode.composeBlurb(): String = when (this) {
    // Round 8 P4 (D9 qualifier): a first Shielded message goes out before any key exchange, so E2E starts only once
    // the contact has answered it. The screen shows new_chat_mode_blurb_vault (EN byte-equal to this, and ES).
    ConversationMode.VAULT ->
        "The most private mode. E2E encrypted once your contact has answered the key exchange, every message " +
            "on-chain (costs ZEC). Quantum Shield is optional (in-person QR)."
    // OPEN is only offered here once we hold the peer's NOSTR key (scanned from their ZCHAT contact QR),
    // so it delivers a FREE, end-to-end-encrypted (NIP-17 gift-wrapped) NOSTR DM from the first message.
    // The recipient accepts the request once, then the chat is free both ways.
    // QA2 Q2-51 (ZC-1593): what costs no ZEC is texts and calls (an attachment needs a paid key exchange).
    ConversationMode.OPEN ->
        "Free from message #1 — end-to-end encrypted DM over Nostr relays; texts and calls cost no ZEC. Recipient " +
            "accepts your request once."
    // QA2 Q2-23 (ZC-1622): one short sentence, so "Send Message" stays on the screen; the count is the code's.
    ConversationMode.TUNNEL ->
        "2–3 on-chain payments (first message, then handshake), each at least 0.00017 ZEC. Then messages and calls " +
            "are free over Nostr relays."
}

/**
 * The caption the screen shows for each mode (Round 8 P10, PHONE_TRIAGE ES-4): the English of each resource is
 * [composeBlurb] byte for byte, which stays as the reference the honesty tests read.
 */
@StringRes
private fun ConversationMode.composeBlurbRes(): Int =
    when (this) {
        ConversationMode.VAULT -> R.string.new_chat_mode_blurb_vault
        ConversationMode.OPEN -> R.string.new_chat_mode_blurb_open
        ConversationMode.TUNNEL -> R.string.new_chat_mode_blurb_tunnel
    }

/** Two-to-three-word function tag shown UNDER each mode name on its button (plain-language cue). */
@StringRes
private fun ConversationMode.shortTagRes(): Int =
    when (this) {
        ConversationMode.VAULT -> R.string.new_chat_mode_tag_vault
        ConversationMode.OPEN -> R.string.new_chat_mode_tag_open
        ConversationMode.TUNNEL -> R.string.new_chat_mode_tag_tunnel
    }

/**
 * An amount option's name in the phone's language: the two fixed tiers are their figure, written the way the balances
 * are (MessageAmount's English label is the same figure); Send All and Custom are words.
 */
@Composable
private fun amountLabel(amount: MessageAmount): String =
    when (amount) {
        MessageAmount.MINIMAL, MessageAmount.SMALL ->
            "${zecDisplay(Zatoshi(amount.zatoshi), rememberDesiredFormatLocale())} ZEC"
        MessageAmount.SEND_ALL -> stringResource(R.string.new_chat_amount_send_all)
        MessageAmount.CUSTOM -> stringResource(R.string.new_chat_amount_custom)
    }

/** An amount option's one-line description (MessageAmount.description in English). */
@StringRes
private fun MessageAmount.captionRes(): Int =
    when (this) {
        MessageAmount.MINIMAL -> R.string.new_chat_amount_minimal_desc
        MessageAmount.SMALL -> R.string.new_chat_amount_small_desc
        MessageAmount.SEND_ALL -> R.string.new_chat_amount_send_all_desc
        MessageAmount.CUSTOM -> R.string.new_chat_amount_custom_desc
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
    // True once we hold the peer's NOSTR key (scanned/pasted from their ZCHAT contact code). OPEN is
    // selectable only then, because only then can it deliver a free NOSTR DM from message #1.
    openAvailable: Boolean,
    // Contact-code acquisition actions — the FIRST-CLASS path to a free OPEN chat pre-handshake (the
    // #224 no-spend gate stays: a keyless OPEN is never unlocked, the user is funneled to the code).
    onScanContactCode: () -> Unit,
    onPasteContactCode: () -> Unit,
    onSelect: (ConversationMode) -> Unit
) {
    // First-use note before switching to Open: it travels over a public NOSTR relay and the recipient
    // must accept the request once. Shown once per screen visit (no nag once acknowledged or if Open is
    // already the active mode).
    var showOpenWarning by remember { mutableStateOf(false) }
    var openAcknowledged by remember { mutableStateOf(selected == ConversationMode.OPEN) }
    // Explainer shown when the user taps the (visible but locked) Open chip without the peer's key —
    // offers the two ways to get it: scan their contact QR or paste their zchat: code.
    var showOpenNeedsCode by remember { mutableStateOf(false) }
    // NOTE: the disabled-dim is applied to the label + chips only (not the whole Column) so the
    // contact-code actions below stay full-brightness — they are the correct FIRST step before any
    // recipient is entered (the code carries the address itself).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.new_chat_mode_header),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = RajdhaniFontFamily,
            letterSpacing = 1.sp,
            color = chatColors().primary,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .alpha(if (enabled) 1f else 0.5f)
        )
        // QA2 Q2-92 (ZC-1665): the chips are one radio group; each says whether it is selected.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // QA2 ZC-1706: dimmed while there is no recipient, but still readable on Nightwire Light.
                .alpha(if (enabled) 1f else 0.7f)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // SHIELDED (private on-chain) and TUNNEL (one on-chain bootstrap, then free NOSTR + calls)
            // work from any Zcash address. OPEN delivers a FREE NOSTR DM from message #1, but ONLY when
            // we already hold the peer's NOSTR key — which we do once the user scanned/pasted the peer's
            // ZCHAT contact code ([openAvailable]). Without that key OPEN stays VISIBLE but locked: it
            // used to be omitted entirely, which hid the mode (and its free-from-message-#1 promise)
            // exactly when the contact-code path could still unlock it. Tapping the locked chip explains
            // + offers "Scan code" / "Paste code" — never a keyless (paid) OPEN send (#224 stays closed).
            listOf(ConversationMode.VAULT, ConversationMode.TUNNEL, ConversationMode.OPEN).forEach { mode ->
                // QA2 Q2-36 (ZC-1631): no chip is highlighted before there is a recipient.
                val isSelected = enabled && mode == selected
                val locked = mode == ConversationMode.OPEN && !openAvailable
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
                        // The locked Open chip stays tappable even BEFORE a recipient is entered — the
                        // contact code carries the address itself, so scan/paste is exactly the right
                        // first step for a fresh chat.
                        .selectable(
                            selected = isSelected,
                            enabled = enabled || locked,
                            role = Role.RadioButton,
                        ) {
                            when {
                                // Locked Open: explain + route to the contact-code actions.
                                locked -> showOpenNeedsCode = true
                                // Intercept the switch TO Open the first time to warn about the privacy
                                // trade-off; every other selection applies immediately.
                                mode == ConversationMode.OPEN && !openAcknowledged -> showOpenWarning = true
                                else -> onSelect(mode)
                            }
                        }
                        .padding(vertical = 8.dp)
                        .alpha(if (locked) 0.8f else 1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = mode.label(),
                            fontSize = 14.sp,
                            fontFamily = RajdhaniFontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) chatColors().primary else chatColors().textPrimary
                        )
                        // Plain-language function cue under every mode name (not just the selected one).
                        Text(
                            text = if (locked) stringResource(R.string.new_chat_mode_needs_code) else stringResource(mode.shortTagRes()),
                            // QA2 Q2-90 (ZC-1597): 12.sp, and it may wrap to two lines.
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = RajdhaniFontFamily,
                            color =
                                if (isSelected) chatColors().primary.copy(alpha = 0.8f) else chatColors().textPrimary.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        if (showOpenNeedsCode) {
            AlertDialog(
                onDismissRequest = { showOpenNeedsCode = false },
                title = { Text(stringResource(R.string.new_chat_open_needs_code_title)) },
                text = {
                    Text(stringResource(R.string.new_chat_open_needs_code_body))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showOpenNeedsCode = false
                        onScanContactCode()
                    }) { Text(stringResource(R.string.new_chat_scan_code)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showOpenNeedsCode = false
                            onPasteContactCode()
                        }) { Text(stringResource(R.string.new_chat_paste_code)) }
                        TextButton(onClick = { showOpenNeedsCode = false }) { Text(stringResource(R.string.new_chat_cancel)) }
                    }
                }
            )
        }

        if (showOpenWarning) {
            AlertDialog(
                onDismissRequest = { showOpenWarning = false },
                title = { Text(stringResource(R.string.new_chat_open_warning_title)) },
                text = {
                    Text(stringResource(R.string.new_chat_open_warning_body))
                },
                confirmButton = {
                    TextButton(onClick = {
                        openAcknowledged = true
                        showOpenWarning = false
                        onSelect(ConversationMode.OPEN)
                    }) { Text(stringResource(R.string.new_chat_use_open)) }
                },
                dismissButton = {
                    TextButton(onClick = { showOpenWarning = false }) { Text(stringResource(R.string.new_chat_cancel)) }
                }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (enabled) {
                stringResource(selected.composeBlurbRes())
            } else {
                stringResource(R.string.new_chat_enter_recipient)
            },
            fontSize = 12.sp,
            color = chatColors().textSecondary
        )
        // Bridge 3: with Shielded selected, say that friends still on ZChat 2.12.4 or older won't see it yet.
        if (UpgradeReminders.showsComposeLine(selected)) {
            Text(
                text = stringResource(R.string.upgrade_compose_line),
                fontSize = 12.sp,
                color = chatColors().textSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // First-class contact-code actions (#35): with the peer's ZCHAT code the chat starts FREE over
        // NOSTR from message #1 (Open) — surface scan/paste right here instead of burying them behind
        // the locked chip. Hidden once the key is known (Open is then simply selectable above).
        if (!openAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onScanContactCode) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = chatColors().primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.new_chat_scan_contact_code), color = chatColors().primary, fontSize = 13.sp)
                }
                TextButton(onClick = onPasteContactCode) {
                    Text(stringResource(R.string.new_chat_paste_code), color = chatColors().primary, fontSize = 13.sp)
                }
            }
            Text(
                text = stringResource(R.string.new_chat_code_free_hint),
                fontSize = 11.sp,
                color = chatColors().textTertiary
            )
        }
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
                    text = contactDisplayName(contact.name),
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
    notSaved: Boolean,
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
        title = {
            Text(stringResource(R.string.new_chat_add_contact_title), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                // Show a wide leading/trailing window so the user can verify they are adding the
                // intended address — confirming a near-identical-looking address to contacts is a
                // money-movement footgun. Allowed to wrap to a second line.
                Text(
                    text = stringResource(R.string.new_chat_add_contact_address, address.take(20), address.takeLast(16)),
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
                // ZC-733-B: the save was refused because the saved names cannot be read.
                if (notSaved) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.chat_list_contact_not_saved), fontSize = 13.sp, color = chatColors().textPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.new_chat_contact_name_label), color = chatColors().textSecondary) },
                    placeholder = { Text(stringResource(R.string.new_chat_contact_name_placeholder), color = chatColors().textTertiary) },
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
                Text(stringResource(R.string.new_chat_add), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.new_chat_cancel), color = chatColors().textSecondary)
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
    isAmountOverBalance: Boolean,
    sendAllAmountDisplay: String,
    onAmountSelect: (MessageAmount) -> Unit,
    onCustomAmountChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Local text state for custom amount to prevent glitching from round-trip conversion. F1: it is the field's reading
    // state; the view model gets only its settled text ('.' decimal point), "" while a pasted amount waits for a reading.
    // QA2 m8: the holder edits the state it is handed and stores what the machine took through localCustomState.
    val localCustomState = remember { mutableStateOf(AmountFieldState.settled(customAmountText)) }
    var localCustom by localCustomState
    val customSelection = rememberAmountTextFieldSelection(localCustom) { it.shown }
    val formatLocale = rememberDesiredFormatLocale()

    AlertDialog(
        onDismissRequest = { if (!isAmountOverBalance) onDismiss() },
        containerColor = chatColors().bgElevated,
        titleContentColor = chatColors().textPrimary,
        textContentColor = chatColors().textSecondary,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(stringResource(R.string.new_chat_amount_dialog_title), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.new_chat_amount_dialog_desc),
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
                if (availableBalanceDisplay.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.new_chat_available, availableBalanceDisplay),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBalanceZero) chatColors().error else chatColors().primary
                    )
                }
                // C-1 / Round 9 R9-1: "Available" is what can pay another person (Ironwood); Send All budgets from it.
                Spacer(modifier = Modifier.height(16.dp))

                // Amount options. QA2 Q2-92 (ZC-1665): one radio group; each option says whether it is selected.
                Column(modifier = Modifier.selectableGroup()) {
                    MessageAmount.entries.forEach { amount ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .selectable(
                                    selected = selectedAmount == amount,
                                    role = Role.RadioButton,
                                    onClick = { onAmountSelect(amount) },
                                ),
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (selectedAmount == amount) {
                                            chatColors().primary.copy(alpha = 0.15f)
                                        } else if (amount == MessageAmount.SEND_ALL) {
                                            chatColors().success.copy(alpha = 0.1f)
                                        } else {
                                            chatColors().surface
                                        }
                                )
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = amountLabel(amount),
                                        fontSize = 15.sp,
                                        fontWeight =
                                            if (selectedAmount == amount) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        color = chatColors().textPrimary
                                    )
                                    Text(
                                        text = stringResource(amount.captionRes()),
                                        fontSize = 11.sp,
                                        color = chatColors().textSecondary
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
                                    // The selected state is the option's own semantics; the tick is only the picture of it.
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = chatColors().primary,
                                    )
                                }
                            }
                        }
                    }
                }

                // Custom amount input (shown when CUSTOM is selected)
                if (selectedAmount == MessageAmount.CUSTOM) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        // QA2 m4 (ZC-1759): the field reports the selection before each edit.
                        value = customSelection.valueFor(localCustom.shown),
                        onValueChange = { fieldValue ->
                            customSelection.onValueChange(
                                fieldValue,
                                edit = { state, newText, selectionBefore ->
                                    acceptAmountInputResult(state, newText, formatLocale, AmountUnit.ZEC, selectionBefore)
                                },
                                onState = { taken ->
                                    localCustomState.value = taken
                                    onCustomAmountChange(taken.settled)
                                }
                            )
                        },
                        label = { Text(stringResource(R.string.new_chat_custom_amount_label), color = chatColors().textSecondary) },
                        placeholder = { Text("0.00001", color = chatColors().textTertiary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = amountFieldTransformation(localCustom, decimalSeparatorOf(formatLocale)),
                        isError = localCustom.isQuestionOpen,
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
                    AmountReadingChooser(
                        raw = localCustom.shown,
                        options = localCustom.ask,
                        notUsable = localCustom.notUsable,
                        error = localCustom.error,
                        unit = AmountUnit.ZEC,
                        locale = formatLocale,
                        onChoose = {
                            localCustom = localCustom.choose(it, MAX_AMOUNT_DECIMALS)
                            onCustomAmountChange(localCustom.settled)
                        },
                    )
                    if (isAmountOverBalance) {
                        // QA2 Q2-84 (ZC-1660): said under the field; how the amount is read is unchanged.
                        Text(
                            text = stringResource(R.string.new_chat_amount_over_balance, availableBalanceDisplay),
                            fontSize = 12.sp,
                            color = chatColors().warning
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                // QA2 Q2-84 (ZC-1660): "Done" waits while the custom amount is more than the available balance.
                enabled = !isAmountOverBalance,
                colors = ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.new_chat_done), fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
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
                contentDescription = stringResource(R.string.new_chat_sent_icon),
                modifier = Modifier
                    .size(160.dp)
                    .scale(iconScale)
                    .cyanGlow(radius = 24.dp, alpha = 0.5f, cornerRadius = 80.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(24.dp))

            // "MESSAGE SENT" in Orbitron with gradient
            Text(
                text = stringResource(R.string.new_chat_sent_title),
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
                text = stringResource(R.string.new_chat_sent_subtitle),
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
                    text = stringResource(R.string.new_chat_sent_add_to_contacts),
                    onClick = state.onAddToContacts,
                    type = CyberButtonType.Primary
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            CyberButtonFullWidth(
                text = stringResource(R.string.new_chat_sent_done),
                onClick = state.onDone,
                type = CyberButtonType.Ghost
            )
        }
    }
}

/**
 * D2 (REVIEW_7C F11): a send whose submit started may have gone out. The notice in the phone's language and one
 * Close; there is deliberately no Retry, so the same send cannot be paid for twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MayHaveBeenSentView(state: ZchatComposeState.MayHaveBeenSent) {
    Scaffold(
        containerColor = chatColors().background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.chat_new_chat),
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = chatColors().textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = state.onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.new_chat_back),
                            tint = chatColors().textPrimary
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = chatColors().surface
                    )
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = ChatSendCopy.localizedResource(state.message)?.let { stringResource(it) } ?: state.message,
                color = chatColors().textPrimary,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = state.onClose,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = chatColors().primary,
                        contentColor = chatColors().textOnAccent
                    ),
                shape = RoundedCornerShape(NightwireColors.RadiusButton)
            ) {
                Text(
                    stringResource(R.string.send_confirmation_failure_close_button),
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** M1 — the text for a contact code that bound nothing ([ContactCodeNotice]). */
@Composable
private fun contactCodeNoticeText(notice: ContactCodeNotice?): String =
    when (notice) {
        null -> ""
        ContactCodeNotice.AddressInvalid -> stringResource(R.string.new_chat_code_address_invalid)
        is ContactCodeNotice.KeyAlreadyBound -> stringResource(R.string.new_chat_code_key_already_bound, notice.displayName)
    }

/** ZC-1461: "Scan a code" and "My code", side by side above the recipient field. */
@Composable
private fun NewChatStartButtons(
    onScanClick: () -> Unit,
    onMyCodeClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onScanClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(NightwireColors.RadiusButton),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent
                )
        ) {
            Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.new_chat_scan_a_code))
        }
        OutlinedButton(
            onClick = onMyCodeClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(NightwireColors.RadiusButton),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = chatColors().primary),
            border = androidx.compose.foundation.BorderStroke(1.dp, chatColors().primary)
        ) {
            Icon(imageVector = Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.new_chat_my_code))
        }
    }
}
