package co.electriccoin.zcash.ui.screen.chat.view

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.screen.chat.crypto.QsAlert
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentIds
import co.electriccoin.zcash.ui.screen.chat.model.CallsLockedBanner
import co.electriccoin.zcash.ui.screen.chat.model.ChatCallAffordance
import co.electriccoin.zcash.ui.screen.chat.model.ChatDetailState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ChatRequestBanner
import co.electriccoin.zcash.ui.screen.chat.model.ChatStatusLines
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ExtraSecurityMenuState
import co.electriccoin.zcash.ui.screen.chat.model.MemoTemplate
import co.electriccoin.zcash.ui.screen.chat.model.MessageAuthentication
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.PaymentBubble
import co.electriccoin.zcash.ui.screen.chat.model.PaymentQuote
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.ReconnectMenu
import co.electriccoin.zcash.ui.screen.chat.model.SafetyNumberButton
import co.electriccoin.zcash.ui.screen.chat.model.SafetyNumberButtons
import co.electriccoin.zcash.ui.screen.chat.model.SendCostLabel
import co.electriccoin.zcash.ui.screen.chat.model.ShieldedReminderUi
import co.electriccoin.zcash.ui.screen.chat.model.SystemNoteText
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockInfo
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockType
import co.electriccoin.zcash.ui.screen.chat.model.TunnelSetupChip
import co.electriccoin.zcash.ui.screen.chat.model.UnknownReason
import co.electriccoin.zcash.ui.screen.chat.model.UnverifiedChip
import co.electriccoin.zcash.ui.screen.chat.model.UpgradeReminders
import co.electriccoin.zcash.ui.screen.chat.model.WelcomeZec
import co.electriccoin.zcash.ui.screen.chat.model.extraSecurityMenuState
import co.electriccoin.zcash.ui.screen.chat.model.fulfilMemoText
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote
import co.electriccoin.zcash.ui.screen.chat.model.paymentRequestSendCost
import co.electriccoin.zcash.ui.screen.chat.model.zecDisplay
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote
import co.electriccoin.zcash.ui.screen.chat.util.MAX_MESSAGE_INPUT_CHARS
import co.electriccoin.zcash.ui.screen.chat.util.capDraft
import co.electriccoin.zcash.ui.screen.chat.util.draftNeedsSaving
import co.electriccoin.zcash.ui.screen.chat.util.draftWasCut
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.mergedHandOffDraft
import co.electriccoin.zcash.ui.screen.onboarding.ZchatTeamConstants
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

// ZCHAT Brand Colors - fallback defaults; use chatColors() for theme-awareness
private val ZchatCyan = Color(0xFF00D9FF)
private val ZchatGreen = Color(0xFF00E676)
private val ZchatNavy = Color(0xFF0D1B2A)
private val ZchatNavyLight = Color(0xFF1B2838)
private val ZchatTeal = Color(0xFF00838F)

