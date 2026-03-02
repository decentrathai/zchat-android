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
import androidx.compose.foundation.shape.CircleShape
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
import co.electriccoin.zcash.ui.design.component.cyberpunk.CyberButtonFullWidth
import co.electriccoin.zcash.ui.design.component.cyberpunk.CyberButtonType
import co.electriccoin.zcash.ui.design.component.cyberpunk.GlassSurface
import co.electriccoin.zcash.ui.design.theme.modifiers.cyanGlow
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZchatComposeView(state: ZchatComposeState) {
    when (state) {
        is ZchatComposeState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NightwireColors.AccentPrimary)
            }
        }
        is ZchatComposeState.Error -> {
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
private fun ComposeReadyView(state: ZchatComposeState.Ready) {
    Scaffold(
        containerColor = NightwireColors.BgBase,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "New Message",
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = NightwireColors.TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = state.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NightwireColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = state.onScanQrClick) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = NightwireColors.AccentPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightwireColors.BgSurface
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
                label = { Text("Recipient Address", color = NightwireColors.TextSecondary) },
                placeholder = { Text("Paste or scan Zcash address...", color = NightwireColors.TextTertiary) },
                singleLine = true,
                isError = state.recipientAddress.isNotEmpty() && !state.isValidAddress,
                supportingText = if (state.recipientAddress.isNotEmpty() && !state.isValidAddress) {
                    { Text("Invalid Zcash address", color = NightwireColors.ColorDanger) }
                } else if (state.selectedContact != null) {
                    { Text("Contact: ${state.selectedContact.name}", color = NightwireColors.AccentPrimary) }
                } else null,
                trailingIcon = {
                    if (state.isValidAddress && state.selectedContact == null) {
                        IconButton(onClick = state.onShowAddContactDialog) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add to contacts",
                                tint = NightwireColors.AccentPrimary
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = NightwireColors.BgInput,
                    unfocusedContainerColor = NightwireColors.BgInput,
                    focusedTextColor = NightwireColors.TextPrimary,
                    unfocusedTextColor = NightwireColors.TextPrimary,
                    cursorColor = NightwireColors.AccentPrimary,
                    focusedBorderColor = NightwireColors.BorderActive,
                    unfocusedBorderColor = NightwireColors.BorderDefault,
                    errorBorderColor = NightwireColors.ColorDanger
                )
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
                    color = NightwireColors.AccentPrimary
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
                            tint = NightwireColors.TextTertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No contacts yet",
                            fontSize = 15.sp,
                            color = NightwireColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Paste an address or scan QR code",
                            fontSize = 13.sp,
                            color = NightwireColors.TextTertiary
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
                    containerColor = NightwireColors.BgSurface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.message,
                        onValueChange = state.onMessageChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        label = { Text("Message", color = NightwireColors.TextSecondary) },
                        placeholder = { Text("Type your message...", color = NightwireColors.TextTertiary) },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = NightwireColors.BgInput,
                            unfocusedContainerColor = NightwireColors.BgInput,
                            focusedTextColor = NightwireColors.TextPrimary,
                            unfocusedTextColor = NightwireColors.TextPrimary,
                            cursorColor = NightwireColors.AccentPrimary,
                            focusedBorderColor = NightwireColors.BorderActive,
                            unfocusedBorderColor = NightwireColors.BorderDefault
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
                            Text(
                                text = "${state.message.length} / ${state.maxMessageLength} chars",
                                fontSize = 11.sp,
                                color = NightwireColors.TextSecondary
                            )
                            if (state.chunkCount > 1) {
                                Text(
                                    text = "${state.chunkCount} chunks",
                                    fontSize = 11.sp,
                                    color = NightwireColors.AccentPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Available balance
                    if (state.availableBalanceDisplay.isNotEmpty()) {
                        Text(
                            text = "Available: ${state.availableBalanceDisplay}",
                            fontSize = 11.sp,
                            color = NightwireColors.AccentPrimary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // Amount adjustment row
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = state.onShowAmountDialog),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.isZeroAmount)
                                NightwireColors.ColorDanger.copy(alpha = 0.1f)
                            else
                                NightwireColors.BgElevated
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
                                        NightwireColors.ColorDanger
                                    else
                                        NightwireColors.TextPrimary
                                )
                                Text(
                                    text = "Fee: ${state.feeDisplay}",
                                    fontSize = 11.sp,
                                    color = NightwireColors.TextSecondary
                                )
                                if (state.isZeroAmount) {
                                    Text(
                                        text = "Zero amount may be delayed by miners",
                                        fontSize = 11.sp,
                                        color = NightwireColors.ColorDanger
                                    )
                                }
                            }
                            TextButton(onClick = state.onShowAmountDialog) {
                                Text("Adjust", color = NightwireColors.AccentPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Send Button
                    val sendEnabled = state.isValidAddress && state.message.isNotBlank() && !state.isSending
                    Button(
                        onClick = state.onSendClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (sendEnabled) Modifier.shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(NightwireColors.RadiusButton),
                                    ambientColor = NightwireColors.AccentPrimaryGlow,
                                    spotColor = NightwireColors.AccentPrimaryGlow
                                ) else Modifier
                            ),
                        enabled = sendEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NightwireColors.AccentPrimary,
                            contentColor = NightwireColors.TextOnAccent,
                            disabledContainerColor = NightwireColors.AccentPrimary.copy(alpha = 0.3f),
                            disabledContentColor = NightwireColors.TextOnAccent.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(NightwireColors.RadiusButton)
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = NightwireColors.TextOnAccent
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
                sendAllAmountDisplay = state.sendAllAmountDisplay,
                onAmountSelect = state.onAmountSelect,
                onCustomAmountChange = state.onCustomAmountChange,
                onDismiss = state.onDismissAmountDialog
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
                NightwireColors.AccentPrimary.copy(alpha = 0.15f)
            else
                NightwireColors.BgSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected)
                            NightwireColors.AccentPrimary
                        else
                            NightwireColors.BgElevated
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected)
                        NightwireColors.TextOnAccent
                    else
                        NightwireColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NightwireColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${contact.address.take(8)}...${contact.address.takeLast(6)}",
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary,
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
        containerColor = NightwireColors.BgElevated,
        titleContentColor = NightwireColors.TextPrimary,
        textContentColor = NightwireColors.TextSecondary,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Add to Contacts", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "Address: ${address.take(12)}...${address.takeLast(8)}",
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Contact Name", color = NightwireColors.TextSecondary) },
                    placeholder = { Text("Enter name...", color = NightwireColors.TextTertiary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = NightwireColors.BgInput,
                        unfocusedContainerColor = NightwireColors.BgInput,
                        focusedTextColor = NightwireColors.TextPrimary,
                        unfocusedTextColor = NightwireColors.TextPrimary,
                        cursorColor = NightwireColors.AccentPrimary,
                        focusedBorderColor = NightwireColors.BorderActive,
                        unfocusedBorderColor = NightwireColors.BorderDefault
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NightwireColors.AccentPrimary,
                    contentColor = NightwireColors.TextOnAccent
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Add", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NightwireColors.TextSecondary)
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
    sendAllAmountDisplay: String,
    onAmountSelect: (MessageAmount) -> Unit,
    onCustomAmountChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Local text state for custom amount to prevent glitching from round-trip conversion
    var localCustomText by remember { mutableStateOf(customAmountText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightwireColors.BgElevated,
        titleContentColor = NightwireColors.TextPrimary,
        textContentColor = NightwireColors.TextSecondary,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Message Amount", fontFamily = RajdhaniFontFamily, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "Amount of ZEC to send with each message chunk",
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary
                )
                if (availableBalanceDisplay.isNotEmpty()) {
                    Text(
                        text = "Available: $availableBalanceDisplay",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NightwireColors.AccentPrimary
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
                                NightwireColors.AccentPrimary.copy(alpha = 0.15f)
                            else if (amount == MessageAmount.ZERO)
                                NightwireColors.ColorDanger.copy(alpha = 0.1f)
                            else if (amount == MessageAmount.SEND_ALL)
                                NightwireColors.AccentSuccess.copy(alpha = 0.1f)
                            else
                                NightwireColors.BgSurface
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
                                        NightwireColors.ColorDanger
                                    else
                                        NightwireColors.TextPrimary
                                )
                                Text(
                                    text = amount.description,
                                    fontSize = 11.sp,
                                    color = if (amount == MessageAmount.ZERO)
                                        NightwireColors.ColorDanger.copy(alpha = 0.7f)
                                    else
                                        NightwireColors.TextSecondary
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
                                        color = NightwireColors.AccentPrimary
                                    )
                                }
                            }
                            if (selectedAmount == amount) {
                                Text(
                                    text = "\u2713",
                                    color = NightwireColors.AccentPrimary,
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
                        label = { Text("Custom Amount (ZEC)", color = NightwireColors.TextSecondary) },
                        placeholder = { Text("0.00001", color = NightwireColors.TextTertiary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = NightwireColors.BgInput,
                            unfocusedContainerColor = NightwireColors.BgInput,
                            focusedTextColor = NightwireColors.TextPrimary,
                            unfocusedTextColor = NightwireColors.TextPrimary,
                            cursorColor = NightwireColors.AccentPrimary,
                            focusedBorderColor = NightwireColors.BorderActive,
                            unfocusedBorderColor = NightwireColors.BorderDefault
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NightwireColors.AccentPrimary,
                    contentColor = NightwireColors.TextOnAccent
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

    Box(modifier = Modifier.fillMaxSize().background(NightwireColors.BgBase)) {
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
                        colors = listOf(NightwireColors.AccentPrimary, NightwireColors.AccentSecondary)
                    )
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Encrypted & delivered to the blockchain",
                style = TextStyle(fontSize = 14.sp, color = NightwireColors.TextSecondary)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Recipient address pill in glass surface
            GlassSurface(
                cornerRadius = 20.dp,
                contentPadding = 12.dp,
                borderColor = NightwireColors.AccentPrimary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${state.recipientAddress.take(10)}...${state.recipientAddress.takeLast(10)}",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = NightwireColors.AccentPrimary,
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
