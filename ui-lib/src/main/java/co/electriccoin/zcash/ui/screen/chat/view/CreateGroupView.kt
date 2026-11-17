package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.draw.shadow
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.CreateGroupState
import co.electriccoin.zcash.ui.screen.chat.model.PeerName
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.groupMessageCostFloor
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost

/**
 * Create Group screen for setting up a new group chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupView(
    state: CreateGroupState,
    onBackClick: () -> Unit,
    onGroupNameChange: (String) -> Unit,
    onMemberToggle: (String) -> Unit,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = chatColors()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Group placeholder (no groupId yet — the group avatar becomes editable by
                        // the admin from Group Settings once the group exists).
                        ZchatAvatar(
                            ref = ZchatAvatarRef.Group(null),
                            displayName = null,
                            size = 36.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.group_new_title),
                            fontWeight = FontWeight.Bold
                        )
                    }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Group name input
            OutlinedTextField(
                value = state.groupName,
                onValueChange = { if (it.length <= 50) onGroupNameChange(it) },
                label = { Text(stringResource(R.string.group_name_label)) },
                placeholder = { Text(stringResource(R.string.group_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.surface,
                    focusedLabelColor = colors.primary,
                    unfocusedLabelColor = colors.textSecondary,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.primary
                )
            )

            Text(
                text = "${state.groupName.length}/50",
                fontSize = 11.sp,
                color = colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Member selection header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.group_add_members),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = stringResource(R.string.group_create_selected, state.selectedMembers.size, 9),
                    fontSize = 13.sp,
                    color = if (state.selectedMembers.size >= 9) colors.error else colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ZC-733-B: saved names that cannot be read are said above the list.
            UnreadableStoreNotice(namesUnreadable = state.contactNamesUnreadable)

            // Contact list
            if (state.availableContacts.isEmpty()) {
                // Empty state
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
                            tint = colors.textSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.group_no_contacts),
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.group_no_contacts_hint),
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.availableContacts) { contact ->
                        val isSelected = state.selectedMembers.contains(contact.address)
                        ContactSelectionItem(
                            contact = contact,
                            isSelected = isSelected,
                            onToggle = { onMemberToggle(contact.address) },
                            colors = colors
                        )
                    }
                }
            }

            // Error message
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = state.error ?: "",
                    color = colors.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Cost note. QA2 Q2-59 (W-137): above the Create button, 14.sp, textSecondary at full alpha (it was 13.sp
            // under the button at the bottom edge).
            // R2-2 (c): one on-chain transaction per member, at least 0.00017 ZEC each (was "~0.0001").
            // R6-2: the members paid for are the invitees, not the creator (was memberCount, one too many).
            // Round 10b R10B-3 (F-R9C-6): null with nobody chosen, when the note asks for a member instead of quoting
            // a cost for one (it forced the count up to 1 and quoted at least 0.00017 ZEC for 1 member).
            val costMembers = state.costNoteMembers
            // Round 8 P9 (ES-2): the figures follow the phone's language, as the balances do.
            val formatLocale = rememberDesiredFormatLocale()
            Text(
                text =
                    if (costMembers == null) {
                        stringResource(R.string.group_create_pick_members)
                    } else {
                        LocalContext.current.resources.getQuantityString(
                            R.plurals.create_group_cost_note,
                            costMembers,
                            costMembers,
                            PaidActionCopy.zec(onChainSendMinimumCost(), formatLocale),
                            PaidActionCopy.zec(groupMessageCostFloor(costMembers), formatLocale),
                        )
                    },
                fontSize = 14.sp,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Create button
            val createEnabled = state.isValid && !state.isCreating
            Button(
                onClick = onCreateGroup,
                enabled = createEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(
                        if (createEnabled) Modifier.shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(NightwireColors.RadiusButton),
                            ambientColor = chatColors().accentPrimaryGlow,
                            spotColor = chatColors().accentPrimaryGlow
                        ) else Modifier
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    disabledContainerColor = colors.surface
                ),
                shape = RoundedCornerShape(NightwireColors.RadiusButton)
            ) {
                if (state.isCreating) {
                    val creatingDescription = stringResource(R.string.group_creating_description)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics { contentDescription = creatingDescription },
                        color = colors.textOnAccent,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.group_creating),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textOnAccent
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        // The group's size, creator included (R6-2); in the phone's language (R6-3). With nobody
                        // chosen yet it names no size (Round 10b R10B-3, F-R9C-6: it named a group of 1).
                        text =
                            if (state.buttonGroupSize == null) {
                                stringResource(R.string.group_create_button_none)
                            } else {
                                pluralStringResource(R.plurals.group_create_button, state.memberCount, state.memberCount)
                            },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactSelectionItem(
    contact: Contact,
    isSelected: Boolean,
    onToggle: () -> Unit,
    colors: ChatColors
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colors.primary.copy(alpha = 0.1f) else colors.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // QA2 Q2-60 (W-137): a stored name that is only the address (or a short form of it) is no name, so the
            // short address shows once (8...6, as every other screen) and the avatar uses the hash initials.
            val realName = PeerName.realName(contact.name, contact.address)
            // Avatar — stored local photo when set, else per-address initials (solid on selection).
            ZchatAvatar(
                ref = ZchatAvatarRef.Contact(contact.address),
                displayName = realName,
                size = 40.dp,
                solid = isSelected
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Contact info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = realName?.let { contactDisplayName(it) } ?: PeerName.shortAddress(contact.address),
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (realName != null) {
                    Text(
                        text = PeerName.shortAddress(contact.address),
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.primary,
                    uncheckedColor = colors.textSecondary,
                    checkmarkColor = Color.White
                )
            )
        }
    }
}