// Note: chatColors() function is now defined in ChatThemeColors.kt and shared across all chat views

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailView(
    state: ChatDetailState,
    onBackClick: () -> Unit,
    onSendMessage: (message: String, amountZatoshi: Long) -> Boolean,
    onSendReply: (message: String, replyToId: String, replyPreview: String, amountZatoshi: Long) -> Boolean =
        { msg, _, _, amt -> onSendMessage(msg, amt) },
    // Round 10a F-R9P-1: the composer's cost row, sized by the send's own rule (ChatViewModel.composerMessageCost).
    composerMessageCost: (text: String, replyToId: String?, replyPreview: String, amountZatoshi: Long) -> Zatoshi,
    isKeyChanged: Boolean = false,
    onDismissKeyChanged: () -> Unit = {},
    // ZC-250: a send in this chat was refused because the contact's key changed; the key-changed banner stays expanded
    // with the refusal notice until it is collapsed (onAcknowledgeKeyChangeRefusal) or the key is accepted.
    keyChangeRefused: Boolean = false,
    onAcknowledgeKeyChangeRefusal: () -> Unit = {},
    // ZC-545: the contact's refused key that waits for the user's decision (null: none is remembered), and the two
    // decisions of its dialog. Accept passes both keys of the snapshot the dialog showed.
    refusedKeyOffer: co.electriccoin.zcash.ui.screen.chat.model.RefusedKeyOfferView? = null,
    onAcceptRefusedKey: (refusedKey: String, ourKey: String) -> Unit = { _, _ -> },
    onDismissRefusedKey: (refusedKey: String) -> Unit = { _ -> },
    // R2-2 (a): a stranger's first KEX waits for the user to accept this chat; nothing is paid before.
    awaitingChatAccept: Boolean = false,
    // ZC-822: the waiting request's address is a saved contact (the banner's words only).
    chatRequestFromSavedContact: Boolean = false,
    // Round 10b R10B-1 (F-R9C-5): what Accept can pay, counted as the Allow question counts it (ChatViewModel.chatRequestAcceptTransactions).
    chatRequestTransactions: Int = 1,
    onAcceptChatRequest: () -> Unit = {},
    // ZC-1363: that Accept was tapped and waits for the funds (ChatViewModel.acceptWaitingForFunds).
    acceptWaitsForFunds: Boolean = false,
    // ZC-1017: a paid key exchange held in this Open chat, offered as a Tunnel chat (Accept and pay / Keep Open).
    openChatTunnelOffer: co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage =
        co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage.NONE,
    onAcceptTunnelOffer: () -> Unit = {},
    onKeepChatOpen: () -> Unit = {},
    // F8 (ZC-112): a setup message of this chat never reached the chain (null: none). Retry hands back the marker of the
    // failed attempt this notice was drawn from; the price is asked before anything is paid.
    handshakeNotSent: co.electriccoin.zcash.ui.common.sendattempt.HandshakeOffer? = null,
    onRetryHandshake: (Long) -> Unit = {},
    showDecryptRecovery: Boolean = false,
    showRotationReminder: Boolean = false,
    onRotateKeyCta: () -> Unit = {},
    onDismissRotationReminder: () -> Unit = {},
    // Bridge 3: the Shielded-chat reminder (hidden = the "May need update" chip) and its buttons.
    shieldedReminderHidden: Boolean = false,
    // ZC-1465: the contact already wrote back over the relays (ChatViewModel.wasHeardFromOverRelays).
    heardFromPeer: Boolean = false,
    // ZC-1464: the contact declined our chat request (ChatViewModel.isRequestDeclinedBy); the composer is closed.
    requestDeclined: Boolean = false,
    // ZC-1270: the contact's newest relay capability tag (UpgradeReminders.contactIsProven213); null when none was seen.
    peerCapIronwood: Boolean? = null,
    onHideShieldedReminder: () -> Unit = {},
    onShowShieldedReminder: () -> Unit = {},
    onAskThemToUpdate: () -> Unit = {},
    onChatFreeInstead: () -> Unit = {},
    safetyNumber: String? = null,
    isVerified: Boolean = false,
    // ZC-634 (5.5 item 8): the key is only a claim and a ZBOOT was held back (the held-connection banner).
    keyClaimHeld: Boolean = false,
    // W-72 (SPEC_F R8): the key carries a pre-update mark (the Unverified banner says "Not confirmed").
    keyNotConfirmed: Boolean = false,
    onMarkVerified: () -> Unit = {},
    quantumShieldStatus: String = "NONE", // "NONE", "PENDING", "ACTIVE"
    // D3-3: the other phone's claim about this chat's shield (QsAlert name: NONE, KEYS_DIFFER, DOWNGRADED, CONFIRMED).
    quantumShieldAlert: String = "NONE",
    onInitiateQuantumShield: () -> Unit = {},
    onResetQuantumShield: () -> Unit = {},
    // ZC-1476 / ZC-321: the ⋮ entry's turn off (ON) and cancel (PENDING) open AndroidChat's turn-off confirm.
    onAskTurnOffQuantumShield: () -> Unit = {},
    onSendImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendViewOnceImage: () -> Unit = {},
    onMarkFileViewed: (fileHash: String) -> Unit = {},
    // Voice messages
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    isRecordingViewOnce: Boolean = false,
    onMicTap: () -> Unit = {},
    onMicLongPress: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    // Per-chat conversation mode (Vault / Tunnel / Open)
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode =
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
    onPickConversationMode: () -> Unit = {},
    onPlaceCall: () -> Unit = {},
    onPlaceVideoCall: () -> Unit = {},
    // #256 — reconnect / re-send our NOSTR identity to recover a one-way handshake (calls/Open stuck).
    onReconnect: () -> Unit = {},
    onResetEncryption: () -> Unit = {},
    // B17 — disappearing-messages TTL (seconds; 0 = off) + setter (synced to the peer).
    disappearingTtlSeconds: Long = 0L,
    onSetDisappearingTtl: (Long) -> Unit = {},
    uploadProgress: Float? = null,
    fileDownloadProgress: Map<String, Float> = emptyMap(),
    fileDownloadFailures: Set<String> = emptySet(),
    onRetryDownload: (zfileContent: String, peerAddress: String) -> Unit = { _, _ -> },
    onDeleteMessage: (String) -> Unit,
    onRetryMessage: (messageId: String) -> Unit = { },
    // D-6: every payment confirm hands on the exact quote it showed.
    onSendPayment: (amountZec: Double, memo: String, quote: ChatSendQuote) -> Boolean,
    onSendReaction: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    // Time-lock callbacks
    onSendScheduledMessage: (message: String, unlockTimestamp: Long) -> Unit = { _, _ -> },
    onSendBlockLockedMessage: (message: String, unlockHeight: Long) -> Unit = { _, _ -> },
    onSendPaymentLockedMessage: (message: String, requiredZatoshi: Long) -> Unit = { _, _ -> },
    onSendConditionalMessage: (message: String, answer: String, hint: String) -> Unit = { _, _, _ -> },
    onUnlockPaymentMessage: (txId: String, senderAddress: String, amount: Long) -> Unit = { _, _, _ -> },
    onUnlockConditionalMessage: (txId: String, senderAddress: String, answer: String, answerHash: String) -> Boolean = { _, _, _, _ -> false },
    // Payment request callbacks
    // ZC-250: true when the request was taken (sent, or held behind its cost question); false keeps the dialog's input.
    onSendPaymentRequest: (amountZatoshi: Long, reason: String) -> Boolean = { _, _ -> false },
    onFulfillPaymentRequest: (amountZatoshi: Long, requestId: String, quote: ChatSendQuote) -> Unit = { _, _, _ -> },
    // D-6 (fix-all 2.13.0, owner W-10): the exact price of a payment (ChatViewModel.quotePayment); no answer, no Pay.
    onQuotePayment: suspend (amountZatoshi: Long, memoText: String, rawMemo: Boolean) -> PaymentQuote = { _, _, _ ->
        PaymentQuote.Unavailable()
    },
    // Nickname callback
    onNicknameChange: (address: String, nickname: String) -> Unit = { _, _ -> },
    currentBlockHeight: Long? = null,
    // QA2 Q2-98: the wallet's server connection is down (Synchronizer.Status.DISCONNECTED); the header says Offline.
    walletOffline: Boolean = false,
    // QA2 Q2-40: Ironwood that is still confirming would pay [need] (ShortfallReason.classify is StillConfirming).
    fundsStillConfirming: (need: Zatoshi) -> Boolean = { false },
    // Draft callback
    onDraftChange: (String) -> Unit = { },
    // R3-2 (2): text a cancelled first-message cost prompt hands back to the input; taken once.
    restoredTypedText: String? = null,
    onRestoredTypedTextShown: () -> Unit = { },
    // Mute callback
    onMuteToggle: () -> Unit = { },
    // Welcome ZEC suggestion (D-6: Send hands on the confirm's exact quote)
    onSendWelcomeZec: ((quote: ChatSendQuote) -> Unit)? = null,
    showWelcomeZecSuggestion: Boolean = false,
    modifier: Modifier = Modifier
) {
    // SECURITY (privacy): the open conversation is the most sensitive screen (plaintext messages,
    // attachments, view-once media) — block screenshots / screen-recording / app-switcher thumbnail
    // while foregrounded.
    if (shouldSecureScreen) {
        SecureScreen()
    }
    when (state) {
        is ChatDetailState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is ChatDetailState.Success -> {
            ChatDetailContent(
                conversation = state.conversation,
                balance = state.balance,
                peerSendableBalance = state.peerSendableBalance,
                zecPriceUsd = state.zecPriceUsd,
                privacyStatus = state.privacyStatus,
                isKeyChanged = isKeyChanged,
                onDismissKeyChanged = onDismissKeyChanged,
                keyChangeRefused = keyChangeRefused,
                onAcknowledgeKeyChangeRefusal = onAcknowledgeKeyChangeRefusal,
                refusedKeyOffer = refusedKeyOffer,
                onAcceptRefusedKey = onAcceptRefusedKey,
                onDismissRefusedKey = onDismissRefusedKey,
                awaitingChatAccept = awaitingChatAccept,
                chatRequestFromSavedContact = chatRequestFromSavedContact,
                chatRequestTransactions = chatRequestTransactions,
                onAcceptChatRequest = onAcceptChatRequest,
                acceptWaitsForFunds = acceptWaitsForFunds,
                openChatTunnelOffer = openChatTunnelOffer,
                onAcceptTunnelOffer = onAcceptTunnelOffer,
                onKeepChatOpen = onKeepChatOpen,
                handshakeNotSent = handshakeNotSent,
                onRetryHandshake = onRetryHandshake,
                showDecryptRecovery = showDecryptRecovery,
                showRotationReminder = showRotationReminder,
                onRotateKeyCta = onRotateKeyCta,
                onDismissRotationReminder = onDismissRotationReminder,
                shieldedReminderHidden = shieldedReminderHidden,
                heardFromPeer = heardFromPeer,
                requestDeclined = requestDeclined,
                peerCapIronwood = peerCapIronwood,
                onHideShieldedReminder = onHideShieldedReminder,
                onShowShieldedReminder = onShowShieldedReminder,
                onAskThemToUpdate = onAskThemToUpdate,
                onChatFreeInstead = onChatFreeInstead,
                safetyNumber = safetyNumber,
                isVerified = isVerified,
                keyClaimHeld = keyClaimHeld,
                keyNotConfirmed = keyNotConfirmed,
                onMarkVerified = onMarkVerified,
                quantumShieldStatus = quantumShieldStatus,
                quantumShieldAlert = quantumShieldAlert,
                onInitiateQuantumShield = onInitiateQuantumShield,
                onResetQuantumShield = onResetQuantumShield,
                onAskTurnOffQuantumShield = onAskTurnOffQuantumShield,
                onSendImage = onSendImage,
                onTakePhoto = onTakePhoto,
                onSendFile = onSendFile,
                onSendViewOnceImage = onSendViewOnceImage,
                onMarkFileViewed = onMarkFileViewed,
                isRecording = isRecording,
                recordingSeconds = recordingSeconds,
                isRecordingViewOnce = isRecordingViewOnce,
                onMicTap = onMicTap,
                onMicLongPress = onMicLongPress,
                onSendRecording = onSendRecording,
                onCancelRecording = onCancelRecording,
                conversationMode = conversationMode,
                onPickConversationMode = onPickConversationMode,
                onPlaceCall = onPlaceCall,
                onPlaceVideoCall = onPlaceVideoCall,
                onReconnect = onReconnect,
                disappearingTtlSeconds = disappearingTtlSeconds,
                onSetDisappearingTtl = onSetDisappearingTtl,
                onResetEncryption = onResetEncryption,
                uploadProgress = uploadProgress,
                fileDownloadProgress = fileDownloadProgress,
                fileDownloadFailures = fileDownloadFailures,
                onRetryDownload = onRetryDownload,
                onBackClick = onBackClick,
                onSendMessage = { msg, amt -> onSendMessage(msg, amt) },
                onSendReply = { msg, replyToId, replyPreview, amt -> onSendReply(msg, replyToId, replyPreview, amt) },
                composerMessageCost = composerMessageCost,
                onDeleteMessage = onDeleteMessage,
                onRetryMessage = onRetryMessage,
                onSendPayment = onSendPayment,
                onSendReaction = onSendReaction,
                onSendScheduledMessage = onSendScheduledMessage,
                onSendBlockLockedMessage = onSendBlockLockedMessage,
                onSendPaymentLockedMessage = onSendPaymentLockedMessage,
                onSendConditionalMessage = onSendConditionalMessage,
                onSendPaymentRequest = onSendPaymentRequest,
                onFulfillPaymentRequest = onFulfillPaymentRequest,
                onQuotePayment = onQuotePayment,
                onNicknameChange = onNicknameChange,
                currentBlockHeight = currentBlockHeight,
                walletOffline = walletOffline,
                fundsStillConfirming = fundsStillConfirming,
                onDraftChange = onDraftChange,
                restoredTypedText = restoredTypedText,
                onRestoredTypedTextShown = onRestoredTypedTextShown,
                onMuteToggle = onMuteToggle,
                onSendWelcomeZec = onSendWelcomeZec,
                showWelcomeZecSuggestion = showWelcomeZecSuggestion,
                modifier = modifier
            )
        }
        is ChatDetailState.Error -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = state.message, color = chatColors().error)
                TextButton(onClick = onBackClick) { Text(stringResource(R.string.back_navigation)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailContent(
    conversation: Conversation,
    balance: Zatoshi,
    zecPriceUsd: Double?,
    privacyStatus: PrivacyStatus,
    // C-5: the Payment dialog's budget; defaults to [balance] for callers that do not know it.
    peerSendableBalance: Zatoshi = balance,
    isKeyChanged: Boolean = false,
    onDismissKeyChanged: () -> Unit = {},
    // ZC-250: a send in this chat was refused because the contact's key changed; the key-changed banner stays expanded
    // with the refusal notice until it is collapsed (onAcknowledgeKeyChangeRefusal) or the key is accepted.
    keyChangeRefused: Boolean = false,
    onAcknowledgeKeyChangeRefusal: () -> Unit = {},
    // ZC-545: the contact's refused key that waits for the user's decision (null: none is remembered), and the two
    // decisions of its dialog. Accept passes both keys of the snapshot the dialog showed.
    refusedKeyOffer: co.electriccoin.zcash.ui.screen.chat.model.RefusedKeyOfferView? = null,
    onAcceptRefusedKey: (refusedKey: String, ourKey: String) -> Unit = { _, _ -> },
    onDismissRefusedKey: (refusedKey: String) -> Unit = { _ -> },
    // R2-2 (a): a stranger's first KEX waits for the user to accept this chat; nothing is paid before.
    awaitingChatAccept: Boolean = false,
    // ZC-822: the waiting request's address is a saved contact (the banner's words only).
    chatRequestFromSavedContact: Boolean = false,
    // Round 10b R10B-1 (F-R9C-5): what Accept can pay, counted as the Allow question counts it (ChatViewModel.chatRequestAcceptTransactions).
    chatRequestTransactions: Int = 1,
    onAcceptChatRequest: () -> Unit = {},
    // ZC-1363: that Accept was tapped and waits for the funds (ChatViewModel.acceptWaitingForFunds).
    acceptWaitsForFunds: Boolean = false,
    // ZC-1017: a paid key exchange held in this Open chat, offered as a Tunnel chat (Accept and pay / Keep Open).
    openChatTunnelOffer: co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage =
        co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage.NONE,
    onAcceptTunnelOffer: () -> Unit = {},
    onKeepChatOpen: () -> Unit = {},
    // F8 (ZC-112): a setup message of this chat never reached the chain (null: none). Retry hands back the marker of the
    // failed attempt this notice was drawn from; the price is asked before anything is paid.
    handshakeNotSent: co.electriccoin.zcash.ui.common.sendattempt.HandshakeOffer? = null,
    onRetryHandshake: (Long) -> Unit = {},
    showDecryptRecovery: Boolean = false,
    showRotationReminder: Boolean = false,
    onRotateKeyCta: () -> Unit = {},
    onDismissRotationReminder: () -> Unit = {},
    // Bridge 3: the Shielded-chat reminder (hidden = the "May need update" chip) and its buttons.
    shieldedReminderHidden: Boolean = false,
    // ZC-1465: the contact already wrote back over the relays (ChatViewModel.wasHeardFromOverRelays).
    heardFromPeer: Boolean = false,
    // ZC-1464: the contact declined our chat request (ChatViewModel.isRequestDeclinedBy); the composer is closed.
    requestDeclined: Boolean = false,
    // ZC-1270: the contact's newest relay capability tag (UpgradeReminders.contactIsProven213); null when none was seen.
    peerCapIronwood: Boolean? = null,
    onHideShieldedReminder: () -> Unit = {},
    onShowShieldedReminder: () -> Unit = {},
    onAskThemToUpdate: () -> Unit = {},
    onChatFreeInstead: () -> Unit = {},
    safetyNumber: String? = null,
    isVerified: Boolean = false,
    // ZC-634 (5.5 item 8): the key is only a claim and a ZBOOT was held back (the held-connection banner).
    keyClaimHeld: Boolean = false,
    // W-72 (SPEC_F R8): the key carries a pre-update mark (the Unverified banner says "Not confirmed").
    keyNotConfirmed: Boolean = false,
    onMarkVerified: () -> Unit = {},
    quantumShieldStatus: String = "NONE",
    quantumShieldAlert: String = "NONE",
    onInitiateQuantumShield: () -> Unit = {},
    onResetQuantumShield: () -> Unit = {},
    // ZC-1476 / ZC-321: the ⋮ entry's turn off (ON) and cancel (PENDING) open AndroidChat's turn-off confirm.
    onAskTurnOffQuantumShield: () -> Unit = {},
    onSendImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendViewOnceImage: () -> Unit = {},
    onMarkFileViewed: (fileHash: String) -> Unit = {},
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    isRecordingViewOnce: Boolean = false,
    onMicTap: () -> Unit = {},
    onMicLongPress: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode =
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
    onPickConversationMode: () -> Unit = {},
    onPlaceCall: () -> Unit = {},
    onPlaceVideoCall: () -> Unit = {},
    // #256 — reconnect / re-send our NOSTR identity to recover a one-way handshake (calls/Open stuck).
    onReconnect: () -> Unit = {},
    onResetEncryption: () -> Unit = {},
    // B17 — disappearing-messages TTL (seconds; 0 = off) + setter (synced to the peer).
    disappearingTtlSeconds: Long = 0L,
    onSetDisappearingTtl: (Long) -> Unit = {},
    uploadProgress: Float? = null,
    fileDownloadProgress: Map<String, Float> = emptyMap(),
    fileDownloadFailures: Set<String> = emptySet(),
    onRetryDownload: (zfileContent: String, peerAddress: String) -> Unit = { _, _ -> },
    onBackClick: () -> Unit,
    onSendMessage: (message: String, amountZatoshi: Long) -> Boolean,
    onSendReply: (message: String, replyToId: String, replyPreview: String, amountZatoshi: Long) -> Boolean,
    composerMessageCost: (text: String, replyToId: String?, replyPreview: String, amountZatoshi: Long) -> Zatoshi,
    onDeleteMessage: (String) -> Unit,
    onRetryMessage: (messageId: String) -> Unit,
    onSendPayment: (amountZec: Double, memo: String, quote: ChatSendQuote) -> Boolean,
    onSendReaction: (messageId: String, emoji: String) -> Unit,
    // Time-lock callbacks
    onSendScheduledMessage: (message: String, unlockTimestamp: Long) -> Unit,
    onSendBlockLockedMessage: (message: String, unlockHeight: Long) -> Unit,
    onSendPaymentLockedMessage: (message: String, requiredZatoshi: Long) -> Unit,
    onSendConditionalMessage: (message: String, answer: String, hint: String) -> Unit,
    // Payment request callbacks
    onSendPaymentRequest: (amountZatoshi: Long, reason: String) -> Boolean,
    onFulfillPaymentRequest: (amountZatoshi: Long, requestId: String, quote: ChatSendQuote) -> Unit,
    onQuotePayment: suspend (amountZatoshi: Long, memoText: String, rawMemo: Boolean) -> PaymentQuote,
    // Nickname callback
    onNicknameChange: (address: String, nickname: String) -> Unit,
    currentBlockHeight: Long?,
    walletOffline: Boolean = false,
    fundsStillConfirming: (need: Zatoshi) -> Boolean = { false },
    // Draft callback
    onDraftChange: (String) -> Unit,
    restoredTypedText: String? = null,
    onRestoredTypedTextShown: () -> Unit = { },
    // Mute callback
    onMuteToggle: () -> Unit,
    // Welcome ZEC suggestion
    onSendWelcomeZec: ((quote: ChatSendQuote) -> Unit)? = null,
    showWelcomeZecSuggestion: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

    // Live NOSTR outbound readiness. A TUNNEL send only goes free over NOSTR when the publisher is
    // registered (isOutboundReady); during the cold-launch window the peer pubkey may already be known
    // (hasNostrCallChannel) while the publisher isn't, and a send then falls back to a CHARGED on-chain
    // memo. Gating the "Free" label on this prevents a false "Free — sent over NOSTR" that actually spends.
    val nostrOutboundReady by co.electriccoin.zcash.ui.nostr.NostrChatBridge.outboundReady.collectAsState()

    // Initialize with draft if available, capped like typed text: a text shared in from another app or a draft
    // stored by an older version may be longer than the composer allows (SHARED-TEXT-CAP).
    var messageText by remember { mutableStateOf(conversation.draft?.let(::capDraft) ?: "") }
    val listState = rememberLazyListState()
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showTimeLockDialog by remember { mutableStateOf(false) }
    var showPaymentRequestDialog by remember { mutableStateOf(false) }
    // Tapping "Pay X ZEC" on a request bubble must NOT spend real funds on a single tap in a
    // scrollable list. Hold the pending (amountZatoshi, requestId) and confirm before fulfilling.
    var pendingPayRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // Same guard for the one-tap "Send Welcome ZEC" suggestion card.
    var showWelcomeZecConfirm by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<MemoTemplate?>(null) }
    var showAmountPicker by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameText by remember { mutableStateOf(conversation.contactName ?: "") }
    var showPrivacyStatus by remember { mutableStateOf(false) }
    var showSafetyNumberDialog by remember { mutableStateOf(false) }
    // ZC-545: the dialog of a remembered refused key, and the snapshot (both keys and their number) it opened with.
    var showKeyReviewDialog by remember { mutableStateOf(false) }
    var keyReviewSnapshot by remember {
        mutableStateOf<co.electriccoin.zcash.ui.screen.chat.model.RefusedKeyOfferView?>(null)
    }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

    // Clipboard and context for copy functionality
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    // TX-1: system notes are drawn in the phone's language today (SystemNoteText.display).
    val noteCopy = remember(context) { ChatNoteCopy.from(context) }
    // QA2 Q2-41: a system note names a contact with no nickname "Your contact", never the header's short address.
    val noteName =
        conversation.contactName?.takeIf { conversation.hasContactName }
            ?: stringResource(R.string.chat_contact_fallback_name)

    // Contact-avatar editing (Phase 1: local-only) — a viewer-side override for THIS contact's
    // photo, driven from the existing nickname/edit-contact dialog below. Downscale + store happen
    // off the main thread via AvatarStore.
    val avatarStore = koinInject<AvatarStore>()
    val avatarScope = rememberCoroutineScope()
    val contactAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatarScope.launch {
                val bytes = loadAvatarBytesFromUri(context, uri)
                val stored = bytes != null &&
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        avatarStore.setContactAvatar(conversation.peerAddress, bytes)
                    }
                if (!stored) {
                    Toast.makeText(context, context.getString(R.string.chat_image_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Message amount state (in zatoshi)
    // Default amount options: 1000, 5000, 10000, 50000, 100000 zatoshi
    val amountOptions = listOf(1000L, 5000L, 10000L, 50000L, 100000L)
    var selectedAmountIndex by remember { mutableIntStateOf(0) }
    val selectedAmount = amountOptions[selectedAmountIndex]

    // Reply state
    var replyToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // Search state — declared here (above the BackHandler) so hardware/gesture back can dismiss search.
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Hardware/gesture back: while SEARCHING, close search first (mirrors the top-bar back arrow at the
    // "Close search" icon); otherwise clear an active reply (the X on ReplyPreview is the only other way
    // out). Without the isSearching branch, system back while searching fell through to the NavHost and
    // POPPED the entire chat detail out to the chat list — the in-app back ARROW closed search, but the
    // hardware back button did not (Suite-H nav finding, reproduced on-device).
    BackHandler(enabled = isSearching || replyToMessage != null) {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else {
            replyToMessage = null
        }
    }

    // R3-2 (2): a cancelled first-message cost prompt hands the typed text back (the input was cleared when the
    // send was accepted behind the prompt). T5b X-05: it goes into an empty box as it is, and below what the box holds
    // by then (an earlier text that was handed back, or something typed meanwhile); it used to be dropped then.
    LaunchedEffect(restoredTypedText) {
        val restored = restoredTypedText ?: return@LaunchedEffect
        messageText = mergedHandOffDraft(messageText, restored)
        onRestoredTypedTextShown()
    }

    // ZC-135: a saved draft longer than the composer allows opens cut; say so once, with the composer's own notice.
    LaunchedEffect(Unit) {
        if (draftWasCut(conversation.draft)) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.chat_composer_trimmed, MAX_MESSAGE_INPUT_CHARS),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    // Auto-save draft with debounce (500ms delay). ZC-135: never the cut of a longer saved draft the user has not
    // changed (it used to be written over the saved draft at once).
    LaunchedEffect(messageText) {
        if (draftNeedsSaving(messageText, conversation.draft)) {
            kotlinx.coroutines.delay(500L)
            onDraftChange(messageText)
        }
    }
    // Flush the draft synchronously when leaving the screen so the last keystroke inside the 500ms
    // debounce window isn't lost on back-navigate / fast background.
    val latestDraft = rememberUpdatedState(messageText)
    // rememberUpdatedState for the SAVED draft too: DisposableEffect(Unit)'s onDispose captures the
    // FIRST composition's `conversation`, so comparing against a stale conversation.draft could skip a
    // needed flush (e.g. debounce persisted "abcX", user deleted the X, then backed out < 500ms).
    val latestSavedDraft = rememberUpdatedState(conversation.draft ?: "")
    DisposableEffect(Unit) {
        onDispose {
            if (draftNeedsSaving(latestDraft.value, latestSavedDraft.value)) {
                onDraftChange(latestDraft.value)
            }
        }
    }

    // SECURITY (view-once): sweep any orphaned plaintext view-once audio temp copies (vo_aud_*.tmp)
    // left in the file cache by a crash/kill mid-playback (whose onDispose cleanup never ran). Runs on
    // chat open, before any view-once reveal starts, so it can't race an active playback temp.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                java.io.File(context.cacheDir, "zchat_files")
                    .listFiles { f -> f.name.startsWith("vo_aud_") && f.name.endsWith(".tmp") }
                    ?.forEach { secureWipeAndDelete(it) }
            }
        }
    }

    // (isSearching / searchQuery declared above, next to replyToMessage, so the BackHandler can use them.)
    // Overflow ("⋮") menu for secondary top-bar actions, to keep the action row uncrowded.
    var showTopBarMenu by remember { mutableStateOf(false) }
    // ZC-1471: the header lock's dialog (replaces the ⋮ item that only showed a toast).
    var showLockDialog by remember { mutableStateOf(false) }
    // ZC-1479: the header mode chip's dialog.
    var showModeDialog by remember { mutableStateOf(false) }
    // The Extra Security turn-off confirm of the claim warnings' "Turn off here" (the old banner's long press is gone).
    var showQsTurnOffDialog by remember { mutableStateOf(false) }
    // Confirm gate for the destructive "Reset encryption" recovery action.
    var showResetEncryptionConfirm by remember { mutableStateOf(false) }
    var showDisappearingDialog by remember { mutableStateOf(false) } // B17

    // Normalize any message still carrying a raw protocol payload (raw "ZFILE|…"/"ZBOOT|…") so it
    // renders as the rich file bubble / friendly note instead of leaking the raw string to either side.
    val normalizedMessages = remember(conversation.messages) { conversation.messages.map { it.forDisplay() } }

    // Filter messages based on search
    val filteredMessages = remember(normalizedMessages, searchQuery) {
        if (searchQuery.isBlank()) {
            normalizedMessages
        } else {
            normalizedMessages.filter {
                // displayText, NOT text: a still-locked time-lock message keeps its full plaintext in
                // `text` (the lock is display-only), so filtering on `text` would let search reveal the
                // existence — and, via the highlight — the content of a message the UI is hiding.
                it.displayText.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val displayMessages = remember(filteredMessages) { filteredMessages.asReversed() }
    // Pre-compute message lookup map for O(1) reply-to resolution instead of O(n) linear search
    val messageById = remember(normalizedMessages) { normalizedMessages.associateBy { it.id } }

    // Check if peer address is a valid Zcash address
    // Unified addresses start with "u1" and are 200+ chars, Sapling starts with "zs" and is 78+ chars
    val isValidAddress = (conversation.peerAddress.startsWith("u1") && conversation.peerAddress.length > 100) ||
            (conversation.peerAddress.startsWith("zs") && conversation.peerAddress.length > 70)

    // Scroll to bottom when a NEW message arrives — but NOT while searching, or an arriving message would
    // yank the user off the search result they're reading (B9 edge case). Keyed on the newest message id
    // rather than list SIZE so a deletion / disappearing-messages TTL expiry (which only shrinks the list)
    // no longer yanks the reader to the bottom, and a delete+arrive that leaves size unchanged still scrolls.
    LaunchedEffect(conversation.messages.lastOrNull()?.id) {
        if (conversation.messages.isNotEmpty() && !isSearching) {
            listState.animateScrollToItem(0)
        }
    }

    // B9: jump to the first search result. displayMessages is the filtered list reversed (reverseLayout),
    // so item 0 is the newest match = result #1. Re-runs whenever the query changes.
    LaunchedEffect(searchQuery, isSearching) {
        if (isSearching && searchQuery.isNotBlank() && displayMessages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            // ZC-1705, ZC-1755: the bar is as tall as the lines it holds at every font scale, 1.0 included: the name, the
            // chip row and, on an unnamed chat, the nickname hint below the chips. Each header text has its own line height
            // (without one it inherits the 28 sp of the title style and three lines overflow the 64 dp default).
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            TopAppBar(
                expandedHeight = chatHeaderBarHeightDp(fontScale, hasHint = !conversation.hasContactName).dp,
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search messages...") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            }
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    // Tap to edit nickname
                                    nicknameText = conversation.contactName ?: ""
                                    showNicknameDialog = true
                                },
                                onLongClick = {
                                    // Long press to copy address
                                    clipboardManager.setText(AnnotatedString(conversation.peerAddress))
                                    Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                                }
                            )
                        ) {
                            // Stored local photo when set; else initials on the per-address accent.
                            // Passing null when there's no real name fixes the old "U1" fallback
                            // (every unified address starts with "u1").
                            ZchatAvatar(
                                ref = ZchatAvatarRef.Contact(conversation.peerAddress),
                                displayName = if (conversation.hasContactName) conversation.contactName else null,
                                size = 36.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = conversation.displayName,
                                        fontSize = 17.sp,
                                        lineHeight = 22.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // QA2 Q2-33: a muted chat says so in its top bar (the crossed bell, read as "Muted").
                                    if (conversation.isMuted) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.NotificationsOff,
                                            contentDescription = stringResource(R.string.chat_header_muted),
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                // Direction-A: "● shielded" status subline — every ZCHAT chat is
                                // end-to-end encrypted/shielded. Lock + mode chip + the peer address (if named).
                                // ZC-1705: the set-nickname hint of an unnamed chat is its own line below (it may wrap to
                                // two), not the tail of this row: beside the lock and the chip it had room for one cut word.
                                val subText = Conversation.truncateAddress(conversation.peerAddress)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // ZC-1471: the encryption lock, closed or amber pending (never open: every mode is
                                    // encrypted). A tap opens the lock dialog with the e2eMenuRow text.
                                    val lockState = e2eLockState(conversation.appLayerE2EPending)
                                    // U7 review follow-up (Q2-53): the Unverified banner is gone, so an unverified
                                    // contact keeps a visible cue here: the lock is tinted as a warning until verified.
                                    val headerUnverified =
                                        safetyNumber != null && conversation.isE2EReady && !isVerified && !isKeyChanged
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .clickable { showLockDialog = true },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector =
                                                if (lockState == E2ELockState.PENDING) Icons.Default.LockClock else Icons.Default.Lock,
                                            // Review #13 nit 3: TalkBack hears the unverified cue the tint shows.
                                            contentDescription =
                                                if (lockState == E2ELockState.CLOSED && headerUnverified) {
                                                    stringResource(R.string.chat_a11y_encryption_unverified)
                                                } else {
                                                    stringResource(e2eLockContentDescription(lockState))
                                                },
                                            tint =
                                                if (lockState == E2ELockState.PENDING || headerUnverified) {
                                                    colors.warning
                                                } else {
                                                    colors.success
                                                },
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(3.dp))
                                    // Mode-aware, plain-language status word (the green dot already says
                                    // "encrypted"). The old static "shielded" confused users — now it reflects
                                    // how THIS chat is actually delivered: shielded = on-chain, tunnel/open = NOSTR.
                                    // ES-A (W-11): a resource; Spanish names the mode as the mode picker does.
                                    val modeLabel = when (conversationMode) {
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT ->
                                            stringResource(R.string.chat_header_mode_shielded)
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL ->
                                            stringResource(R.string.chat_header_mode_tunnel)
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN ->
                                            stringResource(R.string.chat_header_mode_open)
                                    }
                                    // ZC-1479 (owner, W-122 answer 3): the mode chip, an icon per mode and the Extra
                                    // Security badge (violet on, amber setting up); a tap explains the mode. REVISION 3
                                    // (ZC-1486): one readable label, "Mode: Open|Tunnel|Shielded".
                                    val chipExtraSecurity =
                                        runCatching { QuantumShieldStatus.valueOf(quantumShieldStatus) }
                                            .getOrDefault(QuantumShieldStatus.NONE)
                                    val modeChipDescription = stringResource(modeChipContentDescription(conversationMode))
                                    Row(
                                        modifier =
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { showModeDialog = true }
                                                .clearAndSetSemantics { contentDescription = modeChipDescription }
                                                .padding(horizontal = 3.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // QA2 Q2-28: the chip takes the lock's colour, so a pending lock never sits beside a green chip.
                                        val chipTint = if (lockState == E2ELockState.PENDING) colors.warning else colors.success
                                        Icon(
                                            imageVector = modeChipIcon(conversationMode),
                                            contentDescription = null,
                                            tint = chipTint,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = modeLabel,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            color = chipTint,
                                            fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        if (chipExtraSecurity != QuantumShieldStatus.NONE) {
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Icon(
                                                imageVector = Icons.Default.Shield,
                                                contentDescription = null,
                                                tint =
                                                    if (chipExtraSecurity == QuantumShieldStatus.ACTIVE) {
                                                        EXTRA_SECURITY_ON_COLOR
                                                    } else {
                                                        EXTRA_SECURITY_PENDING_COLOR
                                                    },
                                                modifier = Modifier.size(10.dp),
                                            )
                                        }
                                    }
                                    // QA2 Q2-98: while the wallet is offline the header says so, before the address.
                                    if (walletOffline) {
                                        // U7 review nit 12: the separator is part of the one resource ("· Offline").
                                        Text(
                                            text = stringResource(R.string.chat_header_offline),
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            color = colors.warning,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                    // weight(1f) so the address ellipsizes and the fixed "shielded"
                                    // label never gets clipped in the icon-crowded header.
                                    if (conversation.hasContactName) {
                                        Text(
                                            text = " · $subText",
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            color = colors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                if (!conversation.hasContactName) {
                                    Text(
                                        text = stringResource(R.string.chat_nickname_hint),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = colors.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    if (isSearching) {
                                        R.string.chat_list_cd_close_search
                                    } else {
                                        R.string.back_navigation_content_description
                                    }
                                )
                        )
                    }
                },
                actions = {
                    if (!isSearching) {
                        // E2E on/off toggle + safety-number + search all moved into the overflow ⋮ menu
                        // (#bug-header-decram) so the action row holds only voice/video/⋮ — the name +
                        // address get the freed width instead of being clipped by a crowded icon row.
                        // The "● shielded" subtitle still conveys the chat is encrypted at a glance.
                        // Voice/video call. A call routes over the peer's NOSTR identity on the free
                        // relay (startCall → placeCall(peerNostrPubkey)) — it NEVER spends on-chain — so
                        // it's placeable whenever we hold that pubkey (hasNostrCallChannel), EVEN in a
                        // VAULT message conversation. We therefore offer the buttons when either the
                        // message mode is call-capable (Tunnel/Open, which will bootstrap on tap) OR a
                        // NOSTR call channel already exists. Gating on the message mode alone wrongly hid
                        // calls on an already-established VAULT chat where the free relay was right there.
                        //
                        // callsReady (= channel exists) drives only the TINT, not the tap: bright when a
                        // call connects now, muted as a "not ready yet" cue otherwise. On a muted tap we
                        // STILL invoke the place-call path — startCall detects the missing peer key, kicks
                        // the ZBOOT/KEX handshake, and shows an informative toast. So we never swallow the
                        // tap; the call path owns both the not-ready UX and the handshake kick.
                        // ZC-1883 (owner W-146): no Call buttons on the ZChat Team chat.
                        if (ChatCallAffordance.buttonsShown(
                                mode = conversationMode,
                                hasNostrCallChannel = conversation.hasNostrCallChannel,
                                isTeamChat = ZchatTeamConstants.isTeamAddress(conversation.peerAddress),
                            )
                        ) {
                            val callsReady = conversation.hasNostrCallChannel
                            val callTint = if (callsReady) chatColors().primary else chatColors().textTertiary
                            IconButton(onClick = { onPlaceCall() }) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription =
                                        stringResource(
                                            if (callsReady) R.string.chat_a11y_voice_call else R.string.chat_a11y_voice_call_not_ready
                                        ),
                                    tint = callTint,
                                )
                            }
                            IconButton(onClick = { onPlaceVideoCall() }) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription =
                                        stringResource(
                                            if (callsReady) R.string.chat_a11y_video_call else R.string.chat_a11y_video_call_not_ready
                                        ),
                                    tint = callTint,
                                )
                            }
                        }
                        // Overflow menu: secondary/occasional actions (mute, verify safety number,
                        // conversation-mode picker) moved off the cramped row. The mode-picker lock
                        // glyph no longer sits next to the E2E lock, removing the two-locks confusion.
                        IconButton(onClick = { showTopBarMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.chat_menu_more_actions),
                                tint = chatColors().textSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = showTopBarMenu,
                            onDismissRequest = { showTopBarMenu = false },
                        ) {
                            // ZC-1471: the encryption row left this menu for the header lock (its dialog shows the
                            // e2eMenuRow text the toast used to show).
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_search)) },
                                onClick = { showTopBarMenu = false; isSearching = true },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = chatColors().textSecondary)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (conversation.isMuted) R.string.chat_menu_unmute else R.string.chat_menu_mute
                                        )
                                    )
                                },
                                onClick = { showTopBarMenu = false; onMuteToggle() },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (conversation.isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = if (conversation.isMuted) chatColors().error else chatColors().textSecondary,
                                    )
                                },
                            )
                            if (safetyNumber != null && conversation.isE2EReady) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_verify_safety_number)) },
                                    onClick = { showTopBarMenu = false; showSafetyNumberDialog = true },
                                    leadingIcon = {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = chatColors().primary)
                                    },
                                )
                            }
                            // Extra Security (Post-Quantum). ZC-1476: the entry is never hidden; before the key exchange it is
                            // shown disabled with the reason. ZC-1477 / ZC-445: PENDING continues the setup (the same QR) and
                            // ON still shows this phone's code, so the other phone can scan it; ZC-321: PENDING can be cancelled.
                            val extraSecurityMenu =
                                extraSecurityMenuState(
                                    conversation.isE2EReady,
                                    runCatching { QuantumShieldStatus.valueOf(quantumShieldStatus) }
                                        .getOrDefault(QuantumShieldStatus.NONE),
                                )
                            DropdownMenuItem(
                                enabled = extraSecurityMenu.enabled,
                                text = { Text(stringResource(extraSecurityMenu.labelRes)) },
                                onClick = {
                                    showTopBarMenu = false
                                    when (extraSecurityMenu) {
                                        ExtraSecurityMenuState.ENABLE, ExtraSecurityMenuState.CONTINUE -> onInitiateQuantumShield()
                                        ExtraSecurityMenuState.ON -> onAskTurnOffQuantumShield()
                                        ExtraSecurityMenuState.NEEDS_KEY_EXCHANGE -> Unit
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Shield, contentDescription = null, tint = chatColors().textSecondary)
                                },
                            )
                            // CONTINUE's own entry already shows the code; ON turns off, so its code is a second entry.
                            if (extraSecurityMenu.showsMyCode && extraSecurityMenu != ExtraSecurityMenuState.CONTINUE) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.quantum_shield_code_show_mine)) },
                                    onClick = {
                                        showTopBarMenu = false
                                        onInitiateQuantumShield()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = chatColors().textSecondary)
                                    },
                                )
                            }
                            if (extraSecurityMenu.offersCancel) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_extra_security_cancel)) },
                                    onClick = {
                                        showTopBarMenu = false
                                        onAskTurnOffQuantumShield()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = chatColors().textSecondary)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    // R6-3: the entry is translated; the mode names are product names in every language.
                                    Text(stringResource(R.string.chat_menu_conversation_mode, conversationMode.label()))
                                },
                                onClick = { showTopBarMenu = false; onPickConversationMode() },
                                leadingIcon = {
                                    val ic = when (conversationMode) {
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT -> Icons.Default.Shield
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL -> Icons.Default.Lock
                                        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN -> Icons.Default.LockOpen
                                    }
                                    val tint = if (conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN) chatColors().textSecondary else chatColors().primary
                                    Icon(ic, contentDescription = null, tint = tint)
                                },
                            )
                            // ZC-1473: Reconnect is drawn only where it re-sends something (ReconnectMenu.visible: hidden in Open).
                            if (ReconnectMenu.visible(conversationMode)) {
                                // #256 — recover a one-way / asymmetric handshake: re-send OUR identity to the peer.
                                // Free over the relay when the peer's relay key is known; otherwise it is an on-chain
                                // transaction (a KEX when the session is torn), so the item says it can cost ZEC.
                                // P2b: at most one paid re-send per contact key per hour (see reSendOurIdentity).
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.chat_menu_reconnect_title))
                                            Text(
                                                stringResource(R.string.chat_menu_reconnect_subtitle),
                                                fontSize = 12.sp,
                                                color = chatColors().textSecondary,
                                            )
                                        }
                                    },
                                    onClick = {
                                        showTopBarMenu = false
                                        onReconnect()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = chatColors().primary)
                                    },
                                )
                            }
                            // B17 — disappearing messages. Needs an authenticated carrier to sync the timer:
                            // E2E ratchet for VAULT, a pinned NOSTR pubkey for TUNNEL/OPEN.
                            DropdownMenuItem(
                                enabled = conversation.isE2EReady || conversation.hasNostrCallChannel,
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.chat_menu_disappearing,
                                            if (disappearingTtlSeconds > 0L) {
                                                ttlLabel(disappearingTtlSeconds)
                                            } else {
                                                stringResource(R.string.chat_menu_disappearing_off)
                                            }
                                        )
                                    )
                                },
                                onClick = { showTopBarMenu = false; showDisappearingDialog = true },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = if (disappearingTtlSeconds > 0L) chatColors().primary else chatColors().textSecondary,
                                    )
                                },
                            )
                            // Recover a chat stuck on "🔒 its decryption key isn't on this device"
                            // (AEADBadTagException) after a restore/wipe desynced the E2E keys. Gated by a
                            // confirm dialog because it tears the secure session back to first-contact.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_reset_encryption)) },
                                onClick = { showTopBarMenu = false; showResetEncryptionConfirm = true },
                                leadingIcon = {
                                    Icon(Icons.Default.LockReset, contentDescription = null, tint = chatColors().error)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.primary,
                    actionIconContentColor = colors.primary
                )
            )
        },
        bottomBar = {
            Column {
                // Image upload progress indicator
                if (uploadProgress != null) {
                    ImageUploadProgressBar(progress = uploadProgress)
                }
                // Template picker row (shown when templates button is tapped)
                if (showTemplates) {
                    TemplatePickerRow(
                        templates = MemoTemplate.BUILT_IN_TEMPLATES,
                        zecPriceUsd = zecPriceUsd,
                        onTemplateSelected = { template ->
                            selectedTemplate = template
                            showTemplates = false
                            showPaymentDialog = true
                        },
                        onDismiss = { showTemplates = false }
                    )
                }
                // Reply preview
                if (replyToMessage != null) {
                    ReplyPreview(
                        message = replyToMessage!!,
                        onDismiss = { replyToMessage = null }
                    )
                }
                MessageInput(
                    value = messageText,
                    onValueChange = { messageText = it },
                    onSend = {
                        if (messageText.isNotBlank() && isValidAddress) {
                            // Clear the input only if the send was ACCEPTED. A pre-queue rejection
                            // (key changed / funds not yet shielded / another send in flight) returns
                            // false and we KEEP the typed text (B1-msg-lost-on-blocked-send).
                            val accepted = if (replyToMessage != null) {
                                // Pass the tapped message's displayText as the authoritative quote preview
                                // (displayText keeps locked-message plaintext out). This is what fixes both
                                // sender echo and receiver from rendering a reply with no quote.
                                onSendReply(
                                    messageText,
                                    replyToMessage!!.id,
                                    replyToMessage!!.displayText.take(50),
                                    selectedAmount,
                                )
                            } else {
                                onSendMessage(messageText, selectedAmount)
                            }
                            if (accepted) {
                                replyToMessage = null
                                messageText = ""
                            }
                        }
                    },
                    onPayClick = { showPaymentDialog = true },
                    onTemplatesClick = { showTemplates = !showTemplates },
                    onLockClick = { showTimeLockDialog = true },
                    onRequestClick = { showPaymentRequestDialog = true },
                    onSendImage = onSendImage,
                    onTakePhoto = onTakePhoto,
                    onSendFile = onSendFile,
                    onSendViewOnceImage = onSendViewOnceImage,
                    attachmentsNeedKeyExchange =
                        conversationMode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT &&
                            !conversation.isE2EReady,
                    onAmountClick = { showAmountPicker = true },
                    selectedAmount = selectedAmount,
                    // F-R9P-1: a reply is sized with its reply reference, the same preview the send passes.
                    replyToId = replyToMessage?.id,
                    composerMessageCost = { text ->
                        composerMessageCost(
                            text,
                            replyToMessage?.id,
                            replyToMessage?.displayText?.take(50) ?: "",
                            selectedAmount,
                        )
                    },
                    // ZC-696: a send (or an arrival, or the key exchange finishing) changes the wire the next message
                    // needs, so the price on the send button is computed again.
                    costRefreshKey = conversation.messages.size to conversation.isE2EReady,
                    conversationMode = conversationMode,
                    // A TUNNEL chat only spends ZEC for the one-time on-chain ZBOOT handshake; once the
                    // peer's NOSTR pubkey is known (hasNostrCallChannel) AND the NOSTR publisher is ready
                    // (nostrOutboundReady) every further send is free over NOSTR. BOTH are required:
                    // the actual send routing (handleNostrRouteIfApplicable) only goes free when
                    // isOutboundReady(), else it falls back to a charged on-chain memo — so the label
                    // must not claim "Free" until outbound is live, or it would mislead during the
                    // cold-launch window and a send there would silently spend ZEC.
                    tunnelSendIsFree = conversation.hasNostrCallChannel && nostrOutboundReady,
                    isEnabled = isValidAddress && !requestDeclined,
                    disabledMessage =
                        when {
                            !isValidAddress -> "Cannot reply - sender address unknown"
                            requestDeclined -> stringResource(R.string.chat_composer_request_declined)
                            else -> null
                        },
                    isRecording = isRecording,
                    recordingSeconds = recordingSeconds,
                    isRecordingViewOnce = isRecordingViewOnce,
                    onMicTap = onMicTap,
                    onMicLongPress = onMicLongPress,
                    onSendRecording = onSendRecording,
                    onCancelRecording = onCancelRecording,
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Show explanation banner for unknown senders
            if (!isValidAddress) {
                UnknownSenderBanner(
                    reason = conversation.messages.firstOrNull()?.unknownReason
                )
            }

            // Handshake-waiting banner: on a call-capable transport (Tunnel/Open) the call channel
            // only exists once the peer's NOSTR pubkey is known (hasNostrCallChannel) — i.e. after the
            // contact replies. Match the call BUTTON's "ready" signal exactly so a muted button and the
            // banner agree. Once the channel exists (including a VAULT chat that already established one)
            // calls are ready, so the banner hides. Surface it so a locked call doesn't read as a broken
            // app (Fable 5 feedback).
            // ZC-634 (5.5 item 8): while this chat's key is only a claim and a connection message was held back
            // (keyClaimHeld), the banner says so and names the dialog's button (chat_safety_number_mark_verified), not Reconnect.
            if (isValidAddress && keyClaimHeld) {
                CollapsibleStatusBanner(
                    icon = Icons.Default.LockClock,
                    tint = chatColors().warning,
                    shortLabel = stringResource(R.string.chat_key_unconfirmed_held_short),
                    fullText = stringResource(R.string.chat_key_unconfirmed_held_body),
                )
            } else if (!keyClaimHeld && isValidAddress) {
                // ZC-1418: once the contact answered (key exchange complete) and our own Tunnel setup step (the on-chain
                // ZBOOT) cannot be paid from what can go to others now, the banner names that step and its ZEC, not Reconnect.
                val setupQuote = handshakeSpendQuote(1)
                val isTunnel = conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL
                val lockedStage =
                    CallsLockedBanner.stage(
                        // ZC-1883: no "calls locked" banner on the ZChat Team chat (calls with the Team are off).
                        callsSupported =
                            ChatCallAffordance.bannerCallsSupported(
                                mode = conversationMode,
                                isTeamChat = ZchatTeamConstants.isTeamAddress(conversation.peerAddress),
                            ),
                        hasNostrCallChannel = conversation.hasNostrCallChannel,
                        contactAnswered = conversation.isE2EReady,
                        ownSetupWaitsForFunds = isTunnel && peerSendableBalance.value < setupQuote.value,
                    )
                when {
                    CallsLockedBanner.offersReconnect(lockedStage) ->
                        CollapsibleStatusBanner(
                            icon = Icons.Default.LockClock,
                            tint = chatColors().warning,
                            shortLabel = "Waiting for contact — calls locked",
                            fullText =
                                "Waiting for your contact to reply — calls unlock after their first message. " +
                                    "If it stays stuck, open the ⋮ menu and tap “Reconnect (re-send my key)”.",
                        )
                    lockedStage == CallsLockedBanner.Stage.OWN_SETUP_NEEDS_FUNDS ->
                        CollapsibleStatusBanner(
                            icon = Icons.Default.LockClock,
                            tint = chatColors().warning,
                            shortLabel = stringResource(R.string.chat_calls_locked_own_setup_needs_funds_short),
                            fullText =
                                stringResource(
                                    R.string.chat_calls_locked_own_setup_needs_funds,
                                    PaidActionCopy.zec(setupQuote, rememberDesiredFormatLocale()),
                                ),
                        )
                    else -> Unit
                }
            }

            // R2-2 (a): a chat request from an address we have no chat with. Its KEXACK (a paid handshake)
            // goes out only when the user taps Accept here.
            // ZC-1017 (decided under W-57): in an Open chat the held paid key exchange is offered as a Tunnel chat. Accept
            // switches the chat to Tunnel before the one answer is paid (ChatViewModel.acceptOpenChatTunnelOffer), for the
            // amount quoted here; Keep Open pays nothing. With too little to pay, Accept is off and a line says so.
            val tunnelOffer = openChatTunnelOffer
            if (tunnelOffer != co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage.NONE && !isKeyChanged) {
                // The one answer Accept pays and confirms (a later on-chain ZBOOT would need its own Allow question).
                val offerQuote = handshakeSpendQuote(1)
                val offerAmount = PaidActionCopy.zec(offerQuote, rememberDesiredFormatLocale())
                val offerAffordable =
                    co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer
                        .canAccept(peerSendableBalance.value, offerQuote.value)
                val offered = tunnelOffer == co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage.OFFERED
                CollapsibleStatusBanner(
                    icon = Icons.Default.Info,
                    tint = chatColors().warning,
                    // QA2 Q2-39: the collapsed line names what Accept costs (the quote, never a constant).
                    shortLabel = stringResource(R.string.chat_open_tunnel_offer_short, offerAmount),
                    fullText = stringResource(R.string.chat_open_tunnel_offer_full, conversation.displayName, offerAmount),
                    // QA2 Q2-40 (MONEY_CHECKS): ZEC that is still confirming and would pay it is told apart from not
                    // enough ZEC, so the line never says "you need X" while the shown balance is larger.
                    notice =
                        when {
                            !offered || offerAffordable -> null
                            fundsStillConfirming(offerQuote) ->
                                stringResource(R.string.chat_open_tunnel_offer_confirming_funds, offerAmount)
                            // ZC-1814: what the wallet must reach (ZC-1417): the price, or above it the price plus the
                            // marginal fee, never the price alone while Accept is off for a wallet holding more.
                            else ->
                                stringResource(
                                    R.string.chat_open_tunnel_offer_needs_funds,
                                    co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer
                                        .needsFundsFigure(peerSendableBalance, offerQuote, rememberDesiredFormatLocale()),
                                )
                        },
                    actions =
                        if (offered) {
                            {
                                TextButton(onClick = onAcceptTunnelOffer, enabled = offerAffordable) {
                                    Text(
                                        stringResource(R.string.chat_open_tunnel_offer_accept, offerAmount),
                                        color = if (offerAffordable) chatColors().warning else chatColors().warning.copy(alpha = 0.4f),
                                        fontSize = 13.sp,
                                    )
                                }
                                TextButton(onClick = onKeepChatOpen) {
                                    Text(
                                        stringResource(R.string.chat_open_tunnel_offer_keep_open),
                                        color = chatColors().warning,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                )
            }

            if (awaitingChatAccept &&
                !isKeyChanged &&
                tunnelOffer == co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer.Stage.NONE
            ) {
                // TX-2: in a chat we already write to (an accepted Open chat whose peer is not a saved contact) the
                // words say "in this chat", not "New contact". Text only; they name no sender (a KEX does not prove
                // it) and point to the safety number. The hold and Accept are the same either way.
                val knownChat = ChatRequestBanner.isKnownChat(conversation.messages, chatRequestFromSavedContact)
                CollapsibleStatusBanner(
                    icon = Icons.Default.Info,
                    tint = chatColors().warning,
                    shortLabel =
                        stringResource(
                            if (knownChat) R.string.chat_request_banner_short_known else R.string.chat_request_banner_short
                        ),
                    fullText =
                        pluralStringResource(
                            if (knownChat) R.plurals.chat_request_banner_count_known else R.plurals.chat_request_banner_count,
                            chatRequestTransactions,
                            chatRequestTransactions,
                            PaidActionCopy.zec(handshakeSpendQuote(chatRequestTransactions), rememberDesiredFormatLocale()),
                        ),
                    // ZC-1363: an Accept waiting for its funds stays as a line here, with the progress the funds-wait row shows.
                    notice =
                        privacyStatus.pendingFundsProgress?.takeIf { acceptWaitsForFunds }?.let { funds ->
                            stringResource(
                                R.string.chat_accept_waits_for_funds_banner,
                                funds.confirmations,
                                funds.required,
                                funds.etaMinutes,
                            )
                        },
                    actions = {
                        TextButton(onClick = onAcceptChatRequest) {
                            Text(
                                stringResource(R.string.chat_request_accept),
                                color = chatColors().warning,
                                fontSize = 13.sp,
                            )
                        }
                    },
                )
            }

            // F8 (ZC-112): "Key exchange not sent". A setup message of this chat (our key exchange, its reply or our relay
            // identity) was proven never to have reached the chain. Nothing is sent again by itself: Retry asks its price
            // first. Hidden while the Accept or the key-changed banner asks for a decision (HandshakeNotSentBanner).
            if (co.electriccoin.zcash.ui.screen.chat.model.HandshakeNotSentBanner
                    .shows(handshakeNotSent, awaitingChatAccept, isKeyChanged)
            ) {
                val offer = handshakeNotSent
                CollapsibleStatusBanner(
                    icon = Icons.Default.Info,
                    tint = chatColors().warning,
                    shortLabel = stringResource(R.string.chat_handshake_not_sent_short),
                    fullText = stringResource(R.string.chat_handshake_not_sent_full),
                    actions = {
                        TextButton(onClick = { offer?.let { onRetryHandshake(it.startedAt) } }) {
                            Text(
                                stringResource(R.string.chat_retry),
                                color = chatColors().warning,
                                fontSize = 13.sp,
                            )
                        }
                    },
                )
            }

            // Key-Changed Warning Banner (collapsible). Highest-severity status: tap to expand the full
            // explanation + Verify action; × accepts the new key (onDismissKeyChanged: free in Shielded, a paid reconnect
            // question first elsewhere). Starts collapsed like the rest. ZC-250: after a refused send it stays expanded
            // with the refusal notice above Verify and ×; collapsing it acknowledges the notice.
            // ZC-545: while a refused key is remembered (refusedKeyOffer), the text says when it arrived, and Verify and
            // the × both open the one dialog that shows THAT key's safety number; nothing is accepted from here.
            if (isKeyChanged) {
                val pendingSince =
                    refusedKeyOffer?.let { offer ->
                        stringResByDateTime(
                            java.time.ZonedDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(offer.firstSeenMs),
                                java.time.ZoneId.systemDefault(),
                            ),
                            useFullFormat = true,
                        ).getValue()
                    }
                CollapsibleStatusBanner(
                    icon = Icons.Default.Warning,
                    tint = Color(0xFFFF2D78),
                    shortLabel = stringResource(R.string.chat_key_changed_banner_short),
                    fullText =
                        if (pendingSince != null) {
                            stringResource(R.string.chat_key_changed_banner_body_pending, pendingSince)
                        } else {
                            stringResource(R.string.chat_key_changed_banner_body)
                        },
                    onDismiss = {
                        if (refusedKeyOffer != null) {
                            keyReviewSnapshot = refusedKeyOffer
                            showKeyReviewDialog = true
                        } else {
                            onDismissKeyChanged()
                        }
                    },
                    dismissDescription = stringResource(R.string.chat_key_changed_accept_key),
                    notice =
                        when {
                            !keyChangeRefused -> null
                            refusedKeyOffer != null -> stringResource(R.string.chat_key_changed_send_refused)
                            else -> stringResource(R.string.chat_key_changed_send_refused_no_key)
                        },
                    onNoticeAcknowledged = onAcknowledgeKeyChangeRefusal,
                    actions = {
                        TextButton(
                            onClick = {
                                if (refusedKeyOffer != null) {
                                    keyReviewSnapshot = refusedKeyOffer
                                    showKeyReviewDialog = true
                                } else {
                                    showSafetyNumberDialog = true
                                }
                            },
                        ) {
                            Text(stringResource(R.string.chat_key_changed_verify), color = Color(0xFFFF2D78), fontSize = 13.sp)
                        }
                    },
                )
            }

            // Decrypt-recovery banner (#bug-msg-both-ways): a received message couldn't be decrypted —
            // the E2E keys desynced (a wallet restore or a one-sided reset on either device). Make recovery
            // DISCOVERABLE here instead of forcing the user to hunt the ⋮ menu. "Re-establish" opens the same
            // confirm-gated Reset flow (so no surprise on-chain spend). Auto-clears once a message decrypts.
            if (showDecryptRecovery && !isKeyChanged) {
                CollapsibleStatusBanner(
                    icon = Icons.Default.LockReset,
                    tint = chatColors().warning,
                    shortLabel = stringResource(R.string.chat_decrypt_recovery_title),
                    fullText = stringResource(R.string.chat_decrypt_recovery_body),
                    actions = {
                        TextButton(onClick = { showResetEncryptionConfirm = true }) {
                            Text(stringResource(R.string.chat_decrypt_recovery_reestablish), color = chatColors().warning, fontSize = 13.sp)
                        }
                    },
                )
            }

            // Bridge 3: every Shielded chat reminds that a contact still on ZChat 2.12.4 or older can't see
            // Shielded messages or ZEC until they update. Hide leaves a small chip that opens it again.
            when (
                UpgradeReminders.shieldedReminder(
                    conversationMode,
                    isValidAddress,
                    shieldedReminderHidden,
                    UpgradeReminders.contactIsProven213(peerCapIronwood, conversation.messages),
                )
            ) {
                ShieldedReminderUi.BANNER ->
                    CollapsibleStatusBanner(
                        icon = Icons.Default.Info,
                        tint = chatColors().warning,
                        shortLabel = stringResource(R.string.upgrade_shielded_short),
                        fullText =
                            stringResource(R.string.upgrade_shielded_full, conversation.displayName) + " " +
                                stringResource(R.string.upgrade_nothing_lost),
                        actions = {
                            TextButton(onClick = onAskThemToUpdate) {
                                Text(stringResource(R.string.upgrade_ask_them_to_update), color = chatColors().warning, fontSize = 13.sp)
                            }
                            TextButton(onClick = onChatFreeInstead) {
                                Text(stringResource(R.string.upgrade_chat_free_instead), color = chatColors().warning, fontSize = 13.sp)
                            }
                            TextButton(onClick = onHideShieldedReminder) {
                                Text(stringResource(R.string.upgrade_shielded_hide), color = chatColors().warning, fontSize = 13.sp)
                            }
                        },
                    )
                ShieldedReminderUi.CHIP -> ShieldedReminderChip(onClick = onShowShieldedReminder)
                ShieldedReminderUi.NONE -> Unit
            }

            // Bridge 3: until a free message from the contact arrives, an Open chat explains what a chat
            // request lets through.
            if (isValidAddress &&
                UpgradeReminders.showsChatRequestHint(conversationMode, conversation.messages, heardFromPeer = heardFromPeer)
            ) {
                UpgradeNoticeLine(
                    text =
                        stringResource(R.string.upgrade_request_hint_1, conversation.displayName) + " " +
                            stringResource(R.string.upgrade_request_hint_2),
                )
            }

            // Unverified-contact banner: with open-inbox TOFU first-contact, a new peer is trusted on
            // first use but NOT cryptographically proven. Surface that clearly (the main mitigation for
            // inbound spoofing) with a one-tap path to compare the safety number. Hidden once verified,
            // and suppressed while the louder key-changed banner is showing.
            // W-72 (SPEC_F R8): while the key carries its pre-update mark the banner says "Not confirmed" and its button is
            // Compare (the same dialog); the claim-held banner above keeps priority (then the banner reads as before).
            // QA2 Q2-53: the plain Unverified-contact notice is no banner any more (two stacked banners took a third of
            // the chat); it is in the header lock's dialog with Verify safety number. The pre-update mark keeps its banner.
            if (conversation.isE2EReady && !isVerified && !isKeyChanged && safetyNumber != null) {
                val legacyLabel = keyNotConfirmed && !keyClaimHeld
                if (legacyLabel) {
                    CollapsibleStatusBanner(
                        icon = Icons.Default.Info,
                        tint = chatColors().warning,
                        shortLabel = stringResource(R.string.chat_legacy_key_label),
                        fullText = stringResource(R.string.chat_legacy_key_body),
                        actions = {
                            TextButton(onClick = { showSafetyNumberDialog = true }) {
                                Text(
                                    stringResource(R.string.chat_legacy_key_compare),
                                    color = chatColors().warning,
                                    fontSize = 13.sp
                                )
                            }
                        },
                    )
                }
            }

            // Key-rotation reminder (#178 Part B). Shown at most once/week in NOSTR chats.
            if (showRotationReminder) {
                CollapsibleStatusBanner(
                    icon = Icons.Default.Refresh,
                    tint = chatColors().primary,
                    shortLabel = "Rotate your key (optional)",
                    fullText = "Rotating your key occasionally limits what an old key could ever expose if it " +
                        "leaked. It's optional and costs a tiny on-chain re-handshake. Tap Rotate to do it now, " +
                        "or Later to dismiss for a week.",
                    onDismiss = onDismissRotationReminder,
                    actions = {
                        TextButton(onClick = onRotateKeyCta) {
                            Text("Rotate", color = chatColors().primary, fontSize = 13.sp)
                        }
                    },
                )
            }

            // ZC-1479 (owner, W-122 answer 3): Extra Security's status left the chat body for the header mode chip (its
            // badge and dialog; the other phone's confirmation is shown there too); its setup, code and turn-off are the
            // ⋮ entry (ZC-1476). The body keeps only the two warnings of the other phone's signed claim (D3-3), with
            // "Turn off here", which asks first: nothing turns off by itself or with one tap.
            val extraSecurityStatus = remember(quantumShieldStatus) {
                runCatching { QuantumShieldStatus.valueOf(quantumShieldStatus) }
                    .getOrDefault(QuantumShieldStatus.NONE)
            }
            val claimAlert =
                remember(quantumShieldAlert) {
                    runCatching { QsAlert.valueOf(quantumShieldAlert) }.getOrDefault(QsAlert.NONE)
                }
            if (extraSecurityStatus == QuantumShieldStatus.ACTIVE &&
                (claimAlert == QsAlert.KEYS_DIFFER || claimAlert == QsAlert.DOWNGRADED)
            ) {
                val alertColor = chatColors().warning
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(alertColor.copy(alpha = 0.10f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    when (claimAlert) {
                        QsAlert.KEYS_DIFFER -> {
                            Text(
                                text = stringResource(R.string.quantum_shield_keys_differ_title),
                                color = alertColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.quantum_shield_keys_differ_body),
                                color = chatColors().textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        QsAlert.DOWNGRADED -> {
                            Text(
                                text = stringResource(R.string.quantum_shield_downgraded_title),
                                color = alertColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.quantum_shield_downgraded_body),
                                color = chatColors().textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        QsAlert.CONFIRMED, QsAlert.NONE -> Unit
                    }
                    TextButton(onClick = { showQsTurnOffDialog = true }) {
                        Text(stringResource(R.string.quantum_shield_turn_off_here), color = alertColor)
                    }
                }
            }

            // ZC-1479: the privacy card shows only a line that needs the user (an unfinished, paid Tunnel setup; stranded,
            // confirming or unshielded funds). A healthy chat shows none: the header lock says it is encrypted.
            val bodyLines =
                ChatStatusLines.show(conversationMode, privacyStatus, extraSecurityStatus, conversation.hasNostrCallChannel)
            if (bodyLines.isNotEmpty()) {
                PrivacyStatusCard(
                    privacyStatus = privacyStatus,
                    conversationMode = conversationMode,
                    tunnelHandshakeComplete = conversation.hasNostrCallChannel,
                    isExpanded = showPrivacyStatus,
                    onToggle = { showPrivacyStatus = !showPrivacyStatus },
                    // For NOSTR conversations the on-chain pool/anonymity-set panel is irrelevant
                    // (messages are off-chain NIP-17 DMs). Route the tap to E2E safety-number
                    // verification instead, when a safety number is available.
                    onTapNostr = {
                        if (safetyNumber != null) {
                            showSafetyNumberDialog = true
                        } else {
                            // No safety number yet (handshake not complete) — avoid a dead tap by
                            // expanding the privacy panel so the user still gets feedback + context.
                            showPrivacyStatus = !showPrivacyStatus
                        }
                    }
                )
            }

            // Search results count. B10: count OCCURRENCES (what HighlightedText marks), not the number
            // of matching messages — searching "a" highlighted hundreds of hits but the header said "4".
            val totalMatches = remember(filteredMessages, searchQuery) {
                if (searchQuery.isBlank()) {
                    0
                } else {
                    // displayText for the same reason as the filter above: counting hits in `text`
                    // would report matches inside locked messages the user cannot see. Same match
                    // logic as the highlighter, so the count and the highlights can never disagree.
                    filteredMessages.sumOf { m -> searchMatchRanges(m.displayText, searchQuery).size }
                }
            }
            if (isSearching && searchQuery.isNotBlank()) {
                Text(
                    text = "$totalMatches ${if (totalMatches == 1) "match" else "matches"} in ${filteredMessages.size} " +
                        if (filteredMessages.size == 1) "message" else "messages",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true
            ) {
                // Empty conversation — show a friendly start state instead of a blank screen (notably
                // right after first contact, before the first on-chain message confirms).
                if (displayMessages.isEmpty() && !(showWelcomeZecSuggestion && onSendWelcomeZec != null)) {
                    item(key = "empty_state") {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // When the empty area is the result of a 0-match search, say so
                            // explicitly — the generic "No messages yet" implies the chat is empty.
                            val searchingEmpty = isSearching && searchQuery.isNotBlank()
                            Icon(
                                imageVector = if (searchingEmpty) Icons.Default.Search else Icons.Default.Lock,
                                contentDescription = if (searchingEmpty) "No search results" else "End-to-end encrypted",
                                tint = chatColors().textSecondary,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchingEmpty) "No messages match your search" else "No messages yet",
                                color = chatColors().textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                            )
                            if (!searchingEmpty) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Say hello — messages here are end-to-end encrypted.",
                                    color = chatColors().textSecondary,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                items(displayMessages.size, key = { displayMessages[it].id }) { index ->
                    val message = displayMessages[index]
                    // QA2 Q2-22: one Column fixes the order, so the day chip sits above its first message (a reverseLayout
                    // list stacks an item's separate children bottom-up, which drew the chip under the bubble).
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Date separator: compare with previous message (next in list since reversed)
                        if (index < displayMessages.size - 1) {
                            val nextMsg = displayMessages[index + 1]
                            val msgDate =
                                message.timestamp
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            val nextDate =
                                nextMsg.timestamp
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            if (msgDate != nextDate) {
                                DateSeparator(msgDate)
                            }
                        } else if (displayMessages.size > 1) {
                            // First message in conversation — show its date
                            val msgDate =
                                message.timestamp
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            DateSeparator(msgDate)
                        }
                        // Call-log entries render as a centered pill, not a sender bubble.
                        if (message.isCallLog) {
                            CallLogPill(message.callLog!!)
                            return@items
                        }
                        // System notes (e.g. "contact rotated their key") render as a centered info pill (#225).
                        // TX-1: a mode note is drawn in today's words with the header's name; the stored text is unchanged.
                        if (message.isSystemNote) {
                            SystemNotePill(
                                SystemNoteText.display(message.id, message.text, noteName, noteCopy),
                            )
                            return@items
                        }
                        val fileProgress = message.fileHash?.let { fileDownloadProgress[it] }
                        val fileDownloadFailed = message.fileHash?.let { it in fileDownloadFailures } ?: false
                        val isOutgoingInFlight =
                            message.isOutgoing &&
                                message.isPending &&
                                message.fileHash != null &&
                                uploadProgress != null
                        MessageBubble(
                            message = message,
                            fundsProgress = privacyStatus.pendingFundsProgress,
                            messageById = messageById,
                            zecPriceUsd = zecPriceUsd,
                            onDeleteMessage = onDeleteMessage,
                            onReplyClick = { replyToMessage = message },
                            onReactionClick = { emoji -> onSendReaction(message.id, emoji) },
                            onPayRequest = { amountZatoshi, requestId ->
                                // Confirm before spending — see pendingPayRequest dialog below.
                                pendingPayRequest = amountZatoshi to requestId
                            },
                            onImageClick = { path -> fullscreenImagePath = path },
                            onMarkFileViewed = onMarkFileViewed,
                            onRetryMessage = onRetryMessage,
                            // Receiver-side download failed: bubble shows a tap-to-retry affordance that
                            // re-fetches using the message's own serialized ZFILE + peer address.
                            downloadFailed = fileDownloadFailed,
                            onRetryDownload = {
                                message.fileZfileContent?.let { onRetryDownload(it, message.peerAddress) }
                            },
                            highlightSearch = searchQuery.takeIf { it.isNotBlank() },
                            // Sender bubble shows uploadProgress; receiver bubble shows download fraction.
                            bubbleProgress = if (isOutgoingInFlight) uploadProgress else fileProgress,
                            // D9: gates the "unverified" chip on non-authenticated incoming bubbles.
                            isE2EReady = conversation.isE2EReady,
                            // Round 8 P3: a payment request offers Pay only when this chat has a real address to pay.
                            canPay = isValidAddress,
                        )
                    }
                }

                // Welcome ZEC suggestion — shown at the top (last in reversed list)
                if (showWelcomeZecSuggestion && onSendWelcomeZec != null) {
                    item(key = "welcome_zec") {
                        WelcomeZecCard(onSend = { showWelcomeZecConfirm = true })
                    }
                }
            }
        }
    }

    // Reset-encryption confirm gate (destructive, user-initiated recovery).
    if (showResetEncryptionConfirm) {
        AlertDialog(
            onDismissRequest = { showResetEncryptionConfirm = false },
            icon = { Icon(Icons.Default.LockReset, contentDescription = null, tint = chatColors().error) },
            // Round 8 D5: English and Spanish resources; the paid-action question with the price follows Reset.
            title = { Text(stringResource(R.string.chat_reset_encryption_title)) },
            text = { Text(stringResource(R.string.chat_reset_encryption_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetEncryptionConfirm = false
                    onResetEncryption()
                }) { Text(stringResource(R.string.chat_reset_encryption_confirm), color = chatColors().error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetEncryptionConfirm = false }) {
                    Text(stringResource(R.string.chat_reset_encryption_cancel))
                }
            },
        )
    }

    // B17 — disappearing-messages TTL picker.
    if (showDisappearingDialog) {
        var selected by remember(showDisappearingDialog) { mutableStateOf(disappearingTtlSeconds) }
        var customMinutes by remember(showDisappearingDialog) { mutableStateOf("") }
        // Round 8 D10 (DECISIONS_NIGHT §5): every word of the dialog comes from English and Spanish resources; the timed
        // presets read the chat_ttl_* plurals the ⋮ menu shows.
        val presets = listOf(0L, 60L, 3600L, 86400L, 604800L)
        AlertDialog(
            onDismissRequest = { showDisappearingDialog = false },
            icon = { Icon(Icons.Default.Timer, contentDescription = null, tint = chatColors().primary) },
            title = { Text(stringResource(R.string.disappearing_dialog_title)) },
            text = {
                Column {
                    presets.forEach { secs ->
                        val label = if (secs == 0L) stringResource(R.string.disappearing_dialog_off) else ttlLabel(secs)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selected = secs; customMinutes = "" }.padding(vertical = 6.dp),
                        ) {
                            RadioButton(selected = selected == secs && customMinutes.isBlank(), onClick = { selected = secs; customMinutes = "" })
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        RadioButton(selected = customMinutes.isNotBlank(), onClick = {})
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.disappearing_dialog_custom) + " ")
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { v -> customMinutes = v.filter { it.isDigit() }.take(6) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.disappearing_dialog_custom_hint)) },
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Round 8 D10 (DECISIONS_NIGHT §5): expiry tombstones a message (58279b394), so the dialog says it is for good.
                    val isShielded = conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT
                    Text(
                        listOfNotNull(
                            stringResource(R.string.disappearing_dialog_body, conversation.displayName),
                            stringResource(R.string.disappearing_dialog_permanent),
                            stringResource(R.string.disappearing_dialog_body_shielded).takeIf { isShielded },
                        ).joinToString(" "),
                        fontSize = 12.sp,
                        color = chatColors().textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // A custom value of 0 means OFF (0L), NOT a coerced 60s — coerceIn(60, …) previously
                    // turned a "0" the user typed to mean "off" into a silent 1-minute TTL. Blank falls
                    // back to the selected preset radio.
                    val secs = customMinutes.toLongOrNull()
                        ?.let { if (it <= 0L) 0L else (it * 60L).coerceIn(60L, 31_536_000L) }
                        ?: selected
                    showDisappearingDialog = false
                    onSetDisappearingTtl(secs)
                }) { Text(stringResource(R.string.disappearing_dialog_apply), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDisappearingDialog = false }) { Text(stringResource(R.string.disappearing_dialog_cancel)) }
            },
        )
    }

    // Confirm before fulfilling an incoming payment request (real ZEC leaves the wallet). Prevents an
    // accidental one-tap spend from a "Pay X ZEC" bubble in a scrolling list.
    pendingPayRequest?.let { (payAmountZatoshi, payRequestId) ->
        // R8-fixup F8: the requested amount AND what the payment takes from the wallet in all (the platform fee and the
        // network fee come on top), in the phone's language with its decimal separator (PaidActionCopy.zec). D-6 (owner
        // W-10): ONE exact figure from the payment's quote, never "at least"; Pay waits for it and pays exactly it.
        val payLocale = rememberDesiredFormatLocale()
        val payZec = PaidActionCopy.zec(Zatoshi(payAmountZatoshi), payLocale)
        val payQuote by produceState<PaymentQuote?>(null, payAmountZatoshi, payRequestId) {
            value = onQuotePayment(payAmountZatoshi, fulfilMemoText(payRequestId), true)
        }
        val payExact = payQuote as? PaymentQuote.Exact
        AlertDialog(
            onDismissRequest = { pendingPayRequest = null },
            icon = { Icon(Icons.Default.AttachMoney, contentDescription = null, tint = chatColors().primary) },
            title = { Text(stringResource(R.string.pay_request_confirm_title, payZec)) },
            text = {
                Column {
                    if (payExact != null) {
                        Text(
                            stringResource(
                                R.string.pay_request_confirm_body,
                                payZec,
                                conversation.displayName,
                                PaidActionCopy.zec(payExact.total, payLocale),
                                PaidActionCopy.zec(payExact.platformFee, payLocale),
                                PaidActionCopy.zec(payExact.networkFee, payLocale),
                            )
                        )
                    } else {
                        PaymentQuoteStatus(payQuote, payLocale)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Bridge 3: the payment line.
                    Text(
                        stringResource(R.string.upgrade_payment_line, conversation.displayName) + " " +
                            stringResource(R.string.upgrade_payment_not_lost),
                        fontSize = 12.sp,
                        color = chatColors().textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = payExact != null,
                    onClick = {
                        if (payExact != null) {
                            pendingPayRequest = null
                            onFulfillPaymentRequest(payAmountZatoshi, payRequestId, payExact.quote)
                        }
                    },
                ) { Text(stringResource(R.string.pay_request_confirm_button, payZec), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPayRequest = null }) { Text(stringResource(R.string.paid_action_cancel)) }
            },
        )
    }

    // Confirm before the one-tap "Send Welcome ZEC" suggestion spends real funds. ES-W: the send is submitted
    // at once (no review screen), so the confirm names the amount AND what leaves the wallet in all (the platform fee and
    // the network fee on top), in the phone's number format. D-6 (owner W-10): ONE exact figure from the payment's quote,
    // never the floor; Send waits for it and pays exactly it.
    if (showWelcomeZecConfirm) {
        val welcomeLocale = rememberDesiredFormatLocale()
        val welcomeZec = PaidActionCopy.zec(WelcomeZec.amount, welcomeLocale)
        val welcomeQuote by produceState<PaymentQuote?>(null) {
            value = onQuotePayment(WelcomeZec.AMOUNT_ZATOSHI, WelcomeZec.MEMO, false)
        }
        val welcomeExact = welcomeQuote as? PaymentQuote.Exact
        AlertDialog(
            onDismissRequest = { showWelcomeZecConfirm = false },
            icon = { Icon(Icons.Default.AttachMoney, contentDescription = null, tint = chatColors().primary) },
            title = { Text(stringResource(R.string.welcome_zec_confirm_title, welcomeZec)) },
            text = {
                Column {
                    if (welcomeExact != null) {
                        Text(
                            stringResource(
                                R.string.welcome_zec_confirm_body,
                                welcomeZec,
                                conversation.displayName,
                                PaidActionCopy.zec(welcomeExact.total, welcomeLocale),
                                PaidActionCopy.zec(welcomeExact.platformFee, welcomeLocale),
                                PaidActionCopy.zec(welcomeExact.networkFee, welcomeLocale),
                            )
                        )
                    } else {
                        PaymentQuoteStatus(welcomeQuote, welcomeLocale)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Bridge 3: the payment line, as in the Payment dialog and the pay-request confirm.
                    Text(
                        stringResource(R.string.upgrade_payment_line, conversation.displayName) + " " +
                            stringResource(R.string.upgrade_payment_not_lost),
                        fontSize = 12.sp,
                        color = chatColors().textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = welcomeExact != null,
                    onClick = {
                        if (welcomeExact != null) {
                            showWelcomeZecConfirm = false
                            onSendWelcomeZec?.invoke(welcomeExact.quote)
                        }
                    },
                ) { Text(stringResource(R.string.welcome_zec_send_button, welcomeZec), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showWelcomeZecConfirm = false }) { Text(stringResource(R.string.paid_action_cancel)) }
            },
        )
    }

    // Payment Dialog
    if (showPaymentDialog) {
        PaymentDialog(
            balance = balance,
            peerSendable = peerSendableBalance,
            zecPriceUsd = zecPriceUsd,
            recipientName = conversation.displayName,
            prefilledTemplate = selectedTemplate,
            quotePayment = { amountZatoshi, memo -> onQuotePayment(amountZatoshi, memo, false) },
            onDismiss = {
                showPaymentDialog = false
                selectedTemplate = null
            },
            onSendPayment = { amount, memo, quote ->
                // ZC-250: a payment refused before anything was built (a key change, another send running) keeps the
                // dialog open with what was typed; a key change also shows its notice in the banner.
                if (onSendPayment(amount, memo, quote)) {
                    showPaymentDialog = false
                    selectedTemplate = null
                }
            }
        )
    }

    // Safety Number Verification Dialog
    // Fullscreen image viewer
    if (fullscreenImagePath != null) {
        val path = fullscreenImagePath!!
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullscreenImagePath = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            val fhash = remember(path) { java.io.File(path).name }
            var bitmap by remember(path) {
                mutableStateOf<android.graphics.Bitmap?>(null)
            }
            // Re-decode when the download state for this file changes (progress ticks/clears on
            // completion, or the failure flag flips) — otherwise a retry that succeeds while the
            // fullscreen viewer is open would never repaint the image (the effect was keyed only on
            // the invariant path).
            androidx.compose.runtime.LaunchedEffect(path, fileDownloadProgress[fhash], fhash in fileDownloadFailures) {
                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { decodeSampledBitmap(path, reqPx = 2048) }.getOrNull()
                }
            }
            androidx.compose.runtime.DisposableEffect(path) {
                onDispose {
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap = null
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullscreenImagePath = null },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_fullscreen_image_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Cache miss OR decode failure: show a progress + status so the user
                    // doesn't see a silent black screen and assume the app froze.
                    val pathFile = remember(path) { java.io.File(path) }
                    val exists = pathFile.exists()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Resolve the message backing this fullscreen path so we can detect a failed
                        // download (by fileHash) and offer retry instead of a perpetual spinner.
                        val fullscreenMsg = remember(path, fileDownloadFailures) {
                            conversation.messages
                                .firstOrNull { it.fileHash != null && pathFile.name == it.fileHash }
                        }
                        val fullscreenFailed = fullscreenMsg?.fileHash?.let { it in fileDownloadFailures } ?: false
                        // Retry needs the serialized ZFILE to re-fetch. Older messages keep the
                        // fileHash but not the ZFILE payload — for those a retry button would be a
                        // dead tap, so show an explanatory line instead of a fake affordance.
                        val fullscreenZfile = fullscreenMsg?.fileZfileContent
                        if (!exists && fullscreenFailed && fullscreenZfile != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onRetryDownload(fullscreenZfile, fullscreenMsg.peerAddress)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.chat_fullscreen_retry_download),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.chat_fullscreen_download_failed_retry),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                            }
                        } else if (!exists && fullscreenFailed) {
                            Text(
                                text = stringResource(R.string.chat_fullscreen_download_failed_no_data),
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        } else if (!exists) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = chatColors().primary
                            )
                            Text(
                                text = stringResource(R.string.chat_transfer_downloading),
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.chat_fullscreen_cannot_preview),
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                // ZC-1694 Q2-66: Save and Share beside the X, the same actions as the bubble's long-press menu, shown only
                // while the picture's bytes are on disk and it is not view-once (the menu's own rule). ZC-1731: a message that is not
                // found shows neither (fail closed).
                val viewerContext = LocalContext.current
                val viewerMessage = remember(path) { conversation.messages.firstOrNull { it.fileHash == fhash } }
                val viewerFile = remember(path) { java.io.File(path) }
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (bmp != null && viewerMessage != null && !viewerMessage.fileViewOnce && viewerFile.exists()) {
                        IconButton(
                            onClick = { saveFileToDownloads(viewerContext, viewerFile, viewerMessage.fileType) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.chat_message_menu_save),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = { shareFile(viewerContext, viewerFile, viewerMessage.fileType) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.chat_message_menu_share),
                                tint = Color.White,
                            )
                        }
                    }
                    IconButton(
                        onClick = { fullscreenImagePath = null },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_fullscreen_close),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    // ZC-545 (WAIVERS.md W-32): the one dialog of a remembered refused key. It shows the safety number of OUR key with
    // THAT key (the pair an accept adopts), when the key first arrived, how many other keys arrived later, and what
    // accepting costs in this chat's mode. It keeps the snapshot it opened with: Accept passes both keys of it, so a
    // key or an own key that changed meanwhile is never adopted. Not their key names this same snapshot's refused key
    // and keeps the old one. It is hidden when the snapshot has no old key (interrupted accept). Close changes nothing.
    if (showKeyReviewDialog) {
        val snapshot = keyReviewSnapshot
        if (snapshot == null) {
            showKeyReviewDialog = false
        } else {
            val firstReceived =
                stringResByDateTime(
                    java.time.ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(snapshot.firstSeenMs),
                        java.time.ZoneId.systemDefault(),
                    ),
                    useFullFormat = true,
                ).getValue()
            AlertDialog(
                onDismissRequest = { showKeyReviewDialog = false },
                title = {
                    Text(stringResource(R.string.chat_key_changed_review_title), color = chatColors().textPrimary)
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(R.string.chat_key_changed_review_body),
                            color = chatColors().textSecondary,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = snapshot.safetyNumber.chunked(4).joinToString(" "),
                            color = chatColors().primary,
                            fontSize = 18.sp,
                            fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF101828), shape = RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_key_changed_offer_received, firstReceived),
                            color = chatColors().textSecondary,
                            fontSize = 12.sp,
                        )
                        if (snapshot.laterOffers > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.chat_key_changed_other_keys, snapshot.laterOffers),
                                color = chatColors().error,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                stringResource(
                                    if (conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
                                        R.string.chat_key_changed_review_cost_shielded
                                    } else {
                                        R.string.chat_key_changed_review_cost_paid
                                    },
                                ),
                            color = chatColors().textSecondary,
                            fontSize = 12.sp,
                        )
                        if (snapshot.contactIsOlder) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.chat_key_changed_review_older),
                                color = chatColors().textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        if (!snapshot.oldKeyStored) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.chat_key_changed_interrupted),
                                color = chatColors().error,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showKeyReviewDialog = false
                            onAcceptRefusedKey(snapshot.refusedKey, snapshot.ourKey)
                        },
                    ) {
                        Text(stringResource(R.string.chat_key_changed_accept_this_key), color = chatColors().primary)
                    }
                },
                dismissButton = {
                    Row {
                        if (snapshot.oldKeyStored) {
                            TextButton(
                                onClick = {
                                    showKeyReviewDialog = false
                                    onDismissRefusedKey(snapshot.refusedKey)
                                },
                            ) {
                                Text(stringResource(R.string.chat_key_changed_dismiss_key), color = chatColors().primary)
                            }
                        }
                        TextButton(onClick = { showKeyReviewDialog = false }) {
                            Text(stringResource(R.string.chat_key_changed_review_close), color = chatColors().primary)
                        }
                    }
                },
                containerColor = chatColors().surface,
            )
        }
    }

    // D3-3: turn off Extra Security, asked first (resetQuantumShield).
    if (showQsTurnOffDialog) {
        AlertDialog(
            onDismissRequest = { showQsTurnOffDialog = false },
            title = { Text(stringResource(R.string.quantum_shield_turn_off_title), color = Color(0xFFE8EDF5)) },
            text = {
                Text(
                    stringResource(R.string.quantum_shield_turn_off_body),
                    color = Color(0xFFE8EDF5),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showQsTurnOffDialog = false
                    onResetQuantumShield()
                }) {
                    Text(stringResource(R.string.quantum_shield_turn_off_confirm), color = Color(0xFFFF3344))
                }
            },
            dismissButton = {
                TextButton(onClick = { showQsTurnOffDialog = false }) {
                    Text(stringResource(R.string.quantum_shield_turn_off_cancel), color = Color(0xFF00E5FF))
                }
            },
            containerColor = Color(0xFF0D1117),
        )
    }

    // ZC-1479: the mode chip's dialog: what this mode does and costs (the picker's own line), the Extra Security status
    // that left the chat body, and the way to change the mode.
    if (showModeDialog) {
        val dialogExtraSecurity =
            runCatching { QuantumShieldStatus.valueOf(quantumShieldStatus) }.getOrDefault(QuantumShieldStatus.NONE)
        val dialogClaimAlert = runCatching { QsAlert.valueOf(quantumShieldAlert) }.getOrDefault(QsAlert.NONE)
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(conversationMode.label(), color = chatColors().textPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(modeBlurbRes(conversationMode)), color = chatColors().textSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.chat_extra_security_banner, stringResource(dialogExtraSecurity.labelRes())),
                        color = chatColors().textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (dialogExtraSecurity != QuantumShieldStatus.NONE) {
                        Text(stringResource(R.string.quantum_shield_setup_intro), color = chatColors().textSecondary, fontSize = 13.sp)
                    }
                    // D3-3: the other phone's signed claim confirms the key (reassurance, so here and not in the body).
                    if (dialogExtraSecurity == QuantumShieldStatus.ACTIVE && dialogClaimAlert == QsAlert.CONFIRMED) {
                        Text(stringResource(R.string.quantum_shield_keys_confirmed), color = chatColors().textSecondary, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text(stringResource(R.string.chat_header_dialog_close), color = chatColors().primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showModeDialog = false
                    onPickConversationMode()
                }) {
                    Text(stringResource(R.string.chat_mode_dialog_change), color = chatColors().primary)
                }
            },
            containerColor = chatColors().surface,
        )
    }

    // ZC-1471: the header lock's dialog. E2E is always on in every ZCHAT chat (NIP-17 gift wrap for Open/Tunnel, the
    // per-message-key ratchet for Shielded); Round 8 D9: a Shielded chat whose contact has not answered the key exchange
    // says its messages are protected by Zcash's shielded encryption only (the send path decides with the same predicate).
    if (showLockDialog) {
        val e2eRow = e2eMenuRow(conversation.appLayerE2EPending)
        // QA2 Q2-28: an Open chat with no safety number says so instead of sending the user to one. U7 review
        // follow-up: an Open chat with an earlier key exchange has one (the Verify button below shows), so it keeps
        // the e2e text.
        val lockSubtitle =
            if (conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN &&
                !conversation.appLayerE2EPending &&
                (safetyNumber == null || !conversation.isE2EReady)
            ) {
                R.string.chat_menu_e2e_subtitle_open
            } else {
                e2eRow.subtitle
            }
        // QA2 Q2-53: the Unverified-contact notice that was a banner of its own.
        val unverified = safetyNumber != null && conversation.isE2EReady && !isVerified && !isKeyChanged
        AlertDialog(
            onDismissRequest = { showLockDialog = false },
            title = { Text(stringResource(e2eRow.title), color = chatColors().textPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(lockSubtitle), color = chatColors().textSecondary, fontSize = 13.sp)
                    if (unverified) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.chat_unverified_contact_body),
                            color = chatColors().textSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLockDialog = false }) {
                    Text(stringResource(R.string.chat_header_dialog_close), color = chatColors().primary)
                }
            },
            dismissButton = {
                if (safetyNumber != null && conversation.isE2EReady) {
                    TextButton(onClick = {
                        showLockDialog = false
                        showSafetyNumberDialog = true
                    }) {
                        Text(stringResource(R.string.chat_menu_verify_safety_number), color = chatColors().primary)
                    }
                }
            },
            containerColor = chatColors().surface,
        )
    }

    if (showSafetyNumberDialog && safetyNumber != null) {
        AlertDialog(
            onDismissRequest = { showSafetyNumberDialog = false },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = chatColors().primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.chat_safety_number_title),
                        color = chatColors().textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    // ZC-1475: the close cross; the dialog's only other action is Mark as verified.
                    IconButton(onClick = { showSafetyNumberDialog = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_safety_number_close_cd),
                            tint = chatColors().textSecondary,
                        )
                    }
                }
            },
            text = {
                Column {
                    // #190: surface a CHANGED key right where the user re-verifies. A key change on an
                    // established contact is a MITM/rotation signal that invalidates the safety number
                    // they previously compared — so the verify dialog (not just the inline banner) must
                    // tell them the old number is stale and to re-compare the NEW one before trusting.
                    if (isKeyChanged) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = chatColors().error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            // ZC-545: reached only while no new key is remembered, so the number below is still the
                            // OLD pair's; the text says so (it used to call it "this new number").
                            Text(
                                text = stringResource(R.string.chat_key_changed_safety_dialog_old_number),
                                color = chatColors().error,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(
                        text = stringResource(R.string.chat_safety_number_compare),
                        color = chatColors().textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // Display safety number in groups of 4
                    Text(
                        text = safetyNumber.chunked(4).joinToString(" "),
                        color = chatColors().primary,
                        fontSize = 18.sp,
                        fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF101828),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_safety_number_mismatch),
                        color = chatColors().error.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                    )
                    if (isVerified) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.chat_safety_number_you_verified),
                            color = chatColors().primary,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            // ZC-1475: one primary action (Mark as verified, until the contact is verified) and the close cross in the
            // title; no "Done" beside it. Only offered once; a key change clears the flag and the option reappears.
            // Marking is the user's out-of-band confirmation, not a wire-protocol change, so it is fully
            // backward-compatible.
            confirmButton = {
                if (SafetyNumberButton.MARK_VERIFIED in SafetyNumberButtons.forState(isVerified)) {
                    Button(onClick = {
                        onMarkVerified()
                        showSafetyNumberDialog = false
                    }) {
                        Text(stringResource(R.string.chat_safety_number_mark_verified))
                    }
                }
            },
            containerColor = chatColors().surface,
        )
    }

    // Nickname Edit Dialog
    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text(stringResource(R.string.contact_nickname_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.contact_nickname_prompt),
                        fontSize = 15.sp,
                        color = chatColors().textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        // Cap nickname length so a pathological input can't break list/header layouts.
                        onValueChange = { if (it.length <= 40) nicknameText = it },
                        placeholder = { Text(stringResource(R.string.contact_nickname_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = Conversation.truncateAddress(conversation.peerAddress),
                        fontSize = 13.sp,
                        color = chatColors().textSecondary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Local contact photo (Phase 1) — viewer-side override, stored only on this device.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            showNicknameDialog = false
                            contactAvatarPicker.launch("image/*")
                        }) {
                            Text(stringResource(R.string.photo_change))
                        }
                        TextButton(onClick = {
                            avatarScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                avatarStore.removeContactAvatar(conversation.peerAddress)
                            }
                        }) {
                            Text(stringResource(R.string.photo_remove), color = chatColors().error)
                        }
                    }
                }
            },
            confirmButton = {
                // Saving a BLANK field REMOVES the nickname (ZchatPreferences.setNickname treats blank as delete — see
                // nickname_clearBySettingBlank test). QA2 Q2-26: "Remove nickname" is offered only when there is one to
                // remove; with none, a blank field has nothing to save and the action is off.
                val removesNickname = nicknameText.isBlank() && conversation.hasContactName
                TextButton(
                    enabled = nicknameText.isNotBlank() || conversation.hasContactName,
                    onClick = {
                        onNicknameChange(conversation.peerAddress, nicknameText.trim())
                        showNicknameDialog = false
                    }
                ) {
                    Text(
                        stringResource(
                            if (removesNickname) {
                                R.string.contact_nickname_remove
                            } else {
                                R.string.contact_nickname_save
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    nicknameText = conversation.contactName ?: ""
                    showNicknameDialog = false
                }) {
                    Text(stringResource(R.string.chat_list_dialog_cancel))
                }
            }
        )
    }

    // Amount Picker Dialog
    if (showAmountPicker) {
        AlertDialog(
            onDismissRequest = { showAmountPicker = false },
            title = { Text(stringResource(R.string.chat_amount_picker_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.chat_amount_picker_body),
                        fontSize = 15.sp,
                        color = chatColors().textSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    amountOptions.forEachIndexed { index, amount ->
                        val zecAmount = amount / 100_000_000.0
                        val isSelected = index == selectedAmountIndex
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    selectedAmountIndex = index
                                    showAmountPicker = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    chatColors().primary.copy(alpha = 0.15f)
                                else chatColors().bgElevated
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format("%.5f ZEC", zecAmount),
                                    fontSize = 17.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "$amount zatoshi",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAmountPicker = false }) {
                    Text(stringResource(R.string.chat_action_cancel))
                }
            }
        )
    }

    // Time-Lock Composer Dialog
    if (showTimeLockDialog) {
        TimeLockComposerDialog(
            currentBlockHeight = currentBlockHeight,
            onDismiss = { showTimeLockDialog = false },
            onSendScheduledMessage = { message, timestamp ->
                onSendScheduledMessage(message, timestamp)
                showTimeLockDialog = false
            },
            onSendBlockLockedMessage = { message, height ->
                onSendBlockLockedMessage(message, height)
                showTimeLockDialog = false
            },
            onSendPaymentLockedMessage = { message, zatoshi ->
                onSendPaymentLockedMessage(message, zatoshi)
                showTimeLockDialog = false
            },
            onSendConditionalMessage = { message, answer, hint ->
                onSendConditionalMessage(message, answer, hint)
                showTimeLockDialog = false
            }
        )
    }

    // Payment Request Composer Dialog
    if (showPaymentRequestDialog) {
        PaymentRequestComposerDialog(
            recipientName = conversation.displayName,
            zecPriceUsd = zecPriceUsd,
            requestSendCost = paymentRequestSendCost(conversationMode),
            onDismiss = { showPaymentRequestDialog = false },
            onSendRequest = { amountZatoshi, reason ->
                // ZC-250: a refused request (a key change, a Keystone account, a busy send) keeps the dialog open with
                // the typed amount and reason. A request held behind its cost question answers true and closes it.
                if (onSendPaymentRequest(amountZatoshi, reason)) {
                    showPaymentRequestDialog = false
                }
            }
        )
    }
}

