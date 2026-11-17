package co.electriccoin.zcash.ui.screen.chat.view

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminNotice
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupIntroduceInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageCost
import co.electriccoin.zcash.ui.screen.chat.model.IntroduceProgress
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.PeerName
import co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupSendResult
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Group chat detail view for viewing and sending messages in a group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailView(
    state: GroupDetailState,
    isSendingMessage: Boolean,
    sendResult: GroupSendResult?,
    onSendResultConsumed: () -> Unit,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSyncKeys: () -> Unit,
    // R5-1 — what one message to this group costs on-chain right now, from the send path's own figures.
    messageCost: GroupMessageCost? = null,
    // Round 8 D4 — the invite came from an address we never chatted with and waits for Join (Join / Ignore below).
    awaitingJoin: Boolean = false,
    // Round 8 P1 — the invite waits for Join because its reply cannot go over the relays (Join pays it).
    joinReplyIsPaid: Boolean = false,
    // ZC-517: this waiting reply cannot fit one memo, so Join can only try the relays.
    joinReplyIsRelayOnly: Boolean = false,
    joinReplyIsFree: Boolean = false,
    // ZC-922 (W-137 B-4): the inviter is a known contact (a chat, key or saved contact), so the banner is not the stranger's.
    joinInviterIsKnown: Boolean = false,
    // ZC-922 review F4: the group's creator is one of our own addresses (the self-address registry, any representation).
    groupCreatorIsUs: Boolean = false,
    onJoinInvite: () -> Unit = {},
    onIgnoreInvite: () -> Unit = {},
    // G3 8a (groups SPEC §3.1): what the last Join on this screen returned (null: no Join yet). True keeps Join disabled.
    joinOutcome: Boolean? = null,
    // Round 8 MA3 (member announce): the announcer's "Introduce them" / "Introduce members" tap, its "Not now", and the
    // progress of a run that pays.
    onIntroduceMembers: () -> Unit = {},
    onIntroduceNotNow: () -> Unit = {},
    introduceProgress: IntroduceProgress? = null,
    // KI-22: a change of this group started here (Sync keys, Introduce) waits for another change of the same group.
    waitingForOtherChange: Boolean = false,
    modifier: Modifier = Modifier,
    // ZC-022 (KI-25 R6): the one line about the group's admin (the check line or a catch-up line), or none.
    adminNotice: GroupAdminNotice? = null,
) {
    val colors = chatColors()

    if (shouldSecureScreen) {
        SecureScreen()
    }

    when (state) {
        is GroupDetailState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = colors.primary)
            }
        }
        is GroupDetailState.Error -> {
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
        is GroupDetailState.Success -> {
            GroupDetailContent(
                conversation = state.conversation,
                currentUserAddress = state.currentUserAddress,
                groupCreatorIsUs = groupCreatorIsUs,
                isSendingMessage = isSendingMessage,
                sendResult = sendResult,
                onSendResultConsumed = onSendResultConsumed,
                onBackClick = onBackClick,
                onSettingsClick = onSettingsClick,
                onSendMessage = onSendMessage,
                onDraftChange = onDraftChange,
                onSyncKeys = onSyncKeys,
                messageCost = messageCost,
                awaitingJoin = awaitingJoin,
                joinReplyIsPaid = joinReplyIsPaid,
                joinReplyIsRelayOnly = joinReplyIsRelayOnly,
                joinReplyIsFree = joinReplyIsFree,
                joinInviterIsKnown = joinInviterIsKnown,
                onJoinInvite = onJoinInvite,
                onIgnoreInvite = onIgnoreInvite,
                joinOutcome = joinOutcome,
                onIntroduceMembers = onIntroduceMembers,
                onIntroduceNotNow = onIntroduceNotNow,
                introduceProgress = introduceProgress?.takeIf { it.groupId == state.conversation.groupInfo.groupId },
                colors = colors,
                modifier = modifier,
                waitingForOtherChange = waitingForOtherChange,
                adminNotice = adminNotice,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailContent(
    conversation: GroupConversation,
    currentUserAddress: String,
    groupCreatorIsUs: Boolean,
    isSendingMessage: Boolean,
    sendResult: GroupSendResult?,
    onSendResultConsumed: () -> Unit,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSyncKeys: () -> Unit,
    messageCost: GroupMessageCost?,
    // Round 8 D4: nothing may be written or sent in a group whose invite still waits for Join.
    awaitingJoin: Boolean,
    joinReplyIsPaid: Boolean,
    joinReplyIsRelayOnly: Boolean,
    joinReplyIsFree: Boolean,
    joinInviterIsKnown: Boolean,
    onJoinInvite: () -> Unit,
    onIgnoreInvite: () -> Unit,
    joinOutcome: Boolean?,
    onIntroduceMembers: () -> Unit,
    onIntroduceNotNow: () -> Unit,
    introduceProgress: IntroduceProgress?,
    colors: ChatColors,
    modifier: Modifier = Modifier,
    waitingForOtherChange: Boolean = false,
    adminNotice: GroupAdminNotice? = null,
) {
    // P0.5: key the composer on the group IDENTITY, not the draft VALUE. Keying on conversation.draft meant
    // any Success re-emission that re-read the persisted draft (e.g. after a failed send calls
    // loadGroupDetail, or the second emission once blockchain history loads) reset messageText and destroyed
    // text the user had typed since the last debounce save. The outcome LaunchedEffect below still clears the
    // composer on a successful / partial send.
    var messageText by remember(conversation.groupInfo.groupId) {
        mutableStateOf(conversation.draft ?: "")
    }

    // Hoist the message sort out of the LazyColumn content lambda: keyed on conversation.messages so the
    // O(n log n) sort runs only when the message list actually changes, not on every recomposition (every
    // composer keystroke reads messageText in the same scope, which would otherwise re-sort the whole list).
    val sortedMessages = remember(conversation.messages) {
        conversation.messages.sortedWith { a, b -> GroupMessage.compareForOrdering(a, b) }
    }

    // Round 8 P6 (PHONE_TRIAGE F5b): false once the admin removed us, or we left: the composer is off and no cost is shown.
    val inGroup = conversation.groupInfo.isActive

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // P0.5 — armed at send-tap; the composer is cleared only when the send completes and something
    // actually went out (success / partial delivery), never optimistically at tap time — a silent
    // failure used to destroy the typed message.
    var awaitingSendOutcome by remember { mutableStateOf(false) }

    // R5-1 / R6-1 — the outcome effect below is not a composable scope, so the banner reads the resources directly.
    val outcomeResources = LocalContext.current.resources

    // R6-1 — the banner is shown from THIS scope, not from the outcome effect below. That effect is keyed on
    // the outcome and consumes it; consuming it changes the key, which cancelled the effect and with it the
    // showSnackbar() it was suspended in, so no send outcome was ever visible for more than a frame.
    val snackbarScope = rememberCoroutineScope()

    // P0.1 — surface the send/invite outcome as a visible banner (was Log.w + Toast-only), and
    // resolve the deferred composer clear above.
    LaunchedEffect(isSendingMessage, sendResult) {
        if (isSendingMessage) return@LaunchedEffect
        val result = sendResult
        // SF-2 — the event flow is process-wide, so an event from ANOTHER group can appear here.
        // Ignore it entirely: don't consume it (the other group still needs it), don't clear this
        // composer, don't show a snackbar for a send that wasn't ours.
        if (result != null && result.groupId != conversation.groupInfo.groupId) return@LaunchedEffect
        // ZC-420-G: a group-open notice (RelayUnconfirmed with atOpen) is shown, but is never the outcome of a send the
        // composer waits for: it neither ends the wait nor clears the text.
        if (awaitingSendOutcome && !(result is GroupSendResult.RelayUnconfirmed && result.atOpen)) {
            awaitingSendOutcome = false
            val nothingTransmitted =
                result is GroupSendResult.NoActiveRecipients ||
                    result is GroupSendResult.AllFailed ||
                    // R5-1: the cost prompt was declined — nothing was sent, so the text stays put.
                    result is GroupSendResult.CostDeclined ||
                    // R6-3: too long for one group memo — nothing was sent; the user shortens the text.
                    result is GroupSendResult.MessageTooLong ||
                    // Round 8 P6: this phone is no longer in the group — nothing was sent.
                    result is GroupSendResult.NotInGroup ||
                    // ZC-757: a rebuilt group before its new key reached a member — nothing was sent.
                    result is GroupSendResult.NeedsNewKey
            if (!nothingTransmitted) {
                messageText = ""
            }
        }
        if (result != null) {
            // Round 8 D8 (REVIEW_7C F3): built by groupSendBannerText (GroupSendBanner.kt), which names a refusal no
            // balance can fix instead of "check your balance".
            val text =
                groupSendBannerText(
                    result = result,
                    string = outcomeResources::getString,
                    plural = { id, quantity, args -> outcomeResources.getQuantityString(id, quantity, *args.toTypedArray()) },
                )
            snackbarScope.launch { snackbarHostState.showSnackbar(text) }
            onSendResultConsumed()
        }
    }

    // Auto-save draft with debounce. P0.5: paused while a send outcome is pending — the ViewModel
    // owns the draft during a send (it clears it only on success), and a transient composer state
    // must not overwrite a message that may still need to be retried.
    LaunchedEffect(messageText, awaitingSendOutcome) {
        if (awaitingSendOutcome) return@LaunchedEffect
        if (messageText != (conversation.draft ?: "")) {
            kotlinx.coroutines.delay(500L)
            onDraftChange(messageText)
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(conversation.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onSettingsClick)
                    ) {
                        // Group avatar — stored local photo when set (editable by the admin from
                        // Group Settings), else the shared gradient placeholder. Tapping the header
                        // already opens settings, where the admin-gated edit lives.
                        ZchatAvatar(
                            ref = ZchatAvatarRef.Group(conversation.groupInfo.groupId),
                            displayName = conversation.groupInfo.name,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = conversation.groupInfo.name,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = groupMemberCountText(conversation, knownOnly = messageCost?.knownOnly == true),
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
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
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.group_settings_title),
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.background,
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Round 8 D4 (DECISIONS_NIGHT §1): an invite from an address we never chatted with. Nothing was joined for
            // the user and nothing was sent or paid; Join sends the one reply, Ignore deletes the group.
            if (awaitingJoin) {
                GroupInviteRequestBanner(
                    inviter = conversation.groupInfo.creatorAddress,
                    groupName = conversation.groupInfo.name,
                    replyIsPaid = joinReplyIsPaid,
                    replyIsRelayOnly = joinReplyIsRelayOnly,
                    replyIsFree = joinReplyIsFree,
                    inviterIsKnown = joinInviterIsKnown,
                    joinEnabled = joinOutcome != true,
                    onJoin = onJoinInvite,
                    onIgnore = onIgnoreInvite,
                    colors = colors,
                )
            }

            // G3 8a (groups SPEC §3.1, §3.7 with review G): what Join did. It says nothing about the "I joined" reply (a
            // paid reply can still be refused, and that has its own notice); a Join that returned false sent nothing.
            if (joinOutcome != null) {
                Text(
                    text =
                        stringResource(
                            if (joinOutcome) R.string.group_join_sent else R.string.group_join_failed,
                            conversation.groupInfo.name,
                        ),
                    fontSize = 12.sp,
                    color = if (joinOutcome) colors.textSecondary else colors.error,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Round 8 MA2 (member announce, design §8): the one-time notice the first time the creator shares the list.
            if (inGroup && conversation.rosterSharedNotice) {
                Text(
                    text = stringResource(R.string.group_roster_now_shared),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // KI-22: this screen's change of the group waits for another change of the same group.
            if (waitingForOtherChange) {
                Text(
                    text = stringResource(R.string.group_waiting_for_other_change),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Round 8 MA3 (member announce, design §6.2, §4.1, §9): the announcer's introduce banner or button.
            conversation.introduce?.takeIf { inGroup }?.let { introduce ->
                IntroduceBanner(
                    introduce = introduce,
                    progress = introduceProgress,
                    onIntroduce = onIntroduceMembers,
                    onNotNow = onIntroduceNotNow,
                    colors = colors,
                )
            }

            // ZC-022 (KI-25 R6): this phone cannot confirm the group's admin, or what is not caught up yet. It cannot be
            // hidden; the composer, sending and the member list work as before.
            adminNotice?.let { notice -> GroupAdminNoticeBlock(notice = notice, onCheck = onSettingsClick, colors = colors) }

            // ZC-757 (G1, G6): a group this phone rebuilt from its history, before its new key reached a member.
            when (RebuiltGroupKey.stage(conversation.groupInfo)) {
                RebuiltGroupKey.Stage.NEEDS_KEY -> R.string.group_rebuilt_needs_key
                RebuiltGroupKey.Stage.NO_READER -> R.string.group_rebuilt_no_reader
                RebuiltGroupKey.Stage.NONE -> null
            }?.let { notice ->
                Text(
                    text = stringResource(notice),
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.warning.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            // Round 8 P6 (PHONE_TRIAGE F5b): say why nothing can be written here.
            if (!inGroup) {
                Text(
                    text =
                        stringResource(
                            if (conversation.groupInfo.removedByAdmin) R.string.group_you_were_removed else R.string.group_you_left,
                        ),
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.warning.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            // Messages list. QA2 Q2-61 (W-137): no empty-state invitation to write while the first message is being sent.
            if (conversation.messages.isEmpty() && !isSendingMessage) {
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
                        // ZC-922 (W-137 B-4): who added us, also before the first message.
                        GroupAddedYouNote(conversation, groupCreatorIsUs, colors)
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(colors.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.group_no_messages),
                            fontSize = 17.sp,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.group_send_first_message),
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                        // #10 — persistent explanation when the group has invitees who haven't accepted
                        // yet (total > active). The send path already shows a transient snackbar
                        // ("Waiting for members to accept…"), but it auto-dismisses, so a creator whose
                        // first message reached nobody saw an empty thread with no lasting reason. This
                        // durable hint keeps the "why nothing sends yet" visible until someone joins.
                        // Count only INVITED members — NOT members.size − active, which would also count
                        // REMOVED (LEFT) members and falsely claim invites are pending after a kick.
                        val pendingInvites =
                            conversation.members.count { it.status == MemberStatus.INVITED }
                        if (pendingInvites > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            // R6-1: nothing is queued for an invitee — a message is not sent to them and
                            // is not re-sent when they join — so the hint no longer promises "will send".
                            Text(
                                text =
                                    LocalContext.current.resources.getQuantityString(
                                        R.plurals.group_waiting_for_invited,
                                        pendingInvites,
                                        pendingInvites,
                                    ),
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 40.dp)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ZC-922 (W-137 B-4): "X added you to this group." at the group's start.
                    // Only when it shows: an empty item would still take the list's spacing.
                    if (conversation.groupInfo.creatorAddress.isNotBlank() && !groupCreatorIsUs) {
                        item(key = "group-note-added-you") {
                            GroupAddedYouNote(conversation, groupCreatorIsUs, colors)
                        }
                    }
                    items(
                        items = sortedMessages,
                        key = { it.id }
                    ) { message ->
                        GroupMessageBubble(
                            message = message,
                            isOwnMessage = message.senderAddress == currentUserAddress,
                            onSyncKeys = onSyncKeys,
                            colors = colors,
                            senderNotInList = message.senderAddress in conversation.sendersNotInList,
                            senderRemoved = message.senderAddress in conversation.sendersRemoved,
                            senderName = conversation.senderNames[message.senderAddress],
                        )
                    }
                }
            }

            // Bridge 3: members still on ZChat 2.12.4 or older don't see group messages until they update.
            Text(
                text = stringResource(R.string.upgrade_group_line),
                // QA2 Q2-65 size / Q2-68 (W-137): 12.sp, as the cost lines.
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )

            // R5-1 (round 4 report §5B): what THIS message costs on-chain right now. The old line counted
            // every active member, including the ones reachable free over the relays, and showed no total —
            // so it never stated the price of the message about to be sent. [messageCost] comes from the send
            // path's own recipient list and per-memo sizer, so the line and the spend cannot disagree.
            //
            // It is recomputed whenever the group detail reloads, so a change in relay reachability can leave
            // it briefly stale. That can never cause an unquoted spend: the send quotes again from scratch and
            // the per-group consent prompt is what actually authorises the money.
            val resources = LocalContext.current.resources
            // Round 8 P6: no cost line in a group this phone is no longer in.
            // QA2 Q2-61 (W-137): while a message is being sent the line says so, and that the text stays until sent.
            // QA2 U5 review F4 (ZC-1650): isSendingMessage is one flow for all groups, so the line says so only over a
            // composer that holds the text; a send in another group does not put it over this empty box.
            val formatLocale = rememberDesiredFormatLocale()
            if (inGroup && ((isSendingMessage && messageText.isNotBlank()) || (messageCost?.recipients ?: 0) > 0)) {
                Text(
                    // Round 8 P7 (PHONE_TRIAGE F4): built by groupMessageCostText (GroupReachText.kt), which on a
                    // member's phone says the message goes only to the members this phone knows.
                    text =
                        if ((isSendingMessage && messageText.isNotBlank()) || messageCost == null) {
                            stringResource(R.string.group_composer_sending)
                        } else {
                            groupMessageCostText(
                                cost = messageCost,
                                string = resources::getString,
                                plural = { id, quantity, args -> resources.getQuantityString(id, quantity, *args.toTypedArray()) },
                                locale = formatLocale,
                            )
                        },
                    // QA2 Q2-68 (W-137): 12.sp (was 11.sp).
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
            // Round 8 P5 (PHONE_TRIAGE F5a): members the message is not sent to (and not paid for) because they can't
            // read it yet: owed the current key, with no secure session to send it under.
            if (inGroup && messageCost != null && messageCost.cannotRead > 0) {
                Text(
                    text =
                        resources.getQuantityString(
                            R.plurals.group_members_cannot_read,
                            messageCost.cannotRead,
                            messageCost.cannotRead,
                        ),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }

            // Message input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text(stringResource(R.string.chat_composer_placeholder)) },
                    // Round 8 D4: disabled until Join. Round 8 P6: and in a group this phone is no longer in.
                    enabled = !awaitingJoin && inGroup,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.background,
                        focusedContainerColor = colors.background,
                        unfocusedContainerColor = colors.background,
                        cursorColor = colors.primary
                    ),
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (messageText.isNotBlank() && !isSendingMessage && !awaitingJoin && inGroup)
                                colors.primary
                            else
                                colors.primary.copy(alpha = 0.5f)
                        )
                        .clickable(
                            enabled = messageText.isNotBlank() && !isSendingMessage && !awaitingJoin && inGroup
                        ) {
                            // P0.5 — do NOT clear the composer here: it's cleared only after the
                            // send reports an outcome that transmitted something (see the outcome
                            // LaunchedEffect above), so a failed send can't destroy the message.
                            awaitingSendOutcome = true
                            onSendMessage(messageText)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSendingMessage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_composer_send),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Round 8 MA3 (member announce, MEMBER_ANNOUNCE_DESIGN.md v2 §6.2, §4.1, §6.3, §9) — on the group's creator: the
 * banner when members don't know about each other yet and some can only be told on-chain ("Introduce them" / "Not
 * now"), or, on a group this phone rebuilt, the "introduce members again" button; the "couldn't reach yet" line; and
 * the progress of a run that pays. Nothing is paid by this banner: the tap asks with the cost first.
 */
@Composable
private fun IntroduceBanner(
    introduce: GroupIntroduceInfo,
    progress: IntroduceProgress?,
    onIntroduce: () -> Unit,
    onNotNow: () -> Unit,
    colors: ChatColors,
) {
    val lines = introduce.showBanner || introduce.rebuilt || introduce.unreachablePairs > 0 || progress != null
    if (!lines) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (progress != null) {
            Text(
                text =
                    stringResource(
                        R.string.group_introduce_progress,
                        progress.done,
                        progress.total,
                        introduceMinutesLeft(progress.total - progress.done),
                    ),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        } else if (introduce.rebuilt) {
            androidx.compose.material3.TextButton(onClick = onIntroduce) {
                Text(stringResource(R.string.group_rebuilt_introduce), fontSize = 13.sp, color = colors.primary)
            }
        } else if (introduce.showBanner) {
            Text(stringResource(R.string.group_introduce_banner), fontSize = 13.sp, color = colors.textPrimary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onNotNow) {
                    Text(stringResource(R.string.group_introduce_not_now), color = colors.textSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onIntroduce) {
                    Text(stringResource(R.string.group_introduce_action), color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (introduce.unreachablePairs > 0 && progress == null) {
            Text(stringResource(R.string.group_introduce_unreachable), fontSize = 12.sp, color = colors.textSecondary)
        }
    }
}

/**
 * ZC-022 (KI-25 R6, R9) — the one line a group chat shows about its admin: that this phone cannot confirm the admin,
 * with "Check" (which opens Group settings, where the card is), or one of the two lines that say what is not caught up
 * after a confirmation and what to ask the admin. Nothing is sent or paid by this block.
 */
@Composable
private fun GroupAdminNoticeBlock(
    notice: GroupAdminNotice,
    onCheck: () -> Unit,
    colors: ChatColors,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text =
                stringResource(
                    when (notice) {
                        GroupAdminNotice.CHECK_LINE -> R.string.group_admin_check_line
                        GroupAdminNotice.CATCHUP_KEY -> R.string.group_admin_catchup_key
                        GroupAdminNotice.CATCHUP_LIST -> R.string.group_admin_catchup_list
                    },
                ),
            fontSize = 13.sp,
            color = colors.textPrimary,
        )
        if (notice == GroupAdminNotice.CHECK_LINE) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onCheck) {
                    Text(stringResource(R.string.group_admin_check_action), color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Round 8 MA3 — the minutes [remaining] paid announces take: one transaction each, each waiting for the previous one's
 * change to confirm (about 200 s each at 75 s blocks, design §6.4: about 30 minutes for 9). An estimate, at least 1.
 * ZC-114: scaled by the spacing of the blocks at the last tip ([blockSeconds]; a third from NU7 on).
 */
internal fun introduceMinutesLeft(
    remaining: Int,
    blockSeconds: Long = ChainTiming.blockSeconds(ChainTiming.lastTip),
): Int {
    val seconds = remaining.coerceAtLeast(0) * PAID_SEND_SECONDS_EACH * blockSeconds / ChainTiming.PRE_NU7_BLOCK_SECONDS
    return ((seconds + 59) / 60).toInt().coerceAtLeast(1)
}

/** About how long one paid announce takes at 75 s blocks (three blocks or so), in seconds. */
private const val PAID_SEND_SECONDS_EACH = 200L

/**
 * G3 fixup (T4b cross review, G-G3-2) — Join's result on the group screen after the needs-join record was read again:
 * a "joined" [outcome] is cleared once the invite waits for Join again ([awaitingJoin]: the reply went back to waiting,
 * or a new invite arrived), so the screen no longer says "You joined" and Join can be tapped again. Every other value
 * is kept (a failed Join keeps its notice; a later false never replaces a true while nothing waits).
 */
internal fun joinOutcomeAfterRecordRead(
    outcome: Boolean?,
    awaitingJoin: Boolean,
): Boolean? = if (awaitingJoin && outcome == true) null else outcome

/**
 * ZC-776 — Join is taken once per read of the needs-join record. The group screen builds one gate each time it reads
 * the record; the first Join callback on that read passes, every later one on the same read does nothing. A released
 * relay-only Join keeps no outcome and removes the record, so a second callback before the screen had read the store
 * again found no record and kept "Couldn't join … Nothing was sent" although the reply had gone out. The next read of
 * the record (after Join, or when the reply went back to waiting) builds a new gate, so Join can be tapped again.
 * Callbacks run on the main thread only.
 */
internal class JoinTapGate {
    private var taken = false

    /** True for the first Join callback on this read of the record, false for every later one. */
    fun firstTap(): Boolean = !taken.also { taken = true }
}

/**
 * Round 8 D4 (DECISIONS_NIGHT §1) — the banner of a group invite from an address the user never chatted with. The
 * price is the floor of one on-chain reply ("at least"); the reply goes free over NOSTR when the inviter's relay key
 * is known, which the text allows ("can cost").
 *
 * Round 8 P1 (PHONE_TRIAGE F3), ZC-288 — with [replyIsPaid], the invite waits because its reply could not go over the
 * relays: Join tries them once more first, the reply costs at least that price only if they still can't; nothing paid.
 * ZC-517: [replyIsRelayOnly] means the memo cannot fit on-chain, so no price is offered or formatted.
 */
@Composable
private fun GroupInviteRequestBanner(
    inviter: String,
    groupName: String,
    replyIsPaid: Boolean,
    replyIsRelayOnly: Boolean,
    replyIsFree: Boolean,
    inviterIsKnown: Boolean,
    joinEnabled: Boolean,
    onJoin: () -> Unit,
    onIgnore: () -> Unit,
    colors: ChatColors,
) {
    val shortInviter = if (inviter.length > 18) inviter.take(10) + "…" + inviter.takeLast(8) else inviter
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.warning.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text =
                if (replyIsRelayOnly) {
                    // ZC-517: the failed-try sentence only after a relay try failed; before one, why it is relay-only.
                    stringResource(
                        if (replyIsPaid) R.string.group_invite_join_relay_only else R.string.group_invite_join_relay_only_untried,
                        shortInviter,
                        groupName,
                    )
                } else if (replyIsFree) {
                    // ZC-1224: an accepted Open chat with the creator; Join goes free over it, so no price.
                    stringResource(R.string.group_invite_join_known_free, shortInviter, groupName)
                } else if (inviterIsKnown && !replyIsPaid) {
                    // ZC-922 (W-137 B-4): a known contact's invite waits for Join too. Not the stranger text, and no
                    // "Open chat" claim (a Tunnel or Shielded contact); the paid branch keeps its own quote.
                    stringResource(R.string.group_invite_join_known, shortInviter, groupName)
                } else {
                    stringResource(
                        if (replyIsPaid) {
                            R.string.group_invite_join_known_paid
                        } else {
                            R.string.group_invite_request_banner
                        },
                        shortInviter,
                        groupName,
                        PaidActionCopy.zec(
                            co.electriccoin.zcash.ui.screen.chat.model
                                .handshakeSpendQuote(1),
                            rememberDesiredFormatLocale(),
                        ),
                    )
                },
            fontSize = 13.sp,
            color = colors.textPrimary,
        )
        // Round 8 MA3 (member announce, design §9): what joining shares, and how a message to the group is paid.
        Text(
            text = stringResource(R.string.group_join_shares_address),
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            androidx.compose.material3.TextButton(onClick = onIgnore) {
                Text(stringResource(R.string.group_invite_request_ignore), color = colors.textSecondary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.TextButton(onClick = onJoin, enabled = joinEnabled) {
                Text(stringResource(R.string.group_invite_request_join), color = colors.warning, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * ZC-922 (Q2-57b, W-137 B-4) — a centred system pill "X added you to this group." for a group whose creator is not us
 * (compact invites set creatorAddress to the inviter). The name is the chat header's (senderNames), else the short
 * address. Nothing is stored; groups already stored show it too.
 */
@Composable
private fun GroupAddedYouNote(
    conversation: GroupConversation,
    creatorIsUs: Boolean,
    colors: ChatColors,
) {
    val creator = conversation.groupInfo.creatorAddress
    if (creator.isBlank() || creatorIsUs) return
    val name = conversation.senderNames[creator] ?: PeerName.shortAddress(creator)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.group_note_added_you, name),
            fontSize = 12.sp,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * R6-3 (round 5 report §5 item 6) — the member count under the group's name, in the phone's language. Same
 * rule as [GroupConversation.memberCountLabel]: every member not removed, plus how many have joined while
 * some have not. Round 8 P7: "known members" on a phone that is not the group's admin ([groupMemberCountLabel]).
 */
@Composable
private fun groupMemberCountText(
    conversation: GroupConversation,
    knownOnly: Boolean,
): String {
    val resources = LocalContext.current.resources
    return groupMemberCountLabel(conversation, knownOnly) { id, quantity, args ->
        resources.getQuantityString(id, quantity, *args.toTypedArray())
    }
}

@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    isOwnMessage: Boolean,
    onSyncKeys: () -> Unit,
    colors: ChatColors,
    // Round 8 MA2 (design §5.2): the sender is not in this phone's member list (the creator shares it), so a message to
    // the group does not reach them.
    senderNotInList: Boolean = false,
    // Round 10b R10B-2 (F-R9B-2): the creator removed the sender, so a message to the group does not reach them.
    senderRemoved: Boolean = false,
    // GROUP-LABEL: the name the 1-to-1 chat header uses for the sender (GroupConversation.senderNames), null = none.
    senderName: String? = null,
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm")
    }
    val isUndecryptable = message.isUndecryptable

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isOwnMessage) colors.outgoingBubble else colors.incomingBubble
                )
                // Tap an undecryptable bubble to reload group state and re-derive missing keys.
                .then(
                    if (isUndecryptable) Modifier.clickable(onClick = onSyncKeys) else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Sender name (for other people's messages)
            if (!isOwnMessage) {
                Text(
                    text = senderName ?: PeerName.shortAddress(message.senderAddress),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (senderRemoved) {
                    Text(
                        text = stringResource(R.string.group_sender_removed),
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                } else if (senderNotInList) {
                    Text(
                        text = stringResource(R.string.group_sender_not_in_list),
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Own message renders ON the outgoing bubble, which is dark-teal in EVERY theme — so its
            // foreground must be LIGHT in every theme. textOnAccent is light only in LIGHT themes (it is
            // near-black 0xFF080B12 in Nightwire dark = the on-bright-accent color, NOT an on-dark-bubble
            // color); textPrimary is light only in DARK themes. Pick per theme (matches ChatDetailView #249).
            val onBubble = if (colors.isLight) colors.textOnAccent else colors.textPrimary

            // Message content
            Text(
                text = message.displayText,
                color = if (isOwnMessage) onBubble else colors.textPrimary,
                fontSize = 15.sp
            )

            // Recovery action for messages this device can't decrypt (missing group key).
            if (isUndecryptable) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.group_tap_to_sync_keys),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary
                )
            }

            // Timestamp and status. QA2 Q2-68 (W-137): sender, notes, time and status glyph at 12.sp, full alpha (were
            // 10/11.sp, time and status at alpha 0.8).
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.timestamp
                        .atZone(ZoneId.systemDefault())
                        .format(timeFormatter),
                    fontSize = 12.sp,
                    color = if (isOwnMessage) onBubble else colors.textSecondary
                )

                if (isOwnMessage) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            message.isFailed -> "!"
                            message.isPending -> "⏱"
                            else -> "✓"
                        },
                        fontSize = 12.sp,
                        color = when {
                            message.isFailed -> colors.error
                            else -> onBubble
                        }
                    )
                }
            }
        }
    }
}
