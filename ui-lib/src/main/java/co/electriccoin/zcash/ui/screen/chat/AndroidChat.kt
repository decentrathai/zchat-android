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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.ChatSweepPrompt
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.chatSweepPrompt
import co.electriccoin.zcash.ui.common.notification.OpenChat
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookArgs
import co.electriccoin.zcash.ui.screen.chat.crypto.QsAlert
import co.electriccoin.zcash.ui.screen.chat.crypto.QsProblem
import co.electriccoin.zcash.ui.screen.chat.datasource.TeamContactMigration
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatDetailState
import co.electriccoin.zcash.ui.screen.chat.model.ChatFreeInstead
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.PendingImageSend
import co.electriccoin.zcash.ui.screen.chat.model.UpgradeReminders
import co.electriccoin.zcash.ui.screen.chat.model.WelcomeZec
import co.electriccoin.zcash.ui.screen.chat.model.zecDisplay
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.DestroyRecordSeal
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatDetailView
import co.electriccoin.zcash.ui.screen.chat.view.ChatListView
import co.electriccoin.zcash.ui.screen.chat.view.CreateGroupView
import co.electriccoin.zcash.ui.screen.chat.view.GroupDetailView
import co.electriccoin.zcash.ui.screen.chat.view.GroupSettingsView
import co.electriccoin.zcash.ui.screen.chat.view.JoinTapGate
import co.electriccoin.zcash.ui.screen.chat.view.ZchatComposeView
import co.electriccoin.zcash.ui.screen.chat.view.ZchatReceiveView
import co.electriccoin.zcash.ui.screen.chat.view.askThemToUpdateChooser
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
import co.electriccoin.zcash.ui.screen.chat.view.joinOutcomeAfterRecordRead
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ZchatComposeVM
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ZchatReceiveVM
import co.electriccoin.zcash.ui.screen.invite.InviteFriend
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import co.electriccoin.zcash.ui.screen.moveoldbalance.MoveOldBalanceArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun AndroidChatList() {
    val viewModel = koinViewModel<ChatViewModel>()
    val groupViewModel = koinViewModel<GroupViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val contactBook = koinInject<ContactBook>()
    val addressBookRepository = koinInject<AddressBookRepository>()
    val zchatPreferences = koinInject<ZchatPreferences>()
    // ZC-733-B: the contact book's and the picture index's "cannot be read" flags, said in the banner column.
    val contactNamesUnreadable by contactBook.unreadable.collectAsStateWithLifecycle()
    val pictureIndexUnreadable by koinInject<co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore>()
        .unavailable
        .collectAsStateWithLifecycle()
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()
    // ZC-946 part 1: the list draws the last finished pass with this screen's own sends shown at once as "sending".
    val displayListState by remember(viewModel) { viewModel.displayChatList() }
        .collectAsStateWithLifecycle(initialValue = viewModel.chatListState.value)
    val currentUserAddress by viewModel.currentUserAddress.collectAsStateWithLifecycle()
    val userStatus by viewModel.userStatus.collectAsStateWithLifecycle()
    // #224 — pending inbound OPEN ("free NOSTR from message #1") contact requests.
    val messageRequests by viewModel.messageRequests.collectAsStateWithLifecycle()
    var showRequestsDialog by remember { mutableStateOf(false) }
    // ZC-1464: the declined requests (newest first), re-read whenever the pending list changes (Reject and Restore both
    // update it); shown in the requests sheet with Restore, and not at all while it is empty.
    val declinedRequests = remember(messageRequests) { viewModel.declinedMessageRequests() }
    // B8 — a notification/in-app-banner tap arms this; open the Requests sheet once the list has seeded
    // (it loads async from EncryptedSharedPreferences, so the arm must survive an initially-empty list).
    val openRequestsArmed by co.electriccoin.zcash.ui.nostr.NostrChatBridge.openRequestsSheetArmed
        .collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openRequestsArmed, messageRequests) {
        if (openRequestsArmed && messageRequests.isNotEmpty()) {
            showRequestsDialog = true
            co.electriccoin.zcash.ui.nostr.NostrChatBridge
                .clearOpenRequestsSheetArm()
        }
    }
    // C1 (UX audit) — seed-backup reminder, surfaced on the Chats home (see ChatViewModel).
    val showBackupReminder by viewModel.walletBackupAvailable.collectAsStateWithLifecycle()
    // Non-null when an Accept was REFUSED (key-change / pubkey-reuse / self-spoof) — shown as a dialog so
    // the user understands why no chat opened, instead of being stranded on a dead-end screen.
    // ES-A: the refusal's text resource, shown in the language the app shows.
    var requestActionError by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    // R2-2 (b): a paid tap on this screen (status broadcast, leaving a group) asks first.
    val listSendState by viewModel.sendMessageState.collectAsStateWithLifecycle()
    (listSendState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsPaidActionConfirmation)?.let { ask ->
        co.electriccoin.zcash.ui.screen.chat.view.PaidActionConfirmDialog(
            confirmation = ask.confirmation,
            onConfirm = { viewModel.confirmPaidAction() },
            onDismiss = { viewModel.dismissPaidAction() },
        )
    }
    // R6-4: this screen's ChatViewModel reads the history, so it is the one that joins a group from an invite
    // and holds the paid "I joined" reply until the user agrees to pay for automatic transactions. The question
    // it raises must be shown here, or the reply waits for an answer nobody sees and the creator keeps us Invited.
    (listSendState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsHandshakeSpendConsent)
        ?.takeIf { viewModel.isHandshakeSpendQuestionOpen() }
        ?.let { ask ->
            HandshakeSpendConsentDialog(
                ask = ask,
                onAllow = { viewModel.grantHandshakeSpendConsent() },
                onNotNow = { viewModel.dismissOrchardShieldingWarning() },
            )
        }
    // Round 8 D4: this screen's ChatViewModel raises the automatic setups, so it shows the Keystone explanation too.
    KeystoneAutomaticSetupNotice(viewModel, listSendState)
    // Round 8 D7: the status is set from this screen, so it says when the broadcast did not reach every contact.
    StatusBroadcastPartialNotice(viewModel, listSendState)
    GroupPaidActionDialog(groupViewModel)

    // Get DestroyManager from DI
    val destroyManager = koinInject<DestroyManager>()

    // ZC-1014: asked once, when the chat list first opens after a start that set a Destroy record aside.
    var destroyRerunOffered by remember { mutableStateOf(DestroyRecordSeal.takeRerunOffer()) }

    // Coroutine scope for the user-initiated "Destroy All" button (below). The REMOTE-kill wipe is no
    // longer driven from here: it's triggered inside ChatViewModel.checkForRemoteKill from viewModelScope,
    // so it fires even when this list screen isn't composed (this scope would be cancelled then). (#2)
    val destroyScope = rememberCoroutineScope()

    // #15/#6: acceptMessageRequest now suspends (its EncryptedSharedPreferences AES-GCM scan runs off the
    // main thread via withContext(IO)), so the Accept tap must launch it. withContext(IO) returns to this
    // scope's Main dispatcher, so the navigation / requestActionError UI writes below still run on Main.
    val scope = rememberCoroutineScope()

    // Dialog state for add/edit contact
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showEditContactDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showLeaveGroupConfirmDialog by remember { mutableStateOf(false) }
    var selectedGroupId by remember { mutableStateOf("") }
    var selectedAddress by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    // Force recomposition when contacts change
    var contactsVersion by remember { mutableStateOf(0) }

    // Bridge 4: the entry for the old ZChat Team address (whose keys the team does not hold) is renamed so
    // nobody writes to it expecting an answer. Contact book only: nothing is sent. R4-6 (15): a one-off,
    // recorded in preferences, so a label the user removes afterwards is not put back at the next start.
    val teamRenameOnce =
        androidx.compose.runtime.remember(zchatPreferences) {
            object : TeamContactMigration.RenameOnce {
                override fun done(): Boolean = zchatPreferences.hasRenamedOldTeamContact()

                override fun markDone() = zchatPreferences.setRenamedOldTeamContact()
            }
        }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val renamed =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TeamContactMigration.runAtStart(contactBook, teamRenameOnce)
            }
        if (renamed) contactsVersion++
    }

    // One-shot three-mode onboarding. Flag persists so the dialog only ever shows once
    // per install + seed; resets if the user wipes app data.
    var showModeIntro by remember { mutableStateOf(!zchatPreferences.hasSeenModeIntro()) }

    // Bridge 2: the catch-up bar after an upgrade from 2.12.4 or older.
    val postUpgradeNotice = org.koin.compose.koinInject<co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeNotice>()
    val upgradeCatchUpPending by postUpgradeNotice.catchUpPending.collectAsStateWithLifecycle()
    val updateRequired by NetworkUpgradeGuard.state.collectAsStateWithLifecycle()

    // ZC-1104 (W-79): the Groups row starts collapsed; the user's choice is kept in the main ZCHAT preferences.
    val groupsFolderStore =
        remember(zchatPreferences) {
            zchatPreferences.chatListUiPrefs?.let {
                co.electriccoin.zcash.ui.screen.chat.datasource
                    .ChatListGroupsFolderStore(it)
            }
        }
    var groupsExpanded by remember(groupsFolderStore) { mutableStateOf(groupsFolderStore?.isExpanded() ?: false) }
    val groupUnread by viewModel.groupUnread.collectAsStateWithLifecycle()
    val groupLastMessageMs by viewModel.groupLastMessageMs.collectAsStateWithLifecycle()

    ChatListView(
        groupUnread = groupUnread,
        groupLastMessageMs = groupLastMessageMs,
        groupsExpanded = groupsExpanded,
        onToggleGroupsFolder = {
            groupsExpanded = !groupsExpanded
            groupsFolderStore?.setExpanded(groupsExpanded)
        },
        upgradeCatchUpPending = upgradeCatchUpPending,
        contactNamesUnreadable = contactNamesUnreadable,
        pictureIndexUnreadable = pictureIndexUnreadable,
        updateRequired = updateRequired != null,
        state = displayListState,
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
                Toast.makeText(context, context.getString(R.string.chat_list_address_copied), Toast.LENGTH_SHORT).show()
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
            selectedGroupId = groupId
            showLeaveGroupConfirmDialog = true
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
        // ZC-1042-X1: fail closed, as Emergency Destroy: a PIN store that cannot be read counts as a PIN that is set.
        hasDestroyPin = runCatching { zchatPreferences.hasDestroyPin() }.getOrDefault(true),
        // DESTROY-ADV-C: the flow reads the saved choice again when it opens (off the main thread).
        onDestroyOpened = { withContext(Dispatchers.IO) { destroyManager.readDestroyFlowChoice() } },
        destroyRerunOffered = destroyRerunOffered,
        onDestroyRerunAnswered = { destroyRerunOffered = false },
        onVerifyDestroyPin = { pin ->
            zchatPreferences.verifyDestroyPinWithLockout(pin)
        },
        onInviteFriendClick = {
            navigationRouter.forward(InviteFriend)
        },
        messageRequestCount = messageRequests.size,
        onRequestsClick = { if (messageRequests.isNotEmpty()) showRequestsDialog = true },
        // QA2 B-3 (ZC-1512): Restore is reachable with no pending request; the menu entry shows only when one is declined.
        declinedRequestCount = declinedRequests.size,
        onDeclinedRequestsClick = { showRequestsDialog = true },
        showBackupReminder = showBackupReminder,
        onBackupReminderClick = {
            navigationRouter.forward(co.electriccoin.zcash.ui.screen.home.backup.SeedBackupInfo)
        },
        // Propagate the newly-set self photo to established contacts over FREE NOSTR (ZPROF). Free-path
        // only, to peers whose NOSTR pubkey we already hold — never on-chain, never a public feed.
        // Direct immediate trigger (belt-and-suspenders alongside the version-flow observer in the VM):
        // fire the broadcast the instant the picker returns, for the common case where the app survives.
        onSelfAvatarChanged = { viewModel.broadcastSelfAvatarToContacts() },
        onSelfAvatarRemoved = { viewModel.broadcastSelfAvatarRemoveToContacts() }
    )

    // Auto-close the sheet once every request has been handled (done in an effect, NOT inline, so we
    // never write Compose state during composition).
    // ZC-1464: the sheet stays while a declined request can still be restored from it.
    androidx.compose.runtime.LaunchedEffect(messageRequests.isEmpty(), declinedRequests.isEmpty()) {
        if (messageRequests.isEmpty() && declinedRequests.isEmpty()) showRequestsDialog = false
    }

    // #224 — Message Requests sheet: accept (start a free OPEN chat) or reject+block each request.
    if (showRequestsDialog && (messageRequests.isNotEmpty() || declinedRequests.isNotEmpty())) {
        MessageRequestsDialog(
            requests = messageRequests,
            declinedRequests = declinedRequests,
            onRestore = { req -> viewModel.restoreDeclinedRequest(req) },
            onAccept = { req ->
                scope.launch {
                    when (viewModel.acceptMessageRequest(req)) {
                        ChatViewModel.AcceptRequestResult.ACCEPTED -> {
                            showRequestsDialog = false
                            navigationRouter.forward(ChatDetail(req.senderAddress))
                        }
                        ChatViewModel.AcceptRequestResult.CONFLICT_KEY_CHANGED ->
                            requestActionError = R.string.message_requests_error_key_changed
                        ChatViewModel.AcceptRequestResult.CONFLICT_PUBKEY_REUSED ->
                            requestActionError = R.string.message_requests_error_pubkey_reused
                        ChatViewModel.AcceptRequestResult.REJECTED_SELF ->
                            requestActionError = R.string.message_requests_error_self
                    }
                }
            },
            onReject = { req ->
                viewModel.rejectMessageRequest(req)
            },
            onDismiss = {
                showRequestsDialog = false
                co.electriccoin.zcash.ui.nostr.NostrChatBridge
                    .clearOpenRequestsSheetArm()
            }
        )
    }

    // #224 — explains a refused Accept (key-change / pubkey-reuse / self-spoof) instead of silently
    // stranding the user. The requests sheet stays open behind it.
    requestActionError?.let { msg ->
        AlertDialog(
            onDismissRequest = { requestActionError = null },
            title = { Text(stringResource(R.string.message_requests_error_title)) },
            text = { Text(stringResource(msg)) },
            confirmButton = {
                TextButton(onClick = { requestActionError = null }) { Text(stringResource(R.string.chat_dialog_ok)) }
            }
        )
    }

    // Delete Chat Confirmation Dialog (T1b-3: in the phone's language)
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.chat_list_delete_chat_title)) },
            text = { Text(stringResource(R.string.chat_list_delete_chat_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.hideChat(selectedAddress)
                        showDeleteConfirmDialog = false
                        Toast.makeText(context, context.getString(R.string.chat_list_chat_hidden), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.chat_list_delete_chat_confirm), color = NightwireColors.ColorDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.chat_list_delete_chat_cancel))
                }
            }
        )
    }

    // Leave Group Confirmation Dialog
    if (showLeaveGroupConfirmDialog) {
        // ES-A: the group screen's own texts, with the group's name (or its short id when it has none).
        val leavingGroupName =
            (chatListState as? co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Success)
                ?.groups
                ?.firstOrNull { it.groupId == selectedGroupId }
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: selectedGroupId.take(8)
        AlertDialog(
            onDismissRequest = { showLeaveGroupConfirmDialog = false },
            title = { Text(stringResource(R.string.group_leave_confirm_title)) },
            text = {
                Text(stringResource(R.string.group_leave_confirm_body, leavingGroupName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // ES-L: "Left group" only once the leave is done. leaveGroup first asks the paid GROUP_LEAVE
                        // question and calls this lambda only after the leave succeeded; a declined question or a
                        // failed leave shows nothing (as on the group screen).
                        val leftText = context.getString(R.string.chat_list_leave_group_left)
                        groupViewModel.leaveGroup(selectedGroupId) {
                            viewModel.refresh()
                            Toast.makeText(context.applicationContext, leftText, Toast.LENGTH_SHORT).show()
                        }
                        showLeaveGroupConfirmDialog = false
                    }
                ) {
                    Text(stringResource(R.string.group_leave_confirm_button), color = NightwireColors.ColorDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveGroupConfirmDialog = false }) {
                    Text(stringResource(R.string.chat_list_dialog_cancel))
                }
            }
        )
    }

    // Add Contact Dialog
    if (showAddContactDialog) {
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text(stringResource(R.string.chat_list_add_contact_title)) },
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
                        label = { Text(stringResource(R.string.chat_list_contact_name_label)) },
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
                            // Save to ZCHAT local contact book. ZC-733-B: a book that cannot be read refuses; then
                            // the dialog stays open, says so, and the wallet's Contacts list is not written either.
                            val saved =
                                contactBook.tryAddContact(
                                    Contact(
                                        address = selectedAddress,
                                        name = trimmedName,
                                        addedAt = Instant.now()
                                    )
                                )
                            if (saved) {
                                // Also save to main Address Book for consistency
                                addressBookRepository.saveContact(
                                    name = trimmedName,
                                    address = selectedAddress,
                                    chain = "zcash"
                                )
                                contactsVersion++ // Trigger recomposition
                                showAddContactDialog = false
                                Toast.makeText(context, context.getString(R.string.chat_list_contact_added), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.chat_list_contact_not_saved), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = contactName.isNotBlank()
                ) {
                    Text(stringResource(R.string.chat_list_add_contact_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text(stringResource(R.string.chat_list_contact_dialog_cancel))
                }
            }
        )
    }

    // Edit Contact Dialog
    if (showEditContactDialog) {
        AlertDialog(
            onDismissRequest = { showEditContactDialog = false },
            title = { Text(stringResource(R.string.chat_list_edit_contact_title)) },
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
                        label = { Text(stringResource(R.string.chat_list_contact_name_label)) },
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
                            // Update ZCHAT local contact book. ZC-733-B: refused while the book cannot be read.
                            if (contactBook.tryUpdateContactName(selectedAddress, trimmedName)) {
                                // Also update main Address Book
                                addressBookRepository.saveContact(
                                    name = trimmedName,
                                    address = selectedAddress,
                                    chain = "zcash"
                                )
                                contactsVersion++ // Trigger recomposition
                                showEditContactDialog = false
                                Toast.makeText(context, context.getString(R.string.chat_list_contact_updated), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.chat_list_contact_not_saved), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = contactName.isNotBlank()
                ) {
                    Text(stringResource(R.string.chat_list_edit_contact_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Remove from ZCHAT local contact book. ZC-733-B: refused while the book cannot be read.
                        if (contactBook.tryRemoveContact(selectedAddress)) {
                            // Note: We don't remove from main Address Book to preserve user's contacts
                            // The user can manually remove from Address Book if needed
                            contactsVersion++ // Trigger recomposition
                            showEditContactDialog = false
                            Toast.makeText(context, context.getString(R.string.chat_list_contact_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.chat_list_contact_not_saved), Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.chat_list_edit_contact_remove), color = NightwireColors.ColorDanger)
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
    onDismiss: () -> Unit,
    // ZC-1464: the declined requests, each with Restore (back to the pending list); no section while it is empty.
    declinedRequests: List<co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.MessageRequest> = emptyList(),
    onRestore: (co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.MessageRequest) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_requests_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.message_requests_body),
                    fontSize = 12.sp,
                    color = NightwireColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                val noText = stringResource(R.string.message_requests_no_text)
                requests.forEach { req ->
                    val shortAddr = requestSenderLabel(req)
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = shortAddr,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NightwireColors.TextPrimary
                        )
                        Text(
                            text = req.firstMessage.take(140).ifBlank { noText },
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { onReject(req) }) {
                                Text(
                                    stringResource(R.string.message_requests_reject),
                                    color = NightwireColors.ColorDanger
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { onAccept(req) }) {
                                Text(
                                    stringResource(R.string.message_requests_accept),
                                    color = NightwireColors.AccentPrimary
                                )
                            }
                        }
                    }
                }
                if (declinedRequests.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.message_requests_declined_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = NightwireColors.TextPrimary
                    )
                    // QA2 Q2-25: a declined request is not lost; Restore brings it back to the requests above.
                    Text(
                        text = stringResource(R.string.message_requests_declined_hint),
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary
                    )
                    declinedRequests.forEach { req ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = requestSenderLabel(req),
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = NightwireColors.TextSecondary
                            )
                            TextButton(onClick = { onRestore(req) }) {
                                Text(
                                    stringResource(R.string.message_requests_restore),
                                    color = NightwireColors.AccentPrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.message_requests_close)) }
        }
    )
}