// Common reaction emojis
private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    messageById: Map<String, ChatMessage>,
    zecPriceUsd: Double? = null,
    onDeleteMessage: (String) -> Unit,
    onReplyClick: () -> Unit,
    onReactionClick: (emoji: String) -> Unit,
    onPayRequest: (amountZatoshi: Long, requestId: String) -> Unit = { _, _ -> },
    onImageClick: (imagePath: String) -> Unit = {},
    onMarkFileViewed: (fileHash: String) -> Unit = {},
    onRetryMessage: (messageId: String) -> Unit = {},
    downloadFailed: Boolean = false,
    onRetryDownload: () -> Unit = {},
    highlightSearch: String? = null,
    bubbleProgress: Float? = null,
    // ZC-1271: how far the wallet's soonest confirming note has come.
    fundsProgress: co.electriccoin.zcash.ui.screen.chat.model.ConfirmationProgress? = null,
    // D9 "mark, don't reroute": true once this conversation's E2E session is established. Gates the
    // "unverified" chip — in a not-yet-established chat plaintext is expected, so no marker there.
    isE2EReady: Boolean = false,
    // Round 8 P3 (PHONE_TRIAGE F1): the chat's key is a real address; false hides a payment request's Pay button.
    canPay: Boolean,
    modifier: Modifier = Modifier
) {
    // Theme-aware colors
    val colors = chatColors()

    // Clipboard for the "Copy" affordance — non-deprecated Compose API. We copy the human-readable
    // displayText, never the raw ZMSG/ZFILE/ZBOOT memo.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val copyContext = LocalContext.current

    val isOutgoing = message.isOutgoing
    val isPending = message.isPending
    // ZC-946 part 1 (review 19 Oct, finding 2): an early row arms no menu, and the pass's row starts with it closed.
    var showMenu by remember(message.optimistic) { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Find quoted message if this is a reply (O(1) map lookup instead of O(n) linear search)
    val quotedMessage = message.replyToId?.let { replyId ->
        messageById[replyId]
    }

    // Theme-aware bubble colors
    val bubbleColor = when {
        isOutgoing && isPending -> colors.outgoingBubble.copy(alpha = 0.6f) // Lighter for pending
        isOutgoing -> colors.outgoingBubble
        else -> colors.incomingBubble
    }

    // Bubble foreground: dark themes use textPrimary/textSecondary (readable on dark bubbles).
    // In LIGHT theme the OUTGOING bubble is teal (BubbleSent ~#006B78) — dark textPrimary (~3:1)
    // and textSecondary (~1.26:1, invisible) fail there — so use white (textOnAccent) for
    // outgoing-light text + a subtle-white timestamp. Incoming + all dark cases unchanged.
    val cc = chatColors()
    val onLightSentBubble = isOutgoing && cc.isLight
    val textColor = if (onLightSentBubble) cc.textOnAccent else cc.textPrimary
    val timeColor = if (onLightSentBubble) cc.textOnAccent.copy(alpha = 0.8f) else cc.textSecondary

    // Deep Cyber (Nightwire) styling — the neon border / asymmetric corners apply ONLY in the
    // Nightwire palettes. (The old `colors.background == chatColors().background` was a self-compare
    // that was constant-true in every theme, so Default/Light/Dark wrongly got Nightwire bubbles.)
    val isZypherpunkMode = colors.isNightwire

    Column(modifier = modifier) {

        Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .combinedClickable(
                            onClick = { },
                            onLongClick = { if (!message.optimistic) showMenu = true }
                        )
                        .then(
                            if (isZypherpunkMode) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = if (isOutgoing) chatColors().bubbleSentBorder
                                    else chatColors().bubbleReceivedBorder,
                                    shape = RoundedCornerShape(
                                        topStart = if (isOutgoing) 20.dp else 4.dp,
                                        topEnd = if (isOutgoing) 20.dp else 20.dp,
                                        bottomStart = if (isOutgoing) 20.dp else 20.dp,
                                        bottomEnd = if (isOutgoing) 4.dp else 20.dp
                                    )
                                )
                            } else Modifier
                        ),
                    shape = RoundedCornerShape(
                        topStart = if (isZypherpunkMode) (if (isOutgoing) 20.dp else 4.dp) else 16.dp,
                        topEnd = if (isZypherpunkMode) 20.dp else 16.dp,
                        bottomStart = if (isZypherpunkMode) 20.dp else (if (isOutgoing) 16.dp else 4.dp),
                        bottomEnd = if (isZypherpunkMode) (if (isOutgoing) 4.dp else 20.dp) else (if (isOutgoing) 4.dp else 16.dp)
                    ),
                    colors = CardDefaults.cardColors(containerColor = bubbleColor)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        // Quoted message preview (if this is a reply). Guard on non-blank so a reply whose
                        // preview couldn't be resolved doesn't render an empty quote bar (use isNotBlank,
                        // not != null — the old default was "" which is non-null and drew a blank box).
                        val quoteText = quotedMessage?.displayText?.takeIf { it.isNotBlank() }
                            ?: message.replyToPreview?.takeIf { it.isNotBlank() }
                        if (quoteText != null) {
                            QuotedMessagePreview(
                                previewText = quoteText,
                                isOutgoing = isOutgoing
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // D9 "mark, don't reroute" — forged-bubble marker. In an ESTABLISHED (E2E-ready)
                        // thread, an incoming on-chain body that was NOT a ratcheted E2E1 blob is a
                        // plaintext memo anyone can pay to send into this thread. Mark it inline above the
                        // body instead of rerouting (rerouting would break first-contact/legacy/ZTL/ZREQ
                        // threading). Visual-only: reply/react stay enabled. Never shown for outgoing
                        // bubbles, before the E2E session exists, or (Round 10b R10B-2, F-R9C-3) on a message
                        // mined before the chat's key exchange completed (plaintext was expected then). An
                        // unverified payment request has its own chip that says why it matters.
                        val unverifiedChip =
                            MessageAuthentication.unverifiedChip(
                                isOutgoing = isOutgoing,
                                authenticated = message.authenticated,
                                isE2EReady = isE2EReady,
                                preSession = message.preSession,
                                isPaymentRequest = message.paymentRequest != null,
                            )
                        if (unverifiedChip != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.warning.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            when (unverifiedChip) {
                                                UnverifiedChip.PAYMENT_REQUEST -> R.string.chat_unverified_payment_request
                                                UnverifiedChip.SENDER -> R.string.chat_unverified_sender
                                            }
                                        ),
                                    fontSize = 11.sp,
                                    color = colors.warning,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // #bug-timelock-flicker — the snapshot timeLock.isUnlocked is only recomputed on a
                        // tx-set change, so a SCHEDULED lock kept showing the locked card AFTER its wall-clock
                        // unlock (until the next sync re-derived the list) — the "timer disappeared then the
                        // content appeared a minute later" report. Drive the reveal LIVE: tick once a second
                        // and treat a SCHEDULED lock as unlocked the moment the clock passes its time. The
                        // gate (message.timeLock != null) is constant for a given message, so the composable
                        // call-order stays stable. Non-SCHEDULED locks keep the snapshot (they need on-chain
                        // height / a payment / an answer that a wall clock can't decide).
                        val mTimeLock = message.timeLock
                        // Gate the ticker ONLY on fields that are CONSTANT for a given message (lock presence,
                        // type, unlock time) — NEVER on the changing isLocked snapshot — so the number of
                        // composables called stays stable across recompositions (a conditional remember/
                        // LaunchedEffect that appears/disappears would crash Compose).
                        val scheduledUnlockTs =
                            if (mTimeLock != null && mTimeLock.lockType == TimeLockType.SCHEDULED) mTimeLock.unlockTimestamp else null
                        val nowSecLive = if (scheduledUnlockTs != null) {
                            var nowSec by remember(message.id) { mutableStateOf(System.currentTimeMillis() / 1000) }
                            LaunchedEffect(message.id) {
                                while (true) {
                                    kotlinx.coroutines.delay(1000)
                                    nowSec = System.currentTimeMillis() / 1000
                                }
                            }
                            nowSec
                        } else {
                            0L
                        }
                        val liveLocked = when {
                            !message.isLocked || mTimeLock == null -> false
                            scheduledUnlockTs != null -> nowSecLive < scheduledUnlockTs // reveal when clock passes
                            else -> true // block/payment/conditional locks keep the snapshot decision
                        }

                        // Time-locked message display
                        if (liveLocked && mTimeLock != null) {
                            LockedMessageContent(
                                timeLock = mTimeLock,
                                isOutgoing = isOutgoing,
                                // message.text already holds the plaintext body (locks are display-only).
                                revealedContent = message.text
                            )
                        } else if (message.isPaymentRequest && message.paymentRequest != null) {
                            // Payment request display
                            PaymentRequestContent(
                                paymentRequest = message.paymentRequest,
                                zecPriceUsd = zecPriceUsd,
                                isOutgoing = isOutgoing,
                                canPay = canPay,
                                onPayClick = {
                                    onPayRequest(message.paymentRequest.amountZatoshi, message.id)
                                },
                                failed = isOutgoing && message.effectiveStatus == MessageStatus.FAILED,
                                paymentPending = message.paymentRequest.paymentPending,
                            )
                        } else if (message.fileHash != null && message.fileViewOnce && message.fileViewed) {
                            // View-once already consumed — render the locked placeholder so the
                            // user can see that something WAS there without being able to recover it.
                            ViewOnceConsumedPlaceholder(
                                isAudio = message.fileType == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A,
                                isOutgoing = isOutgoing,
                            )
                        } else if (message.fileHash != null && message.fileViewOnce && !message.fileViewed) {
                            // View-once not yet consumed — sealed bubble. Tap reveals the media,
                            // then marks the file viewed (wipes cache, flips bubble to the
                            // "consumed" placeholder above on next render).
                            ViewOnceRevealBubble(
                                message = message,
                                bubbleProgress = bubbleProgress,
                                isOutgoing = isOutgoing,
                                onImageClick = onImageClick,
                                onMarkViewed = { onMarkFileViewed(message.fileHash) },
                                downloadFailed = downloadFailed,
                                onRetryDownload = onRetryDownload,
                            )
                        } else if (message.fileHash != null && message.fileType == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A) {
                            // Voice message bubble — play/pause + duration + transfer progress.
                            VoiceMessageBubble(
                                message = message,
                                bubbleProgress = bubbleProgress,
                                isOutgoing = isOutgoing,
                            )
                        } else if (message.fileHash != null) {
                            // File message — try to render cached decrypted image
                            val context = LocalContext.current
                            val cacheFile = remember(message.fileHash) {
                                java.io.File(context.cacheDir, "zchat_files/${message.fileHash}")
                            }
                            // Stack the upload/download progress bar above the image preview
                            // when a transfer is in flight. The bar is hidden once the bubble
                            // settles to the decoded image.
                            val activeProgress = bubbleProgress
                            if (activeProgress != null && activeProgress < 1f) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(transferLabelRes(message.isOutgoing)),
                                            fontSize = 11.sp,
                                            color = chatColors().textSecondary,
                                        )
                                        Text(
                                            text = "${(activeProgress * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            color = chatColors().primary,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { activeProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = chatColors().primary,
                                        trackColor = chatColors().bgInput,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                            // Decode bitmap off the main thread to avoid UI jank
                            var bitmap by remember(message.fileHash) {
                                mutableStateOf<android.graphics.Bitmap?>(null)
                            }
                            // Round 10b R10B-3 (F-R9C-2): also keyed on the transfer state, as the fullscreen
                            // viewer is. A photo received while the chat is open shows before its download has
                            // written the cache; the progress entry is cleared when the download ends (and the
                            // failure flag flips on a retry), so the decode runs again then and the photo draws
                            // inline instead of staying a file row until the chat is reopened. Once decoded, a
                            // progress tick (an outgoing upload) does not decode it again.
                            androidx.compose.runtime.LaunchedEffect(message.fileHash, bubbleProgress, downloadFailed) {
                                if (bitmap != null) return@LaunchedEffect
                                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (cacheFile.exists()) {
                                        runCatching {
                                            decodeSampledBitmap(cacheFile.absolutePath, reqPx = 800)
                                        }.getOrNull()
                                    } else {
                                        null
                                    }
                                }
                            }
                            androidx.compose.runtime.DisposableEffect(message.fileHash) {
                                onDispose {
                                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                                    bitmap = null
                                }
                            }
                            val bmp = bitmap
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.chat_cd_shared_image),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        // Use combinedClickable so a long-press routes to the
                                        // bubble menu (Save / Share / Forward) instead of being
                                        // swallowed by the tap-to-fullscreen handler.
                                        .combinedClickable(
                                            onClick = { onImageClick(cacheFile.absolutePath) },
                                            onLongClick = { if (!message.optimistic) showMenu = true },
                                        ),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                // Cache miss — render blurhash placeholder if available. Decode
                                // off the composition thread (was blocking main with N decodes for
                                // long lists). Empty blurhash short-circuits without scheduling.
                                val blurhashKey = message.fileBlurhash.takeIf { !it.isNullOrEmpty() }
                                var blurhashBitmap by remember(blurhashKey) {
                                    mutableStateOf<android.graphics.Bitmap?>(null)
                                }
                                androidx.compose.runtime.LaunchedEffect(blurhashKey) {
                                    blurhashBitmap = blurhashKey?.let { hash ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                            runCatching {
                                                val pixels = co.electriccoin.zcash.ui.screen.chat.filesharing
                                                    .BlurhashDecoder.decode(hash, 32, 32)
                                                if (pixels != null) {
                                                    android.graphics.Bitmap.createBitmap(
                                                        pixels, 32, 32,
                                                        android.graphics.Bitmap.Config.ARGB_8888
                                                    )
                                                } else null
                                            }.getOrNull()
                                        }
                                    }
                                }
                                androidx.compose.runtime.DisposableEffect(blurhashKey) {
                                    onDispose {
                                        blurhashBitmap?.takeIf { !it.isRecycled }?.recycle()
                                        blurhashBitmap = null
                                    }
                                }
                                val blurBmp = blurhashBitmap
                                if (blurBmp != null) {
                                    // Placeholder tap forwards to the same fullscreen-opener path
                                    // as the loaded-image branch — the dialog itself decodes the
                                    // cache file or shows a download spinner if it's not there yet.
                                    Box(modifier = Modifier.clickable { onImageClick(cacheFile.absolutePath) }) {
                                        androidx.compose.foundation.Image(
                                            bitmap = blurBmp.asImageBitmap(),
                                            contentDescription = "Loading image",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Text(
                                            text = message.text,
                                            fontSize = 13.sp,
                                            color = Color.White,
                                            // Cap the caption so a long one can't overflow the 200dp
                                            // image bounds on small screens.
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .padding(8.dp)
                                                .fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                        )
                                        // Image ZFILEs almost always carry a blurhash, so the
                                        // download-failed branch below would never show. Overlay a
                                        // tap-to-retry chip on the blur preview instead. Retry needs
                                        // the serialized ZFILE — if it's missing (old messages with
                                        // only a fileHash), show a non-interactive note, not a dead tap.
                                        if (downloadFailed) {
                                            val canRetry = message.fileZfileContent != null
                                            Row(
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.Black.copy(alpha = 0.6f))
                                                    .then(
                                                        if (canRetry) Modifier.clickable { onRetryDownload() }
                                                        else Modifier
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (canRetry) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "Retry download",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = if (canRetry) "Tap to retry" else "Data missing",
                                                    fontSize = 13.sp,
                                                    color = Color.White,
                                                )
                                            }
                                        }
                                    }
                                } else if (downloadFailed) {
                                    // Download failed and nothing is cached: offer an explicit retry
                                    // instead of a dead placeholder. Mirrors the failed-send affordance.
                                    // Retry needs the serialized ZFILE; if it's missing, say so rather
                                    // than render a button that silently does nothing.
                                    val canRetry = message.fileZfileContent != null
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .then(
                                                if (canRetry) Modifier.clickable { onRetryDownload() }
                                                else Modifier
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (canRetry) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Retry download",
                                                tint = chatColors().error,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = if (canRetry) "Download failed — tap to retry"
                                            else "Download failed — message data missing",
                                            fontSize = 13.sp,
                                            color = chatColors().error,
                                        )
                                    }
                                } else {
                                    // Cached NON-IMAGE file (Document/PDF/ZIP/TXT): open it in an external
                                    // viewer. Routing this tap to onImageClick (the fullscreen IMAGE viewer)
                                    // dead-ended at "Cannot preview this file" for anything that isn't an image.
                                    Text(
                                        text = message.text,
                                        fontSize = 15.sp,
                                        color = chatColors().primary,
                                        modifier = Modifier.clickable {
                                            openFileExternally(context, cacheFile, message.fileType)
                                        }
                                    )
                                }
                            }
                        } else if (message.status == MessageStatus.FAILED && AttachmentIds.isUpload(message.id)) {
                            // R10A-2 (F-R9P-4): a failed upload row's label in the phone's language now, not the one
                            // written into the row when it failed (the view model outlives a language change).
                            val failedLabel = AttachmentIds.failedLabelRes(message.id)
                            Text(
                                text = if (failedLabel != null) stringResource(failedLabel) else message.text,
                                fontSize = 15.sp,
                                color = textColor
                            )
                        } else if (message.text.startsWith("\uD83D\uDCCE ")) {
                            // File message without cached image — show placeholder
                            Text(
                                text = message.text,
                                fontSize = 15.sp,
                                color = chatColors().primary
                            )
                        } else {
                            // Message text with optional search highlighting
                            if (highlightSearch != null && message.displayText.contains(highlightSearch, ignoreCase = true)) {
                                HighlightedText(
                                    text = message.displayText,
                                    highlight = highlightSearch,
                                    textColor = textColor
                                )
                            } else {
                                // ZC-1273: a payment says what it moved, drawn here so older rows show it too.
                                val paymentLine = PaymentBubble.of(message, message.paymentAmountZatoshi)
                                if (paymentLine != null) {
                                    val paidAmount = zecDisplay(Zatoshi(paymentLine.amountZatoshi), rememberDesiredFormatLocale())
                                    Text(
                                        text =
                                            if (paymentLine.isSent) {
                                                stringResource(R.string.chat_payment_bubble_sent, paidAmount)
                                            } else {
                                                stringResource(R.string.chat_payment_bubble_received, paidAmount)
                                            },
                                        fontSize = 15.sp,
                                        color = textColor
                                    )
                                    paymentLine.memo?.let { memo ->
                                        Text(text = memo, fontSize = 13.sp, color = textColor)
                                    }
                                } else {
                                    Text(
                                        text = message.text,
                                        fontSize = 15.sp,
                                        color = textColor
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text =
                                    formatMessageTime(
                                        message.timestamp,
                                        DateFormat.is24HourFormat(LocalContext.current),
                                        rememberDesiredFormatLocale(),
                                    ),
                                fontSize = 11.sp,
                                color = timeColor
                            )
                            // Show status indicator for outgoing messages
                            if (isOutgoing) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageStatusIndicator(
                                    status = message.effectiveStatus,
                                    isOutgoing = true
                                )
                            }
                        }
                    }
                }
            }

            // Long-press dropdown menu. ZC-946 part 1: none on a send drawn before the pass has it (reply, react, delete).
            DropdownMenu(
                expanded = showMenu && !message.optimistic,
                onDismissRequest = { showMenu = false },
                containerColor = chatColors().bgElevated
            ) {
                // Reply option
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_message_menu_reply)) },
                    onClick = {
                        showMenu = false
                        onReplyClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null
                        )
                    }
                )
                // React option
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_message_menu_react)) },
                    onClick = {
                        showMenu = false
                        showReactionPicker = true
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.EmojiEmotions,
                            contentDescription = null
                        )
                    }
                )
                // Copy option — available on every bubble (sent and received). Copies the decoded
                // displayText so the clipboard never holds a raw protocol memo. File/locked bubbles
                // have no plain text to copy, so the item is hidden for them.
                val copyText = message.displayText
                val isCopyable = message.fileHash == null && !message.isLocked &&
                    !message.isPaymentRequest && copyText.isNotBlank()
                if (isCopyable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_message_menu_copy)) },
                        onClick = {
                            showMenu = false
                            clipboardManager.setText(AnnotatedString(copyText))
                            Toast.makeText(copyContext, copyContext.getString(R.string.chat_message_copied), Toast.LENGTH_SHORT).show()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null
                            )
                        }
                    )
                }
                // Save / Forward / Share — only for file bubbles whose bytes are still on
                // disk. We skip view-once (the bytes are gone) and skip messages whose
                // cache hasn't been downloaded yet.
                val context = LocalContext.current
                val fileHash = message.fileHash
                if (fileHash != null && !message.fileViewOnce) {
                    val cacheFile = remember(fileHash) {
                        java.io.File(context.cacheDir, "zchat_files/$fileHash")
                    }
                    if (cacheFile.exists()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_message_menu_save)) },
                            onClick = {
                                showMenu = false
                                saveFileToDownloads(context, cacheFile, message.fileType)
                            },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_message_menu_share)) },
                            onClick = {
                                showMenu = false
                                shareFile(context, cacheFile, message.fileType)
                            },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        )
                        val forwardRouter = koinInject<co.electriccoin.zcash.ui.NavigationRouter>()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_message_menu_forward)) },
                            onClick = {
                                showMenu = false
                                // Forward into the IN-APP SharePicker (pick a ZCHAT contact), not the OS
                                // share sheet. The per-conversation wrappedKey can't be reused, so we
                                // forward the DECRYPTED bytes: copy them into a unique share-inbox cache
                                // file, arm PendingShareStore, and open the picker. Only images route
                                // through the picker (handlePickedImage is image-only); non-image files
                                // fall back to the OS share sheet, which can hand them anywhere.
                                val ft = message.fileType
                                val isImage = ft == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.JPEG ||
                                    ft == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.PNG ||
                                    ft == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.GIF ||
                                    ft == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.WEBP
                                if (isImage) {
                                    val copied = runCatching {
                                        val dir = java.io.File(context.cacheDir, "share-inbox").apply { mkdirs() }
                                        val ext = ft.mimeType.substringAfter('/')
                                        val dest = java.io.File(dir, "fwd_${System.currentTimeMillis()}_${System.nanoTime()}.$ext")
                                        cacheFile.copyTo(dest, overwrite = true)
                                        dest
                                    }.getOrNull()
                                    if (copied != null) {
                                        co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.setPending(
                                            co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.PendingShare.Images(listOf(copied)),
                                        )
                                        forwardRouter.forward(co.electriccoin.zcash.ui.screen.chat.SharePicker)
                                    } else {
                                        // Copy failed — degrade to OS share rather than dead-ending.
                                        shareFile(context, cacheFile, message.fileType, asForward = true)
                                    }
                                } else {
                                    shareFile(context, cacheFile, message.fileType, asForward = true)
                                }
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        )
                    }
                }
                // Delete option
                DropdownMenuItem(
                    // Round 8 D8: in the phone's language.
                    text = { Text(stringResource(R.string.chat_message_menu_delete)) },
                    onClick = {
                        showMenu = false
                        showDeleteConfirm = true
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = chatColors().error
                        )
                    }
                )
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(stringResource(R.string.chat_delete_message_title)) },
                    text = {
                        // C4 (HONOR check 3b): a failed row that never reached the submit is not told that its
                        // transaction stays on the blockchain — none was made. In the phone's language.
                        Text(
                            stringResource(
                                co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
                                    .deleteConfirmBodyFor(message)
                            ),
                            fontSize = 13.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                onDeleteMessage(message.id)
                            }
                        ) {
                            Text(stringResource(R.string.chat_delete_message_confirm), color = chatColors().error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text(stringResource(R.string.chat_action_cancel))
                        }
                    },
                )
            }

            // Reaction picker
            DropdownMenu(
                expanded = showReactionPicker,
                onDismissRequest = { showReactionPicker = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            // 44dp tappable area (was ~32dp — below the comfortable touch-target size).
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    showReactionPicker = false
                                    onReactionClick(emoji)
                                }
                                .wrapContentSize(Alignment.Center)
                        )
                    }
                }
            }
        }

        // Pending/queued send affordance (Bug 8b): a message waiting for the previous tx's change
        // notes to confirm on-chain. Shows a slim progress bar + coarse ETA. Outgoing-only; a
        // genuine "in flight" file transfer already renders its own bar inside the bubble, so we
        // skip those (bubbleProgress != null) to avoid a double indicator.
        if (isOutgoing && message.effectiveStatus == MessageStatus.SENDING && bubbleProgress == null) {
            // A NOSTR/TUNNEL outgoing message is delivered instantly over a relay — it does NOT wait
            // for a Zcash block, so it must NOT show the on-chain ~75s block-time ETA / countdown.
            // Two cases: "nostr-out-…" = published over NOSTR; "tunnel-wait-…" = queued, waiting for
            // the secure connection (handshake) before it flushes over NOSTR. Both are off-chain and
            // free. Only on-chain pending sends (id "pending_…") get the block-based estimate.
            // R10A-2 (F-R9P-3): a photo, file or voice note still uploading is not on the chain either.
            val estimate = co.electriccoin.zcash.ui.screen.chat.model.PendingSendEstimate
            val isOffChainRow = estimate.isOffChainRow(message.id, message.txId?.toString())
            // The latest attempt's start: a Retry stamps it, and the row's own time is its first start.
            val queuedAtMillis = message.submitStartedAtMillis ?: message.timestamp.toEpochMilli()
            // Recompute roughly once a second so the label/bar advance while waiting.
            var nowMillis by remember(message.id) { mutableStateOf(System.currentTimeMillis()) }
            androidx.compose.runtime.LaunchedEffect(message.id) {
                while (true) {
                    nowMillis = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1000L)
                }
            }
            val elapsedSeconds = ((nowMillis - queuedAtMillis) / 1000L).coerceAtLeast(0L)
            // NOSTR sends and uploads are not tied to blocks → indeterminate bar, no block-ETA progress fraction.
            val progress = if (isOffChainRow) {
                null
            } else {
                estimate.progressFor(elapsedSeconds)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, end = 4.dp)
                    .widthIn(max = 220.dp)
            ) {
                // R2-7 (c): no block countdown, and a Tunnel wait that says what to do after 10 minutes.
                // R10A-2 (F-R9P-3): one choice for every row (upload, Tunnel wait, relay, on-chain).
                val minutes = estimate.elapsedMinutes(elapsedSeconds)
                // ZC-1271: a first message waiting for its funds says how far they are.
                val funds = fundsProgress?.takeIf { message.waitsForFunds }
                Text(
                    text =
                        if (funds != null) {
                            stringResource(R.string.chat_pending_funds_confirming, funds.confirmations, funds.required, funds.etaMinutes)
                        } else {
                            stringResource(estimate.rowLabelRes(message.id, message.txId?.toString(), elapsedSeconds), minutes)
                        },
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = chatColors().primary,
                        trackColor = chatColors().bgInput,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = chatColors().primary,
                        trackColor = chatColors().bgInput,
                    )
                }
            }
        }

        // P2a: a FAILED send whose submit started may already be on its way; say so above Retry. F4: in the words of
        // what the history pass read about it (attemptVerdict); before any pass, as before.
        if (isOutgoing && message.effectiveStatus == MessageStatus.FAILED && message.sendMayHaveGoneOut) {
            Text(
                text = attemptRowNotice(message.attemptVerdict),
                fontSize = 11.sp,
                color = chatColors().error,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp, end = 4.dp),
            )
        }

        // R2-7 (c): say why the send failed, above Retry (the reason the user was shown when it failed).
        co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
            .failureReasonFor(message)
            ?.let { reason ->
                Text(
                    text = localizedSendNotice(reason),
                    fontSize = 11.sp,
                    color = chatColors().error,
                    textAlign = TextAlign.End,
                    modifier =
                        Modifier
                            .align(Alignment.End)
                            .widthIn(max = 260.dp)
                            .padding(top = 4.dp, end = 4.dp),
                )
            }

        // Retry affordance (Bug 8b): a FAILED outgoing send can be re-queued with one tap.
        if (isOutgoing && message.effectiveStatus == MessageStatus.FAILED && !message.optimistic) {
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, end = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRetryMessage(message.id) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.chat_retry_sending),
                    tint = chatColors().error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_retry),
                    fontSize = 12.sp,
                    color = chatColors().error,
                )
            }
        }

        // Reactions display
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(start = if (isOutgoing) 0.dp else 8.dp, end = if (isOutgoing) 8.dp else 0.dp)
                    .align(if (isOutgoing) Alignment.End else Alignment.Start),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Group reactions by emoji
                val groupedReactions = message.reactions.groupBy { it.emoji }
                groupedReactions.forEach { (emoji, reactions) ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = chatColors().bgElevated
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = emoji, fontSize = 14.sp)
                            if (reactions.size > 1) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${reactions.size}",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sealed view-once bubble. Tap to reveal the underlying image or play the audio. Consumption is
 * immediate: an image fires [onMarkViewed] as soon as it renders, audio as soon as playback starts
 * — [onMarkViewed] wipes the local cache and flips the bubble into the consumed state. A reveal
 * the user never "finishes" is still burned.
 */
