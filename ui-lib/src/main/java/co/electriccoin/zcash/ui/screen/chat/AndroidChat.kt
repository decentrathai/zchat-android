package co.electriccoin.zcash.ui.screen.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatDetailState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatDetailView
import co.electriccoin.zcash.ui.screen.chat.view.ChatListView
import co.electriccoin.zcash.ui.screen.chat.view.CreateGroupView
import co.electriccoin.zcash.ui.screen.chat.view.GroupDetailView
import co.electriccoin.zcash.ui.screen.chat.view.GroupSettingsView
import co.electriccoin.zcash.ui.screen.chat.view.ZchatComposeView
import co.electriccoin.zcash.ui.screen.chat.view.ZchatReceiveView
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ZchatComposeVM
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ZchatReceiveVM
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookArgs
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun AndroidChatList() {
    val viewModel = koinViewModel<ChatViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val contactBook = koinInject<ContactBook>()
    val addressBookRepository = koinInject<AddressBookRepository>()
    val zchatPreferences = koinInject<ZchatPreferences>()
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()
    val currentUserAddress by viewModel.currentUserAddress.collectAsStateWithLifecycle()
    val userStatus by viewModel.userStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Get DestroyManager from DI
    val destroyManager = koinInject<DestroyManager>()

    // Coroutine scope for destroy operations — auto-cancelled when leaving composition
    val destroyScope = rememberCoroutineScope()

    // Set remote kill callback on ViewModel
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.setRemoteKillCallback {
            destroyScope.launch {
                destroyManager.destroyAll(requestUninstall = true)
            }
        }
    }

    // Dialog state for add/edit contact
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showEditContactDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    // Force recomposition when contacts change
    var contactsVersion by remember { mutableStateOf(0) }

    ChatListView(
        state = chatListState,
        userStatus = userStatus,
        onConversationClick = { peerAddress ->
            navigationRouter.forward(ChatDetail(peerAddress))
        },
        onGroupClick = { groupId ->
            navigationRouter.forward(GroupDetail(groupId))
        },
        onNewChatClick = {
            // Navigate to ZCHAT compose screen for new message
            navigationRouter.forward(ZchatCompose)
        },
        onNewGroupClick = {
            // Navigate to create group screen
            navigationRouter.forward(CreateGroup)
        },
        onSettingsClick = {
            navigationRouter.forward(MoreArgs)
        },
        onCopyAddressClick = {
            currentUserAddress?.let { address ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ZCHAT Address", address)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        },
        onQrCodeClick = {
            navigationRouter.forward(ZchatReceive)
        },
        onContactsClick = {
            navigationRouter.forward(AddressBookArgs)
        },
        onRefresh = {
            viewModel.refresh()
        },
        onDeleteChat = { address ->
            selectedAddress = address
            showDeleteConfirmDialog = true
        },
        onDeleteGroup = { groupId ->
            // TODO: Show confirmation dialog and leave group
            android.util.Log.d("ZCHAT_GROUP", "Leave group: $groupId")
        },
        onAddContact = { address ->
            selectedAddress = address
            contactName = ""
            showAddContactDialog = true
        },
        onEditContact = { address ->
            selectedAddress = address
            contactName = contactBook.getContact(address)?.name ?: ""
            showEditContactDialog = true
        },
        onSetUserStatus = { status, broadcast ->
            if (status.isBlank()) {
                viewModel.clearUserStatus()
            } else {
                viewModel.setUserStatus(status, broadcast)
            }
        },
        getContact = { address ->
            // Use contactsVersion to trigger recomposition
            @Suppress("UNUSED_EXPRESSION")
            contactsVersion
            contactBook.getContact(address)
        },
        // Destroy All functionality
        onDestroyAll = {
            destroyScope.launch {
                destroyManager.destroyAll(requestUninstall = true)
            }
        },
        hasDestroyPin = zchatPreferences.hasDestroyPin(),
        onSetupDestroyPin = { pin ->
            zchatPreferences.setDestroyPin(pin)
        },
        onVerifyDestroyPin = { pin ->
            zchatPreferences.verifyDestroyPin(pin)
        }
    )

    // Delete Chat Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Chat") },
            text = {
                Text("Are you sure you want to delete this chat? The messages will still be stored on the blockchain, but they will be hidden from this app.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.hideChat(selectedAddress)
                        showDeleteConfirmDialog = false
                        Toast.makeText(context, "Chat hidden", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Contact Dialog
    if (showAddContactDialog) {
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Add to Contacts") },
            text = {
                Column {
                    Text(
                        text = selectedAddress,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (contactName.isNotBlank()) {
                            val trimmedName = contactName.trim()
                            // Save to ZCHAT local contact book
                            contactBook.addContact(
                                Contact(
                                    address = selectedAddress,
                                    name = trimmedName,
                                    addedAt = Instant.now()
                                )
                            )
                            // Also save to main Address Book for consistency
                            addressBookRepository.saveContact(
                                name = trimmedName,
                                address = selectedAddress,
                                chain = "zcash"
                            )
                            contactsVersion++ // Trigger recomposition
                            showAddContactDialog = false
                            Toast.makeText(context, "Contact added", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = contactName.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Contact Dialog
    if (showEditContactDialog) {
        AlertDialog(
            onDismissRequest = { showEditContactDialog = false },
            title = { Text("Edit Contact") },
            text = {
                Column {
                    Text(
                        text = selectedAddress,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (contactName.isNotBlank()) {
                            val trimmedName = contactName.trim()
                            // Update ZCHAT local contact book
                            contactBook.updateContactName(selectedAddress, trimmedName)
                            // Also update main Address Book
                            addressBookRepository.saveContact(
                                name = trimmedName,
                                address = selectedAddress,
                                chain = "zcash"
                            )
                            contactsVersion++ // Trigger recomposition
                            showEditContactDialog = false
                            Toast.makeText(context, "Contact updated", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = contactName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Remove from ZCHAT local contact book
                        contactBook.removeContact(selectedAddress)
                        // Note: We don't remove from main Address Book to preserve user's contacts
                        // The user can manually remove from Address Book if needed
                        contactsVersion++ // Trigger recomposition
                        showEditContactDialog = false
                        Toast.makeText(context, "Contact removed", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
fun AndroidZchatReceive() {
    val viewModel = koinViewModel<ZchatReceiveVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ZchatReceiveView(state = state)
}

@Composable
fun AndroidZchatCompose() {
    val viewModel = koinViewModel<ZchatComposeVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showCostDisclaimer by viewModel.showCostDisclaimer.collectAsStateWithLifecycle()

    ZchatComposeView(state = state)

    // Message Cost Disclaimer Dialog (one-time)
    if (showCostDisclaimer) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCostDisclaimer() },
            title = { Text("Message Cost") },
            text = {
                Column {
                    Text(
                        text = "ZCHAT uses the Zcash blockchain to send private, encrypted messages.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Each message requires a small amount of ZEC (typically 0.00001 ZEC per message chunk, plus network fees).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This disclaimer will only appear once. Future messages will be sent directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.acknowledgeCostDisclaimer() }
                ) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCostDisclaimer() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AndroidChatDetail(peerAddress: String) {
    val viewModel = koinViewModel<ChatViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()
    val currentUserAddress by viewModel.currentUserAddress.collectAsStateWithLifecycle()
    val sendMessageState by viewModel.sendMessageState.collectAsStateWithLifecycle()
    val showCostDisclaimer by viewModel.showCostDisclaimer.collectAsStateWithLifecycle()
    val currentBlockHeight by viewModel.currentBlockHeight.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Handle send message state changes
    androidx.compose.runtime.LaunchedEffect(sendMessageState) {
        when (sendMessageState) {
            is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error -> {
                val rawMessage = (sendMessageState as co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error).message
                // Make error messages more user-friendly
                val userMessage = when {
                    rawMessage.contains("Please wait for your previous message", ignoreCase = true) ->
                        rawMessage  // Preserve the specific pending-change message
                    rawMessage.contains("Insufficient balance", ignoreCase = true) ||
                    rawMessage.contains("InsufficientFunds", ignoreCase = true) ||
                    rawMessage.contains("Insufficient amount of ZEC", ignoreCase = true) ->
                        "Insufficient balance. Please add ZEC to your wallet to send messages."
                    rawMessage.contains("network", ignoreCase = true) ||
                    rawMessage.contains("connection", ignoreCase = true) ->
                        "Network error. Please check your connection and try again."
                    else -> rawMessage
                }
                Toast.makeText(context, userMessage, Toast.LENGTH_LONG).show()
                viewModel.resetSendState()
            }
            is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Success -> {
                Toast.makeText(context, "Message sent", Toast.LENGTH_SHORT).show()
                viewModel.resetSendState()
            }
            else -> { /* handled by UI */ }
        }
    }

    val state = when (val listState = chatListState) {
        is co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Loading -> {
            ChatDetailState.Loading
        }
        is co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Error -> {
            ChatDetailState.Error(listState.message)
        }
        is co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Success -> {
            val conversation = listState.conversations.find { it.peerAddress == peerAddress }
            if (conversation != null && currentUserAddress != null) {
                ChatDetailState.Success(
                    conversation = conversation,
                    currentUserAddress = currentUserAddress!!,
                    balance = listState.balance,
                    zecPriceUsd = listState.zecPriceUsd,
                    privacyStatus = listState.privacyStatus
                )
            } else {
                ChatDetailState.Error("Conversation not found")
            }
        }
    }

    ChatDetailView(
        state = state,
        onBackClick = { navigationRouter.back() },
        onSendMessage = { message, amountZatoshi ->
            // Send message directly using the ViewModel with selected amount
            viewModel.sendMessage(peerAddress, message, amountZatoshi)
            // Clear draft when message is sent
            viewModel.clearDraft(peerAddress)
        },
        onSendReply = { message, replyToId, amountZatoshi ->
            // Send reply to a specific message with selected amount
            viewModel.sendReply(peerAddress, message, replyToId, amountZatoshi)
            // Clear draft when reply is sent
            viewModel.clearDraft(peerAddress)
        },
        onDeleteMessage = { messageId ->
            viewModel.hideMessage(messageId)
        },
        onSendPayment = { amountZec, memo ->
            // Send payment using the ViewModel
            viewModel.sendPayment(peerAddress, amountZec, memo)
        },
        onSendReaction = { messageId, emoji ->
            // Send reaction to a message
            viewModel.sendReaction(peerAddress, messageId, emoji)
        },
        onSendReadReceipt = { messageId ->
            // Send read receipt for a message
            viewModel.sendReadReceipt(peerAddress, messageId)
        },
        // Time-lock message callbacks
        onSendScheduledMessage = { message, unlockTimestamp ->
            viewModel.sendScheduledMessage(peerAddress, message, unlockTimestamp)
        },
        onSendBlockLockedMessage = { message, unlockHeight ->
            viewModel.sendBlockLockedMessage(peerAddress, message, unlockHeight)
        },
        onSendPaymentLockedMessage = { message, requiredZatoshi ->
            viewModel.sendPaymentLockedMessage(peerAddress, message, requiredZatoshi)
        },
        onSendConditionalMessage = { message, answer, hint ->
            viewModel.sendConditionalMessage(peerAddress, message, answer, hint)
        },
        onSendPaymentRequest = { amountZatoshi, reason ->
            viewModel.sendPaymentRequest(peerAddress, amountZatoshi, reason)
        },
        onFulfillPaymentRequest = { amountZatoshi, requestId ->
            viewModel.fulfillPaymentRequest(peerAddress, amountZatoshi, requestId)
        },
        onNicknameChange = { address, nickname ->
            viewModel.setNickname(address, nickname)
        },
        currentBlockHeight = currentBlockHeight,
        onDraftChange = { draft ->
            viewModel.saveDraft(peerAddress, draft)
        },
        onE2EToggle = { enabled ->
            viewModel.setE2EEnabled(peerAddress, enabled)
        },
        onMuteToggle = {
            viewModel.toggleMuteConversation(peerAddress)
        }
    )

    // Message Cost Disclaimer Dialog (one-time)
    if (showCostDisclaimer) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCostDisclaimer() },
            title = { Text("Message Cost") },
            text = {
                Column {
                    Text(
                        text = "ZCHAT uses the Zcash blockchain to send private, encrypted messages.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Each message requires a small amount of ZEC (typically 0.00001 ZEC per message chunk, plus network fees).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This disclaimer will only appear once. Future messages will be sent directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.acknowledgeCostDisclaimer() }
                ) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCostDisclaimer() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Orchard Pool Shielding Warning Dialog
    if (sendMessageState is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsOrchardShielding) {
        val shieldingState = sendMessageState as co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsOrchardShielding
        AlertDialog(
            onDismissRequest = { viewModel.dismissOrchardShieldingWarning() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF9800)
                    )
                    Text("Shield Your Funds")
                }
            },
            text = {
                Column {
                    Text(
                        text = "For maximum privacy, ZCHAT uses the Orchard pool exclusively.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your funds are currently in:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (shieldingState.saplingBalance.value > 0) {
                        Text(
                            text = "• Sapling pool: ${shieldingState.saplingBalance.value / 100_000_000.0} ZEC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (shieldingState.transparentBalance.value > 0) {
                        Text(
                            text = "• Transparent: ${shieldingState.transparentBalance.value / 100_000_000.0} ZEC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Use ZCHAT to shield your funds to the Orchard pool, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.dismissOrchardShieldingWarning() }
                ) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun AndroidCreateGroup() {
    val viewModel = koinViewModel<GroupViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val createGroupState by viewModel.createGroupState.collectAsStateWithLifecycle()

    // Load available contacts when screen opens
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadAvailableContacts()
    }

    // Navigate to group detail when group is created
    androidx.compose.runtime.LaunchedEffect(createGroupState.createdGroupId) {
        createGroupState.createdGroupId?.let { groupId ->
            viewModel.resetCreateGroupState()
            navigationRouter.replace(GroupDetail(groupId))
        }
    }

    CreateGroupView(
        state = createGroupState,
        onBackClick = { navigationRouter.back() },
        onGroupNameChange = { name -> viewModel.setGroupName(name) },
        onMemberToggle = { address -> viewModel.toggleMemberSelection(address) },
        onCreateGroup = { viewModel.createGroup() }
    )
}

@Composable
fun AndroidGroupDetail(groupId: String) {
    val viewModel = koinViewModel<GroupViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val groupDetailState by viewModel.groupDetailState.collectAsStateWithLifecycle()
    val isSendingMessage by viewModel.isSendingMessage.collectAsStateWithLifecycle()

    // Load group detail when screen opens
    androidx.compose.runtime.LaunchedEffect(groupId) {
        viewModel.loadGroupDetail(groupId)
    }

    GroupDetailView(
        state = groupDetailState,
        isSendingMessage = isSendingMessage,
        onBackClick = { navigationRouter.back() },
        onSettingsClick = {
            navigationRouter.forward(GroupSettings(groupId))
        },
        onSendMessage = { message ->
            viewModel.sendGroupMessage(groupId, message)
        },
        onDraftChange = { draft ->
            viewModel.saveGroupDraft(groupId, draft)
        }
    )
}

@Composable
fun AndroidGroupSettings(groupId: String) {
    val context = LocalContext.current
    val viewModel = koinViewModel<GroupViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val groupSettingsState by viewModel.groupSettingsState.collectAsStateWithLifecycle()

    // Load group settings when screen opens
    androidx.compose.runtime.LaunchedEffect(groupId) {
        viewModel.loadGroupSettings(groupId)
    }

    GroupSettingsView(
        state = groupSettingsState,
        onBackClick = { navigationRouter.back() },
        onLeaveGroup = {
            viewModel.leaveGroup(groupId) {
                // Navigate back to chat list after leaving
                navigationRouter.backToRoot()
            }
        },
        onCopyGroupId = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Group ID", groupId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Group ID copied", Toast.LENGTH_SHORT).show()
        }
    )
}