/** A request's sender as the sheet shows it: the CLAIMED address, shortened (#224; ZC-1464 declined rows too). */
private fun requestSenderLabel(req: co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.MessageRequest): String =
    if (req.senderAddress.length > 24) {
        "${req.senderAddress.take(14)}…${req.senderAddress.takeLast(8)}"
    } else {
        req.senderAddress
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
    val priceQuestion by viewModel.paidActionConfirmation.collectAsStateWithLifecycle()

    ZchatComposeView(state = state)

    // ZC-601: the exact price of the first message, asked before anything is built, stored or paid. Cancel, Back and a
    // tap outside all dismiss it and leave the form as it was.
    priceQuestion?.let {
        co.electriccoin.zcash.ui.screen.chat.view.PaidActionConfirmDialog(
            confirmation = it,
            onConfirm = { viewModel.confirmPaidAction() },
            onDismiss = { viewModel.dismissPaidAction() },
        )
    }

    // Message Cost Disclaimer Dialog (one-time)
    if (showCostDisclaimer) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCostDisclaimer() },
            // R2-7 (c): the figure is the FINAL_PLAN 2.6 floor (it used to quote only the 0.00001 ZEC per chunk).
            title = { Text(stringResource(R.string.chat_message_cost_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.chat_message_cost_intro),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_message_cost_body),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_message_cost_once),
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
    val mgr =
        co.electriccoin.zcash.ui.call.CallController.current.value
            ?: withTimeoutOrNull(5_000L) {
                co.electriccoin.zcash.ui.call.CallController.current
                    .first { it != null }
            }
    when {
        mgr == null ->
            Toast.makeText(context, "Call subsystem isn't ready yet — please try again in a moment.", Toast.LENGTH_SHORT).show()
        peerPub == null -> {
            // Don't dead-end the user. Publish our NOSTR key (shielded ZBOOT) so the peer learns
            // it; once they're also on Tunnel/Open, calls connect with no manual npub paste.
            onNeedKeyExchange()
            Toast
                .makeText(
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
    // ZC-946 part 1: the chat draws the last finished pass with its own sends shown at once as "sending"; the
    // effects below (downloads, the read marker) keep following the finished pass only.
    val displayListState by remember(viewModel) { viewModel.displayChatList() }
        .collectAsStateWithLifecycle(initialValue = viewModel.chatListState.value)
    val currentUserAddress by viewModel.currentUserAddress.collectAsStateWithLifecycle()
    val sendMessageState by viewModel.sendMessageState.collectAsStateWithLifecycle()
    // R3-2 (2): the typed text of a first message whose cost prompt was cancelled, handed back to the input.
    val restoredTypedText by viewModel.restoredTypedText.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val fileDownloadProgress by viewModel.fileDownloadProgress.collectAsStateWithLifecycle()
    val fileDownloadFailures by viewModel.fileDownloadFailures.collectAsStateWithLifecycle()
    val showCostDisclaimer by viewModel.showCostDisclaimer.collectAsStateWithLifecycle()
    val currentBlockHeight by viewModel.currentBlockHeight.collectAsStateWithLifecycle()
    // QA2 Q2-40: the selected account, so the Tunnel offer can tell ZEC that is still confirming from not enough ZEC.
    val accountDataSource = koinInject<co.electriccoin.zcash.ui.common.datasource.AccountDataSource>()
    val selectedWalletAccount by accountDataSource.selectedAccount.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Quantum Shield QR dialog state
    var showQuantumShieldDialog by remember { mutableStateOf(false) }
    var quantumShieldQrPayload by remember { mutableStateOf("") }

    // D3-3: the other phone's signed claim about this chat's shield, in its own holder (never in the scan confirmation).
    // Recomputed when the chat opens or its shield changes here, on every stored claim and on the refresh tick.
    val quantumShieldAlerts by viewModel.quantumShieldAlerts.collectAsStateWithLifecycle()
    val quantumShieldAlert = quantumShieldAlerts[peerAddress] ?: QsAlert.NONE
    // ZC-1476: the status is observed as state: reading the status map subscribes this screen, so the ⋮ entry follows
    // enable, scan, cancel and turn off at once. The value shown is still the stored one, read on each recomposition, so
    // a change the map has not seen yet (a session reset) is never hidden behind an older entry.
    val quantumShieldStatuses by viewModel.quantumShieldStatuses.collectAsStateWithLifecycle()
    val quantumShieldStatusName = quantumShieldStatuses.let { viewModel.getQuantumShieldStatus(peerAddress).name }
    androidx.compose.runtime.LaunchedEffect(peerAddress, quantumShieldStatusName) {
        viewModel.refreshQuantumShieldAlert(peerAddress)
    }
    // D3-3: a dialog's "Turn off here" asks first, with the same confirm as the banner's long press.
    var showQuantumShieldTurnOffConfirm by remember { mutableStateOf(false) }
    val turnOffQuantumShield: () -> Unit = {
        viewModel.resetQuantumShield(peerAddress)
        android.widget.Toast
            .makeText(context, context.getString(R.string.quantum_shield_turned_off_toast), android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // Conversation-mode picker state
    val zchatPreferences = koinInject<co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences>()
    var currentMode by remember(peerAddress) {
        mutableStateOf(zchatPreferences.getConversationMode(peerAddress))
    }
    var showModePicker by remember { mutableStateOf(false) }
    // Bridge 3: Hide on the Shielded-chat reminder is kept per chat (the chip then shows); "Chat free instead"
    // shows the no-key text when no chat key is stored for this contact.
    var shieldedReminderHidden by remember(peerAddress) {
        mutableStateOf(zchatPreferences.isShieldedReminderHidden(peerAddress))
    }
    var showNoKeyText by remember { mutableStateOf(false) }
    // Keep the header's mode word live: the mode can change UNDER an open chat (receiver-side silent
    // Tunnel adoption on an inbound ZBOOT, a peer's ZMODE auto-adopt) — setConversationMode bumps the
    // shared handshake tick, so re-read on every bump instead of showing "shielded" until reopen.
    val handshakeTick by zchatPreferences.e2eHandshakeTicks.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(peerAddress, handshakeTick) {
        currentMode = zchatPreferences.getConversationMode(peerAddress)
    }

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
            // HS-6: opening a chat may ask once more about setups the user answered "Not now" to.
            viewModel.askAgainForHandshakeConsent()
            viewModel.retryKexAckIfResponder(peerAddress)
            // R9-2: in every mode (a Shielded chat skips the bootstrap below), the KEX the user confirmed with a first
            // message and that has not gone yet (the chat was left, the app was closed) is sent now, with no question.
            viewModel.retryOwedKeyExchange(peerAddress)
            // ZC-420: in every mode, our relay identity that only a public relay took is confirmed or delivered again
            // over the relays (free); a contact with no such mark costs nothing here.
            viewModel.repairUnconfirmedRelayIdentity(peerAddress)
            if (zchatPreferences.getConversationMode(peerAddress) !=
                co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT
            ) {
                viewModel.ensureNostrBootstrapSent(peerAddress)
            }
        }
    }
    // QA2 B-5 (Q2-67, ZC-1653): every picture waits here for the preview (ImageSendPreviewDialog below); only the
    // preview's Send hands it to handlePickedImage, one after another (imageSendMutex).
    val pendingImageSendsState =
        remember(peerAddress) {
            mutableStateOf<List<PendingImageSend>>(emptyList())
        }
    var pendingImageSends by pendingImageSendsState
    val imageSendMutex = remember { kotlinx.coroutines.sync.Mutex() }
    // U7 review follow-up (Normal 10): leaving the chat drops the pictures still waiting for their preview; the
    // share-inbox copies of shared ones (consumed from PendingShareStore) are deleted with them, never orphaned.
    androidx.compose.runtime.DisposableEffect(pendingImageSendsState) {
        onDispose {
            pendingImageSendsState.value.forEach { pending ->
                pending.shareInboxFile?.let { file -> runCatching { file.delete() } }
            }
        }
    }
    // Share-sheet / "Send via ZCHAT" image delivery. The SharePicker armed one or more images for THIS
    // peer in PendingShareStore; consume them once (guarded so re-composition doesn't re-send) and queue
    // each for the preview; its Send hands them to the existing handlePickedImage path SEQUENTIALLY.
    androidx.compose.runtime.LaunchedEffect(peerAddress) {
        val files =
            co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore
                .consumeArmedFor(peerAddress)
        // SR-C2: with no end-to-end key the notice shows once for the whole share and nothing is prepared or sent; the
        // shared pictures stay in the app they were shared from (these are copies in the share inbox).
        if (!files.isNullOrEmpty() && viewModel.refuseAttachmentIfBlocked(peerAddress)) {
            files.forEach { runCatching { it.delete() } }
        } else if (!files.isNullOrEmpty()) {
            // B-5: each shared picture is previewed (one at a time) before anything is read or sent.
            pendingImageSends =
                pendingImageSends +
                files.map { file ->
                    PendingImageSend(
                        uri = android.net.Uri.fromFile(file),
                        viewOnce = false,
                        shareInboxFile = file,
                    )
                }
            // Best-effort cleanup of the copied share-inbox file happens when the preview hands it off or is cancelled.
        }
    }

    // #178 Part A: one-time security note shown after switching a chat to OPEN/TUNNEL.
    var modeSecurityNote by remember { mutableStateOf<co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?>(null) }
    // Peer asked to move this chat to a paid mode (Shielded); we surface an accept/keep prompt. #37:
    // collected from the PERSISTED process-wide prefs flow (not a per-VM StateFlow) so the prompt shows
    // no matter which ChatViewModel instance processed the inbound control — or whether the app was
    // even open when it arrived.
    val pendingModeSwitches by viewModel.pendingModeSwitches.collectAsStateWithLifecycle()
    // #178 Part B: weekly key-rotation reminder banner + confirm/restart dialogs.
    var showRotationReminder by remember { mutableStateOf(false) }
    var showRotationConfirm by remember { mutableStateOf(false) }
    var showRotationRestartNote by remember { mutableStateOf(false) }

    // Flips true right before launching the image picker for view-once mode; consumed
    // (read+reset) in the picker callback.
    var pendingViewOnceImagePick by remember { mutableStateOf(false) }

    // Image picker for file sharing (Phase 2)
    val imagePickerLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract.ActivityResultContracts
                    .GetContent()
        ) { uri ->
            val viewOnce = pendingViewOnceImagePick
            pendingViewOnceImagePick = false
            if (uri != null) {
                // B-5: the pick waits for the preview's Send.
                pendingImageSends =
                    pendingImageSends + PendingImageSend(uri, viewOnce)
            }
        }

    // Camera capture URI is generated in the onTakePhoto handler below, but the result
    // callback needs it. Hoist into a remembered holder so result + launch see the same URI.
    val pendingCameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract.ActivityResultContracts
                    .TakePicture()
        ) { success: Boolean ->
            val uri = pendingCameraUri.value
            pendingCameraUri.value = null
            if (success && uri != null) {
                // B-5: the photo waits for the preview's Send.
                pendingImageSends =
                    pendingImageSends + PendingImageSend(uri, viewOnce = false)
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
                if (viewOnce) {
                    viewModel.handleRecordedAudioViewOnce(peerAddress, file, durMs, context)
                } else {
                    viewModel.handleRecordedAudio(peerAddress, file, durMs, context)
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            audioRecorder?.cancel()
            audioRecorder = null
        }
    }

    // QA2 B-5 (Q2-67, ZC-1653): the preview of the next waiting picture. Cancel sends nothing. Send hands it to the view
    // model, where the upload slot, the key-exchange gate and a Shielded chat's "Pay for this?" question come next.
    pendingImageSends.firstOrNull()?.let { pending ->
        co.electriccoin.zcash.ui.screen.chat.view.ImageSendPreviewDialog(
            pending = pending,
            onCancel = {
                pendingImageSends = pendingImageSends.drop(1)
                pending.shareInboxFile?.let { file -> runCatching { file.delete() } }
            },
            onSend = {
                pendingImageSends = pendingImageSends.drop(1)
                scope.launch {
                    imageSendMutex.withLock {
                        if (pending.viewOnce) {
                            viewModel.handlePickedImageViewOnce(peerAddress, pending.uri, context)
                        } else {
                            viewModel.handlePickedImage(peerAddress, pending.uri, context)
                        }
                        // Serialise: handlePickedImage single-flights its upload slot (tryStart), so a second
                        // image sent before the first finishes would be dropped. The upload runs async in the
                        // VM scope, so first wait (briefly) for progress to leave idle (started), then wait for
                        // it to return to idle (finished/failed) before the next. Both bounded so a
                        // fast-failing image can't wedge the queue.
                        withTimeoutOrNull(3_000L) {
                            viewModel.uploadProgress.first { it != null }
                        }
                        withTimeoutOrNull(120_000L) {
                            viewModel.uploadProgress.first { it == null }
                        }
                        // Best-effort cleanup of a shared picture's share-inbox copy once handed off.
                        pending.shareInboxFile?.let { file -> runCatching { file.delete() } }
                    }
                }
            },
        )
    }

    // Track whether the in-flight permission request should land in view-once mode.
    var pendingMicViewOnce by remember { mutableStateOf(false) }
    val recordPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract.ActivityResultContracts
                    .RequestPermission()
        ) { granted: Boolean ->
            val viewOnce = pendingMicViewOnce
            pendingMicViewOnce = false
            if (granted) {
                try {
                    audioRecorder =
                        co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder
                            .start(context)
                    recordingSeconds = 0
                    recordingViewOnce = viewOnce
                } catch (e: Exception) {
                    Toast
                        .makeText(
                            context,
                            e.toZchatUserMessage(context.getString(R.string.chat_recorder_unavailable)),
                            Toast.LENGTH_LONG
                        ).show()
                }
            } else {
                Toast.makeText(context, context.getString(R.string.chat_mic_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

    // #240: place-call permission gate. VoiceCallManager.placeCall / acceptIncoming already ABORT with
    // PermissionDenied when RECORD_AUDIO isn't granted, but nothing in the call path ever REQUESTS it
    // (only the voice-memo mic button did). So a user who never recorded a memo taps Call and it
    // silently dies with no prompt. Request the permission AT the call tap, then place the call once
    // granted. Mic is the only hard requirement — the manager degrades a video call to audio-only when
    // CAMERA is denied — so we request CAMERA for video too but only block the call on mic.
    var pendingCallVideo by remember(peerAddress) { mutableStateOf<Boolean?>(null) }
    val callPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract.ActivityResultContracts
                    .RequestMultiplePermissions()
        ) { grants ->
            val video = pendingCallVideo
            pendingCallVideo = null
            if (video != null) {
                val micOk =
                    grants[android.Manifest.permission.RECORD_AUDIO] == true ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (micOk) {
                    val mode =
                        if (video) {
                            co.electriccoin.zcash.ui.call.CallMode.VIDEO
                        } else {
                            co.electriccoin.zcash.ui.call.CallMode.AUDIO
                        }
                    scope.launch {
                        startCall(context, zchatPreferences, peerAddress, mode) {
                            viewModel.ensureNostrBootstrapSent(peerAddress, force = true)
                        }
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_mic_permission_needed_for_call), Toast.LENGTH_LONG).show()
                }
            }
        }
    val initiateCall: (Boolean) -> Unit = { video ->
        val micGranted =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cameraGranted =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted && (!video || cameraGranted)) {
            val mode =
                if (video) {
                    co.electriccoin.zcash.ui.call.CallMode.VIDEO
                } else {
                    co.electriccoin.zcash.ui.call.CallMode.AUDIO
                }
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
    val filePickerLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract.ActivityResultContracts
                    .OpenDocument()
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
                // Single source of truth for error copy (folds the old hand-rolled when() into PART C):
                // clean ZCHAT-voiced strings pass through unchanged, while anything that still looks like
                // a raw framework exception is replaced with the fallback — the leaky else is gone.
                val userMessage = rawMessage.toZchatUserMessage("Message couldn't be sent — please try again.")
                Toast.makeText(context, localizedSendNotice(context, userMessage), Toast.LENGTH_LONG).show()
                viewModel.resetSendState()
            }
            is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Success -> {
                val label = (sendMessageState as co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Success).label
                Toast.makeText(context, localizedSendNotice(context, label), Toast.LENGTH_SHORT).show()
                viewModel.resetSendState()
            }
            else -> { /* handled by UI */ }
        }
    }

    fun detailStateOf(chatList: co.electriccoin.zcash.ui.screen.chat.model.ChatListState): ChatDetailState =
        when (val listState = chatList) {
            is co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Loading -> {
                ChatDetailState.Loading
            }
            is co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Error -> {
                ChatDetailState.Error(listState.message)
            }
            is co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Success -> {
                // Round 8 P4 (F6): New Chat hands an existing contact to its chat. A chat the user hid has no visible
                // messages and is not in the list; open it empty (with its draft) instead of "Conversation not found".
                val cached = listState.origin == co.electriccoin.zcash.ui.screen.chat.model.StateOrigin.CACHED
                val conversation =
                    listState.conversations.find { it.peerAddress == peerAddress }
                        ?: if (cached) null else viewModel.emptyConversationFor(peerAddress)
                if (cached) {
                    // ZC-946 part 2: the kept pass, read-only, until this screen's own pass ends.
                    conversation?.let {
                        ChatDetailState.Success(
                            conversation = it,
                            currentUserAddress = listState.currentUserAddress,
                            balance = listState.balance,
                            peerSendableBalance = listState.peerSendableBalance,
                            origin = listState.origin,
                        )
                    } ?: ChatDetailState.Loading
                } else if (conversation != null && currentUserAddress != null) {
                    ChatDetailState.Success(
                        conversation = conversation,
                        currentUserAddress = currentUserAddress!!,
                        balance = listState.balance,
                        peerSendableBalance = listState.peerSendableBalance,
                        zecPriceUsd = listState.zecPriceUsd,
                        privacyStatus = listState.privacyStatus
                    )
                } else {
                    ChatDetailState.Error("Conversation not found")
                }
            }
        }
    val state = detailStateOf(displayListState)
    val liveState = detailStateOf(chatListState)

    // File downloads: trigger background download for any ZFILE messages whose cache is empty
    androidx.compose.runtime.LaunchedEffect(liveState) {
        val s = liveState
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

    // R9-6 (REVIEW_R8 F6): while this chat is on screen, a message for it raises no banner or notification in the
    // foreground (it shows here). Cleared when the screen leaves.
    androidx.compose.runtime.DisposableEffect(peerAddress) {
        OpenChat.opened(peerAddress)
        onDispose { OpenChat.closed(peerAddress) }
    }

    // Mark this conversation read whenever its content is shown (open or new messages arrive while
    // viewing). Clears the unread badge / preview bolding on the chat list. Keyed on the message
    // count so a message that lands while the screen is open also advances the read marker.
    androidx.compose.runtime.LaunchedEffect(
        peerAddress,
        (liveState as? ChatDetailState.Success)?.conversation?.messages?.size
    ) {
        if (liveState is ChatDetailState.Success) {
            viewModel.markConversationRead(peerAddress)
        }
    }

    // #178 Part B's weekly key-rotation reminder is not shown in 2.13.0 (Bridge 4, FINAL_PLAN 2.6): rotation
    // hasn't been tested with contacts on older ZChat versions. Rotate key stays in the chat menu, and its
    // confirm dialog says so.

    // B17 — per-conversation disappearing-messages TTL (process-wide prefs flow).
    val disappearTtls by viewModel.disappearingTtls.collectAsStateWithLifecycle()
    // ZC-250: the contact whose send the key-change gate refused; that chat's key-changed banner shows the notice.
    val keyChangeRefusal by viewModel.keyChangeRefusal.collectAsStateWithLifecycle()
    // F8 (ZC-112): the chats whose setup message never reached the chain ("Key exchange not sent", with Retry). Read
    // from the ledger when the chat opens and after every history pass; nothing is sent again without the Retry's quote.
    val handshakeNotSent by viewModel.handshakeNotSent.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(peerAddress) { viewModel.refreshHandshakeNotice() }

    // ZC-1465: the contact already wrote back over the relays (the Open chat-request hint then hides). A preference read,
    // remembered and re-read when the message count changes, not on every recomposition.
    val heardFromPeer =
        remember(peerAddress, (liveState as? ChatDetailState.Success)?.conversation?.messages?.size) {
            viewModel.wasHeardFromOverRelays(peerAddress)
        }

    val cachedConversation =
        (state as? ChatDetailState.Success)
            ?.takeIf { it.origin == co.electriccoin.zcash.ui.screen.chat.model.StateOrigin.CACHED }
            ?.conversation
    if (cachedConversation != null) {
        co.electriccoin.zcash.ui.screen.chat.view.CachedChatDetailView(
            conversation = cachedConversation,
            onBackClick = { navigationRouter.back() },
        )
        return
    }
    ChatDetailView(
        state = state,
        onBackClick = { navigationRouter.back() },
        isKeyChanged = viewModel.isE2EKeyChanged(peerAddress),
        onDismissKeyChanged = { viewModel.dismissE2EKeyChanged(peerAddress) },
        keyChangeRefused = keyChangeRefusal == peerAddress,
        onAcknowledgeKeyChangeRefusal = { viewModel.acknowledgeKeyChangeRefusal() },
        // ZC-545: the refused key that waits for the user's decision; its dialog accepts exactly the pair it showed.
        refusedKeyOffer = viewModel.refusedKeyOffer(peerAddress),
        onAcceptRefusedKey = { refusedKey, ourKey -> viewModel.acceptRefusedKey(peerAddress, refusedKey, ourKey) },
        onDismissRefusedKey = { refusedKey -> viewModel.dismissRefusedKeyOffer(peerAddress, refusedKey) },
        // ZC-1017: never in an Open chat, whose held key exchange is the Tunnel offer below (an Open chat never pays).
        // ZC-693: the banner reads the view model only; it stays until the answer was sent or may have been.
        awaitingChatAccept =
            viewModel.isInboundKexAwaitingAccept(peerAddress) &&
                currentMode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN,
        chatRequestFromSavedContact = viewModel.isSavedContact(peerAddress),
        chatRequestTransactions = viewModel.chatRequestAcceptTransactions(peerAddress),
        onAcceptChatRequest = { viewModel.acceptInboundKexRequest(peerAddress) },
        // ZC-1363: the Accept waits for the funds; read at each composition, as the toast that said so is gone at once.
        acceptWaitsForFunds = viewModel.acceptWaitingForFunds(peerAddress) != null,
        // ZC-1017: the Tunnel offer of a paid key exchange held in an Open chat.
        openChatTunnelOffer = viewModel.openChatTunnelOffer(peerAddress),
        onAcceptTunnelOffer = { viewModel.acceptOpenChatTunnelOffer(peerAddress) },
        onKeepChatOpen = { viewModel.keepChatOpen(peerAddress) },
        handshakeNotSent = handshakeNotSent[peerAddress],
        onRetryHandshake = { offerStartedAt -> viewModel.retryHandshake(peerAddress, offerStartedAt) },
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
        shieldedReminderHidden = shieldedReminderHidden,
        heardFromPeer = heardFromPeer,
        // ZC-1464: the contact declined our chat request; the composer stays closed with its note until a Restore.
        requestDeclined = viewModel.isRequestDeclinedBy(peerAddress),
        // ZC-1270: read at each composition, as a relay message can bring the tag while the chat is open.
        peerCapIronwood = runCatching { zchatPreferences.getPeerIronwoodCap(peerAddress)?.capIronwood }.getOrNull(),
        onHideShieldedReminder = {
            zchatPreferences.setShieldedReminderHidden(peerAddress, true)
            shieldedReminderHidden = true
        },
        onShowShieldedReminder = {
            zchatPreferences.setShieldedReminderHidden(peerAddress, false)
            shieldedReminderHidden = false
        },
        onAskThemToUpdate = {
            try {
                context.startActivity(askThemToUpdateChooser(context))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast
                    .makeText(context, e.toZchatUserMessage("Sharing is unavailable. Please try again."), Toast.LENGTH_LONG)
                    .show()
            }
        },
        onChatFreeInstead = {
            val hasKey = zchatPreferences.getPeerNostrPubkey(peerAddress) != null
            when (UpgradeReminders.chatFreeInstead(hasKey)) {
                ChatFreeInstead.MODE_PICKER -> showModePicker = true
                ChatFreeInstead.NO_KEY_TEXT -> showNoKeyText = true
            }
        },
        safetyNumber = viewModel.computeSafetyNumber(peerAddress),
        isVerified = viewModel.isE2EVerified(peerAddress),
        keyClaimHeld = viewModel.isKeyClaimHeld(peerAddress),
        keyNotConfirmed = viewModel.isKeyNotConfirmed(peerAddress),
        onMarkVerified = { viewModel.markE2EVerified(peerAddress) },
        quantumShieldStatus = quantumShieldStatusName,
        quantumShieldAlert = quantumShieldAlert.name,
        onResetQuantumShield = turnOffQuantumShield,
        // ZC-1476 / ZC-321: turn off (ON) and cancel the setup (PENDING) both ask first; resetQuantumShield either way.
        onAskTurnOffQuantumShield = { showQuantumShieldTurnOffConfirm = true },
        onInitiateQuantumShield = {
            val qrPayload = viewModel.initiateQuantumShield(peerAddress)
            quantumShieldQrPayload = qrPayload
            showQuantumShieldDialog = true
        },
        // SR-C2: every attach and mic entry asks first whether this chat has an end-to-end key to lock an attachment
        // with; with none, the notice shows instead of the picker, the camera or the recorder.
        onSendImage = {
            if (!viewModel.refuseAttachmentIfBlocked(peerAddress)) imagePickerLauncher.launch("image/*")
        },
        onTakePhoto = {
            if (viewModel.refuseAttachmentIfBlocked(peerAddress)) return@ChatDetailView
            val captureFile =
                java.io.File(
                    context.cacheDir,
                    "camera_${System.currentTimeMillis()}.jpg"
                )
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    captureFile
                )
            pendingCameraUri.value = uri
            try {
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                pendingCameraUri.value = null
                Toast.makeText(context, e.toZchatUserMessage(context.getString(R.string.chat_camera_unavailable)), Toast.LENGTH_LONG).show()
            }
        },
        onSendFile = {
            if (viewModel.refuseAttachmentIfBlocked(peerAddress)) return@ChatDetailView
            try {
                filePickerLauncher.launch(
                    arrayOf("application/pdf", "application/zip", "text/plain", "image/*")
                )
            } catch (e: Exception) {
                val notice = e.toZchatUserMessage(context.getString(R.string.chat_file_picker_unavailable))
                Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            }
        },
        onSendViewOnceImage = {
            if (!viewModel.refuseAttachmentIfBlocked(peerAddress)) {
                pendingViewOnceImagePick = true
                imagePickerLauncher.launch("image/*")
            }
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
            if (viewModel.refuseAttachmentIfBlocked(peerAddress)) return@ChatDetailView
            val granted =
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingMicViewOnce = false
                recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                try {
                    audioRecorder =
                        co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder
                            .start(context)
                    recordingSeconds = 0
                    recordingViewOnce = false
                } catch (e: Exception) {
                    Toast
                        .makeText(
                            context,
                            e.toZchatUserMessage(context.getString(R.string.chat_recorder_unavailable)),
                            Toast.LENGTH_LONG
                        ).show()
                }
            }
        },
        onMicLongPress = {
            if (viewModel.refuseAttachmentIfBlocked(peerAddress)) return@ChatDetailView
            val granted =
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingMicViewOnce = true
                recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                try {
                    audioRecorder =
                        co.electriccoin.zcash.ui.screen.chat.filesharing.AudioRecorder
                            .start(context)
                    recordingSeconds = 0
                    recordingViewOnce = true
                    Toast.makeText(context, context.getString(R.string.chat_view_once_recording), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast
                        .makeText(
                            context,
                            e.toZchatUserMessage(context.getString(R.string.chat_recorder_unavailable)),
                            Toast.LENGTH_LONG
                        ).show()
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
                if (viewOnce) {
                    viewModel.handleRecordedAudioViewOnce(peerAddress, file, durMs, context)
                } else {
                    viewModel.handleRecordedAudio(peerAddress, file, durMs, context)
                }
            } else {
                Toast.makeText(context, context.getString(R.string.chat_recording_too_short), Toast.LENGTH_SHORT).show()
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
                    Toast
                        .makeText(
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
        composerMessageCost = { text, replyToId, replyPreview, amountZatoshi ->
            viewModel.composerMessageCost(peerAddress, text, amountZatoshi, replyToId, replyPreview)
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
        onSendPayment = { amountZec, memo, quote ->
            // Send payment using the ViewModel. D-6: the exact quote the dialog showed; the send pays it or nothing.
            // ZC-250: its answer decides whether the dialog closes (false: refused, the typed amount and memo stay).
            viewModel.sendPayment(peerAddress, amountZec, memo, quote)
        },
        onSendReaction = { messageId, emoji ->
            // Send reaction to a message
            viewModel.sendReaction(peerAddress, messageId, emoji)
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
        // ZC-250: the view model's answer decides whether the Request Payment dialog closes.
        onSendPaymentRequest = { amountZatoshi, reason ->
            viewModel.sendPaymentRequest(peerAddress, amountZatoshi, reason)
        },
        onFulfillPaymentRequest = { amountZatoshi, requestId, quote ->
            viewModel.fulfillPaymentRequest(peerAddress, amountZatoshi, requestId, quote)
        },
        // D-6: the three payment confirms ask the exact price here.
        onQuotePayment = { amountZatoshi, memoText, rawMemo ->
            viewModel.quotePayment(peerAddress, amountZatoshi, memoText, rawMemo)
        },
        onNicknameChange = { address, nickname ->
            viewModel.setNickname(address, nickname)
        },
        currentBlockHeight = currentBlockHeight,
        // QA2 Q2-98: the header says Offline while the wallet's server connection is down.
        walletOffline =
            (chatListState as? co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Success)
                ?.syncStatus
                ?.sdkStatus == cash.z.ecc.android.sdk.Synchronizer.Status.DISCONNECTED,
        // QA2 Q2-40 (MONEY_CHECKS): the Tunnel offer's notice tells confirming funds from not enough funds.
        fundsStillConfirming = { need ->
            selectedWalletAccount?.let {
                ShortfallReason.classify(it, need) is ShortfallReason.StillConfirming
            } == true
        },
        onDraftChange = { draft ->
            viewModel.saveDraft(peerAddress, draft)
        },
        restoredTypedText = restoredTypedText?.takeIf { it.peerAddress == peerAddress }?.text,
        onRestoredTypedTextShown = { viewModel.consumeRestoredTypedText() },
        onMuteToggle = {
            viewModel.toggleMuteConversation(peerAddress)
        },
        showWelcomeZecSuggestion =
            (state as? ChatDetailState.Success)?.let { s ->
                s.conversation.contactName != null && s.conversation.messages.none { it.isOutgoing }
            } ?: false,
        onSendWelcomeZec = { quote ->
            viewModel.sendPayment(peerAddress, WelcomeZec.AMOUNT_ZEC, WelcomeZec.MEMO, quote)
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
                    Text(stringResource(R.string.quantum_shield_setup_title), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.quantum_shield_setup_intro),
                        fontSize = 12.sp,
                        color = cc.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.quantum_shield_setup_show),
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // F11 (qs-downgrade REVIEW break 2): the code can be ground by someone who reads both QR codes,
                    // so it checks only codes scanned in person.
                    Text(
                        stringResource(R.string.quantum_shield_setup_in_person),
                        fontSize = 12.sp,
                        color = cc.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    co.electriccoin.zcash.ui.design.component.ZashiQr(
                        state =
                            co.electriccoin.zcash.ui.design.component.QrState(
                                qrData = quantumShieldQrPayload,
                            ),
                        qrSize = 200.dp, // Dp imported via Modifier.padding
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // B1l — honest disclaimer: the QR carries a raw secret, so the exchange is
                    // only as private as the room it happens in.
                    Text(
                        stringResource(R.string.quantum_shield_setup_private),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = cc.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.quantum_shield_setup_auto_on),
                        fontSize = 12.sp,
                        color = cc.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuantumShieldDialog = false }) {
                    Text(stringResource(R.string.quantum_shield_setup_done), color = cc.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showQuantumShieldDialog = false
                    // Set the bridge callback before navigating to scanner
                    co.electriccoin.zcash.ui.screen.chat.filesharing.QuantumShieldScanBridge.setPending(peerAddress) { zcpskPayload ->
                        val success = viewModel.completeQuantumShield(peerAddress, zcpskPayload)
                        if (success) {
                            android.widget.Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.quantum_shield_on_toast),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                        } else if (viewModel.quantumShieldSas.value?.problem == null) {
                            // F11: a readable code that does not match this chat shows its own dialog, not this toast.
                            android.widget.Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.quantum_shield_invalid_code),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                        }
                    }
                    navigationRouter.forward(
                        co.electriccoin.zcash.ui.screen.scan
                            .ScanGenericAddressArgs()
                    )
                }) {
                    Text(stringResource(R.string.quantum_shield_setup_scan), color = cc.primary)
                }
            },
            containerColor = cc.background,
            titleContentColor = cc.textPrimary,
            textContentColor = cc.textPrimary,
        )
    }

    // B1l — Quantum Shield Safety String confirmation. Set by completeQuantumShield() after a
    // successful scan (survives the scanner round-trip via StateFlow); both devices show the same
    // 6-digit code, users compare out-of-band to detect a tampered/swapped QR.
    // R6-5: a setup with a 2.12.4 phone (or one a phone started before its update) has no code the other
    // phone can show; the dialog then says so instead of asking the user to compare a code.
    val quantumShieldSas by viewModel.quantumShieldSas.collectAsStateWithLifecycle()
    quantumShieldSas?.let { confirmation ->
        val cc = chatColors()
        val sas = confirmation.code
        // D3-3 (§2.7 item 8): one dialog per problem. Exhaustive with no else, so a new QsProblem value cannot fall into
        // the "no code" dialog.
        when (confirmation.problem) {
            // F11 (G3, qs-downgrade REVIEW break 7): a rescan that matches neither key of this chat. Neutral: this phone
            // cannot tell another chat's code from a one-side reset, and nothing was stored.
            QsProblem.NOT_THIS_CHATS_CODE ->
                QuantumShieldProblemDialog(
                    title = stringResource(R.string.quantum_shield_not_this_chat_title),
                    body = stringResource(R.string.quantum_shield_not_this_chat_body),
                    onDismiss = { viewModel.clearQuantumShieldSas() },
                )
            // D3-3: a padded (older-format) QR against the other phone's claim that its code is not one. Nothing set up.
            QsProblem.QR_OLD_FORMAT ->
                QuantumShieldProblemDialog(
                    title = stringResource(R.string.quantum_shield_qr_old_format_title),
                    body = stringResource(R.string.quantum_shield_qr_old_format_body),
                    onDismiss = { viewModel.clearQuantumShieldSas() },
                )
            // D3-3: the rescanned QR is the one the other phone's claim commits to: it set Extra Security up again.
            QsProblem.OTHER_PHONE_SET_UP_AGAIN ->
                QuantumShieldProblemDialog(
                    title = stringResource(R.string.quantum_shield_set_up_again_title),
                    body = stringResource(R.string.quantum_shield_set_up_again_body),
                    onDismiss = { viewModel.clearQuantumShieldSas() },
                    turnOffHereLabel = stringResource(R.string.quantum_shield_turn_off_here),
                    onTurnOffHere = {
                        viewModel.clearQuantumShieldSas()
                        showQuantumShieldTurnOffConfirm = true
                    },
                )
            null ->
                if (sas == null) {
                    // R6-5: no code to compare. D3-3: when the other phone's claim shows both phones could have had one
                    // (DOWNGRADED), say that instead, with Turn off here.
                    val downgraded = quantumShieldAlert == QsAlert.DOWNGRADED
                    QuantumShieldProblemDialog(
                        title =
                            stringResource(
                                if (downgraded) R.string.quantum_shield_downgraded_title else R.string.quantum_shield_no_code_title
                            ),
                        body =
                            stringResource(
                                if (downgraded) R.string.quantum_shield_downgraded_body else R.string.quantum_shield_no_code_body
                            ),
                        onDismiss = { viewModel.clearQuantumShieldSas() },
                        turnOffHereLabel = if (downgraded) stringResource(R.string.quantum_shield_turn_off_here) else null,
                        onTurnOffHere = {
                            viewModel.clearQuantumShieldSas()
                            showQuantumShieldTurnOffConfirm = true
                        },
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = { viewModel.clearQuantumShieldSas() },
                        title = { Text(stringResource(R.string.quantum_shield_code_title), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    sas,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 6.sp,
                                    color = cc.primary,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.quantum_shield_code_body),
                                    fontSize = 13.sp,
                                    color = cc.textSecondary,
                                )
                                // ZC-1477: this phone shows its digits because it scanned; the other phone shows the
                                // same digits only after it scans this phone's code, so say so and offer the code.
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.quantum_shield_setup_scan_next),
                                    fontSize = 13.sp,
                                    color = cc.textPrimary,
                                )
                                // D3-3: once the other phone's claim confirms the key (the banner shows it too).
                                if (quantumShieldAlert == QsAlert.CONFIRMED) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.quantum_shield_keys_confirmed),
                                        fontSize = 12.sp,
                                        color = cc.textSecondary,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.clearQuantumShieldSas() }) {
                                Text(stringResource(R.string.quantum_shield_code_matches), color = cc.primary)
                            }
                        },
                        // ZC-1477 / ZC-445: show this phone's code (the stored secret, initiateQuantumShield reuses it).
                        dismissButton = {
                            TextButton(onClick = {
                                viewModel.clearQuantumShieldSas()
                                quantumShieldQrPayload = viewModel.initiateQuantumShield(peerAddress)
                                showQuantumShieldDialog = true
                            }) {
                                Text(stringResource(R.string.quantum_shield_code_show_mine), color = cc.primary)
                            }
                        },
                        containerColor = cc.background,
                        titleContentColor = cc.textPrimary,
                        textContentColor = cc.textPrimary,
                    )
                }
        }
    }

    // D3-3: the turn-off confirm a dialog's "Turn off here" opens (the same texts as the banner's long-press confirm).
    if (showQuantumShieldTurnOffConfirm) {
        val cc = chatColors()
        AlertDialog(
            onDismissRequest = { showQuantumShieldTurnOffConfirm = false },
            title = { Text(stringResource(R.string.quantum_shield_turn_off_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.quantum_shield_turn_off_body), fontSize = 13.sp, color = cc.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showQuantumShieldTurnOffConfirm = false
                    turnOffQuantumShield()
                }) {
                    Text(stringResource(R.string.quantum_shield_turn_off_confirm), color = cc.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuantumShieldTurnOffConfirm = false }) {
                    Text(stringResource(R.string.quantum_shield_turn_off_cancel), color = cc.primary)
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
            // R2-7 (c): the figure is the FINAL_PLAN 2.6 floor (it used to quote only the 0.00001 ZEC per chunk).
            title = { Text(stringResource(R.string.chat_message_cost_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.chat_message_cost_intro),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_message_cost_body),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_message_cost_once),
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

    // Automatic-handshake spend consent: ZChat is about to pay for handshake memos nobody asked for.
    (sendMessageState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsHandshakeSpendConsent)
        ?.takeIf { viewModel.isHandshakeSpendQuestionOpen() }
        ?.let { ask ->
            HandshakeSpendConsentDialog(
                ask = ask,
                onAllow = { viewModel.grantHandshakeSpendConsent() },
                onNotNow = { viewModel.dismissOrchardShieldingWarning() },
            )
        }

    // R2-2 (b): a paid tap (Shielded timer / mode / reaction, re-KEX, first message) asks first.
    (sendMessageState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsPaidActionConfirmation)?.let { ask ->
        co.electriccoin.zcash.ui.screen.chat.view.PaidActionConfirmDialog(
            confirmation = ask.confirmation,
            onConfirm = { viewModel.confirmPaidAction() },
            onDismiss = { viewModel.dismissPaidAction() },
        )
    }

    // SR-C2: a photo, file or voice note was refused before anything was prepared, uploaded or paid: this chat has no
    // end-to-end key. "Switch to Shielded" (Open or Tunnel with no key exchange only) is the mode picker's own switch,
    // which asks first whenever it would pay; the key exchange itself comes with the next message, priced first.
    // ZC-1474: an owed key exchange is sent through the ⋮ menu's Reconnect, and the contact's waiting one is answered
    // through the chat-request banner's Accept, offered (with that banner's price) only where the banner is.
    (sendMessageState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.AttachmentNeedsKeyExchange)?.let { notice ->
        val acceptOffered =
            viewModel.isInboundKexAwaitingAccept(peerAddress) &&
                currentMode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN &&
                viewModel.openChatTunnelOffer(peerAddress) == co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage.NONE
        co.electriccoin.zcash.ui.screen.chat.view.AttachmentKeyExchangeDialog(
            reason = notice.reason,
            onSwitchToShielded = {
                viewModel.resetSendState()
                viewModel.switchConversationMode(peerAddress, co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT)
            },
            onSendKeyExchange = {
                viewModel.resetSendState()
                viewModel.reSendOurIdentity(peerAddress)
            },
            onAcceptKeyExchange = {
                viewModel.resetSendState()
                viewModel.acceptInboundKexRequest(peerAddress)
            },
            onDismiss = { viewModel.resetSendState() },
            acceptTransactions = if (acceptOffered) viewModel.chatRequestAcceptTransactions(peerAddress) else null,
        )
    }

    // SR-A (S-A5): a Shielded photo, file or voice note the wallet cannot pay was refused before anything was uploaded or
    // paid. "Switch to Tunnel" is the mode picker's own switch, which asks first whenever it would pay.
    (sendMessageState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.ShieldedAttachmentNeedsFunds)?.let { notice ->
        co.electriccoin.zcash.ui.screen.chat.view.ShieldedAttachmentNoFundsDialog(
            floor = notice.floor,
            spendable = notice.spendable,
            onSwitchToTunnel = {
                viewModel.resetSendState()
                viewModel.switchConversationMode(notice.peerAddress, co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL)
            },
            onNotNow = { viewModel.resetSendState() },
        )
    }

    // SR-C1 (KI-37): the contact's newest relay message came from ZChat 2.12 or older, which shows a 2.13 attachment's link
    // as a line of code. Asked before anything is uploaded; Cancel (or a dismissal) leaves the row FAILED with its Retry.
    (sendMessageState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.LegacyContactAttachmentWarning)?.let { warning ->
        // The name the chat header shows (the warning's own contact's name if it was raised in another chat).
        val name =
            (state as? ChatDetailState.Success)
                ?.conversation
                ?.takeIf { warning.peerAddress == peerAddress }
                ?.displayName
                ?: zchatPreferences.getDisplayName(warning.peerAddress)
        co.electriccoin.zcash.ui.screen.chat.view.LegacyContactAttachmentDialog(
            name = name,
            onSendAnyway = { viewModel.confirmLegacyContactAttachment() },
            onCancel = { viewModel.cancelLegacyContactAttachment() },
        )
    }

    // Sealed-Orchard dialog: funds are shielded but stranded, and nothing can reach a peer from them.
    if (sendMessageState is co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsIronwoodMigration) {
        val stranded = sendMessageState as co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsIronwoodMigration
        // E3 (sweep spec §1.1): with nothing confirming, offer the one way out, or say why there is none.
        val sweepPrompt = chatSweepPrompt(stranded.orchardBalance, stranded.ironwoodPending)
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
                    Text(
                        when {
                            stranded.ironwoodPending.value > 0 ->
                                stringResource(R.string.chat_stranded_ironwood_confirming_title)
                            sweepPrompt == ChatSweepPrompt.MOVE -> stringResource(R.string.move_old_balance_chat_title)
                            else -> stringResource(R.string.move_old_balance_card_title)
                        }
                    )
                }
            },
            text = {
                Column {
                    Text(text = localizedSendNotice(context, stranded.message), fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text =
                            stringResource(
                                R.string.chat_stranded_orchard_line,
                                zecDisplay(stranded.orchardBalance, rememberDesiredFormatLocale()),
                            ),
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary
                    )
                    if (stranded.ironwoodPending.value > 0) {
                        Text(
                            // Live, not the snapshot captured when the send was refused: the dialog can
                            // stay open across blocks and must count down with the card behind it.
                            text =
                                (
                                    (chatListState as? co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Success)
                                        ?.privacyStatus
                                        ?.confirmationProgress
                                        ?: stranded.confirmationProgress
                                )?.let {
                                    stringResource(
                                        R.string.chat_stranded_ironwood_line_progress,
                                        zecDisplay(stranded.ironwoodPending, rememberDesiredFormatLocale()),
                                        it.label,
                                    )
                                } ?: stringResource(
                                    R.string.chat_stranded_ironwood_line,
                                    zecDisplay(stranded.ironwoodPending, rememberDesiredFormatLocale()),
                                ),
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                    }
                    when (sweepPrompt) {
                        ChatSweepPrompt.MOVE -> R.string.move_old_balance_chat_move_line
                        ChatSweepPrompt.TOO_SMALL -> R.string.move_old_balance_chat_too_small
                        ChatSweepPrompt.NONE -> null
                    }?.let { line ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = stringResource(line), fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_stranded_relays_keep_working),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissOrchardShieldingWarning() }) {
                    Text(stringResource(R.string.chat_dialog_ok), color = chatColors().primary)
                }
            },
            dismissButton =
                if (sweepPrompt == ChatSweepPrompt.MOVE) {
                    {
                        TextButton(
                            onClick = {
                                viewModel.dismissOrchardShieldingWarning()
                                navigationRouter.forward(MoveOldBalanceArgs)
                            }
                        ) {
                            Text(stringResource(R.string.move_old_balance_action), color = chatColors().primary)
                        }
                    }
                } else {
                    null
                },
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
                    Text(stringResource(R.string.chat_shield_dialog_title))
                }
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.chat_shield_needed_intro),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_shield_dialog_funds_in),
                        fontSize = 15.sp
                    )
                    if (shieldingState.saplingBalance.value > 0) {
                        Text(
                            text =
                                stringResource(
                                    R.string.chat_shield_dialog_sapling,
                                    zecDisplay(shieldingState.saplingBalance, rememberDesiredFormatLocale()),
                                ),
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                    }
                    if (shieldingState.transparentBalance.value > 0) {
                        Text(
                            text =
                                stringResource(
                                    R.string.chat_shield_dialog_transparent,
                                    zecDisplay(shieldingState.transparentBalance, rememberDesiredFormatLocale()),
                                ),
                            fontSize = 13.sp,
                            color = NightwireColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_shield_dialog_action),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.dismissOrchardShieldingWarning() }
                ) {
                    Text(stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok))
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
                // T3b-1: the switch is the one call. It asks first when it pays anything on-chain (the mode change, our KEX,
                // our ZBOOT), then changes and syncs the mode (an authenticated ZMODE to the peer; re-picking the current
                // mode pins it and transmits nothing) and, for a relay mode, sends our key and relay identity so calls work
                // without a manual npub paste. The header follows the stored mode (handshakeTick), so a Cancel leaves it
                // showing the mode the chat is still in.
                viewModel.switchConversationMode(peerAddress, picked)
                if (picked != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
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

    // Bridge 3: "Chat free instead" with no chat key stored for this contact: Open can't be picked yet, so say
    // how to get their invite.
    if (showNoKeyText) {
        // The name the chat header shows.
        val name =
            (state as? ChatDetailState.Success)?.conversation?.displayName
                ?: zchatPreferences.getDisplayName(peerAddress)
        AlertDialog(
            onDismissRequest = { showNoKeyText = false },
            confirmButton = { TextButton(onClick = { showNoKeyText = false }) { Text("OK") } },
            title = { Text(stringResource(R.string.upgrade_chat_free_instead)) },
            text = {
                Text(
                    stringResource(R.string.upgrade_no_key_1, name) + " " +
                        stringResource(R.string.upgrade_no_key_2) + " " +
                        stringResource(R.string.upgrade_no_key_3, name)
                )
            },
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

    // Accept/keep prompt for a peer's paid-mode (Shielded) switch — only for THIS open chat.
    pendingModeSwitches[peerAddress]?.let { pending ->
        co.electriccoin.zcash.ui.screen.chat.view.ModeSwitchRequestDialog(
            request = pending,
            onAccept = {
                viewModel.acceptPendingModeSwitch(pending)
                currentMode = pending.newMode
            },
            onKeep = { viewModel.dismissPendingModeSwitch(pending) },
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
                Text(co.electriccoin.zcash.ui.screen.chat.view.ROTATION_CONFIRM_BODY)
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

    GroupPaidActionDialog(viewModel)

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

    // ZC-1104: the group's messages are read while this screen shows them (open, and each message that lands while it
    // is open), which clears them from the chat list's Groups row.
    val shownGroup = (groupDetailState as? co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState.Success)?.conversation
    androidx.compose.runtime.LaunchedEffect(groupId, shownGroup?.groupInfo?.groupId, shownGroup?.messages?.size) {
        if (shownGroup?.groupInfo?.groupId == groupId) viewModel.markGroupRead(groupId)
    }

    GroupPaidActionDialog(viewModel)

    // Round 8 D4 (DECISIONS_NIGHT §1): an invite from an address the user never chatted with waits for Join; Round 8 P1:
    // so does any invite whose reply cannot go over the relays (Join pays it, never the standing consent). Join and
    // Ignore are ChatViewModel's (it owns the group reply), so this screen gets one while the invite waits, and keeps
    // it after Join to show the notice Join raises when the wallet cannot pay the reply yet (Keystone, sealed Orchard,
    // Ironwood still confirming); since P1 Join raises no handshake-spend question.
    // Read again after a confirmed waiting-record write or a group reload. Display only: the reply is never sent
    // without Join clearing the mark.
    val zchatPreferences = koinInject<co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences>()
    // G3 8a (groups SPEC §3.1): Join reloads the group, but that reload can end in a state equal to the one before (no
    // change reaches this screen), so Join also bumps this counter to read the record again at once. joinOutcome keeps
    // what Join returned for the notice under the header; a later false (a second tap finds the mark already cleared)
    // never replaces a true. Both are cleared the next time the screen opens, and a true also when the record is back.
    var joinTick by remember(groupId) { mutableIntStateOf(0) }
    var joinOutcome by remember(groupId) { mutableStateOf<Boolean?>(null) }
    val groupStoreVersions by ZchatPreferences.groupStoreVersion.collectAsStateWithLifecycle()
    val joinRecord =
        remember(groupId, groupDetailState, joinTick, groupStoreVersions[groupId]) {
            runCatching { zchatPreferences.getGroupInviteNeedsJoin(groupId) }.getOrNull()
        }
    // ZC-776: one gate per read of the record (the keys of joinRecord). A second Join callback before this screen has
    // read the store again neither asks Join again nor keeps a failure for a reply this screen already released.
    val joinTapGate = remember(groupId, groupDetailState, joinTick, groupStoreVersions[groupId]) { JoinTapGate() }
    val awaitingJoin = joinRecord != null
    // ZC-517: the returned record clears rendered success before the effect updates the kept outcome.
    val shownJoinOutcome = joinOutcomeAfterRecordRead(joinOutcome, awaitingJoin)
    // G3 fixup (G-G3-2): when the record is back (the reply went back to waiting for Join, or a new invite came), a
    // "joined" result is cleared, so the screen stops saying "You joined" and Join can be tapped again.
    androidx.compose.runtime.LaunchedEffect(joinRecord, joinOutcome) {
        joinOutcome = joinOutcomeAfterRecordRead(joinOutcome, joinRecord != null)
    }
    // Round 8 P1 / ZC-517: after relay failure, only a reply that fits can offer an on-chain price.
    val joinReplyIsPaid =
        co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
            .relayFailed(joinRecord)
    // ZC-517 (residual): a reply too long for one memo is relay-only from its size alone, before any relay try too.
    val joinReplyIsRelayOnly =
        joinRecord != null &&
            !co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
                .canSendOnChain(joinRecord)
    var inviteFlow by remember(groupId) { mutableStateOf(false) }
    // ZC-1224: the creator has an accepted Open chat with us, where Join's reply goes free over the relays: no price.
    val joinCreatorIsOpen =
        shownGroup?.groupInfo?.creatorAddress?.let { creator ->
            runCatching {
                zchatPreferences.getConversationMode(creator) == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN
            }.getOrDefault(false)
        } ?: false
    val joinReplyIsFree = joinRecord != null && !joinReplyIsPaid && !joinReplyIsRelayOnly && joinCreatorIsOpen
    androidx.compose.runtime.LaunchedEffect(awaitingJoin) { if (awaitingJoin) inviteFlow = true }
    val inviteChatViewModel = if (awaitingJoin || inviteFlow) koinViewModel<ChatViewModel>() else null
    inviteChatViewModel?.let { GroupInviteJoinQuestions(it) }
    // ZC-922 (W-137 B-4): every invite waits for Join, so a known contact's needs its own banner text.
    val shownCreator = shownGroup?.groupInfo?.creatorAddress
    val joinInviterIsKnown =
        androidx.compose.runtime.remember(shownCreator, awaitingJoin, joinRecord, inviteChatViewModel) {
            awaitingJoin && shownCreator?.let { inviteChatViewModel?.isKnownGroupInviter(it) } == true
        }
    // ZC-922 review F4: "X added you" only for a creator that is none of our own addresses (any representation).
    // U9 nit: the same decision as everywhere else, a group this phone created is ours whatever its stored creator reads.
    val groupCreatorIsUs =
        androidx.compose.runtime.remember(groupId, shownCreator, groupDetailState) {
            runCatching { zchatPreferences.isGroupSelfCreated(groupId) }.getOrDefault(false) ||
                shownCreator?.let { creator -> runCatching { zchatPreferences.isSelfAddress(creator) }.getOrDefault(false) } == true
        }

    // R5-1 — what one message to this group costs on-chain right now, recomputed whenever the group
    // detail reloads (open, send, member change, key change). The binding surface for the money is the
    // per-group consent prompt the send itself raises; this line is what the user reads before typing.
    val groupMessageCost =
        androidx.compose.runtime.remember(groupDetailState) { viewModel.groupMessageCost(groupId) }
    // ZC-022 (KI-25 R6): the one line about the group's admin, read again with every reload, like the cost line.
    val groupAdminNotice =
        androidx.compose.runtime.remember(groupDetailState) { viewModel.groupAdminNotice(groupId) }
    // Round 8 MA3: the progress line of an "Introduce them" run that pays.
    val introduceProgress by viewModel.introduceProgress.collectAsStateWithLifecycle()
    // KI-22: this screen's change of the group waits for another change of the same group.
    val groupsWaitingForChange by viewModel.groupsWaitingForChange.collectAsStateWithLifecycle()

    GroupDetailView(
        state = groupDetailState,
        isSendingMessage = isSendingMessage,
        sendResult = groupSendEvent,
        messageCost = groupMessageCost,
        awaitingJoin = awaitingJoin,
        joinReplyIsPaid = joinReplyIsPaid,
        joinReplyIsRelayOnly = joinReplyIsRelayOnly,
        joinReplyIsFree = joinReplyIsFree,
        joinInviterIsKnown = joinInviterIsKnown,
        groupCreatorIsUs = groupCreatorIsUs,
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
            // D3/#197 — actually REQUEST the missing key: sends a signed GROUP_KEY_REQUEST to the
            // admin (or, if we ARE the admin, re-pushes any owed keys), then reloads local state.
            // The old handler only reloaded local prefs and could never recover a missing key.
            // ZC-022 (KI-25 R4a): while this phone cannot confirm the group's admin the answer would be refused, so
            // nothing is asked or paid: the tap opens Group settings, where the check is. KI-25 R17: an Open group
            // still asks its admin over the relays only (GroupViewModel.syncKeysTapOpensSettings decides).
            if (viewModel.syncKeysTapOpensSettings(groupId)) {
                navigationRouter.forward(GroupSettings(groupId))
            }
        },
        onJoinInvite = {
            if (joinTapGate.firstTap()) {
                if (joinOutcome != true) {
                    val released = inviteChatViewModel?.joinGroupInvite(groupId, joinRecord) == true
                    joinOutcome = if (released && joinReplyIsRelayOnly) null else released
                }
            }
            joinTick++
            viewModel.loadGroupDetail(groupId)
        },
        joinOutcome = shownJoinOutcome,
        onIgnoreInvite = {
            inviteChatViewModel?.ignoreGroupInvite(groupId)
            viewModel.loadGroups()
            navigationRouter.back()
        },
        // Round 8 MA3 (member announce): the creator's "Introduce them" (a quoted, paid tap) and "Not now".
        onIntroduceMembers = { viewModel.introduceMembers(groupId) },
        onIntroduceNotNow = { viewModel.dismissIntroduceBanner(groupId) },
        introduceProgress = introduceProgress,
        waitingForOtherChange = groupId in groupsWaitingForChange,
        adminNotice = groupAdminNotice,
    )
}

/**
 * Round 8 D4 — what the group screen's ChatViewModel can raise after Join: the automatic-spend question for the paid
 * "I joined" reply (as on the chat list), or the one-time Keystone explanation shown instead of it.
 */
@Composable
private fun GroupInviteJoinQuestions(chatViewModel: ChatViewModel) {
    val sendState by chatViewModel.sendMessageState.collectAsStateWithLifecycle()
    (sendState as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsHandshakeSpendConsent)
        ?.takeIf { chatViewModel.isHandshakeSpendQuestionOpen() }
        ?.let { ask ->
            HandshakeSpendConsentDialog(
                ask = ask,
                onAllow = { chatViewModel.grantHandshakeSpendConsent() },
                onNotNow = { chatViewModel.dismissOrchardShieldingWarning() },
            )
        }
    // Round 8 P1: Join pays its reply itself, so a wallet that cannot pay it yet (sealed Orchard, Ironwood still
    // confirming) says so here; the reply waits for Join again.
    KeystoneAutomaticSetupNotice(
        chatViewModel,
        sendState,
        extraNotices =
            setOf(
                co.electriccoin.zcash.ui.common.model.OrchardSealedException.ORCHARD_SEALED_MESSAGE,
                co.electriccoin.zcash.ui.common.model.OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE,
                co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT,
            ),
    )
}

/**
 * Round 8 D4 (round 7c C5) — the one-time explanation a ChatViewModel shows instead of the automatic-spend question
 * while a Keystone account is selected. The chat detail shows it with its other send errors; the chat list and the
 * group screen show only this one, so no other error they never showed appears there now. Round 8 D10: and the
 * storage notice (ChatSendCopy.STORAGE_PAUSED_PAID_STEP), which their view models can raise after Allow or a KEXACK.
 * Round 8 P1: plus [extraNotices] (the group screen passes the sealed-Orchard and Ironwood-confirming texts a Join can
 * raise).
 */
@Composable
private fun KeystoneAutomaticSetupNotice(
    viewModel: ChatViewModel,
    state: co.electriccoin.zcash.ui.screen.chat.model.SendMessageState,
    extraNotices: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(state) {
        // Round 8 D10 (DECISIONS_NIGHT §6): the notice for a paid chat step the phone's store paused (a consent that was
        // not saved, a KEXACK whose marker was not saved) is raised by these screens' view models too.
        val notices =
            setOf(
                co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP,
                co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.STORAGE_PAUSED_PAID_STEP,
            ) + extraNotices
        val message = (state as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error)?.message
        if (message != null && message in notices) {
            Toast.makeText(context, localizedSendNotice(context, message), Toast.LENGTH_LONG).show()
            viewModel.resetSendState()
        }
    }
}

/**
 * Round 8 D7 (sweep #13) — a status broadcast that did not reach every contact says how many it reached, with both
 * counts formatted by the string resource (ChatSendCopy.STATUS_PARTIAL) in the phone's language.
 * ZC-283: contacts whose memo may have been sent are counted apart (they are not sent the same status again), and
 * "set it again to reach the rest" is said only when some contact was proven not sent. A status set while a broadcast
 * is still running says so here too (ChatSendCopy.STATUS_BROADCAST_BUSY).
 */
@Composable
private fun StatusBroadcastPartialNotice(
    viewModel: ChatViewModel,
    state: co.electriccoin.zcash.ui.screen.chat.model.SendMessageState,
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(state) {
        val partial = state as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.StatusPartial
        val busy =
            (state as? co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error)
                ?.message
                ?.takeIf { it == co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.STATUS_BROADCAST_BUSY }
        val text =
            when {
                busy != null -> localizedSendNotice(context, busy)
                partial == null -> null
                partial.maybe <= 0 ->
                    context.getString(
                        R.string.chat_send_status_partial,
                        partial.reached,
                        partial.total,
                    )
                // Nobody is proven not sent: no "set it again" (it would send nothing).
                partial.reached + partial.maybe >= partial.total ->
                    context.resources.getQuantityString(
                        R.plurals.chat_send_status_partial_maybe,
                        partial.maybe,
                        partial.reached,
                        partial.total,
                        partial.maybe,
                    )
                else ->
                    context.resources.getQuantityString(
                        R.plurals.chat_send_status_partial_maybe_and_rest,
                        partial.maybe,
                        partial.reached,
                        partial.total,
                        partial.maybe,
                    )
            }
        if (text != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            viewModel.resetSendState()
        }
    }
}

@Composable
fun AndroidGroupSettings(groupId: String) {
    val context = LocalContext.current
    val viewModel = koinViewModel<GroupViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val groupSettingsState by viewModel.groupSettingsState.collectAsStateWithLifecycle()
    val addMemberCandidates by viewModel.addMemberCandidates.collectAsStateWithLifecycle()
    // ZC-733-B: the add-member picker says when the saved names cannot be read.
    val contactNamesUnreadable by viewModel.contactNamesUnreadable.collectAsStateWithLifecycle()
    // KI-22: this screen's change of the group waits for another change of the same group.
    val groupsWaitingForChange by viewModel.groupsWaitingForChange.collectAsStateWithLifecycle()

    // Load group settings when screen opens
    androidx.compose.runtime.LaunchedEffect(groupId) {
        viewModel.loadGroupSettings(groupId)
    }

    GroupPaidActionDialog(viewModel)
    GroupRekeyUnkeyableDialog(viewModel)

    // ZC-022 (KI-25 R6, section 3.3): while this phone cannot confirm the group's admin, the card says so and, when the
    // phone's own records name one chat, offers to confirm it. The state is read again with every reload of the screen
    // and after a confirmation (adminTick); reading it writes nothing.
    var adminTick by remember(groupId) { mutableIntStateOf(0) }
    val adminKeyState = remember(groupId, groupSettingsState, adminTick) { viewModel.groupAdminKeyState(groupId) }
    val adminNeedsCheck =
        co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
            .needsConfirmation(adminKeyState)
    val adminCandidate by viewModel.groupAdminCandidate.collectAsStateWithLifecycle()
    val adminCandidateChecked by viewModel.groupAdminCandidateChecked.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(groupId, adminNeedsCheck, adminTick) {
        if (adminNeedsCheck) viewModel.loadGroupAdminCandidate(groupId)
    }
    // The confirmation is ChatViewModel's (it owns the contact's lock and the history pass), so this screen gets one
    // only while the card shows, and keeps it for its dialogs.
    var adminFlow by remember(groupId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(adminNeedsCheck) { if (adminNeedsCheck) adminFlow = true }
    val adminChatViewModel = if (adminNeedsCheck || adminFlow) koinViewModel<ChatViewModel>() else null
    var adminConfirmAsked by remember(groupId) { mutableStateOf(false) }
    val adminGroupName =
        (groupSettingsState as? co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState.Success)
            ?.groupInfo
            ?.name
            .orEmpty()
    adminChatViewModel?.let { chatViewModel ->
        GroupAdminConfirmDialogs(
            chatViewModel = chatViewModel,
            groupId = groupId,
            groupName = adminGroupName,
            asked = adminConfirmAsked,
            onAskedDone = { adminConfirmAsked = false },
            onChanged = {
                viewModel.loadGroupSettings(groupId)
                adminTick++
            },
        )
    }

    GroupSettingsView(
        state = groupSettingsState,
        addMemberCandidates = addMemberCandidates,
        contactNamesUnreadable = contactNamesUnreadable,
        onLoadAddCandidates = { viewModel.loadAddMemberCandidates(groupId) },
        onAddMember = { address ->
            viewModel.addMemberToGroup(groupId, address)
            Toast.makeText(context, "Adding member…", Toast.LENGTH_SHORT).show()
        },
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
        // F8-G: Resend on a "Key not delivered" / "Message not delivered" badge; each asks its price first.
        onResendKey = { address, offer -> viewModel.resendKeyToMember(groupId, address, offer) },
        onResendMessage = { address, offer -> viewModel.resendMessageCopy(groupId, address, offer) },
        onResendInvite = { address ->
            // P1.4 — re-run the single-member invite path for a FAILED member; the badge tracks
            // the outcome (Inviting… → Invited / Invite failed) via loadGroupSettings refreshes.
            viewModel.resendInvite(groupId, address)
            Toast.makeText(context, "Resending invite…", Toast.LENGTH_SHORT).show()
        },
        waitingForOtherChange = groupId in groupsWaitingForChange,
        adminNeedsCheck = adminNeedsCheck,
        adminCandidate = adminCandidate?.takeIf { adminCandidateChecked == groupId },
        adminCandidateChecked = adminCandidateChecked == groupId,
        adminKeyChanged = adminKeyState == co.electriccoin.zcash.ui.screen.chat.model.AdminKeyState.ORPHANED,
        adminOpenElsewhere = remember(groupId, adminKeyState) { viewModel.groupAdminOpenElsewhere(groupId) },
        onOpenAdminChat = { address -> navigationRouter.forward(ChatDetail(address)) },
        onConfirmAdmin = {
            adminChatViewModel?.let {
                it.loadGroupAdminPreview(groupId)
                adminConfirmAsked = true
            }
        },
    )
}

/**
 * ZC-022 (KI-25 R8, R9, sections 3.3 and 3.6) — the dialogs of the admin confirmation in Group settings.
 *
 * The confirm dialog shows who would become the group's admin on this phone (the name, the address in its short form,
 * the group's name, the safety number in groups of four), what that grants, that the person's earlier changes are
 * applied right after, and that it is final. It keeps the snapshot it opened with ([ChatViewModel.groupAdminPreview]:
 * the address and the contact's key), and Confirm passes both. Then the result: "Admin confirmed" with what is and is
 * not caught up, or that nothing was confirmed, or that it could not be saved. ZC-951: a result is shown only when it
 * is the one for this group and for the chat this screen confirmed. Nothing here pays or asks a price.
 */
@Composable
private fun GroupAdminConfirmDialogs(
    chatViewModel: ChatViewModel,
    groupId: String,
    groupName: String,
    asked: Boolean,
    onAskedDone: () -> Unit,
    onChanged: () -> Unit,
) {
    val cc = chatColors()
    val loadedPreview by chatViewModel.groupAdminPreview.collectAsStateWithLifecycle()
    val outcome by chatViewModel.groupAdminConfirmation.collectAsStateWithLifecycle()
    // The chat whose confirmation this screen started: only the outcome for this group and that chat is shown here.
    // ZC-951 residual: its address and name survive a rebuild of the screen (a fold or unfold of the Fold).
    var confirmingAddress by rememberSaveable(groupId) { mutableStateOf<String?>(null) }
    var confirmingName by rememberSaveable(groupId) { mutableStateOf<String?>(null) }

    loadedPreview?.takeIf { asked && it.groupId == groupId }?.let { preview ->
        val close = {
            chatViewModel.clearGroupAdminPreview()
            onAskedDone()
        }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.group_admin_confirm_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text =
                            stringResource(
                                R.string.group_admin_confirm_body,
                                preview.name,
                                co.electriccoin.zcash.ui.screen.chat.model.PeerName
                                    .shortAddress(preview.address),
                                groupName,
                                preview.safetyNumber.chunked(4).joinToString(" "),
                            ),
                        fontSize = 15.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = stringResource(R.string.group_admin_confirm_applies), fontSize = 13.sp, color = cc.textSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = stringResource(R.string.group_admin_confirm_final), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingAddress = preview.address
                        confirmingName = preview.name
                        chatViewModel.confirmGroupAdmin(groupId, preview.address, preview.contactKey)
                        close()
                    },
                ) {
                    Text(stringResource(R.string.group_admin_confirm_button), color = cc.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = close) {
                    Text(stringResource(R.string.chat_action_cancel), color = cc.textSecondary)
                }
            },
        )
    }

    val name = confirmingName
    val result = outcome?.takeIf { it.isFor(groupId, confirmingAddress) }
    if (name != null && result != null) {
        val confirmed = result is co.electriccoin.zcash.ui.screen.chat.model.AdminConfirmOutcome.Confirmed
        val reason = (result as? co.electriccoin.zcash.ui.screen.chat.model.AdminConfirmOutcome.Refused)?.reason
        val notSaved = reason == co.electriccoin.zcash.ui.screen.chat.model.AdminConfirmRefusal.STORE_REFUSED
        // ZC-951 residual: the key the user compared was saved and could not be undone; the card is read again.
        val notUndone = reason == co.electriccoin.zcash.ui.screen.chat.model.AdminConfirmRefusal.KEY_CHANGED_NOT_UNDONE
        val close = {
            confirmingAddress = null
            confirmingName = null
            chatViewModel.clearGroupAdminConfirmation()
            // The card is read again: gone after a confirmation, and showing what changed after a refusal.
            if (!notSaved) onChanged()
        }
        val title: (@Composable () -> Unit)? =
            if (confirmed) {
                { Text(stringResource(R.string.group_admin_confirmed_title), fontWeight = FontWeight.Bold) }
            } else {
                null
            }
        AlertDialog(
            onDismissRequest = close,
            title = title,
            text = {
                Text(
                    text =
                        when {
                            confirmed -> stringResource(R.string.group_admin_confirmed_body, name)
                            notSaved -> stringResource(R.string.group_admin_not_saved)
                            notUndone -> stringResource(R.string.group_admin_saved_key_changed)
                            else -> stringResource(R.string.group_admin_not_confirmed)
                        },
                    fontSize = 15.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = close) {
                    Text(stringResource(R.string.chat_dialog_ok), color = cc.primary)
                }
            },
        )
    }
}

/**
 * The automatic-transaction spend question (handshakes, a group join's "I joined" reply), shown by every screen
 * whose ChatViewModel can raise it: the chat list (R6-4) and a 1:1 chat.
 */
@Composable
private fun HandshakeSpendConsentDialog(
    ask: co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsHandshakeSpendConsent,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNotNow,
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
                Text(stringResource(R.string.handshake_consent_title))
            }
        },
        text = {
            Column {
                Text(text = stringResource(R.string.handshake_consent_body), fontSize = 15.sp)
                Spacer(modifier = Modifier.height(12.dp))
                // R2-2 (c): the quote is a floor (outputs + the fee of a one-input send); a wallet with
                // many small notes pays a larger fee, so it says "at least", never "about".
                Text(
                    text =
                        LocalContext.current.resources.getQuantityString(
                            R.plurals.handshake_consent_cost,
                            ask.pendingPeers,
                            ask.pendingPeers,
                            co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
                                .zec(ask.estimatedCost, rememberDesiredFormatLocale()),
                        ),
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary
                )
                // R6-4: a group join waiting on this answer is named, so "Not now" is not read as harmless.
                if (ask.groupJoins > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            LocalContext.current.resources.getQuantityString(
                                R.plurals.handshake_consent_group_join,
                                ask.groupJoins,
                                ask.groupJoins,
                            ),
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.handshake_consent_footer),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.handshake_consent_allow), color = chatColors().primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.handshake_consent_not_now), color = NightwireColors.TextSecondary)
            }
        },
    )
}