@Composable
private fun ViewOnceRevealBubble(
    message: co.electriccoin.zcash.ui.screen.chat.model.ChatMessage,
    bubbleProgress: Float?,
    isOutgoing: Boolean,
    onImageClick: (imagePath: String) -> Unit,
    onMarkViewed: () -> Unit,
    downloadFailed: Boolean = false,
    onRetryDownload: () -> Unit = {},
) {
    val context = LocalContext.current
    val cacheFile = remember(message.fileHash) {
        java.io.File(context.cacheDir, "zchat_files/${message.fileHash}")
    }
    var revealed by remember(message.fileHash) { mutableStateOf(false) }
    val isAudio = message.fileType == co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A
    val ready = cacheFile.exists()

    // Transfer progress bar (image bubble pattern).
    Column(modifier = Modifier.fillMaxWidth()) {
        if (bubbleProgress != null && bubbleProgress < 1f) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(transferLabelRes(isOutgoing)),
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
                Text(
                    text = "${(bubbleProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = chatColors().primary,
                )
            }
            LinearProgressIndicator(
                progress = { bubbleProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = chatColors().primary,
                trackColor = chatColors().bgInput,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (!revealed) {
            // A failed download must NOT keep the bubble stuck on "Downloading…": when the file
            // isn't cached and the fetch failed, the row becomes a tap-to-retry affordance instead
            // (mirrors the regular file bubble). Tapping reveal stays disabled until the file lands.
            val sealedFailed = !ready && downloadFailed
            // Sealed placeholder — tap to reveal (or, on failure, tap to retry).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(chatColors().bgInput)
                    .clickable(enabled = ready || sealedFailed) {
                        if (sealedFailed) onRetryDownload() else if (ready) revealed = true
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (sealedFailed) Icons.Default.Refresh else Icons.Default.Lock,
                    contentDescription = if (sealedFailed) "Retry download" else null,
                    tint = if (sealedFailed) chatColors().error else chatColors().primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAudio) "View once voice message" else "View once photo",
                        color = chatColors().textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = when {
                            sealedFailed -> "Download failed — tap to retry"
                            ready -> "Tap to ${if (isAudio) "listen" else "view"} — opens once only"
                            else -> stringResource(R.string.chat_transfer_downloading)
                        },
                        color = if (sealedFailed) chatColors().error else chatColors().textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else if (isAudio) {
            // SECURITY (view-once): consume as soon as playback STARTS, not only on natural
            // completion — a paused/aborted listen must still burn the message. EphemeralAudioPlayer
            // fires onConsume once when player.start() is first called.
            EphemeralAudioPlayer(cacheFile = cacheFile, onConsume = onMarkViewed)
        } else {
            // Decode + render image inline. SECURITY (view-once): the reveal IS the single view, so
            // we consume (wipe + mark viewed) the moment the bitmap is on screen — see the
            // LaunchedEffect below — rather than waiting for a second tap into fullscreen (which the
            // user may never make). The in-memory bitmap keeps rendering after the file is wiped.
            var bitmap by remember(message.fileHash) {
                mutableStateOf<android.graphics.Bitmap?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(message.fileHash) {
                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (cacheFile.exists()) {
                        runCatching { decodeSampledBitmap(cacheFile.absolutePath, reqPx = 800) }.getOrNull()
                    } else null
                }
            }
            androidx.compose.runtime.DisposableEffect(message.fileHash) {
                onDispose {
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap = null
                }
            }
            val bmp = bitmap
            // Consume on reveal: fires once when the bitmap first appears. After this the on-disk
            // cache is wiped, so re-opening the conversation shows the consumed placeholder.
            androidx.compose.runtime.LaunchedEffect(message.fileHash, bmp != null) {
                if (bmp != null) onMarkViewed()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "View once photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        // Fullscreen is best-effort: the file is already wiped, so the viewer
                        // shows a cache-miss once consumed. The inline image above is the view.
                        .clickable { onImageClick(cacheFile.absolutePath) },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = "Loading…",
                    color = chatColors().textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Compact "this view-once is gone" placeholder shown after consumption. */
@Composable
private fun ViewOnceConsumedPlaceholder(isAudio: Boolean, isOutgoing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(chatColors().bgInput)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.LockOpen,
            contentDescription = null,
            tint = chatColors().textSecondary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (isAudio) "Voice message — listened" else "Photo — viewed",
            color = chatColors().textSecondary,
            fontSize = 13.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        )
    }
}

/**
 * One-shot audio player used inside a view-once bubble. Auto-starts when composed and fires
 * [onConsume] exactly once the moment playback first STARTS — a view-once voice message must be
 * burned even if the listener pauses or leaves before it finishes. Releases the player on dispose.
 */
@Composable
private fun EphemeralAudioPlayer(cacheFile: java.io.File, onConsume: () -> Unit) {
    val player = remember { android.media.MediaPlayer() }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var consumed by remember { mutableStateOf(false) }
    val onConsumeRef = androidx.compose.runtime.rememberUpdatedState(onConsume)
    // Play from a private temp copy: consuming on start securely WIPES the original in place, which
    // would otherwise corrupt the bytes MediaPlayer is still streaming. Copy first, wipe the
    // original, stream from the copy, delete the copy on dispose.
    val playbackFile = remember { mutableStateOf<java.io.File?>(null) }

    androidx.compose.runtime.LaunchedEffect(cacheFile.absolutePath) {
        runCatching {
            val tmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File.createTempFile("vo_aud_", ".tmp", cacheFile.parentFile).also { t ->
                    cacheFile.copyTo(t, overwrite = true)
                }
            }
            playbackFile.value = tmp
            player.reset()
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            durationMs = player.duration.toLong()
            player.start()
            isPlaying = true
        }.onSuccess {
            // SECURITY (view-once): consume on first start, not on completion.
            if (!consumed) {
                consumed = true
                onConsumeRef.value()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            kotlinx.coroutines.delay(100)
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = android.media.MediaPlayer.OnCompletionListener {
            isPlaying = false
        }
        player.setOnCompletionListener(listener)
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
            // Remove the plaintext temp copy used for playback.
            playbackFile.value?.let { f -> runCatching { f.delete() } }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = chatColors().primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        val totalSec = (durationMs / 1000).coerceAtLeast(1).toInt()
        val posSec = (positionMs / 1000).toInt().coerceAtMost(totalSec)
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (totalSec > 0) posSec.toFloat() / totalSec else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = chatColors().primary,
                trackColor = chatColors().bgInput,
            )
            Text(
                text = "%d:%02d / %d:%02d".format(posSec / 60, posSec % 60, totalSec / 60, totalSec % 60),
                fontSize = 11.sp,
                color = chatColors().textSecondary,
            )
        }
    }
}

/**
 * Voice-message playback bubble. Backed by [MediaPlayer]; releases its resources via
 * DisposableEffect when the bubble leaves composition so we don't leak handles when
 * the user scrolls.
 *
 * The bar above the player echoes the same Uploading/Downloading copy as the image
 * bubble so the two paths feel consistent.
 */
@Composable
private fun VoiceMessageBubble(
    message: co.electriccoin.zcash.ui.screen.chat.model.ChatMessage,
    bubbleProgress: Float?,
    isOutgoing: Boolean,
) {
    val context = LocalContext.current
    val cacheFile = remember(message.fileHash) {
        java.io.File(context.cacheDir, "zchat_files/${message.fileHash}")
    }
    val player = remember { android.media.MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember(message.fileHash) {
        mutableStateOf(message.fileDurationMs ?: 0L)
    }
    var positionMs by remember(message.fileHash) { mutableStateOf(0L) }

    // Whenever the cache file becomes available, prepare the player. We re-prepare on every
    // fileHash change so swapping conversations doesn't keep a stale source attached.
    androidx.compose.runtime.LaunchedEffect(message.fileHash, cacheFile.exists()) {
        if (!cacheFile.exists()) return@LaunchedEffect
        runCatching {
            player.reset()
            player.setDataSource(cacheFile.absolutePath)
            player.prepare()
            val real = player.duration.toLong()
            if (real > 0) durationMs = real
        }
    }

    // Position-poller — only runs while playback is active.
    androidx.compose.runtime.LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            kotlinx.coroutines.delay(100)
        }
    }

    // Stop-on-complete listener — install once.
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = android.media.MediaPlayer.OnCompletionListener {
            isPlaying = false
            positionMs = 0L
        }
        player.setOnCompletionListener(listener)
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (bubbleProgress != null && bubbleProgress < 1f) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(transferLabelRes(isOutgoing)),
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
                Text(
                    text = "${(bubbleProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = chatColors().primary,
                )
            }
            LinearProgressIndicator(
                progress = { bubbleProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = chatColors().primary,
                trackColor = chatColors().bgInput,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val ready = cacheFile.exists()
            IconButton(
                onClick = {
                    if (!ready) return@IconButton
                    if (isPlaying) {
                        runCatching { player.pause() }
                        isPlaying = false
                    } else {
                        runCatching { player.start() }
                        isPlaying = true
                    }
                },
                enabled = ready,
                modifier = Modifier
                    .size(48.dp) // 48dp meets the comfortable touch-target size (was 40dp)
                    .clip(CircleShape)
                    // While downloading, the disabled bg used to be bgInput — only ~3% off the
                    // bubble background in some themes, so a dead button looked tappable. Use a
                    // clearly muted textTertiary tint and a download glyph until the file lands.
                    .background(if (ready) chatColors().primary else chatColors().textTertiary.copy(alpha = 0.35f)),
            ) {
                Icon(
                    imageVector = when {
                        !ready -> Icons.Default.Download
                        isPlaying -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        !ready -> stringResource(R.string.chat_cd_downloading_voice)
                        isPlaying -> stringResource(R.string.chat_cd_pause)
                        else -> stringResource(R.string.chat_cd_play)
                    },
                    tint = if (ready) chatColors().textOnAccent else chatColors().textSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val totalSec = (durationMs / 1000).coerceAtLeast(1).toInt()
                val posSec = (positionMs / 1000).toInt().coerceAtMost(totalSec)
                LinearProgressIndicator(
                    progress = { if (totalSec > 0) posSec.toFloat() / totalSec else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = chatColors().primary,
                    trackColor = chatColors().bgInput,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "%d:%02d / %d:%02d".format(posSec / 60, posSec % 60, totalSec / 60, totalSec % 60),
                    fontSize = 11.sp,
                    color = chatColors().textSecondary,
                )
            }
        }
    }
}

/**
 * Preview of a quoted message inside a reply bubble
 */
@Composable
private fun QuotedMessagePreview(
    previewText: String,
    isOutgoing: Boolean
) {
    // #249 convention: the OUTGOING bubble is dark-teal in EVERY theme, so its foreground must be LIGHT
    // in every theme. textOnAccent is light only in LIGHT themes (it is near-black 0xFF080B12 in
    // Nightwire dark — i.e. it's the on-bright-accent color, NOT an on-dark-bubble color); textPrimary is
    // light only in DARK themes. So pick per theme, exactly like the main bubble at the top of this file.
    val cc = chatColors()
    val onBubble = if (cc.isLight) cc.textOnAccent else cc.textPrimary
    val bgColor = if (isOutgoing) {
        onBubble.copy(alpha = 0.15f)
    } else {
        cc.primary.copy(alpha = 0.1f)
    }
    val textColor = if (isOutgoing) {
        onBubble.copy(alpha = 0.9f)
    } else {
        cc.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .background(
                    if (isOutgoing) onBubble.copy(alpha = 0.6f)
                    else cc.primary
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = previewText.take(80) + if (previewText.length > 80) "..." else "",
            fontSize = 13.sp,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Every case-insensitive occurrence of [query] in [text], as ranges into [text] itself.
 *
 * Extracted from the composable so it can be tested: the highlighter used to locate matches in
 * `text.lowercase()` and then slice the ORIGINAL string with those indices. `lowercase()` is not
 * length-preserving — Turkish dotted capital I (U+0130) lowercases to two characters, i + U+0307 —
 * so past the first such character the two index spaces drift apart and `substring` walks off the
 * end, crashing the chat screen for anyone whose keyboard can produce one. Searching the original
 * string with `ignoreCase` keeps a single index space, which is the property these ranges carry.
 *
 * Returns non-overlapping ranges in ascending order. A blank [query] matches nothing, so callers
 * do not have to special-case it to avoid an infinite loop.
 */
internal fun searchMatchRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = 0
    while (start <= text.length) {
        val idx = text.indexOf(query, start, ignoreCase = true)
        if (idx < 0) break
        ranges += idx until (idx + query.length)
        start = idx + query.length
    }
    return ranges
}

/**
 * Text with highlighted search matches
 */
@Composable
private fun HighlightedText(
    text: String,
    highlight: String,
    textColor: Color
) {
    if (highlight.isBlank()) {
        Text(text = text, fontSize = 15.sp, color = textColor)
        return
    }
    // Actually highlight every case-insensitive occurrence of the query (previously this was a stub
    // that rendered plain text, so search "matches" were never visually marked).
    val highlightBg = Color.Yellow.copy(alpha = 0.5f)
    // Ranges index the ORIGINAL string — see searchMatchRanges for why that matters.
    val annotated =
        buildAnnotatedString {
            var cursor = 0
            for (range in searchMatchRanges(text, highlight)) {
                append(text.substring(cursor, range.first))
                withStyle(SpanStyle(background = highlightBg, color = textColor)) {
                    append(text.substring(range.first, range.last + 1))
                }
                cursor = range.last + 1
            }
            append(text.substring(cursor))
        }
    Text(text = annotated, fontSize = 15.sp, color = textColor)
}

/**
 * Reply preview component shown above the message input
 */
@Composable
private fun ReplyPreview(
    message: ChatMessage,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().primary.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reply icon and indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(chatColors().primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isOutgoing) "Replying to yourself" else "Replying to message",
                    fontSize = 11.sp,
                    color = chatColors().primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = message.text.take(50) + if (message.text.length > 50) "..." else "",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = chatColors().textSecondary
                )
            }
        }
    }
}

/**
 * Message status indicator showing delivery state.
 *
 * Status icons:
 * - SENDING: Clock icon (animated/pulsing)
 * - SENT: Single checkmark (gray)
 * - CONFIRMED: Double checkmark (white)
 * - READ: Double checkmark (blue)
 * - FAILED: Error icon (red)
 */
@Composable
private fun MessageStatusIndicator(
    status: MessageStatus,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    val iconSize = 14.dp
    // Status icons render INSIDE the outgoing (dark-teal-in-every-theme) bubble, so they need a LIGHT
    // tint in every theme. textOnAccent is near-black in Nightwire dark (it's the on-bright-accent
    // color) — use the #249 per-theme split so the checks aren't invisible on the dark bubble.
    val cc = chatColors()
    val onBubble = if (cc.isLight) cc.textOnAccent else cc.textPrimary

    when (status) {
        MessageStatus.SENDING -> {
            // Clock icon for sending state
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.chat_cd_sending),
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.6f)
            )
        }
        MessageStatus.SENT -> {
            // Single checkmark for sent (in mempool)
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = stringResource(R.string.chat_a11y_status_sent),
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.6f)
            )
        }
        MessageStatus.CONFIRMED -> {
            // Double checkmark for confirmed on blockchain
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.chat_a11y_status_on_chain),
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.8f)
            )
        }
        MessageStatus.READ -> {
            // Blue double checkmark for read
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.chat_a11y_status_read),
                modifier = modifier.size(iconSize),
                tint = Color(0xFF4FC3F7) // Light blue
            )
        }
        MessageStatus.FAILED -> {
            // Red error icon for failed
            Icon(
                imageVector = Icons.Default.Error,
                // Round 8 D8: the label a screen reader says, in the phone's language.
                contentDescription = stringResource(R.string.chat_message_status_failed),
                modifier = modifier.size(iconSize),
                tint = chatColors().error // theme-aware error red
            )
        }
        MessageStatus.DECLINED -> {
            // ZC-1464: the contact declined our chat request, so this relay message never reached them (no Retry).
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_message_status_declined),
                modifier = modifier.size(iconSize),
                tint = onBubble.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Composable for displaying locked message content
 */
