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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatDetailState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
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
import co.electriccoin.zcash.ui.screen.invite.InviteFriend
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    // #224 — pending inbound OPEN ("free NOSTR from message #1") contact requests.
    val messageRequests by viewModel.messageRequests.collectAsStateWithLifecycle()
    var showRequestsDialog by remember { mutableStateOf(false) }
    // B8 — a notification/in-app-banner tap arms this; open the Requests sheet once the list has seeded
    // (it loads async from EncryptedSharedPreferences, so the arm must survive an initially-empty list).
    val openRequestsArmed by co.electriccoin.zcash.ui.nostr.NostrChatBridge.openRequestsSheetArmed.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openRequestsArmed, messageRequests) {
        if (openRequestsArmed && messageRequests.isNotEmpty()) {
            showRequestsDialog = true
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.clearOpenRequestsSheetArm()
        }
    }
    // C1 (UX audit) — seed-backup reminder, surfaced on the Chats home (see ChatViewModel).
    val showBackupReminder by viewModel.walletBackupAvailable.collectAsStateWithLifecycle()
    // Non-null when an Accept was REFUSED (key-change / pubkey-reuse / self-spoof) — shown as a dialog so
    // the user understands why no chat opened, instead of being stranded on a dead-end screen.
    var requestActionError by remember { mutableStateOf<String?>(null) }
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

    // One-shot three-mode onboarding. Flag persists so the dialog only ever shows once
    // per install + seed; resets if the user wipes app data.
    var showModeIntro by remember { mutableStateOf(!zchatPreferences.hasSeenModeIntro()) }

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
        },
        // Recovery for a forgotten/mis-set destroy PIN. ChatListView gates this behind a device-credential
        // confirm before invoking it (so it can't be used to bypass the wipe protection casually).
        onResetDestroyPin = {
            zchatPreferences.clearDestroyPin()
        },
        onInviteFriendClick = {
            navigationRouter.forward(InviteFriend)
        },
        messageRequestCount = messageRequests.size,
        onRequestsClick = { if (messageRequests.isNotEmpty()) showRequestsDialog = true },
        showBackupReminder = showBackupReminder,
        onBackupReminderClick = {
            navigationRouter.forward(co.electriccoin.zcash.ui.screen.home.backup.SeedBackupInfo)
        }
    )

    // Auto-close the sheet once every request has been handled (done in an effect, NOT inline, so we
    // never write Compose state during composition).
    androidx.compose.runtime.LaunchedEffect(messageRequests.isEmpty()) {
        if (messageRequests.isEmpty()) showRequestsDialog = false
    }

    // #224 — Message Requests sheet: accept (start a free OPEN chat) or reject+block each request.
    if (showRequestsDialog && messageRequests.isNotEmpty()) {
        MessageRequestsDialog(
            requests = messageRequests,
            onAccept = { req ->
                when (viewModel.acceptMessageRequest(req)) {
                    ChatViewModel.AcceptRequestResult.ACCEPTED -> {
                        showRequestsDialog = false
                        navigationRouter.forward(ChatDetail(req.senderAddress))
                    }
                    ChatViewModel.AcceptRequestResult.CONFLICT_KEY_CHANGED ->
                        requestActionError = "This address is already linked to a DIFFERENT security key. " +
                            "That can mean the contact rotated keys — or an impersonation attempt. We did " +
                            "not link it. Verify the contact out-of-band before accepting."
                    ChatViewModel.AcceptRequestResult.CONFLICT_PUBKEY_REUSED ->
                        requestActionError = "This sender's key is already linked to another contact. " +
                            "Accepting could let one identity impersonate several — so we did not link it."
                    ChatViewModel.AcceptRequestResult.REJECTED_SELF ->
                        requestActionError = "That request claims your OWN address, so it was discarded."
                }
            },
            onReject = { req ->
                viewModel.rejectMessageRequest(req)
            },
            onDismiss = { showRequestsDialog = false; co.electriccoin.zcash.ui.nostr.NostrChatBridge.clearOpenRequestsSheetArm() }
        )
    }

    // #224 — explains a refused Accept (key-change / pubkey-reuse / self-spoof) instead of silently
    // stranding the user. The requests sheet stays open behind it.
    requestActionError?.let { msg ->
        AlertDialog(
            onDismissRequest = { requestActionError = null },
            title = { Text("Couldn't accept request") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { requestActionError = null }) { Text("OK") } }
        )
    }

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
                    Text("Delete", color = NightwireColors.ColorDanger)
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
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = NightwireColors.TextSecondary
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
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = NightwireColors.TextSecondary
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
                    Text("Remove", color = NightwireColors.ColorDanger)
                }
            }
        )
    }

    if (showModeIntro) {
        co.electriccoin.zcash.ui.screen.chat.view.ConversationModeIntroDialog(
            onDismiss = {
                zchatPreferences.setHasSeenModeIntro(true)
                showModeIntro = false
            },
        )
    }
}

