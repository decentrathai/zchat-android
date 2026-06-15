package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
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
    colors: ChatColors,
    modifier: Modifier = Modifier
) {
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showRotateConfirmDialog by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy") }

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
                        // Group avatar
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF00D9FF),
                                            Color(0xFF00E676)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
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
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

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