@Composable
private fun LockedMessageContent(
    timeLock: TimeLockInfo,
    isOutgoing: Boolean,
    // The plaintext body of this message. PAYMENT/CONDITIONAL "locks" are display-only conventions —
    // the text travels UNENCRYPTED in the on-chain memo, so any wallet can already read it. We therefore
    // let a tap reveal the local text (an honest "reveal-on-request"), instead of a dead "tap to pay".
    revealedContent: String? = null,
    modifier: Modifier = Modifier
) {
    var isRevealed by remember(timeLock) { mutableStateOf(false) }
    val canReveal = revealedContent != null &&
        (timeLock.lockType == TimeLockType.PAYMENT || timeLock.lockType == TimeLockType.CONDITIONAL)
    // Locked content renders inside the outgoing (dark-teal-in-every-theme) bubble → needs a LIGHT
    // foreground in every theme; textOnAccent is near-black in Nightwire dark, so use the #249 split.
    val cc = chatColors()
    val onBubble = if (cc.isLight) cc.textOnAccent else cc.textPrimary
    val textColor = if (isOutgoing) onBubble else cc.textSecondary
    val iconColor = if (isOutgoing) onBubble.copy(alpha = 0.8f) else cc.primary

    // Live countdown: lockDescription reads the wall clock at composition, so without a ticker the
    // "Unlocks in Xm" text froze between list rebuilds (looking stuck, then jumping when a sync
    // rebuilt the list). Recompose once a second so it actually counts down smoothly. (#bug-timelock-timer)
    var clockTick by remember(timeLock) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timeLock) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            clockTick = System.currentTimeMillis()
        }
    }
    val liveDescription = remember(timeLock, clockTick) { timeLock.lockDescription }

    Column(
        modifier = if (canReveal && !isRevealed) modifier.clickable { isRevealed = true } else modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (canReveal && isRevealed) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(24.dp),
                tint = iconColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeLock.lockIcon,
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            // Once revealed, show the actual message text in place of the lock description.
            // (canReveal guarantees revealedContent is non-null here.)
            text = if (canReveal && isRevealed) revealedContent else liveDescription,
            fontSize = 15.sp,
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // Show type-specific info
        when (timeLock.lockType) {
            TimeLockType.SCHEDULED -> {
                if (!timeLock.isUnlocked) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Message will appear automatically",
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Hidden by this app until then — the text is not encrypted.",
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            TimeLockType.BLOCK_HEIGHT -> {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    // Was "trustless delivery" — false: the body rides UNENCRYPTED in the on-chain memo.
                    // Only the display timing is tied to block height.
                    text = "Set to unlock at a future Zcash block",
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeLockType.PAYMENT -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    // Honest: the "lock" is display-only and the text was never encrypted, so tapping
                    // reveals it locally — no payment is enforced (the sender is just requesting one).
                    text = if (isRevealed) {
                        "Display-only reminder — sender requested payment. The text was never encrypted."
                    } else {
                        "Tap to reveal — sender requested payment (not enforced)"
                    },
                    fontSize = 13.sp,
                    color = if (isOutgoing) onBubble.copy(alpha = 0.9f) else cc.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeLockType.CONDITIONAL -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRevealed) {
                        "Display-only reminder — sender set a secret answer. The text was never encrypted."
                    } else {
                        "Tap to reveal — answer requested (not enforced)"
                    },
                    fontSize = 13.sp,
                    color = if (isOutgoing) onBubble.copy(alpha = 0.9f) else cc.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPayClick: () -> Unit,
    onTemplatesClick: () -> Unit,
    onLockClick: () -> Unit,
    onRequestClick: () -> Unit,
    onSendImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendViewOnceImage: () -> Unit = {},
    // QA2 Q2-31: an Open or Tunnel chat with no key exchange yet: the attachment rows say the first one pays for it.
    attachmentsNeedKeyExchange: Boolean = false,
    onAmountClick: () -> Unit = {},
    selectedAmount: Long = 1000L,
    // F-R9P-1: the message being replied to (a key for the send button's price), and the price for a typed text.
    replyToId: String? = null,
    composerMessageCost: (String) -> Zatoshi,
    // ZC-696: changes after every send or arrival in this chat, so the price is computed again.
    costRefreshKey: Any? = null,
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode =
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
    // True when this is a TUNNEL chat whose one-time on-chain handshake is already complete (peer
    // NOSTR pubkey known) — so further sends are free over NOSTR and the send button shows no price.
    tunnelSendIsFree: Boolean = false,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    disabledMessage: String? = null,
    // Voice messages: parent owns the AudioRecorder lifecycle + RECORD_AUDIO permission;
    // this row only renders the mic icon (when text is empty) and the recording-state UI.
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    isRecordingViewOnce: Boolean = false,
    onMicTap: () -> Unit = {},
    onMicLongPress: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
) {
    // Theme-aware colors
    val colors = chatColors()
    val inputContext = LocalContext.current
    var showFeatureMenu by remember { mutableStateOf(false) }

    // Deep Cyber (Nightwire) neon effects — only in the Nightwire palettes (the previous
    // `colors.background == chatColors().background` self-compare was constant-true everywhere).
    val isZypherpunkMode = colors.isNightwire
    val borderColor = chatColors().borderDefault
    val surfaceColor = chatColors().surface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Top border (BorderDefault)
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(surfaceColor)
            .navigationBarsPadding() // Prevents being covered by nav bar on Fold 3
    ) {
        // Show disabled message if address is invalid
        if (disabledMessage != null) {
            Text(
                text = disabledMessage,
                fontSize = 13.sp,
                color = chatColors().error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ZC-1479 + ZC-1472 (owner, W-122 answer 3): no cost row and no "Free" row above the composer any more. The
        // price is on the send button (SendCostLabel, below) wherever a send spends ZEC: every Shielded message, and a
        // Tunnel message before its handshake is done (tunnelSendIsFree). Open and a finished Tunnel show no price.
        // R8-fixup F1: the figure is the least a message of the typed length costs (every memo it packs into, the
        // platform fee and the network fee), never the dust of one output; a send of more than one memo is asked about
        // with its exact figure first. Round 10a F-R9P-1: sized by the send's own rule (the encrypted wire, the INIT or a
        // reply, a reply's reference). ZC-696: recomputed after every send too (costRefreshKey), so the first message's
        // INIT price does not stay on the button for the next, shorter wire.
        val sendCost =
            SendCostLabel.text(
                conversationMode,
                tunnelSendIsFree,
                remember(value, selectedAmount, replyToId, conversationMode, tunnelSendIsFree, costRefreshKey) {
                    composerMessageCost(value)
                },
            )
        // R6-3: formatted with the locale Compose sees now, so the figure follows a language change (display only).
        val sendCostText = sendCost?.let { PaidActionCopy.zec(it, rememberDesiredFormatLocale()) }

        // Recording mode: replaces the normal input row with a recording bar so the
        // user can see the elapsed seconds and tap Send or Cancel. View-once recordings
        // tint the mic + label in the danger color so the user notices the mode.
        if (isRecording) {
            // View-once recordings use the danger color so the destructive "burns after one play"
            // mode is unmistakable; a regular recording uses the neutral accent.
            val micTint = if (isRecordingViewOnce) chatColors().error else chatColors().primary
            val label =
                if (isRecordingViewOnce) R.string.chat_composer_recording_view_once else R.string.chat_composer_recording
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancelRecording, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_composer_cancel_recording),
                        tint = chatColors().error,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isRecordingViewOnce) Icons.Default.Lock else Icons.Default.Mic,
                        contentDescription = null,
                        tint = micTint,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(label, recordingSeconds / 60, recordingSeconds % 60),
                        color = chatColors().textPrimary,
                        fontSize = 14.sp,
                    )
                }
                IconButton(
                    onClick = onSendRecording,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(chatColors().primary),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_composer_send_recording),
                        tint = chatColors().textOnAccent,
                    )
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Single + button for all special features
            Box(contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { showFeatureMenu = true },
                    enabled = isEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isZypherpunkMode && isEnabled) chatColors().bgElevated
                            else if (isEnabled) colors.primary else colors.backgroundLight
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_composer_features),
                        // Disabled must read as disabled in EVERY theme. The old Nightwire branch kept
                        // the primary accent even when disabled, making the read-only "+" look live.
                        tint = when {
                            !isEnabled -> chatColors().textTertiary.copy(alpha = 0.5f)
                            isZypherpunkMode -> chatColors().primary
                            else -> colors.background
                        }
                    )
                }

                // Feature menu dropdown
                DropdownMenu(
                    expanded = showFeatureMenu,
                    onDismissRequest = { showFeatureMenu = false },
                    containerColor = chatColors().bgElevated
                ) {
                    // Send Payment option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_send_payment),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_send_payment_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onPayClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.secondary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachMoney,
                                    contentDescription = null,
                                    tint = colors.background,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Time-Locked Message option
                    val attachmentNote =
                        if (attachmentsNeedKeyExchange) stringResource(R.string.chat_attachment_kex_row_note) else null
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_time_lock),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    // Time-locks are on-chain (block-height / payment / conditional unlocks
                                    // need Zcash primitives a relay can't provide), so they're Vault-only.
                                    // Say so upfront instead of letting the user compose then get rejected.
                                    text = if (conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
                                        stringResource(R.string.chat_feature_time_lock_desc)
                                    } else {
                                        stringResource(R.string.chat_feature_time_lock_shielded_only)
                                    },
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        // #bug-timelock-vault-only — time-locks are on-chain only. Disable (grey out) the
                        // item in non-Vault chats so the user can't compose a whole message then get
                        // rejected after Send. The subtitle above already says "Shielded chats only".
                        enabled = conversationMode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
                        onClick = {
                            showFeatureMenu = false
                            onLockClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = colors.background,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Quick Pay Templates option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_templates),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_templates_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onTemplatesClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(ZchatTeal),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalCafe,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Take Photo — capture via system camera
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_take_photo),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_take_photo_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                                attachmentNote?.let { Text(text = it, fontSize = 12.sp, color = chatColors().textSecondary) }
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onTakePhoto()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00C2FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Send Image option (Phase 2 file sharing) — pick from gallery
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_send_image),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_send_image_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                                attachmentNote?.let { Text(text = it, fontSize = 12.sp, color = chatColors().textSecondary) }
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onSendImage()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF7C4DFF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Send view-once Photo — gallery picker but the recipient (and we)
                    // can only see it once.
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_view_once),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_view_once_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary,
                                )
                                attachmentNote?.let { Text(text = it, fontSize = 12.sp, color = chatColors().textSecondary) }
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onSendViewOnceImage()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(chatColors().error),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )

                    // Send File — PDF / ZIP / TXT / image via document picker
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_send_file),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_send_file_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                                attachmentNote?.let { Text(text = it, fontSize = 12.sp, color = chatColors().textSecondary) }
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onSendFile()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFB300)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Request Payment option
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.chat_feature_request_payment),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.chat_feature_request_payment_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        },
                        onClick = {
                            showFeatureMenu = false
                            onRequestClick()
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00BFA5)),  // Teal variant
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RequestQuote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = {
                    // Hard-cap input; if a paste exceeds the cap, tell the user it was trimmed
                    // instead of silently dropping the overflow.
                    if (it.length > MAX_MESSAGE_INPUT_CHARS) {
                        Toast.makeText(
                            inputContext,
                            inputContext.getString(R.string.chat_composer_trimmed, MAX_MESSAGE_INPUT_CHARS),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    onValueChange(capDraft(it))
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text =
                            stringResource(
                                if (isEnabled) R.string.chat_composer_placeholder else R.string.chat_composer_placeholder_disabled
                            ),
                        color = chatColors().textTertiary
                    )
                },
                shape = RoundedCornerShape(NightwireColors.RadiusInput),
                maxLines = 4,
                enabled = isEnabled,
                // The composer hard-caps input at MAX_MESSAGE_INPUT_CHARS (a paste past the limit is
                // silently truncated). Surface a counter once the user nears the cap so the trim
                // isn't invisible; turn it red at the limit.
                supportingText = if (value.length >= MAX_MESSAGE_INPUT_CHARS - 1000) {
                    {
                        val atLimit = value.length >= MAX_MESSAGE_INPUT_CHARS
                        Text(
                            text = "${value.length} / $MAX_MESSAGE_INPUT_CHARS",
                            fontSize = 11.sp,
                            color = if (atLimit) chatColors().error else chatColors().textSecondary,
                        )
                    }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (isEnabled) onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = chatColors().bgInput,
                    unfocusedContainerColor = chatColors().bgInput,
                    focusedTextColor = chatColors().textPrimary,
                    unfocusedTextColor = chatColors().textPrimary,
                    cursorColor = chatColors().primary,
                    focusedBorderColor = chatColors().borderActive,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            val sendEnabled = value.isNotBlank() && isEnabled

            // Send button: only visible when text is entered (per spec)
            if (sendEnabled) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    // ZC-1479: the priced button is a pill as wide as its price.
                    modifier = if (sendCostText != null) Modifier.height(52.dp) else Modifier.size(52.dp)
                ) {
                    // Cyan glow for Nightwire mode
                    if (isZypherpunkMode && sendCostText == null) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(chatColors().accentPrimaryGlow)
                        )
                    }

                    if (sendCostText != null) {
                        // ZC-1479 + ZC-1472: a paid send carries its price on the button. A long press changes the amount
                        // delivered with the message, as the old cost row's tap did.
                        val sendCostDescription = stringResource(R.string.chat_send_cost_cd, sendCostText)
                        Row(
                            modifier =
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(if (isZypherpunkMode) chatColors().primary else colors.secondary)
                                    .combinedClickable(onClick = onSend, onLongClick = onAmountClick)
                                    .clearAndSetSemantics { contentDescription = sendCostDescription }
                                    .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = if (isZypherpunkMode) chatColors().textOnAccent else colors.background,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.chat_send_cost_label, sendCostText),
                                fontSize = 12.sp,
                                // QA2 Q2-42: two lines at word boundaries rather than squeezing the input.
                                maxLines = 2,
                                color = if (isZypherpunkMode) chatColors().textOnAccent else colors.background,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onSend,
                            modifier =
                                Modifier
                                    .size(if (isZypherpunkMode) 44.dp else 48.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (isZypherpunkMode) {
                                            Modifier.background(
                                                chatColors().primary,
                                                CircleShape
                                            )
                                        } else {
                                            Modifier.background(colors.secondary)
                                        }
                                    )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.chat_composer_send),
                                tint = if (isZypherpunkMode) chatColors().textOnAccent else colors.background
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
                // Mic button — only visible when the text field is empty.
                //   Tap        → regular voice message.
                //   Long-press → view-once voice message (deletes after one playback).
                // We use combinedClickable on a Box because IconButton doesn't expose a
                // long-press hook.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) chatColors().bgElevated else chatColors().backgroundLight
                        )
                        .combinedClickable(
                            enabled = isEnabled,
                            onClick = onMicTap,
                            onLongClick = onMicLongPress,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.chat_a11y_record_voice),
                        // Clearly muted when read-only so it doesn't read as a live mic in Nightwire.
                        tint = if (isEnabled) chatColors().primary
                            else chatColors().textTertiary.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/**
 * Best-effort secure delete of a plaintext view-once temp file: overwrite the bytes with random data
 * (best effort — ext4/F2FS may remap rather than rewrite in place) then unlink. Mirrors the on-consume
 * wipe in ChatViewModel so a leftover vo_aud_*.tmp can't be trivially recovered from disk/backup.
 */