/**
 * #224 — Message Requests sheet. Lists pending inbound OPEN ("free NOSTR from message #1") contact
 * requests. Each shows the CLAIMED Zcash address (UNVERIFIED — only the NOSTR key is authenticated) and
 * the first message, with Accept (start a free OPEN chat + bind the key) or Reject (drop + block).
 */
@Composable
private fun MessageRequestsDialog(
    requests: List<co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.MessageRequest>,
    onAccept: (co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.MessageRequest) -> Unit,
    onReject: (co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.MessageRequest) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message requests", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "These people messaged you for free over NOSTR. The Zcash address shown is " +
                        "claimed by the sender and is NOT verified yet — accept only if you recognise it.",
                    fontSize = 12.sp,
                    color = NightwireColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                requests.forEach { req ->
                    val shortAddr = if (req.senderAddress.length > 24) {
                        "${req.senderAddress.take(14)}…${req.senderAddress.takeLast(8)}"
                    } else {
                        req.senderAddress
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = shortAddr,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NightwireColors.TextPrimary
                        )
                        Text(
                            text = req.firstMessage.take(140).ifBlank { "(no message text)" },
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { onReject(req) }) {
                                Text("Reject", color = NightwireColors.ColorDanger)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { onAccept(req) }) {
                                Text("Accept", color = NightwireColors.AccentPrimary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Each message requires a small amount of ZEC (typically 0.00001 ZEC per message chunk, plus network fees).",
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This disclaimer will only appear once. Future messages will be sent directly.",
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary
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

/**
 * Place an outbound call (audio or [mode]==VIDEO) to the conversation peer. Resolves the
 * peer's NOSTR pubkey (set after a Tunnel bootstrap or Open-mode exchange) and surfaces a
 * clear toast when it isn't known yet, rather than silently failing.
 */
private suspend fun startCall(
    context: Context,
    prefs: ZchatPreferences,
    peerAddress: String,
    mode: co.electriccoin.zcash.ui.call.CallMode,
    onNeedKeyExchange: () -> Unit,
) {
    val peerPub = prefs.getPeerNostrPubkey(peerAddress)
    // The foreground service registers the VoiceCallManager asynchronously (startNostrInbox), so the
    // call button can be tapped during the transient window before it's ready. Wait briefly for
    // readiness instead of dead-ending with a "not ready" toast on that race.
    val mgr = co.electriccoin.zcash.ui.call.CallController.current.value
        ?: withTimeoutOrNull(5_000L) {
            co.electriccoin.zcash.ui.call.CallController.current.first { it != null }
        }
    when {
        mgr == null ->
            Toast.makeText(context, "Call subsystem isn't ready yet — please try again in a moment.", Toast.LENGTH_SHORT).show()
        peerPub == null -> {
            // Don't dead-end the user. Publish our NOSTR key (shielded ZBOOT) so the peer learns
            // it; once they're also on Tunnel/Open, calls connect with no manual npub paste.
            onNeedKeyExchange()
            Toast.makeText(
                context,
                "Exchanging secure call keys with your peer. Once they open this chat in Tunnel/Open mode, calls will connect — try again shortly.",
                Toast.LENGTH_LONG,
            ).show()
        }
        else -> mgr.placeCall(peerPub, mode)
    }
}

@Composable
fun AndroidChatDetail(peerAddress: String) {
    val viewModel = koinViewModel<ChatViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()
    val currentUserAddress by viewModel.currentUserAddress.collectAsStateWithLifecycle()
    val sendMessageState by viewModel.sendMessageState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val fileDownloadProgress by viewModel.fileDownloadProgress.collectAsStateWithLifecycle()
    val fileDownloadFailures by viewModel.fileDownloadFailures.collectAsStateWithLifecycle()
    val showCostDisclaimer by viewModel.showCostDisclaimer.collectAsStateWithLifecycle()
    val currentBlockHeight by viewModel.currentBlockHeight.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Quantum Shield QR dialog state
    var showQuantumShieldDialog by remember { mutableStateOf(false) }
    var quantumShieldQrPayload by remember { mutableStateOf("") }

    // Conversation-mode picker state
    val zchatPreferences = koinInject<co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences>()
    var currentMode by remember(peerAddress) {
        mutableStateOf(zchatPreferences.getConversationMode(peerAddress))
    }
    var showModePicker by remember { mutableStateOf(false) }

    // Tunnel/Open bootstrap on chat-open. A conversation STARTED fresh in Tunnel/Open mode via New
    // Chat (ZchatComposeVM.send sets the mode but only sends the first message on-chain as a plain
    // INIT) never kicks the KEX handshake — only the in-chat mode SWITCH (onPick below) did. Without
    // this, such a chat never bootstraps the NOSTR channel (no KEX/ZBOOT is ever sent), so it silently
    // stays on-chain forever and voice/video calls + free NOSTR delivery never unlock. Firing the
    // bootstrap on open closes that gap. ensureNostrBootstrapSent is idempotent — a no-op once our
    // boot/KEX is already sent or the peer's NOSTR pubkey is known — so calling it on every open is safe.
    // Key on currentUserAddress too: AndroidChatDetail gets its OWN ChatViewModel (koinViewModel),
    // whose _currentUserAddress loads async after composition. ensureNostrBootstrapSent bails on a
    // null _currentUserAddress (before it can send the KEX), so firing only on first composition
    // loses the race and the bootstrap never starts. Re-firing when the address resolves (null →
    // value) guarantees the KEX goes out. Still idempotent, so the extra fire is a no-op once sent.
    androidx.compose.runtime.LaunchedEffect(peerAddress, currentUserAddress) {
        if (currentUserAddress != null) {
            // #233: responder-side handshake retry. MUST run even in VAULT — a cold Tunnel first-contact's
            // responder is still in VAULT until it receives the initiator's ZBOOT, and that ZBOOT only comes
            // after OUR KEXACK reaches the initiator. Re-sending the KEXACK here (idempotent, self-gated to
            // the responder + incomplete handshakes) unblocks the deadlock. KEXACK carries no NOSTR/mode data,
            // so this is safe for genuine VAULT chats.
            viewModel.retryKexAckIfResponder(peerAddress)
            if (zchatPreferences.getConversationMode(peerAddress) !=
                co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT
            ) {
                viewModel.ensureNostrBootstrapSent(peerAddress)
            }
        }
    }
    // Share-sheet / "Send via ZCHAT" image delivery. The SharePicker armed one or more images for THIS
    // peer in PendingShareStore; consume them once (guarded so re-composition doesn't re-send) and hand
    // each to the existing handlePickedImage path SEQUENTIALLY — that path atomically single-flights its
    // upload slot (tryStart), so firing them back-to-back is safe: each awaits the previous.
    androidx.compose.runtime.LaunchedEffect(peerAddress) {
        val files = co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.consumeArmedFor(peerAddress)
        if (!files.isNullOrEmpty()) {
            if (files.size > 1) {
                Toast.makeText(context, "Sending ${files.size} images…", Toast.LENGTH_SHORT).show()
            }
            for (file in files) {
                val uri = android.net.Uri.fromFile(file)
                viewModel.handlePickedImage(peerAddress, uri, context)
                // Serialise: handlePickedImage single-flights its upload slot (tryStart), so a second
                // image fired before the first finishes would be dropped. The upload runs async in the
                // VM scope, so first wait (briefly) for progress to leave idle (started), then wait for
                // it to return to idle (finished/failed) before launching the next. Both bounded so a
                // fast-failing image can't wedge the loop.
                withTimeoutOrNull(3_000L) {
                    viewModel.uploadProgress.first { it != null }
                }
                withTimeoutOrNull(120_000L) {
                    viewModel.uploadProgress.first { it == null }
                }
                // Best-effort cleanup of the copied share-inbox file once handed off.
                runCatching { file.delete() }
            }
        }
    }

    // #178 Part A: one-time security note shown after switching a chat to OPEN/TUNNEL.
    var modeSecurityNote by remember { mutableStateOf<co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?>(null) }
    // #178 Part B: weekly key-rotation reminder banner + confirm/restart dialogs.
    var showRotationReminder by remember { mutableStateOf(false) }
    var showRotationConfirm by remember { mutableStateOf(false) }
    var showRotationRestartNote by remember { mutableStateOf(false) }

    // Flips true right before launching the image picker for view-once mode; consumed
    // (read+reset) in the picker callback.
    var pendingViewOnceImagePick by remember { mutableStateOf(false) }

    // Image picker for file sharing (Phase 2)
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val viewOnce = pendingViewOnceImagePick
        pendingViewOnceImagePick = false
        if (uri != null) {
            scope.launch {
                if (viewOnce) viewModel.handlePickedImageViewOnce(peerAddress, uri, context)
                else viewModel.handlePickedImage(peerAddress, uri, context)
            }
        }
    }

    // Camera capture URI is generated in the onTakePhoto handler below, but the result
    // callback needs it. Hoist into a remembered holder so result + launch see the same URI.
    val pendingCameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCameraUri.value
        pendingCameraUri.value = null
        if (success && uri != null) {
            scope.launch { viewModel.handlePickedImage(peerAddress, uri, context) }
        }
    }

    // Audio recording state — owned by the screen Composable so the recorder survives
    // recompositions while a take is in flight, and gets cancelled in onDispose if the
    // user navigates away mid-recording (preventing a stuck MediaRecorder + an orphaned
    // .m4a in the cache).
    var audioRecorder by remember { mutableStateOf<co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder?>(null) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var recordingViewOnce by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(audioRecorder) {
        val r = audioRecorder ?: return@LaunchedEffect
        while (audioRecorder === r && r.durationMs < co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder.MAX_DURATION_MS) {
            recordingSeconds = (r.durationMs / 1000).toInt()
            kotlinx.coroutines.delay(250)
        }
        // Hit the 60s cap → auto-send.
        if (audioRecorder === r) {
            val durMs = r.durationMs
            val viewOnce = recordingViewOnce
            val file = r.stop()
            audioRecorder = null
            recordingSeconds = 0
            recordingViewOnce = false
            if (file != null) {
                if (viewOnce) viewModel.handleRecordedAudioViewOnce(peerAddress, file, durMs, context)
                else viewModel.handleRecordedAudio(peerAddress, file, durMs, context)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            audioRecorder?.cancel()
            audioRecorder = null
        }
    }

    // Track whether the in-flight permission request should land in view-once mode.
    var pendingMicViewOnce by remember { mutableStateOf(false) }
    val recordPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        val viewOnce = pendingMicViewOnce
        pendingMicViewOnce = false
        if (granted) {
            try {
                audioRecorder = co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder.start(context)
                recordingSeconds = 0
                recordingViewOnce = viewOnce
            } catch (e: Exception) {
                Toast.makeText(context, "Could not start recorder: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // #240: place-call permission gate. VoiceCallManager.placeCall / acceptIncoming already ABORT with
    // PermissionDenied when RECORD_AUDIO isn't granted, but nothing in the call path ever REQUESTS it
    // (only the voice-memo mic button did). So a user who never recorded a memo taps Call and it
    // silently dies with no prompt. Request the permission AT the call tap, then place the call once
    // granted. Mic is the only hard requirement — the manager degrades a video call to audio-only when
    // CAMERA is denied — so we request CAMERA for video too but only block the call on mic.
    var pendingCallVideo by remember(peerAddress) { mutableStateOf<Boolean?>(null) }
    val callPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val video = pendingCallVideo
        pendingCallVideo = null
        if (video != null) {
            val micOk = grants[android.Manifest.permission.RECORD_AUDIO] == true ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (micOk) {
                val mode =
                    if (video) co.electriccoin.zcash.ui.call.CallMode.VIDEO
                    else co.electriccoin.zcash.ui.call.CallMode.AUDIO
                scope.launch {
                    startCall(context, zchatPreferences, peerAddress, mode) {
                        viewModel.ensureNostrBootstrapSent(peerAddress, force = true)
                    }
                }
            } else {
                Toast.makeText(context, "Microphone permission is required to place a call", Toast.LENGTH_LONG).show()
            }
        }
    }
    val initiateCall: (Boolean) -> Unit = { video ->
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted && (!video || cameraGranted)) {
            val mode =
                if (video) co.electriccoin.zcash.ui.call.CallMode.VIDEO
                else co.electriccoin.zcash.ui.call.CallMode.AUDIO
            scope.launch {
                startCall(context, zchatPreferences, peerAddress, mode) {
                    viewModel.ensureNostrBootstrapSent(peerAddress, force = true)
                }
            }
        } else {
            pendingCallVideo = video
            val perms =
                if (video) {
                    arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
                } else {
                    arrayOf(android.Manifest.permission.RECORD_AUDIO)
                }
            callPermissionLauncher.launch(perms)
        }
    }

    // Document/file picker — PDFs, ZIPs, text, or any image MIME the system surfaces.
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch { viewModel.handlePickedFile(peerAddress, uri, context) }
        }
    }

    // Handle send message state changes
    androidx.compose.runtime.LaunchedEffect(sendMessageState) {
        when (sendMessageState) {
            is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error -> {
                val rawMessage = (sendMessageState as co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error).message
                // Make error messages more user-friendly
                val userMessage = when {
                    rawMessage.contains("confirm on-chain", ignoreCase = true) ->
                        rawMessage  // Preserve the specific "funds still confirming" message (change or received)
                    rawMessage.contains("Insufficient balance", ignoreCase = true) ||
                    rawMessage.contains("InsufficientFunds", ignoreCase = true) ||
                    rawMessage.contains("Insufficient amount of ZEC", ignoreCase = true) ->
                        "Insufficient balance for an on-chain (Vault) message. Add ZEC, or switch this chat to " +
                            "Tunnel/Open in the ⋮ menu to message free over NOSTR."
                    rawMessage.contains("network", ignoreCase = true) ||
                    rawMessage.contains("connection", ignoreCase = true) ->
                        "Network error. Please check your connection and try again."
                    else -> rawMessage
                }
                Toast.makeText(context, userMessage, Toast.LENGTH_LONG).show()
                viewModel.resetSendState()
            }
            is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Success -> {
                val label = (sendMessageState as co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Success).label
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
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

    // File downloads: trigger background download for any ZFILE messages whose cache is empty
    androidx.compose.runtime.LaunchedEffect(state) {
        val s = state
        if (s is co.electriccoin.zcash.ui.screen.chat.model.ChatDetailState.Success) {
            for (msg in s.conversation.messages) {
                val hash = msg.fileHash ?: continue
                val zfileContent = msg.fileZfileContent ?: continue
                // Don't re-fetch a consumed view-once file: its cache was wiped on view, so the
                // !exists() check below would otherwise resurrect it from the relay. (downloadAndCacheFile
                // enforces this too; this skips the needless launch.)
                if (msg.fileViewOnce && msg.fileViewed) continue
                val cacheFile = java.io.File(context.cacheDir, "zchat_files/$hash")
                if (!cacheFile.exists()) {
                    viewModel.downloadAndCacheFile(zfileContent, msg.peerAddress, context)
                }
            }
        }
    }

    // Mark this conversation read whenever its content is shown (open or new messages arrive while
    // viewing). Clears the unread badge / preview bolding on the chat list. Keyed on the message
    // count so a message that lands while the screen is open also advances the read marker.
    androidx.compose.runtime.LaunchedEffect(
        peerAddress,
        (state as? ChatDetailState.Success)?.conversation?.messages?.size
    ) {
        if (state is ChatDetailState.Success) {
            viewModel.markConversationRead(peerAddress)
        }
    }

    // #178 Part B: surface the key-rotation reminder at most once per week, and only in NOSTR-transport
    // chats (Vault has no relay-published key to rotate).
    androidx.compose.runtime.LaunchedEffect(peerAddress) {
        val mode = zchatPreferences.getConversationMode(peerAddress)
        if (mode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
            val last = zchatPreferences.getLastRotationReminderAt()
            val weekMillis = 7L * 24 * 60 * 60 * 1000
            if (System.currentTimeMillis() - last >= weekMillis) {
                showRotationReminder = true
            }
        }
    }

    // B17 — per-conversation disappearing-messages TTL (process-wide prefs flow).
    val disappearTtls by viewModel.disappearingTtls.collectAsStateWithLifecycle()

    ChatDetailView(
        state = state,
        onBackClick = { navigationRouter.back() },
        isKeyChanged = viewModel.isE2EKeyChanged(peerAddress),
        onDismissKeyChanged = { viewModel.dismissE2EKeyChanged(peerAddress) },
        showDecryptRecovery = viewModel.hasDecryptFailure(peerAddress),
        showRotationReminder = showRotationReminder,
        onRotateKeyCta = {
            // Mark shown so it doesn't re-nag, then confirm (rotation costs an on-chain re-KEX per peer).
            zchatPreferences.setLastRotationReminderAt(System.currentTimeMillis())
            showRotationReminder = false
            showRotationConfirm = true
        },
        onDismissRotationReminder = {
            zchatPreferences.setLastRotationReminderAt(System.currentTimeMillis())
            showRotationReminder = false
        },
        safetyNumber = viewModel.computeSafetyNumber(peerAddress),
        isVerified = viewModel.isE2EVerified(peerAddress),
        onMarkVerified = { viewModel.markE2EVerified(peerAddress) },
        quantumShieldStatus = viewModel.getQuantumShieldStatus(peerAddress).name,
        onResetQuantumShield = {
            viewModel.resetQuantumShield(peerAddress)
            android.widget.Toast.makeText(context, "Extra Security (Post-Quantum) turned off", android.widget.Toast.LENGTH_SHORT).show()
        },
        onInitiateQuantumShield = {
            val qrPayload = viewModel.initiateQuantumShield(peerAddress)
            quantumShieldQrPayload = qrPayload
            showQuantumShieldDialog = true
        },
        onSendImage = { imagePickerLauncher.launch("image/*") },
        onTakePhoto = {
            val captureFile = java.io.File(
                context.cacheDir,
                "camera_${System.currentTimeMillis()}.jpg"
            )
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                captureFile
            )
            pendingCameraUri.value = uri
            try {
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                pendingCameraUri.value = null
                Toast.makeText(context, "Camera unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            }
        },
        onSendFile = {
            try {
                filePickerLauncher.launch(
                    arrayOf("application/pdf", "application/zip", "text/plain", "image/*")
                )
            } catch (e: Exception) {
                Toast.makeText(context, "File picker unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            }
        },
        onSendViewOnceImage = {
            pendingViewOnceImagePick = true
            imagePickerLauncher.launch("image/*")
        },
        onMarkFileViewed = { fileHash -> viewModel.markFileViewed(fileHash, context) },
        conversationMode = currentMode,
        onPickConversationMode = { showModePicker = true },
        onPlaceCall = { initiateCall(false) },
        onPlaceVideoCall = { initiateCall(true) },
        onReconnect = { viewModel.reSendOurIdentity(peerAddress) },
        onResetEncryption = { viewModel.resetSecureSession(peerAddress) },
        disappearingTtlSeconds = disappearTtls[peerAddress]?.ttlSeconds ?: 0L,
        onSetDisappearingTtl = { viewModel.setDisappearingTtl(peerAddress, it) },
        isRecording = audioRecorder != null,
        recordingSeconds = recordingSeconds,
        isRecordingViewOnce = recordingViewOnce,
        onMicTap = {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingMicViewOnce = false
                recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                try {
                    audioRecorder = co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder.start(context)
                    recordingSeconds = 0
                    recordingViewOnce = false
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not start recorder: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        },
        onMicLongPress = {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingMicViewOnce = true
                recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                try {
                    audioRecorder = co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder.start(context)
                    recordingSeconds = 0
                    recordingViewOnce = true
                    Toast.makeText(context, "View-once recording — plays once then deletes", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not start recorder: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        },
        onSendRecording = {
            val r = audioRecorder ?: return@ChatDetailView
            val durMs = r.durationMs
            val viewOnce = recordingViewOnce
            val file = r.stop()
            audioRecorder = null
            recordingSeconds = 0
            recordingViewOnce = false
            if (file != null) {
                if (viewOnce) viewModel.handleRecordedAudioViewOnce(peerAddress, file, durMs, context)
                else viewModel.handleRecordedAudio(peerAddress, file, durMs, context)
            } else {
                Toast.makeText(context, "Recording too short", Toast.LENGTH_SHORT).show()
            }
        },
        onCancelRecording = {
            audioRecorder?.cancel()
            audioRecorder = null
            recordingSeconds = 0
            recordingViewOnce = false
        },
        uploadProgress = uploadProgress,
        fileDownloadProgress = fileDownloadProgress,
        fileDownloadFailures = fileDownloadFailures,
        onRetryDownload = { zfileContent, retryPeerAddress ->
            viewModel.downloadAndCacheFile(zfileContent, retryPeerAddress, context)
        },
        onSendMessage = { message, amountZatoshi ->
            // /ai slash command — local-only AI query, NEVER on-chain (no ZEC cost, no
            // ratchet step). Strict prefix prevents accidental leak of intended-for-AI
            // prompts to the peer over the encrypted channel.
            //
            // SECURITY: use Locale.ROOT for lowercasing — default lowercase() is locale-aware
            // and in Turkish (tr_TR) it maps 'I' to 'ı' (dotless), making `/AI`.lowercase()
            // = `/aı` which would NOT match `/ai` and would leak to peer.
            val trimmed = message.trimStart()
            val lowerRoot = trimmed.lowercase(java.util.Locale.ROOT)
            // The lambda returns whether the input should be CLEARED (send accepted). A pre-queue
            // rejection keeps the user's text (B1-msg-lost-on-blocked-send).
            when {
                trimmed.startsWith("/ai ") -> {
                    viewModel.handleAiCommand(peerAddress, trimmed.removePrefix("/ai ").trim(), context)
                    viewModel.clearDraft(peerAddress)
                    true
                }
                lowerRoot.startsWith("/ai") -> {
                    // Sloppy: /AI, /ai without space, /ai\t, /Ai, /aI etc — reject all.
                    Toast.makeText(
                        context,
                        "Use exact lowercase: /ai <prompt> (with a space)",
                        Toast.LENGTH_SHORT,
                    ).show()
                    false // keep the malformed /ai text so the user can fix it
                }
                else -> {
                    val accepted = viewModel.sendMessage(peerAddress, message, amountZatoshi)
                    if (accepted) viewModel.clearDraft(peerAddress)
                    accepted
                }
            }
        },
        onSendReply = { message, replyToId, replyPreview, amountZatoshi ->
            // Send reply to a specific message with selected amount. replyPreview is the quoted message's
            // displayText captured at tap time — the authoritative source for the quote on both ends.
            val accepted = viewModel.sendReply(peerAddress, message, replyToId, replyPreview, amountZatoshi)
            // Clear draft only if the reply was accepted (kept on a pre-queue rejection).
            if (accepted) viewModel.clearDraft(peerAddress)
            accepted
        },
        onDeleteMessage = { messageId ->
            viewModel.hideMessage(messageId)
        },
        onRetryMessage = { messageId ->
            val retried = viewModel.retryMessage(peerAddress, messageId)
            if (!retried) {
                Toast.makeText(context, "Can't retry this message", Toast.LENGTH_SHORT).show()
            }
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
        },
        showWelcomeZecSuggestion = (state as? ChatDetailState.Success)?.let { s ->
            s.conversation.contactName != null && s.conversation.messages.none { it.isOutgoing }
        } ?: false,
        onSendWelcomeZec = {
            // Navigate to Send screen with pre-filled 0.005 ZEC to this address
            viewModel.sendPayment(peerAddress, 0.005, "Welcome to ZCHAT!")
        }
    )

    // Quantum Shield QR Exchange Dialog
    if (showQuantumShieldDialog && quantumShieldQrPayload.isNotEmpty()) {
        val cc = chatColors()
        AlertDialog(
            onDismissRequest = { showQuantumShieldDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = cc.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extra Security (Post-Quantum)", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Adds a post-quantum key on top of E2E encryption for this chat.",
                        fontSize = 12.sp,
                        color = cc.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Show this QR code to your contact. They scan it, then show you theirs.",
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    co.electriccoin.zcash.ui.design.component.ZashiQr(
                        state = co.electriccoin.zcash.ui.design.component.QrState(
                            qrData = quantumShieldQrPayload,
                        ),
                        qrSize = 200.dp, // Dp imported via Modifier.padding
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "After both sides scan, Extra Security (Post-Quantum) turns on automatically.",
                        fontSize = 12.sp,
                        color = cc.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuantumShieldDialog = false }) {
                    Text("Done", color = cc.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showQuantumShieldDialog = false
                    // Set the bridge callback before navigating to scanner
                    co.electriccoin.zcash.ui.screen.chat.filesharing.QuantumShieldScanBridge.setPending(peerAddress) { zcpskPayload ->
                        val success = viewModel.completeQuantumShield(peerAddress, zcpskPayload)
                        if (success) {
                            android.widget.Toast.makeText(context, "Extra Security (Post-Quantum) is on!", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "Invalid QR code", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    navigationRouter.forward(
                        co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressArgs()
                    )
                }) {
                    Text("Scan Peer's QR", color = cc.primary)
                }
            },
            containerColor = cc.background,
            titleContentColor = cc.textPrimary,
            textContentColor = cc.textPrimary,
        )
    }

    // Message Cost Disclaimer Dialog (one-time)
    if (showCostDisclaimer) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCostDisclaimer() },
            title = { Text("Message Cost") },
            text = {
                Column {
                    Text(
                        text = "ZCHAT uses the Zcash blockchain to send private, encrypted messages.",
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Each message requires a small amount of ZEC (typically 0.00001 ZEC per message chunk, plus network fees).",
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This disclaimer will only appear once. Future messages will be sent directly.",
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary
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
                        tint = chatColors().warning
                    )
                    Text("Shield Your Funds")
                }
            },
            text = {
                Column {
                    Text(
                        text = "For maximum privacy, ZCHAT uses the Orchard pool exclusively.",
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your funds are currently in:",
                        fontSize = 15.sp
                    )
                    if (shieldingState.saplingBalance.value > 0) {
                        Text(
                            text = "• Sapling pool: ${shieldingState.saplingBalance.value / 100_000_000.0} ZEC",
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                    }
                    if (shieldingState.transparentBalance.value > 0) {
                        Text(
                            text = "• Transparent: ${shieldingState.transparentBalance.value / 100_000_000.0} ZEC",
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Use ZCHAT to shield your funds to the Orchard pool, then try again.",
                        fontSize = 15.sp,
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

    if (showModePicker) {
        co.electriccoin.zcash.ui.screen.chat.view.ConversationModePickerDialog(
            current = currentMode,
            // OPEN delivers over NOSTR and needs the peer's NOSTR key. Only offer it once we actually
            // hold that key — otherwise an OPEN send has no channel and silently fails (the "message
            // not sent / retry" bug). Until then the user picks Tunnel, which exchanges the key via an
            // on-chain handshake and then runs free over NOSTR.
            allowOpen = zchatPreferences.getPeerNostrPubkey(peerAddress) != null,
            onPick = { picked ->
                // Cross-device mode sync: persists + pins the explicit choice, pills it, and notifies
                // the peer with an authenticated ZMODE control so THEIR device drops the stale mode
                // (re-picking the current mode pins it but transmits nothing).
                viewModel.changeConversationMode(peerAddress, picked)
                currentMode = picked
                // Switching to a NOSTR transport: proactively publish our NOSTR pubkey to the peer
                // (shielded ZBOOT) so calls work without a manual npub paste. When both sides switch,
                // both keys are exchanged and voice/video connect immediately.
                if (picked != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
                    viewModel.ensureNostrBootstrapSent(peerAddress)
                    // One-time, per-(peer,mode) security note explaining the relay trade-off (#178 Part A).
                    // Only trigger the dialog here; the "seen" flag is persisted on dismiss (below) so a
                    // dialog that never actually renders doesn't get silently marked as shown.
                    if (!zchatPreferences.hasSeenModeSecurityNote(peerAddress, picked)) {
                        modeSecurityNote = picked
                    }
                }
            },
            onDismiss = { showModePicker = false },
        )
    }

    modeSecurityNote?.let { m ->
        co.electriccoin.zcash.ui.screen.chat.view.ModeSecurityNoteDialog(
            mode = m,
            onDismiss = {
                // Persist "seen" only after the user actually dismisses the note they saw.
                zchatPreferences.setSeenModeSecurityNote(peerAddress, m)
                modeSecurityNote = null
            },
        )
    }

    // #178 Part B: rotation confirmation — discloses the on-chain re-KEX cost. Only the on-chain key
    // exchange is charged; this is consistent with Tunnel/Open billing. (#188: the inbox now hot-swaps,
    // so no restart is required afterward.)
    if (showRotationConfirm) {
        AlertDialog(
            onDismissRequest = { showRotationConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showRotationConfirm = false
                    viewModel.rotateNostrIdentity()
                    showRotationRestartNote = true
                }) { Text("Rotate key") }
            },
            dismissButton = { TextButton(onClick = { showRotationConfirm = false }) { Text("Cancel") } },
            title = { Text("Rotate your NOSTR key?") },
            text = {
                Text(
                    "This generates a fresh messaging key and announces it to each of your relay contacts. " +
                        "The announcement is signed by your existing identity, so your contacts' apps adopt " +
                        "the new key automatically and the chat continues — no re-verification needed. It's " +
                        "sent free over the relay; a contact who's offline is re-synced on-chain when you next " +
                        "reach them. The new key activates immediately for both sending and receiving."
                )
            },
        )
    }

    if (showRotationRestartNote) {
        AlertDialog(
            onDismissRequest = { showRotationRestartNote = false },
            confirmButton = { TextButton(onClick = { showRotationRestartNote = false }) { Text("Got it") } },
            title = { Text("Key rotated") },
            text = {
                Text(
                    "Your new key is now active for everything you send and receive — no restart needed. " +
                        "Your contacts continue seamlessly; their app shows a brief “contact rotated their key” note."
                )
            },
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

    // Navigate to group detail when group is created. #199/P0.1: failed invites are emitted on
    // GroupViewModel.groupSendEvent (InviteFailed) and rendered as a banner by the group-detail
    // screen we land on — replacing the old Toast fired from this dying screen.
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
    // P0.1 — visible group-send outcomes (no active recipients / all failed / partial / invite
    // failed), rendered as a snackbar banner inside GroupDetailView and consumed once shown.
    val groupSendEvent by viewModel.groupSendEvent.collectAsStateWithLifecycle()

    // Load group detail when screen opens
    androidx.compose.runtime.LaunchedEffect(groupId) {
        viewModel.loadGroupDetail(groupId)
    }

    GroupDetailView(
        state = groupDetailState,
        isSendingMessage = isSendingMessage,
        sendResult = groupSendEvent,
        onSendResultConsumed = { viewModel.consumeGroupSendEvent() },
        onBackClick = { navigationRouter.back() },
        onSettingsClick = {
            navigationRouter.forward(GroupSettings(groupId))
        },
        onSendMessage = { message ->
            viewModel.sendGroupMessage(groupId, message)
        },
        onDraftChange = { draft ->
            viewModel.saveGroupDraft(groupId, draft)
        },
        onSyncKeys = {
            // Reload group state to re-derive any group keys this device is missing.
            viewModel.loadGroupDetail(groupId)
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
        },
        onKickMember = { address ->
            viewModel.kickMember(groupId, address)
            Toast.makeText(context, "Removing member & rotating key…", Toast.LENGTH_SHORT).show()
        },
        onRotateKey = {
            viewModel.rotateGroupKey(groupId)
            Toast.makeText(context, "Rotating group key…", Toast.LENGTH_SHORT).show()
        },
        onResendInvite = { address ->
            // P1.4 — re-run the single-member invite path for a FAILED member; the badge tracks
            // the outcome (Inviting… → Invited / Invite failed) via loadGroupSettings refreshes.
            viewModel.resendInvite(groupId, address)
            Toast.makeText(context, "Resending invite…", Toast.LENGTH_SHORT).show()
        }
    )
}
