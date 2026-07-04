package co.electriccoin.zcash.ui.screen.chat.view

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    modifier: Modifier = Modifier
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
        is GroupSettingsState.Success -> {
            GroupSettingsContent(
                groupInfo = state.groupInfo,
                members = state.members,
                currentUserAddress = state.currentUserAddress,
                isCreator = state.isCreator,
                onBackClick = onBackClick,
                onLeaveGroup = onLeaveGroup,
                onCopyGroupId = onCopyGroupId,
                onKickMember = onKickMember,
                onRotateKey = onRotateKey,
                onResendInvite = onResendInvite,
                colors = colors,
                modifier = modifier
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
    onBackClick: () -> Unit,
    onLeaveGroup: () -> Unit,
    onCopyGroupId: () -> Unit,
    onKickMember: (String) -> Unit,
    onRotateKey: () -> Unit,
    onResendInvite: (String) -> Unit,
    colors: ChatColors,
    modifier: Modifier = Modifier
) {
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showRotateConfirmDialog by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy") }

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
                    Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Group Settings")
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
                                        .clickable(onClickLabel = "Edit group photo") {
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
                                        contentDescription = "Edit group photo",
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

                        // Member count
                        Text(
                            text = "${members.size} members",
                            fontSize = 15.sp,
                            color = colors.textSecondary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Created date
                        Text(
                            text = "Created ${groupInfo.createdAt.atZone(ZoneId.systemDefault()).format(dateFormatter)}",
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
                                    contentDescription = "Copy Group ID",
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Members Section Header
            item {
                Text(
                    text = "Members",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            }

            // Member List
            if (members.isEmpty()) {
                item {
                    Text(
                        text = "No members",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(members, key = { it.address }) { member ->
                    val memberIsCreator = member.address == groupInfo.creatorAddress
                    MemberItem(
                        member = member,
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
                        canResendInvite = isCreator &&
                            member.status == MemberStatus.INVITED &&
                            member.inviteStatus != InviteStatus.SENT &&
                            member.inviteStatus != InviteStatus.INVITE_PENDING,
                        onResendInvite = { onResendInvite(member.address) },
                        colors = colors
                    )
                }
            }

            // Actions Section
            item {
                Spacer(modifier = Modifier.height(16.dp))

                // #204: ADMIN-ONLY — rotate the group key (per-member signed GROUP_KEY, #187). Periodic
                // hygiene / forward-secrecy refresh; no member removed.
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
                        Text("Rotate Group Key")
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
                    Text("Leave Group")
                }
            }
        }
    }

    // Group-photo change/remove chooser — reachable only via the isCreator-gated affordances above.
    if (showGroupPhotoDialog) {
        AvatarPhotoDialog(
            title = "Group photo",
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
            title = { Text("Leave Group?") },
            text = {
                Text("Are you sure you want to leave \"${groupInfo.name}\"? You will no longer receive messages from this group.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmDialog = false
                        onLeaveGroup()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                ) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rotate-key confirmation dialog
    if (showRotateConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRotateConfirmDialog = false },
            title = { Text("Rotate Group Key?") },
            text = {
                Text("Generate a fresh group key and securely send it to every current member. Old messages stay readable; this strengthens forward privacy going forward.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRotateConfirmDialog = false
                        onRotateKey()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                ) {
                    Text("Rotate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotateConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MemberItem(
    member: GroupMember,
    isCurrentUser: Boolean,
    isCreator: Boolean,
    canKick: Boolean = false,
    onKick: () -> Unit = {},
    canResendInvite: Boolean = false,
    onResendInvite: () -> Unit = {},
    colors: ChatColors
) {
    var showKickConfirmDialog by remember { mutableStateOf(false) }
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
                displayName = member.nickname,
                size = 40.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Name and address
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isCurrentUser) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(you)",
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                if (member.nickname != null) {
                    Text(
                        text = "${member.address.take(8)}...${member.address.takeLast(6)}",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
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
                        text = when (member.inviteStatus) {
                            InviteStatus.FAILED -> "Invite failed"
                            InviteStatus.INVITE_PENDING -> "Inviting…"
                            else -> "Invited" // SENT, or legacy roster saved before tracking
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
                            text = "Resend",
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
                        contentDescription = "Admin badge",
                        tint = colors.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Admin",
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
                        contentDescription = "Remove member",
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
            title = { Text("Remove member?") },
            text = {
                Text("Remove ${member.displayName} from the group? The group key will be rotated so they can no longer read new messages.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKickConfirmDialog = false
                        onKick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKickConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