private fun secureWipeAndDelete(file: java.io.File) {
    runCatching {
        if (file.exists()) {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                if (len > 0) {
                    val buf = ByteArray(8192)
                    java.security.SecureRandom().nextBytes(buf)
                    var written = 0L
                    raf.seek(0)
                    while (written < len) {
                        val n = minOf(buf.size.toLong(), len - written).toInt()
                        raf.write(buf, 0, n)
                        written += n
                    }
                    raf.fd.sync()
                }
            }
        }
    }
    runCatching { file.delete() }
}

/**
 * Best-effort save of a decrypted-locally file blob into the public Downloads (images
 * use the Pictures collection so the gallery picks them up). Uses MediaStore on Android Q+
 * so we don't need WRITE_EXTERNAL_STORAGE. Falls back to a Toast on failure.
 */
private fun saveFileToDownloads(
    context: android.content.Context,
    cacheFile: java.io.File,
    fileType: co.electriccoin.zcash.ui.screen.chat.model.ZFILEType?,
) {
    if (!cacheFile.exists()) {
        Toast.makeText(context, context.getString(R.string.chat_file_not_available), Toast.LENGTH_SHORT).show()
        return
    }
    // This path uses MediaStore.Downloads + RELATIVE_PATH inserts, both API 29 (Q)+ only. minSdk is 27,
    // so on API 27/28 dereferencing MediaStore.Downloads would NoClassDefFoundError-crash. Guard it and
    // tell the user rather than crashing (a legacy WRITE_EXTERNAL_STORAGE path is out of scope here).
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
        Toast.makeText(context, context.getString(R.string.chat_save_needs_android_10), Toast.LENGTH_LONG).show()
        return
    }
    val mime = fileType?.mimeType ?: "application/octet-stream"
    val isImage = mime.startsWith("image/")
    val isAudio = mime.startsWith("audio/")
    val ext = when (fileType) {
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.JPEG -> "jpg"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.PNG -> "png"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.GIF -> "gif"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.WEBP -> "webp"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.PDF -> "pdf"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.ZIP -> "zip"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.TXT -> "txt"
        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A -> "m4a"
        null -> "bin"
    }
    val filename = "zchat_${System.currentTimeMillis()}.$ext"
    val targetCollection = when {
        isImage -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        isAudio -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
    val relativePath = when {
        isImage -> android.os.Environment.DIRECTORY_PICTURES + "/ZCHAT"
        isAudio -> android.os.Environment.DIRECTORY_MUSIC + "/ZCHAT"
        else -> android.os.Environment.DIRECTORY_DOWNLOADS + "/ZCHAT"
    }
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
    }
    val uri = context.contentResolver.insert(targetCollection, values)
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.chat_save_no_destination), Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            cacheFile.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open destination stream")
        Toast.makeText(context, context.getString(R.string.chat_file_saved_to, relativePath, filename), Toast.LENGTH_LONG).show()
    }.onFailure {
        runCatching { context.contentResolver.delete(uri, null, null) }
        Toast.makeText(context, it.toZchatUserMessage(context.getString(R.string.chat_file_save_failed)), Toast.LENGTH_LONG).show()
    }
}

