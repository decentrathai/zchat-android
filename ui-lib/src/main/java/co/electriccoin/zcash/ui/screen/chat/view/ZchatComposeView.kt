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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import co.electriccoin.zcash.ui.design.theme.typography.OrbitronFontFamily
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
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        is ZchatComposeState.Error -> {
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
        topBar = {
            TopAppBar(
                title = { Text("New Message", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = state.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = state.onScanQrClick) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
                label = { Text("Recipient Address") },
                placeholder = { Text("Paste or scan Zcash address...") },
                singleLine = true,
                isError = state.recipientAddress.isNotEmpty() && !state.isValidAddress,
                supportingText = if (state.recipientAddress.isNotEmpty() && !state.isValidAddress) {
                    { Text("Invalid Zcash address", color = MaterialTheme.colorScheme.error) }
                } else if (state.selectedContact != null) {
                    { Text("Contact: ${state.selectedContact.name}", color = MaterialTheme.colorScheme.primary) }
                } else null,
                trailingIcon = {
                    if (state.isValidAddress && state.selectedContact == null) {
                        IconButton(onClick = state.onShowAddContactDialog) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add to contacts",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )

            // Contacts Section
            if (state.contacts.isNotEmpty()) {
                Text(
                    text = "Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No contacts yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Paste an address or scan QR code",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.message,
                        onValueChange = state.onMessageChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        label = { Text("Message") },
                        placeholder = { Text("Type your message...") },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.chunkCount > 1) {
                                Text(
                                    text = "${state.chunkCount} chunks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Available balance
                    if (state.availableBalanceDisplay.isNotEmpty()) {
                        Text(
                            text = "Available: ${state.availableBalanceDisplay}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
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
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (state.isZeroAmount)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Fee: ${state.feeDisplay}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (state.isZeroAmount) {
                                    Text(
                                        text = "Zero amount may be delayed by miners",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            TextButton(onClick = state.onShowAmountDialog) {
                                Text("Adjust")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Send Button
                    Button(
                        onClick = state.onSendClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.isValidAddress && state.message.isNotBlank() && !state.isSending
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sending...")
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Message")
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
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${contact.address.take(8)}...${contact.address.takeLast(6)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        title = { Text("Add to Contacts") },
        text = {
            Column {
                Text(
                    text = "Address: ${address.take(12)}...${address.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Contact Name") },
                    placeholder = { Text("Enter name...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank()
            ) {
                Text("Add")
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
        title = { Text("Message Amount") },
        text = {
            Column {
                Text(
                    text = "Amount of ZEC to send with each message chunk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (availableBalanceDisplay.isNotEmpty()) {
                    Text(
                        text = "Available: $availableBalanceDisplay",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
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
                                MaterialTheme.colorScheme.primaryContainer
                            else if (amount == MessageAmount.ZERO)
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            else if (amount == MessageAmount.SEND_ALL)
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedAmount == amount)
                                        FontWeight.Bold
                                    else
                                        FontWeight.Normal,
                                    color = if (amount == MessageAmount.ZERO)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = amount.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (amount == MessageAmount.ZERO)
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Show recipient amount for Send All
                                if (amount == MessageAmount.SEND_ALL &&
                                    selectedAmount == MessageAmount.SEND_ALL &&
                                    sendAllAmountDisplay.isNotEmpty()
                                ) {
                                    Text(
                                        text = sendAllAmountDisplay,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (selectedAmount == amount) {
                                Text(
                                    text = "\u2713",
                                    color = MaterialTheme.colorScheme.primary,
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
                        label = { Text("Custom Amount (ZEC)") },
                        placeholder = { Text("0.00001") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
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

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0B1A))) {
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
                    fontFamily = OrbitronFontFamily,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF00FFFF), Color(0xFFFF00FF))
                    )
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Encrypted & delivered to the blockchain",
                style = TextStyle(fontSize = 14.sp, color = Color(0xFFA8A8CC))
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Recipient address pill in glass surface
            GlassSurface(
                cornerRadius = 20.dp,
                contentPadding = 12.dp,
                borderColor = Color(0xFF00FFFF).copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${state.recipientAddress.take(10)}...${state.recipientAddress.takeLast(10)}",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = Color(0xFF00FFFF),
                        fontFamily = OrbitronFontFamily,
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
