package co.electriccoin.zcash.ui.screen.chat.view

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.model.AdminCandidate
import co.electriccoin.zcash.ui.screen.chat.model.AdminCandidateSource
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupIntroduceInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupResendOffer
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PeerName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Group settings view showing group info, members, and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsView(
    state: GroupSettingsState,
    onBackClick: () -> Unit,
    onLeaveGroup: () -> Unit,
    onCopyGroupId: () -> Unit,
    onKickMember: (String) -> Unit = {},
    onRotateKey: () -> Unit = {},
    onResendInvite: (String) -> Unit = {},
    // F8-G: Resend on a "Key not delivered" / "Message not delivered" badge: the member and the offer the badge shows.
    onResendKey: (String, GroupResendOffer) -> Unit = { _, _ -> },
    onResendMessage: (String, GroupResendOffer) -> Unit = { _, _ -> },
    addMemberCandidates: List<Contact> = emptyList(),
    // ZC-733-B: the saved names cannot be read; the add-member picker says so.
    contactNamesUnreadable: Boolean = false,
    onLoadAddCandidates: () -> Unit = {},
    onAddMember: (String) -> Unit = {},
    // KI-22: a change of this group started here waits for another change of the same group.
    waitingForOtherChange: Boolean = false,
    modifier: Modifier = Modifier,
    // ZC-022 (KI-25 R6, section 3.3): this phone cannot confirm the group's admin: the card shows. [adminCandidate] is
    // the one chat the phone's own records give (null: none, or not read yet while [adminCandidateChecked] is false).
    adminNeedsCheck: Boolean = false,
    adminCandidate: AdminCandidate? = null,
    adminCandidateChecked: Boolean = false,
    onOpenAdminChat: (String) -> Unit = {},
    onConfirmAdmin: () -> Unit = {},
    // KI-25 R14 (ZC-572): the group's stored admin key is no chat's any more (ORPHANED): the card's body says so.
    adminKeyChanged: Boolean = false,
    // KI-25 R17 (ZC-598, ZC-729): an Open group whose admin's chat is not at its stored admin address: the card's last line.
    adminOpenElsewhere: Boolean = false,
) {
    val colors = chatColors()

    when (state) {
        is GroupSettingsState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = colors.primary)
            }
        }
        is GroupSettingsState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.group_error_title),
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
        is GroupSettingsState.Success -> {
            GroupSettingsContent(
                groupInfo = state.groupInfo,
                members = state.members,
                currentUserAddress = state.currentUserAddress,
                isCreator = state.isCreator,
                knownOnly = state.knownOnly,
                onBackClick = onBackClick,
                onLeaveGroup = onLeaveGroup,
                onCopyGroupId = onCopyGroupId,
                onKickMember = onKickMember,
                onRotateKey = onRotateKey,
                onResendInvite = onResendInvite,
                onResendKey = onResendKey,
                onResendMessage = onResendMessage,
                keyNotDelivered = state.keyNotDelivered,
                messageNotDelivered = state.messageNotDelivered,
                inviteUnconfirmed = state.inviteUnconfirmed,
                addMemberCandidates = addMemberCandidates,
                contactNamesUnreadable = contactNamesUnreadable,
                onLoadAddCandidates = onLoadAddCandidates,
                onAddMember = onAddMember,
                colors = colors,
                modifier = modifier,
                sharedByCreator = state.sharedByCreator,
                introduce = state.introduce,
                names = state.names,
                waitingForOtherChange = waitingForOtherChange,
                adminNeedsCheck = adminNeedsCheck,
                adminCandidate = adminCandidate,
                adminCandidateChecked = adminCandidateChecked,
                onOpenAdminChat = onOpenAdminChat,
                onConfirmAdmin = onConfirmAdmin,
                adminKeyChanged = adminKeyChanged,
                adminOpenElsewhere = adminOpenElsewhere,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSettingsContent(
    groupInfo: GroupInfo,
    members: List<GroupMember>,
    currentUserAddress: String,
    isCreator: Boolean,
    knownOnly: Boolean,
    onBackClick: () -> Unit,
    onLeaveGroup: () -> Unit,
    onCopyGroupId: () -> Unit,
    onKickMember: (String) -> Unit,
    onRotateKey: () -> Unit,
    onResendInvite: (String) -> Unit,
    addMemberCandidates: List<Contact>,
    contactNamesUnreadable: Boolean,
    onLoadAddCandidates: () -> Unit,
    onAddMember: (String) -> Unit,
    colors: ChatColors,
    modifier: Modifier = Modifier,
    // Round 8 MA2: the member addresses the group's creator shared with this phone.
    sharedByCreator: Set<String> = emptySet(),
    // Round 8 MA3: on the group's creator, the member-row notes of the member announce.
    introduce: GroupIntroduceInfo? = null,
    waitingForOtherChange: Boolean = false,
    // GROUP-LABEL: member address -> the name the 1-to-1 chat header uses (GroupSettingsState.Success.names).
    names: Map<String, String> = emptyMap(),
    // F8-G (ZC-112): member address -> the failed key message / message copy its badge stands for, and their Resend.
    keyNotDelivered: Map<String, GroupResendOffer> = emptyMap(),
    messageNotDelivered: Map<String, GroupResendOffer> = emptyMap(),
    // ZC-420-G: the INVITED rows whose invite only a public relay took ("Invited, not confirmed", with Resend).
    inviteUnconfirmed: Set<String> = emptySet(),
    onResendKey: (String, GroupResendOffer) -> Unit = { _, _ -> },
    onResendMessage: (String, GroupResendOffer) -> Unit = { _, _ -> },
    adminNeedsCheck: Boolean = false,
    adminCandidate: AdminCandidate? = null,
    adminCandidateChecked: Boolean = false,
    onOpenAdminChat: (String) -> Unit = {},
    onConfirmAdmin: () -> Unit = {},
    // KI-25 R14 (ZC-572): the group's stored admin key is no chat's any more (ORPHANED): the card's body says so.
    adminKeyChanged: Boolean = false,
    // KI-25 R17 (ZC-598, ZC-729): an Open group whose admin's chat is not at its stored admin address: the card's last line.
    adminOpenElsewhere: Boolean = false,
) {
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showRotateConfirmDialog by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    // R6-3 (round 5 report §5 item 6): the creation date in the language Compose sees now.
    val formatLocale = rememberDesiredFormatLocale()
    val dateFormatter =
        remember(formatLocale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(formatLocale) }

    // A removed (kicked) member is kept in storage as MemberStatus.LEFT for epoch/key bookkeeping, but
    // must NOT appear in the roster — the admin removed them, so they disappear from the list + count.
    val visibleMembers = remember(members) { members.filterNot { it.status == MemberStatus.LEFT } }
    // Round 8 MA3 (design §4): a member whose leave could not be verified stays listed, as "Left (not confirmed)",
    // with "Remove for everyone" (the removal, which tells the other members).
    val listedMembers =
        remember(members, introduce) {
            members.filter { it.status != MemberStatus.LEFT || it.address in introduce?.leftUnconfirmed.orEmpty() }
        }

    // Group-avatar editing (Phase 1: local-only) — gated on isCreator, the SAME admin gate that
    // guards Rotate Group Key and member kick on this screen. Non-admins get a read-only avatar.
    val context = LocalContext.current
    val avatarStore = koinInject<AvatarStore>()
    val avatarScope = rememberCoroutineScope()
    var showGroupPhotoDialog by remember { mutableStateOf(false) }
    val groupAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatarScope.launch {
                val bytes = loadAvatarBytesFromUri(context, uri)
                val stored = bytes != null &&
                    withContext(Dispatchers.IO) { avatarStore.setGroupAvatar(groupInfo.groupId, bytes) }
                if (!stored) {
                    Toast.makeText(context, context.getString(R.string.group_photo_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.group_settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.group_back),
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background
                )
            )
        },
        containerColor = colors.background,
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // KI-22: this screen's change of the group waits for another change of the same group.
            if (waitingForOtherChange) {
                item {
                    Text(
                        text = stringResource(R.string.group_waiting_for_other_change),
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // ZC-022 (KI-25 R6, section 3.3): this phone cannot confirm the group's admin. The card cannot be hidden.
            if (adminNeedsCheck) {
                item {
                    GroupAdminCheckCard(
                        candidate = adminCandidate,
                        candidateChecked = adminCandidateChecked,
                        onOpenChat = onOpenAdminChat,
                        onConfirm = onConfirmAdmin,
                        colors = colors,
                        keyChanged = adminKeyChanged,
                        openElsewhere = adminOpenElsewhere,
                    )
                }
            }
            // Group Info Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Group avatar — stored local photo when set, else the gradient placeholder.
                        // ADMIN ONLY (isCreator): tap the avatar / pencil badge to change or remove
                        // the photo. Phase 1 is local-only; propagation to members is Phase 2
                        // (signed GROUP_INFO, #187 — see AvatarStore).
                        Box {
                            ZchatAvatar(
                                ref = ZchatAvatarRef.Group(groupInfo.groupId),
                                displayName = groupInfo.name,
                                size = 80.dp,
                                modifier = if (isCreator) {
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable(onClickLabel = stringResource(R.string.group_edit_photo)) {
                                            showGroupPhotoDialog = true
                                        }
                                } else {
                                    Modifier
                                }
                            )
                            if (isCreator) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                        .clickable { showGroupPhotoDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.group_edit_photo),
                                        tint = colors.textOnAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Group name
                        Text(
                            text = groupInfo.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Member count. Round 8 P7 (PHONE_TRIAGE F4): a member's roster holds only the members it has
                        // heard from, so on a phone that is not the admin they are "known members". Round 9 R9-5: unless
                        // the creator announced the roster ([knownOnly], the group screen's own rule).
                        val resources = LocalContext.current.resources
                        Text(
                            text =
                                groupSettingsMemberCountLabel(members, knownOnly) { id, quantity, args ->
                                    resources.getQuantityString(id, quantity, *args.toTypedArray())
                                },
                            fontSize = 15.sp,
                            color = colors.textSecondary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Created date
                        Text(
                            text =
                                stringResource(
                                    R.string.group_created_on,
                                    groupInfo.createdAt.atZone(ZoneId.systemDefault()).format(dateFormatter)
                                ),
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Group ID with copy button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.background)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "ID: ${groupInfo.groupId.take(12)}...",
                                fontSize = 13.sp,
                                color = colors.textSecondary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onCopyGroupId,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.group_copy_id),
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Members Section Header (+ admin-only "Add" action)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.group_members_header),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    // Only the admin (creator) may add members — mirrors the kick/rotate admin gate.
                    if (isCreator) {
                        TextButton(onClick = {
                            onLoadAddCandidates()
                            showAddMemberDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.group_add), color = colors.primary, fontSize = 15.sp)
                        }
                    }
                }
            }

            // Member List
            if (visibleMembers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.group_no_members),
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(listedMembers, key = { it.address }) { member ->
                    val memberIsCreator = member.address == groupInfo.creatorAddress
                    MemberItem(
                        member = member,
                        name = names[member.address],
                        isCurrentUser = member.address == currentUserAddress,
                        isCreator = memberIsCreator,
                        // #204: only the admin (current user is creator) may remove OTHER members — never
                        // the admin themselves, never a non-existent self-kick. The kick rotates the key
                        // (kickMember → rotateAndNotify) so the removed member can't read future messages.
                        canKick = isCreator && !memberIsCreator && member.address != currentUserAddress,
                        onKick = { onKickMember(member.address) },
                        // P1.4: only the creator (who sent the invite) can re-run the invite path —
                        // for members whose invite FAILED, and for legacy rosters saved before
                        // tracking (inviteStatus == null): those are exactly the groups whose invites
                        // may have been silently dropped, so give them the repair path too. Duplicate
                        // invites are harmless (the receiver just re-saves the group).
                        // ZC-420-G: also for an invite only a public relay took; its Resend goes over the relays, free.
                        canResendInvite = isCreator &&
                            member.status == MemberStatus.INVITED &&
                            (
                                member.address in inviteUnconfirmed ||
                                    (member.inviteStatus != InviteStatus.SENT && member.inviteStatus != InviteStatus.INVITE_PENDING)
                            ),
                        onResendInvite = { onResendInvite(member.address) },
                        inviteNotConfirmed = member.address in inviteUnconfirmed,
                        colors = colors,
                        sharedByCreator = member.address in sharedByCreator,
                        note =
                            when (member.address) {
                                in introduce?.leftUnconfirmed.orEmpty() -> R.string.group_member_left_unconfirmed
                                in introduce?.noSecureLink.orEmpty() -> R.string.group_member_no_secure_link
                                in introduce?.tooBig.orEmpty() -> R.string.group_member_too_big
                                else -> null
                            },
                        removeForEveryone = isCreator && member.address in introduce?.leftUnconfirmed.orEmpty(),
                        keyOffer = keyNotDelivered[member.address],
                        messageOffer = messageNotDelivered[member.address],
                        onResendKey = { offer -> onResendKey(member.address, offer) },
                        onResendMessage = { offer -> onResendMessage(member.address, offer) },
                    )
                }
            }

            // Round 8 MA3 (member announce, design §7, §13-6): on the creator, while some member may run 2.12 or older.
            if (introduce?.mixedVersion == true) {
                item {
                    Text(
                        text = stringResource(R.string.group_mixed_version_note),
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // Actions Section
            item {
                Spacer(modifier = Modifier.height(16.dp))

                // #204: ADMIN-ONLY — rotate the group key (per-member signed GROUP_KEY, #187). Periodic
                // key-hygiene refresh (limits the blast radius of a leaked key going FORWARD — it does
                // not protect past messages, i.e. not forward secrecy); no member removed.
                if (isCreator) {
                    Button(
                        onClick = { showRotateConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary.copy(alpha = 0.1f),
                            contentColor = colors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.group_rotate_key))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Leave Group Button
                Button(
                    onClick = { showLeaveConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error.copy(alpha = 0.1f),
                        contentColor = colors.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.group_leave))
                }
            }
        }
    }

    // Group-photo change/remove chooser — reachable only via the isCreator-gated affordances above.
    if (showGroupPhotoDialog) {
        val avatarVersion by avatarStore.version.collectAsState()
        val hasGroupPhoto = remember(avatarVersion) { avatarStore.hasGroupAvatar(groupInfo.groupId) }
        AvatarPhotoDialog(
            title = stringResource(R.string.group_photo_title),
            hasPhoto = hasGroupPhoto,
            onDismiss = { showGroupPhotoDialog = false },
            onPickNew = { groupAvatarPicker.launch("image/*") },
            onRemove = {
                avatarScope.launch(Dispatchers.IO) { avatarStore.removeGroupAvatar(groupInfo.groupId) }
            }
        )
    }

    // Leave confirmation dialog
    if (showLeaveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmDialog = false },
            title = { Text(stringResource(R.string.group_leave_confirm_title)) },
            text = {
                Text(stringResource(R.string.group_leave_confirm_body, groupInfo.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmDialog = false
                        onLeaveGroup()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                ) {
                    Text(stringResource(R.string.group_leave_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmDialog = false }) {
                    Text(stringResource(R.string.chat_action_cancel))
                }
            }
        )
    }

    // Rotate-key confirmation dialog
    if (showRotateConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRotateConfirmDialog = false },
            title = { Text(stringResource(R.string.group_rotate_confirm_title)) },
            text = {
                // ZC-757 (G7): before a rebuilt group's new key, the key reaches only the listed members this phone can send it to.
                val beforeNewKey =
                    co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey
                        .stage(groupInfo) == co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey.Stage.NEEDS_KEY
                Text(
                    stringResource(
                        if (beforeNewKey) R.string.group_rotate_confirm_body_rebuilt else R.string.group_rotate_confirm_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRotateConfirmDialog = false
                        onRotateKey()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                ) {
                    Text(stringResource(R.string.group_rotate_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotateConfirmDialog = false }) {
                    Text(stringResource(R.string.chat_action_cancel))
                }
            }
        )
    }

    // Admin-only Add-member picker: pick a saved contact / KEX'd peer (not already in the group) to
    // invite. Tapping a row sends them a GROUP_INVITE with the current key (free over NOSTR when possible).
    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            title = {
                Column {
                    Text(stringResource(R.string.group_add_member_title))
                    // ZC-733-B: saved names that cannot be read are said above the picker's list.
                    UnreadableStoreNotice(namesUnreadable = contactNamesUnreadable)
                }
            },
            text = {
                if (addMemberCandidates.isEmpty()) {
                    Text(
                        stringResource(R.string.group_add_member_empty),
                        color = colors.textSecondary
                    )
                } else {
                    // QA2 Q2-63 (W-137): the list says who can be added (the empty text says it when nobody can).
                    Column {
                        Text(
                            stringResource(R.string.group_add_member_hint),
                            color = colors.textSecondary,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Cap the height and make the candidate list scrollable: an AlertDialog's text slot
                        // doesn't scroll, so an admin with 15+ eligible contacts/peers would have the overflow
                        // rows clipped and unreachable — those members could never be added from this screen.
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState())
                        ) {
                            addMemberCandidates.forEach { contact ->
                                // QA2 Q2-60 (W-137): 8...6 as every other screen, and a stored name that is only the
                                // address is no name (the short address shows once).
                                val short = PeerName.shortAddress(contact.address)
                                val realName = PeerName.realName(contact.name, contact.address)
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onAddMember(contact.address)
                                                showAddMemberDialog = false
                                            }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = realName ?: short,
                                            color = colors.textPrimary,
                                            fontSize = 15.sp
                                        )
                                        if (realName != null) {
                                            Text(text = short, color = colors.textSecondary, fontSize = 12.sp)
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                // QA2 Q2-63 (W-137): "Close", not "Done": the dialog closes whether or not anyone was added.
                TextButton(onClick = { showAddMemberDialog = false }) {
                    Text(stringResource(R.string.group_add_member_close))
                }
            }
        )
    }
}

/**
 * F8-G (ZC-112) — one "not delivered" line of a member row: the badge and the existing Resend button. Under the name,
 * so the row keeps its width at 360 dp and a large font.
 */
@Composable
private fun NotDeliveredRow(
    badge: Int,
    colors: ChatColors,
    onResend: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.error.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(text = stringResource(badge), fontSize = 11.sp, color = colors.error)
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(
            onClick = onResend,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
        ) {
            Text(text = stringResource(R.string.group_resend), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MemberItem(
    member: GroupMember,
    // GROUP-LABEL: the name the 1-to-1 chat header uses for this member, null = none (roster snapshot, else address).
    name: String?,
    isCurrentUser: Boolean,
    isCreator: Boolean,
    canKick: Boolean = false,
    onKick: () -> Unit = {},
    canResendInvite: Boolean = false,
    onResendInvite: () -> Unit = {},
    // ZC-420-G: this INVITED row's invite only a public relay took: its badge says so.
    inviteNotConfirmed: Boolean = false,
    colors: ChatColors,
    // Round 8 MA2 (design §9): the group's creator shared this member with this phone (a verified member announce).
    sharedByCreator: Boolean = false,
    // Round 8 MA3 (design §9): the member announce's note on this row (no secure link, too long, left not confirmed).
    note: Int? = null,
    // Round 8 MA3 (design §4): "Remove for everyone" on a "Left (not confirmed)" row; it runs the removal.
    removeForEveryone: Boolean = false,
    // F8-G (ZC-112): this member's current key message, or a copy of one of our messages, never reached the chain. Each
    // shows its badge with Resend, which asks its price first.
    keyOffer: GroupResendOffer? = null,
    messageOffer: GroupResendOffer? = null,
    onResendKey: (GroupResendOffer) -> Unit = {},
    onResendMessage: (GroupResendOffer) -> Unit = {},
) {
    var showKickConfirmDialog by remember { mutableStateOf(false) }
    val shownName = name ?: member.displayName
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar — the member's locally stored contact photo when the viewer set one, else
            // initials on the member's per-address color (nickname only; address pair otherwise).
            ZchatAvatar(
                ref = ZchatAvatarRef.Contact(member.address),
                displayName = name ?: member.nickname,
                size = 40.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Name and address
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = shownName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isCurrentUser) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.group_member_you),
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                if (name != null || member.nickname != null) {
                    Text(
                        text = PeerName.shortAddress(member.address),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                if (sharedByCreator) {
                    Text(
                        text = stringResource(R.string.group_member_shared_by_creator),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    )
                }
                if (note != null) {
                    Text(
                        text = stringResource(note),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    )
                }
                if (removeForEveryone) {
                    TextButton(
                        onClick = { showKickConfirmDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                    ) {
                        Text(text = stringResource(R.string.group_remove_for_everyone), fontSize = 13.sp)
                    }
                }
                if (keyOffer != null) {
                    NotDeliveredRow(R.string.group_member_key_not_delivered_status, colors) { onResendKey(keyOffer) }
                }
                if (messageOffer != null) {
                    NotDeliveredRow(R.string.group_member_message_not_delivered_status, colors) { onResendMessage(messageOffer) }
                }
            }

            // P1.4 — invite delivery badge on not-yet-active members, so the roster is honest about
            // who can actually receive messages (fan-out only reaches ACTIVE members).
            if (member.status == MemberStatus.INVITED) {
                val inviteFailed = member.inviteStatus == InviteStatus.FAILED
                val badgeColor = if (inviteFailed) colors.error else colors.textSecondary
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeColor.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text =
                            when {
                                // ZC-420-G: only a public relay took the invite (ZChat 2.12.4 does not read it).
                                inviteNotConfirmed -> stringResource(R.string.group_invite_not_confirmed_status)
                                member.inviteStatus == InviteStatus.FAILED -> stringResource(R.string.group_invite_failed_status)
                                member.inviteStatus == InviteStatus.INVITE_PENDING -> stringResource(R.string.group_inviting_status)
                                // SENT, or legacy roster saved before tracking
                                else -> stringResource(R.string.group_invited_status)
                            },
                        fontSize = 11.sp,
                        color = badgeColor
                    )
                }
                // P1.4 — repair path: re-run the single-member invite for a FAILED member.
                if (canResendInvite) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = onResendInvite,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                    ) {
                        Text(
                            text = stringResource(R.string.group_resend),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Role badge
            if (isCreator) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.group_admin_badge_description),
                        tint = colors.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.group_admin_badge),
                        fontSize = 11.sp,
                        color = colors.primary
                    )
                }
            }

            // #204: admin-only remove (kick) button on each non-admin member row.
            if (canKick) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { showKickConfirmDialog = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.group_remove_member_description),
                        tint = colors.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // Kick confirmation dialog
    if (showKickConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showKickConfirmDialog = false },
            title = { Text(stringResource(R.string.group_remove_member_title)) },
            text = {
                Text(stringResource(R.string.group_remove_member_body, shownName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKickConfirmDialog = false
                        onKick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                ) {
                    Text(stringResource(R.string.group_remove_member_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showKickConfirmDialog = false }) {
                    Text(stringResource(R.string.chat_action_cancel))
                }
            }
        )
    }
}

/**
 * ZC-022 (KI-25 R6, R7, section 3.3) — the card Group settings shows while this phone cannot confirm the group's admin:
 * what that means, then what the phone's own records give. No record: the workaround. One chat ([candidate]): where
 * the record comes from, and either what has to be done in that chat first (finish the secure chat, settle a key
 * change, compare the safety number), with "Open chat", or "Confirm the admin", which opens the confirm dialog. There
 * is no list and no pick. Nothing is sent or paid by this card.
 */
@Composable
private fun GroupAdminCheckCard(
    candidate: AdminCandidate?,
    candidateChecked: Boolean,
    onOpenChat: (String) -> Unit,
    onConfirm: () -> Unit,
    colors: ChatColors,
    keyChanged: Boolean = false,
    openElsewhere: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.group_admin_check_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            // KI-25 R14 (ZC-572): the body for a stored admin key that no chat holds any more; R17 (ZC-598): the body for
            // a chat at the stored admin address that is not confirmed yet.
            val body =
                when {
                    keyChanged -> R.string.group_admin_check_body_changed
                    candidate?.source == AdminCandidateSource.ADDRESS -> R.string.group_admin_check_body_address
                    else -> R.string.group_admin_check_body
                }
            Text(text = stringResource(body), fontSize = 13.sp, color = colors.textPrimary)
            if (candidate == null) {
                if (candidateChecked) {
                    // KI-25 R17 (ZC-729): a new Open group would land in the same state, so no new-group advice there.
                    val none = if (openElsewhere) R.string.group_admin_no_candidate_open else R.string.group_admin_no_candidate
                    Text(text = stringResource(none), fontSize = 13.sp, color = colors.textPrimary)
                }
            } else {
                val name = candidate.name?.takeIf { it.isNotBlank() } ?: PeerName.shortAddress(candidate.address)
                val sourceText =
                    when (candidate.source) {
                        AdminCandidateSource.INVITATION -> R.string.group_admin_candidate_invite
                        AdminCandidateSource.ADDRESS -> R.string.group_admin_candidate_address
                    }
                Text(
                    text = stringResource(sourceText, name, PeerName.shortAddress(candidate.address)),
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                )
                // What has to be done in the chat first, in the order the confirmation checks it; null when it is ready.
                val firstStep =
                    when {
                        !candidate.chatFinished -> R.string.group_admin_candidate_not_finished
                        candidate.keyChangePending -> R.string.group_admin_candidate_key_changed
                        !candidate.safetyNumberCompared -> R.string.group_admin_candidate_verify
                        else -> null
                    }
                if (firstStep != null) {
                    Text(text = stringResource(firstStep, name), fontSize = 13.sp, color = colors.textPrimary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (firstStep != null) onOpenChat(candidate.address) else onConfirm() }) {
                        Text(
                            text =
                                stringResource(
                                    if (firstStep != null) R.string.group_admin_open_chat else R.string.group_admin_check_button,
                                ),
                            color = colors.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