/**
 * Share a decrypted file via Android's share sheet. Uses the existing ShareFileProvider
 * authority so receiving apps get a content:// URI with read permission instead of a
 * file:// path.
 */
private fun shareFile(
    context: android.content.Context,
    cacheFile: java.io.File,
    fileType: co.electriccoin.zcash.ui.screen.chat.model.ZFILEType?,
    asForward: Boolean = false,
) {
    if (!cacheFile.exists()) {
        Toast.makeText(context, context.getString(R.string.chat_file_not_available), Toast.LENGTH_SHORT).show()
        return
    }
    // Authority is flavor-specific (foss/debug/testnet suffixes) — a flavor manifest overrides it to
    // "${applicationId}.provider" via tools:replace. Hardcoding the base authority crashed getUriForFile
    // ("Couldn't find meta-data for provider") on every non-base build. Derive it from the real packageName.
    val authority = "${context.packageName}.provider"
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, cacheFile)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = fileType?.mimeType ?: "*/*"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // ZC-1732: both chooser titles are string resources (English and Spanish).
    val chooserTitle =
        context.getString(if (asForward) R.string.chat_forward_chooser_title else R.string.chat_share_chooser_title)
    context.startActivity(android.content.Intent.createChooser(intent, chooserTitle).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Open a received NON-IMAGE file (PDF/ZIP/TXT/…) in an external viewer. Tapping such a bubble used to route
 * into the in-app fullscreen IMAGE viewer, which can't decode a document and dead-ended at "Cannot preview
 * this file" with no way to open, save, or share it. ACTION_VIEW hands it to whatever app can render the mime
 * type; if none can, we fall back to the share sheet so the user can still save/forward it.
 */
private fun openFileExternally(
    context: android.content.Context,
    cacheFile: java.io.File,
    fileType: co.electriccoin.zcash.ui.screen.chat.model.ZFILEType?,
) {
    if (!cacheFile.exists()) {
        Toast.makeText(context, context.getString(R.string.chat_file_not_available), Toast.LENGTH_SHORT).show()
        return
    }
    // Authority is flavor-specific (foss/debug/testnet suffixes) — a flavor manifest overrides it to
    // "${applicationId}.provider" via tools:replace. Hardcoding the base authority crashed getUriForFile
    // ("Couldn't find meta-data for provider") on every non-base build. Derive it from the real packageName.
    val authority = "${context.packageName}.provider"
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, cacheFile)
        val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, fileType?.mimeType ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(viewIntent)
    } catch (e: android.content.ActivityNotFoundException) {
        // No installed app can VIEW this type — offer Share instead of a dead tap.
        shareFile(context, cacheFile, fileType)
    } catch (e: IllegalArgumentException) {
        // FileProvider path/authority drift — degrade to the share sheet rather than crash.
        shareFile(context, cacheFile, fileType)
    }
}