/** R2-2 (b) — a paid group control (create, add, re-invite, remove, rotate, key sync, leave) asks first. */
@Composable
private fun GroupPaidActionDialog(viewModel: GroupViewModel) {
    val ask by viewModel.paidActionConfirmation.collectAsStateWithLifecycle()
    ask?.let {
        co.electriccoin.zcash.ui.screen.chat.view.PaidActionConfirmDialog(
            confirmation = it,
            onConfirm = { viewModel.confirmPaidAction() },
            onDismiss = { viewModel.dismissPaidAction() },
        )
    }
}

/**
 * Round 8 P5 (PHONE_TRIAGE F5a) — a removal or key rotation that can't send the new key to some key holders (no secure
 * session) names them and asks first, before its cost is quoted.
 */
@Composable
private fun GroupRekeyUnkeyableDialog(viewModel: GroupViewModel) {
    val ask by viewModel.rekeyUnkeyableConfirmation.collectAsStateWithLifecycle()
    ask?.let {
        co.electriccoin.zcash.ui.screen.chat.view.RekeyUnkeyableDialog(
            confirmation = it,
            onConfirm = { viewModel.confirmRekeyUnkeyable() },
            onDismiss = { viewModel.dismissRekeyUnkeyable() },
        )
    }
}

/** P2h — a send notice in the phone's language when ChatSendCopy has a translation for it. */
private fun localizedSendNotice(
    context: android.content.Context,
    text: String
): String =
    co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
        .kexAnswerShortfallNeed(text)
        ?.let { need ->
            // ZC-1230: the one notice with a figure in it, in the phone's language and number format.
            context.getString(R.string.chat_send_kex_answer_shortfall, zecDisplay(need, context.resources.configuration.locales[0]))
        }
        ?: co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
            .messageShortfallFigures(text)
            ?.let { (have, need) ->
                // ZC-1417: what can be spent and what the message needs, in the phone's language and number format.
                val locale = context.resources.configuration.locales[0]
                context.getString(R.string.chat_send_insufficient_balance_have_need, zecDisplay(have, locale), zecDisplay(need, locale))
            }
        ?: co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
            .localizedResource(text)
            ?.let { context.getString(it) } ?: text

/**
 * A Quantum Shield answer: [title], [body], OK, and — when [turnOffHereLabel] is set, for a text that says "Tap Turn
 * off here" — that button, whose [onTurnOffHere] opens the turn-off confirm (it never turns anything off itself).
 */
@Composable
private fun QuantumShieldProblemDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    turnOffHereLabel: String? = null,
    onTurnOffHere: () -> Unit = {},
) {
    val cc = chatColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 13.sp, color = cc.textSecondary) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.quantum_shield_no_code_ok), color = cc.primary)
            }
        },
        dismissButton =
            turnOffHereLabel?.let { label ->
                {
                    TextButton(onClick = onTurnOffHere) {
                        Text(label, color = cc.primary)
                    }
                }
            },
        containerColor = cc.background,
        titleContentColor = cc.textPrimary,
        textContentColor = cc.textPrimary,
    )
}