// QA2 Q2-47: the time follows the phone's 12/24-hour setting (is24Hour from DateFormat.is24HourFormat).
private fun formatMessageTime(
    timestamp: Instant,
    is24Hour: Boolean,
    locale: java.util.Locale,
): String = clockTime(timestamp.atZone(ZoneId.systemDefault()), locale, is24Hour)

/**
 * #256 UX — a compact, COLLAPSIBLE status banner. Replaces the old stack of full-width banners that ate
 * ~40% of the chat. Renders as ONE line (icon + short label + ▾) at rest; tapping it expands to the full
 * plain-language explanation plus any action buttons. An optional × dismisses it. Keeps the inline-banner
 * model the user chose ("least change") while collapsing the clutter and explaining each status clearly.
 */
@Composable
private fun CollapsibleStatusBanner(
    icon: ImageVector,
    tint: Color,
    shortLabel: String,
    fullText: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    // The × description for a screen reader; null says "Dismiss".
    dismissDescription: String? = null,
    // ZC-250: while set, the banner is held expanded and shows this notice above its actions; tapping the banner then
    // collapses it and calls onNoticeAcknowledged.
    notice: String? = null,
    onNoticeAcknowledged: () -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    var expandedByTap by remember(shortLabel) { mutableStateOf(false) }
    val expanded = expandedByTap || notice != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f))
            .clickable {
                if (notice != null) {
                    expandedByTap = false
                    onNoticeAcknowledged()
                } else {
                    expandedByTap = !expandedByTap
                }
            }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) fullText else shortLabel,
                color = tint,
                fontSize = if (expanded) 12.sp else 13.sp,
                fontWeight = if (expanded) FontWeight.Normal else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.chat_a11y_collapse else R.string.chat_a11y_expand),
                tint = tint.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
            if (onDismiss != null) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = dismissDescription ?: stringResource(R.string.chat_a11y_dismiss),
                    tint = tint.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .clickable { onDismiss() },
                )
            }
        }
        if (notice != null) {
            Text(
                text = notice,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (expanded && actions != null) {
            // Wraps when the buttons don't fit on one line (the Shielded reminder has three).
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                actions()
            }
        }
    }
}

/** Bridge 3: the Shielded reminder after Hide, a small chip that stays in the chat and opens it again. */
@Composable
private fun ShieldedReminderChip(onClick: () -> Unit) {
    val tint = chatColors().warning
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.12f))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = stringResource(R.string.upgrade_shielded_chip), color = tint, fontSize = 12.sp)
        }
    }
}

/** Bridge 3: a static explanatory line under the chat's status banners. */
@Composable
private fun UpgradeNoticeLine(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = chatColors().textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, fontSize = 12.sp, color = chatColors().textSecondary)
    }
}

/** ZC-1339: what the banner may truthfully say for each [UnknownReason]; only NOT_ZMSG_FORMAT is "not sent using ZCHAT". */
private fun unknownSenderBody(reason: UnknownReason?): Int =
    when (reason) {
        null -> R.string.chat_unknown_sender_body_none
        UnknownReason.NOT_ZMSG_FORMAT -> R.string.chat_unknown_sender_body_not_zmsg
        UnknownReason.MALFORMED_MESSAGE -> R.string.chat_unknown_sender_body_malformed
        UnknownReason.HASH_NOT_IN_CACHE -> R.string.chat_unknown_sender_body_unknown_hash
        UnknownReason.VERSION_MISMATCH -> R.string.chat_unknown_sender_body_version
    }

@Composable
private fun UnknownSenderBanner(
    reason: UnknownReason?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().success.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "Unknown sender",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = chatColors().textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(unknownSenderBody(reason)),
                fontSize = 13.sp,
                color = chatColors().textPrimary.copy(alpha = 0.8f)
            )
        }
    }
}

/** ZC-1479: the Extra Security badge colours of the header mode chip (the old body banners' violet and amber). */
private val EXTRA_SECURITY_ON_COLOR = Color(0xFF7C4DFF)
private val EXTRA_SECURITY_PENDING_COLOR = Color(0xFFFFB800)

/** ZC-1479: the header mode chip's icon. Never an open lock: every mode is encrypted (ZC-1471). */
private fun modeChipIcon(mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode): ImageVector =
    when (mode) {
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT -> Icons.Default.Shield
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL -> Icons.Default.SyncAlt
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN -> Icons.Default.Public
    }

/** ZC-1479, REVISION 3 (ZC-1486): the chip's readable label, "Mode: Shielded", "Mode: Tunnel" or "Mode: Open". */
@androidx.annotation.StringRes
internal fun modeChipContentDescription(mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode): Int =
    when (mode) {
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT -> R.string.chat_a11y_mode_shielded
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL -> R.string.chat_a11y_mode_tunnel
        co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN -> R.string.chat_a11y_mode_open
    }

/**
 * Privacy Status Bar — slim inline indicator for shielded status.
 * Tappable to expand details. Minimal footprint to maximize chat area.
 */
@Composable
private fun PrivacyStatusCard(
    privacyStatus: PrivacyStatus,
    conversationMode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode,
    tunnelHandshakeComplete: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onTapNostr: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The wallet-pool shielding indicator (isFullyShielded) only describes the VAULT (on-chain)
    // transport. In Tunnel/Open the messages travel as NIP-17 gift-wrapped NOSTR DMs — E2E
    // encrypted but NOT on-chain — so claiming "shielded on-chain via Zcash" there is false.
    val isNostr = conversationMode.isNostrTransport
    val isShielded = privacyStatus.isFullyShielded
    // Shielded but stranded in sealed Orchard is not a success state: nothing can be sent to a peer
    // from it, so the card must not say "shielded on-chain" in green.
    val isStranded = !isNostr && privacyStatus.isOrchardStranded
    val accentColor = if ((isNostr || isShielded) && !isStranded) {
        chatColors().success
    } else {
        chatColors().warning
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // NOSTR (Tunnel/Open): the expandable panel below shows on-chain pool /
            // anonymity-set details, which are meaningless for off-chain relay chats.
            // Route the tap to E2E safety-number verification instead. On-chain/vault
            // conversations keep the original expand-to-details behaviour.
            .clickable { if (isNostr) onTapNostr() else onToggle() }
    ) {
        // Slim bar — always visible (single row, ~32dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = stringResource(R.string.chat_a11y_privacy_status),
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when {
                    // ZC-1275: a Tunnel chat's setup is on-chain and paid, until the handshake is complete.
                    TunnelSetupChip.shows(conversationMode, tunnelHandshakeComplete) ->
                        stringResource(R.string.chat_privacy_chip_tunnel_setup)
                    // Tunnel/Open: NIP-17 E2E-encrypted NOSTR DMs, off-chain.
                    isNostr -> stringResource(R.string.chat_privacy_chip_nostr)
                    isStranded && privacyStatus.isIronwoodConfirming -> {
                        val progress = privacyStatus.confirmationProgress
                        if (progress != null) {
                            stringResource(R.string.chat_privacy_chip_ironwood_confirming_progress, progress.label)
                        } else {
                            stringResource(R.string.chat_privacy_chip_ironwood_confirming)
                        }
                    }
                    isStranded -> stringResource(R.string.chat_privacy_chip_stranded)
                    // Vault: on-chain shielded. "on-chain" (not "end-to-end") — the phrase
                    // "end-to-end encrypted" is reserved for the E2E lock in the action bar.
                    isShielded -> stringResource(R.string.chat_privacy_chip_shielded)
                    else -> stringResource(R.string.chat_privacy_chip_needs_shielding)
                },
                // QA2 Q2-68: 12.sp at full colour (11.sp at 0.8 alpha read faint on light themes).
                fontSize = 12.sp,
                color = accentColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(if (isExpanded) R.string.chat_a11y_collapse else R.string.chat_a11y_details),
                tint = accentColor.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
        }

        // Expanded detail panel
        androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(chatColors().bgElevated)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.chat_privacy_card_pool), fontSize = 12.sp, color = chatColors().textSecondary)
                    Text(
                        stringResource(privacyStatus.poolDisplayNameRes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = chatColors().textPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.chat_privacy_card_anonymity_set), fontSize = 12.sp, color = chatColors().textSecondary)
                    Text(
                        stringResource(privacyStatus.anonymitySetRes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = chatColors().textPrimary
                    )
                }
                if (privacyStatus.needsShielding) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.chat_privacy_card_warning_cd),
                            tint = chatColors().warning,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_shield_needed_hint),
                            fontSize = 11.sp,
                            color = chatColors().warning
                        )
                    }
                }
            }
        }
    }
}

/** Centered info pill for a local system note (e.g. "contact rotated their key") — #225. */
@Suppress("MagicNumber")
@Composable
private fun SystemNotePill(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(chatColors().bgElevated)
                .border(1.dp, chatColors().borderDefault, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = text,
                color = chatColors().textSecondary,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Centered pill for a local call-log entry (incoming / outgoing / missed call). */
@Suppress("MagicNumber")
@Composable
private fun CallLogPill(info: co.electriccoin.zcash.ui.screen.chat.model.CallLogInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(chatColors().bgElevated)
                .border(1.dp, chatColors().borderDefault, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = callLogLine(info, separator = "  "),
                color = chatColors().textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Suppress("MagicNumber")
@Composable
private fun DateSeparator(date: LocalDate) {
    val cc = chatColors()
    val today = LocalDate.now()
    val yesterday = today.minus(1, ChronoUnit.DAYS)
    // R6-3 (round 5 report §5 item 6): the words, the month name and the digit grouping follow the language
    // Compose sees now, not Locale.getDefault(), which a language change handled in place never refreshes.
    val formatLocale = rememberDesiredFormatLocale()
    val dateLabel = when (date) {
        today -> stringResource(R.string.chat_date_today)
        yesterday -> stringResource(R.string.chat_date_yesterday)
        else ->
            date
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(formatLocale))
                .uppercase(formatLocale)
    }
    // QA2 Q2-22: the chip shows the day only; the live block height changed on every visit and said nothing about
    // the messages under it.
    val label = dateLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(cc.bgElevated)
                .border(1.dp, cc.borderDefault, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                color = cc.textSecondary,
                fontSize = 12.sp,
                fontFamily = co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun WelcomeZecCard(onSend: () -> Unit) {
    val amountZec = PaidActionCopy.zec(WelcomeZec.amount, rememberDesiredFormatLocale())
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgElevated
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = null,
                    tint = chatColors().primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.welcome_zec_card_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = chatColors().textPrimary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_welcome_zec_hint),
                fontSize = 12.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = onSend,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(NightwireColors.RadiusButton),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = chatColors().primary,
                    contentColor = chatColors().textOnAccent,
                ),
            ) {
                Text(stringResource(R.string.welcome_zec_send_button, amountZec), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// PaymentDialog, TimeLockComposerDialog, TemplatePickerRow, and PaymentRequestComposerDialog
// have been extracted to ChatDialogs.kt for better organization

// Decode an image file to a Bitmap downscaled so its dimensions stay near reqPx.
// Uses the canonical Android pattern (see co.electriccoin.zcash.ui.screen.chat.filesharing.BitmapSampling).
private fun decodeSampledBitmap(path: String, reqPx: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val sample = co.electriccoin.zcash.ui.screen.chat.filesharing
        .BitmapSampling.calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqPx)
        ?: return null
    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(path, decodeOpts)
}

/** R10A-2 (F-R9P-4): the line over a file's transfer bar, in the phone's language. */
@androidx.annotation.StringRes
private fun transferLabelRes(isOutgoing: Boolean): Int =
    if (isOutgoing) R.string.chat_transfer_uploading else R.string.chat_transfer_downloading

@Composable
private fun ImageUploadProgressBar(progress: Float) {
    val label = when {
        progress < 0.15f -> stringResource(R.string.chat_upload_preparing_image)
        progress < 0.25f -> stringResource(R.string.chat_upload_compressing)
        progress < 0.9f -> stringResource(R.string.chat_upload_encrypting, (progress * 100).toInt())
        progress < 1.0f -> stringResource(R.string.chat_upload_finalizing)
        else -> stringResource(R.string.chat_upload_sent)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(chatColors().bgElevated)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = chatColors().textSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = chatColors().primary,
            trackColor = chatColors().bgInput,
        )
    }
}

/** B17 — human label for a disappearing-messages TTL (seconds), in the phone's language (R6-3). */
@Composable
private fun ttlLabel(seconds: Long): String {
    val (plural, count) =
        when {
            seconds == 604800L -> R.plurals.chat_ttl_weeks to 1L
            seconds % 86400L == 0L -> R.plurals.chat_ttl_days to seconds / 86400L
            seconds % 3600L == 0L -> R.plurals.chat_ttl_hours to seconds / 3600L
            else -> R.plurals.chat_ttl_minutes to seconds / 60L
        }
    return pluralStringResource(plural, count.toInt(), count.toInt())
}

/**
 * F4 (fix-all 2.13.0, train T4): the line above Retry on a send that may have gone out, from the history pass's verdict.
 * Its transaction can still be mined: the block after which, if it was not, it can be sent again, and about how long
 * that is. Nothing known yet: checking. A mined payment to the contact may be it, or part of it was mined: Retry stays
 * off. No verdict yet (no pass judged it): the "may already have been sent" line as before.
 */
@Composable
private fun attemptRowNotice(verdict: co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict?): String =
    when (verdict) {
        is co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict.Waiting -> {
            val expiry = verdict.expiryHeight
            val tip = verdict.tip
            if (expiry != null && tip != null) {
                stringResource(
                    R.string.chat_send_row_waiting_for_network,
                    co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
                        .deadBlock(expiry)
                        .toString(),
                    co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
                        .minutesUntilDead(expiry, tip),
                )
            } else {
                stringResource(R.string.chat_send_row_checking)
            }
        }
        co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict.Unknown,
        co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict.NotSent,
        -> stringResource(R.string.chat_send_row_checking)
        co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict.MinedUnattributed ->
            stringResource(R.string.chat_send_row_mined_unattributed)
        co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict.Partial ->
            stringResource(R.string.chat_send_row_partly_sent)
        co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict.Sent, null -> localizedRowNotice()
    }

/** P2a / P2h: the "may already have been sent" line above Retry, in the phone's language. */
@Composable
private fun localizedRowNotice(): String = localizedSendNotice(co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof.ROW_NOTICE)

/** A send notice or failure reason in the phone's language when ChatSendCopy has a translation, else as it is. */
@Composable
private fun localizedSendNotice(notice: String): String {
    // ZC-1417: the shortfall with the SDK's figures, in the phone's language and number format.
    co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
        .messageShortfallFigures(notice)
        ?.let { (have, need) ->
            val locale =
                androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            return androidx.compose.ui.res.stringResource(
                R.string.chat_send_insufficient_balance_have_need,
                zecDisplay(have, locale),
                zecDisplay(need, locale),
            )
        }
    val res =
        co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
            .localizedResource(notice)
    return if (res != null) {
        androidx.compose.ui.res
            .stringResource(res)
    } else {
        notice
    }
}
