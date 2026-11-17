package co.electriccoin.zcash.ui.call

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen overlay rendered on top of any current screen when there's an active or
 * incoming call. Three layouts:
 *   - Ringing  : caller info + Accept / Decline buttons
 *   - Dialling / Connecting / InCall : peer info + Mic toggle + Hang up button
 *   - Ended    : a short notice of why the call ended. ZC-411: it is drawn from the overlay's own state, so it stays
 *                for CallEndReason.noticeHoldMs (the state flips Ended to Idle after 2 s), and a tap dismisses it
 *
 * Hosted by MainActivity so it can sit above every Compose route.
 */
@Composable
fun CallOverlay(modifier: Modifier = Modifier) {
    val manager by CallController.current.collectAsState()
    val mgr = manager ?: return
    val state by mgr.state.collectAsState()
    // B15: single source of truth for mute across ALL call phases (ringing → connected), so the mute
    // button works while the call is still ringing instead of showing a hardcoded/dead "unmuted".
    val muted by mgr.micMuted.collectAsState()
    // Swallow the system Back gesture while a call exists so the user can't
    // accidentally background the in-call UI by pressing Back. Back during a call
    // routes to "hang up" instead.
    val active = state !is VoiceCallManager.CallState.Idle && state !is VoiceCallManager.CallState.Ended
    if (active) {
        BackHandler { mgr.hangUp(CallEndReason.BackPressed) }
        // ZC-1981: this call screen is shown while its activity is started; the ongoing card follows it.
        LifecycleStartEffect(mgr) {
            val host = Any()
            CallController.setCallScreenShown(host, true)
            onStopOrDispose { CallController.setCallScreenShown(host, false) }
        }
    }
    // ZC-1467: the chat's text field keeps focus under the overlay, and its keyboard covered half of the call screen.
    // Each call screen (ringing, dialling, connecting, in call) clears that focus and hides the keyboard once.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissIme = CallImePolicy.shouldDismissIme(state)
    LaunchedEffect(state::class, dismissIme) {
        if (dismissIme) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    // R9-6 (REVIEW_R8 F13): every line names the contact (or a short key for an unknown one), in the phone's language.
    val prefs: ZchatPreferences = org.koin.compose.koinInject()
    val peerHex = callPeerKey(state)
    val peerLabel = remember(peerHex) { peerHex?.let { callPeerLabel(prefs, it) }.orEmpty() }
    val lines = callScreenText(state, peerLabel).map { callLineText(it) }
    when (val s = state) {
        is VoiceCallManager.CallState.Idle -> Unit
        is VoiceCallManager.CallState.Ringing -> RingingScreen(s, mgr, lines)
        is VoiceCallManager.CallState.Dialling -> CallScreen(lines.first(), muted, s.isVideo, mgr, isConnected = false)
        is VoiceCallManager.CallState.Connecting -> CallScreen(lines.first(), muted, s.isVideo, mgr, isConnected = false)
        is VoiceCallManager.CallState.InCall -> CallScreen(lines.first(), muted, s.isVideo, mgr, isConnected = true)
        is VoiceCallManager.CallState.Ended -> Unit
    }
    // ZC-1904 (owner W-148): the other phone asks to switch this audio call to video. Its video shows only after
    // Accept; this phone's camera stays off until the user taps Videocam. Decline (or no answer) keeps the call audio.
    val videoRequest by mgr.videoUpgradeRequest.collectAsState()
    if (videoRequest && state is VoiceCallManager.CallState.InCall) {
        VideoRequestPrompt(
            peerLabel = peerLabel,
            onAccept = { mgr.acceptVideoUpgrade() },
            onDecline = { mgr.declineVideoUpgrade() },
        )
    }
    // ZC-411: the notice outlives the Ended state. The manager flips Ended to Idle after 2 s (that time also gates a
    // redial and is not changed); the long no-relay text needs about 10 s. The notice is set when the state becomes
    // Ended, kept through Idle, and cleared after its reason's hold, by a tap on it, or when a new call starts.
    var endedNotice by remember { mutableStateOf<EndedNotice?>(null) }
    val endings = remember { intArrayOf(0) }
    LaunchedEffect(state) {
        when (val s = state) {
            is VoiceCallManager.CallState.Ended -> endedNotice = EndedNotice(++endings[0], s.reason)
            is VoiceCallManager.CallState.Idle -> Unit
            else -> endedNotice = null
        }
    }
    // Keyed on the notice (reason and per-ending counter): a new ending cancels this wait and starts a full one.
    LaunchedEffect(endedNotice) {
        val shown = endedNotice ?: return@LaunchedEffect
        delay(shown.reason.noticeHoldMs)
        endedNotice = null
    }
    // Never over a call screen: a new call's first frame can come before the effect above has cleared the notice.
    if (!active) endedNotice?.let { notice -> EndedToast(notice.reason, onDismiss = { endedNotice = null }) }
    // ZC-1468: this phone's switch to video got no answer in time (a 2.12.4 phone drops it); the call stays audio.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(mgr) {
        mgr.videoUpgradeDeclined.collect {
            android.widget.Toast
                .makeText(context, context.getString(R.string.call_video_not_accepted), android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }
    // ZC-1904: the other phone's user declined this phone's switch to video; the call stays audio.
    LaunchedEffect(mgr) {
        mgr.videoUpgradeRejected.collect {
            android.widget.Toast
                .makeText(context, context.getString(R.string.call_video_declined), android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }
}

/** ZC-1904: the question shown when the other phone asks to switch to video, over the call screen. */
@Composable
private fun VideoRequestPrompt(
    peerLabel: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        // The scrim takes taps, so the call buttons under the question cannot be pressed by mistake.
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.call_video_request_title),
                    color = NightwireColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.call_video_request_body, peerLabel),
                    color = NightwireColors.TextPrimary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallButton(
                        icon = Icons.Default.VideocamOff,
                        tint = Color.White,
                        background = Color(0xFFCC0033),
                        contentDesc = stringResource(R.string.call_video_request_decline),
                        onClick = onDecline,
                    )
                    CallButton(
                        icon = Icons.Default.Videocam,
                        tint = Color.White,
                        background = Color(0xFF00B97A),
                        contentDesc = stringResource(R.string.call_video_request_accept),
                        onClick = onAccept,
                    )
                }
            }
        }
    }
}

/** ZC-411: the ended-call notice CallOverlay keeps; [ending] counts endings so two with one reason both get a full hold. */
private data class EndedNotice(
    val ending: Int,
    val reason: CallEndReason,
)

/** A line of the call screen: a string resource with its arguments, or [literal] shown as it is (the contact's label). */
internal data class CallScreenLine(
    @StringRes val resId: Int? = null,
    val args: List<String> = emptyList(),
    val literal: String? = null,
)

/**
 * R9-6 (REVIEW_R8 F13): the name the call screen and the incoming-call notification show for [pubkeyHex]: the
 * contact's display name when the key belongs to a contact, otherwise 8 hex characters of the key and "…".
 */
internal fun callPeerLabel(
    prefs: ZchatPreferences,
    pubkeyHex: String,
): String {
    val peer = prefs.findPeerByNostrPubkey(pubkeyHex)
    return if (peer != null) prefs.getDisplayName(peer) else "${pubkeyHex.take(8)}…"
}

/** R9-6: the lines the call screen shows for [state], top to bottom (the ringing screen has two). */
internal fun callScreenText(
    state: VoiceCallManager.CallState,
    peerLabel: String,
): List<CallScreenLine> =
    when (state) {
        is VoiceCallManager.CallState.Ringing ->
            listOf(
                CallScreenLine(if (state.isVideo) R.string.call_incoming_video else R.string.call_incoming_voice),
                CallScreenLine(literal = peerLabel),
            )
        is VoiceCallManager.CallState.Dialling -> listOf(CallScreenLine(R.string.call_dialling, listOf(peerLabel)))
        is VoiceCallManager.CallState.Connecting -> listOf(CallScreenLine(R.string.call_connecting, listOf(peerLabel)))
        is VoiceCallManager.CallState.InCall -> listOf(CallScreenLine(R.string.call_in_call, listOf(peerLabel)))
        is VoiceCallManager.CallState.Idle, is VoiceCallManager.CallState.Ended -> emptyList()
    }

private fun callPeerKey(state: VoiceCallManager.CallState): String? =
    when (state) {
        is VoiceCallManager.CallState.Ringing -> state.peerPubkeyHex
        is VoiceCallManager.CallState.Dialling -> state.peerPubkeyHex
        is VoiceCallManager.CallState.Connecting -> state.peerPubkeyHex
        is VoiceCallManager.CallState.InCall -> state.peerPubkeyHex
        is VoiceCallManager.CallState.Idle, is VoiceCallManager.CallState.Ended -> null
    }

@Composable
private fun callLineText(line: CallScreenLine): String =
    line.resId?.let { stringResource(it, *line.args.toTypedArray()) } ?: line.literal.orEmpty()

/**
 * Routes to the video layout (renderers + controls) or the audio layout based on [isVideo]. ZC-1468: a remote video
 * track (the other phone switched to video) also shows the video layout.
 */
@Composable
private fun CallScreen(label: String, isMuted: Boolean, isVideo: Boolean, mgr: VoiceCallManager, isConnected: Boolean) {
    val tracks by mgr.video.collectAsState()
    if (isVideo || tracks.remote != null) {
        VideoCallScreen(label, isMuted, mgr, isConnected)
    } else {
        InCallScreen(label, isMuted, mgr, isConnected)
    }
}

/**
 * ZC-1468: the action that switches a connected call to video: asks for the camera when it is not granted (the
 * RingingScreen launcher pattern), then [VoiceCallManager.requestVideoUpgrade]. Declining the camera keeps the call audio.
 */
@Composable
private fun rememberVideoUpgrade(mgr: VoiceCallManager): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) mgr.requestVideoUpgrade()
        }
    return {
        val camera = android.Manifest.permission.CAMERA
        val granted = ContextCompat.checkSelfPermission(context, camera) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) mgr.requestVideoUpgrade() else cameraLauncher.launch(camera)
    }
}

@Composable
private fun RingingScreen(state: VoiceCallManager.CallState.Ringing, mgr: VoiceCallManager, lines: List<String>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // A call needs the microphone (RECORD_AUDIO), plus the camera for a video call. The app never
    // asked for these — not at startup, not when the call arrived — and VoiceCallManager runs in a
    // Service/Receiver context that CAN'T prompt, so tapping Accept just ended the call with
    // "Microphone permission needed" and the user never saw a permission dialog. Request them HERE
    // (this overlay is hosted by MainActivity, so we have an Activity context) and only proceed once
    // the mandatory mic permission is granted.
    val neededPerms = remember(state.isVideo) {
        buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (state.isVideo) add(android.Manifest.permission.CAMERA)
        }.toTypedArray()
    }
    fun hasAllPerms(): Boolean = neededPerms.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    // ZC-1935: the call whose Answer was tapped while the permission dialog is open.
    val pendingAnswerCallId = rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Mic is mandatory for any call; camera may be declined but a video call needs it too.
        // ZC-1935: act only on the call whose Answer was tapped, if it still rings.
        val callId = pendingAnswerCallId.value
        pendingAnswerCallId.value = null
        // ZC-1950: an interrupted request returns an empty map and a camera-only one has no RECORD_AUDIO; the phone's
        // own microphone state decides, as in IncomingCallActivity (ZC-1936).
        val micNow =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val micGranted = CallLockScreen.micGrantedAfterRequest(result, micNow)
        when (CallLockScreen.answerAfterPermissions(callId, mgr.state.value, micGranted)) {
            CallLockScreen.AnswerAfterPermissions.ACCEPT -> mgr.acceptIncoming(callId)
            CallLockScreen.AnswerAfterPermissions.END_MIC_DENIED ->
                callId?.let { mgr.hangUpIfRinging(it, CallEndReason.PermissionDenied) }
            CallLockScreen.AnswerAfterPermissions.IGNORE -> Unit
        }
    }
    val onAccept: () -> Unit = {
        if (hasAllPerms()) {
            mgr.acceptIncoming(state.callId)
        } else {
            pendingAnswerCallId.value = state.callId
            permissionLauncher.launch(neededPerms)
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        // QA2 Q2-73 (ZC-1656): opaque, so no chat row or banner shows through the call screen.
        color = Color.Black,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = lines.getOrElse(0) { "" },
                color = NightwireColors.AccentPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            // QA2 Q2-75 (ZC-1599): the caller's name is read first, so it is as large and bright as a call line.
            Text(
                text = lines.getOrElse(1) { "" },
                color = NightwireColors.TextPrimary,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallButton(
                    icon = Icons.Default.CallEnd,
                    tint = Color.White,
                    background = Color(0xFFCC0033),
                    contentDesc = stringResource(R.string.call_action_decline),
                    onClick = { mgr.hangUp(CallEndReason.Declined) },
                )
                CallButton(
                    icon = Icons.Default.Call,
                    tint = Color.White,
                    background = Color(0xFF00B97A),
                    contentDesc = stringResource(R.string.call_action_answer),
                    onClick = onAccept,
                )
            }
        }
    }
}

@Composable
private fun InCallScreen(label: String, isMuted: Boolean, mgr: VoiceCallManager, isConnected: Boolean) {
    // Latches false on the first hang-up tap so rapid repeat taps during the
    // InCall→Ended transition can't queue duplicate teardown/HANGUP ops.
    var hangUpRequested by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = NightwireColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (isConnected) {
                Spacer(Modifier.height(8.dp))
                CallTimer()
            }
            Spacer(Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    tint = if (isMuted) Color(0xFFCC0033) else Color.White,
                    background = Color.DarkGray,
                    contentDesc = stringResource(if (isMuted) R.string.call_action_unmute else R.string.call_action_mute),
                    onClick = { mgr.setMuted(!isMuted) },
                )
                AudioOutputButton(mgr)
                if (isConnected) {
                    // ZC-1468: switch this audio call to video.
                    val switchToVideo = rememberVideoUpgrade(mgr)
                    CallButton(
                        icon = Icons.Default.Videocam,
                        tint = Color.White,
                        background = Color.DarkGray,
                        contentDesc = stringResource(R.string.call_switch_to_video),
                        onClick = switchToVideo,
                    )
                }
                CallButton(
                    icon = Icons.Default.CallEnd,
                    tint = Color.White,
                    background = Color(0xFFCC0033),
                    contentDesc = stringResource(R.string.call_action_end),
                    onClick = { hangUpRequested = true; mgr.hangUp(CallEndReason.UserEnded) },
                    enabled = !hangUpRequested,
                )
            }
        }
    }
}

/**
 * Video-call layout: remote video full-screen, local preview as a small mirrored PiP in
 * the top-right, controls (mute / hang up) bottom-centre. Falls back to the audio layout's
 * label until the remote track arrives.
 */
@Composable
private fun VideoCallScreen(label: String, isMuted: Boolean, mgr: VoiceCallManager, isConnected: Boolean) {
    val tracks by mgr.video.collectAsState()
    val videoOn by mgr.videoEnabled.collectAsState()
    // Latches false on the first hang-up tap so rapid repeat taps can't queue duplicate teardown/HANGUP ops.
    var hangUpRequested by remember { mutableStateOf(false) }
    val egl: org.webrtc.EglBase.Context? = mgr.eglBaseContext
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            val remote = tracks.remote
            if (remote != null && egl != null) {
                VideoRenderer(
                    track = remote,
                    eglContext = egl,
                    mirror = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No remote frames yet — show the connecting label centred. QA2 Q2-77 (ZC-1552): once the call is up,
                // say that the other camera is off.
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = label, color = NightwireColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (isConnected) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = stringResource(R.string.call_remote_camera_off), color = NightwireColors.TextPrimary, fontSize = 15.sp)
                    }
                }
            }
            // QA2 Q2-77/Q2-78 (ZC-1552): the name and the call's duration stay on screen over the video, on a gradient.
            if (remote != null || isConnected) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                            .statusBarsPadding()
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (remote != null) {
                        Text(text = label, color = NightwireColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (isConnected) CallTimer()
                }
            }
            val local = tracks.local
            if (local != null && egl != null) {
                VideoRenderer(
                    track = local,
                    eglContext = egl,
                    mirror = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .width(108.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            // QA2 Q2-78: the other phone's video is on and this phone's camera is not: say it can join.
            if (remote != null && local == null && isConnected) {
                Text(
                    text = stringResource(R.string.call_remote_video_on_hint),
                    color = NightwireColors.TextPrimary,
                    fontSize = 14.sp,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 136.dp, start = 24.dp, end = 24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .padding(top = 24.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    tint = if (isMuted) Color(0xFFCC0033) else Color.White,
                    background = Color.DarkGray,
                    contentDesc = stringResource(if (isMuted) R.string.call_action_unmute else R.string.call_action_mute),
                    onClick = { mgr.setMuted(!isMuted) },
                )
                AudioOutputButton(mgr)
                if (local != null) {
                    CallButton(
                        icon = Icons.Default.Cameraswitch,
                        tint = Color.White,
                        background = Color.DarkGray,
                        contentDesc = stringResource(R.string.call_action_switch_camera),
                        onClick = { mgr.switchCamera() },
                    )
                    CallButton(
                        icon = if (videoOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        tint = if (videoOn) Color.White else Color(0xFFCC0033),
                        background = Color.DarkGray,
                        contentDesc = stringResource(if (videoOn) R.string.call_action_video_off else R.string.call_action_video_on),
                        onClick = { mgr.setVideoEnabled(!videoOn) },
                    )
                } else if (isConnected) {
                    // ZC-1468: the other phone switched to video; this phone may add its own camera.
                    val switchToVideo = rememberVideoUpgrade(mgr)
                    CallButton(
                        icon = Icons.Default.Videocam,
                        tint = Color.White,
                        background = Color.DarkGray,
                        contentDesc = stringResource(R.string.call_switch_to_video),
                        onClick = switchToVideo,
                    )
                }
                CallButton(
                    icon = Icons.Default.CallEnd,
                    tint = Color.White,
                    background = Color(0xFFCC0033),
                    contentDesc = stringResource(R.string.call_action_end),
                    onClick = { hangUpRequested = true; mgr.hangUp(CallEndReason.UserEnded) },
                    enabled = !hangUpRequested,
                )
            }
        }
    }
}

/**
 * Wraps a WebRTC [SurfaceViewRenderer] in Compose. Inits with the call's EglBase context,
 * attaches the [track] as a sink, and releases both on dispose so we never leak the GL
 * surface or leave a dangling sink on a disposed track.
 */
@Composable
private fun VideoRenderer(
    track: VideoTrack,
    eglContext: org.webrtc.EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                setEnableHardwareScaler(true)
                track.addSink(this)
            }
        },
        onRelease = { renderer ->
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        },
    )
}

@Composable
private fun EndedToast(
    reason: CallEndReason,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            // QA2 Q2-81 (ZC-800): opaque, so the screen under it does not show through the text.
            color = Color.Black,
            // ZC-411: the notice can stay for 10 s over the top of the app, so a tap removes it.
            modifier = Modifier.padding(top = 64.dp).clickable(onClick = onDismiss),
        ) {
            Text(
                text = stringResource(reason.labelRes),
                color = NightwireColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    background: Color,
    contentDesc: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    // QA2 Q2-75 (ZC-1599): a caption under the icon; TalkBack reads the button's description once, not the caption.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(background),
        ) {
            Icon(imageVector = icon, contentDescription = contentDesc, tint = tint)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = contentDesc,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.width(72.dp).clearAndSetSemantics {},
        )
    }
}

/** Ticking call-duration display (m:ss). Starts when it first enters composition — i.e. on InCall. */
@Composable
private fun CallTimer() {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) {
            elapsed = (android.os.SystemClock.elapsedRealtime() - start) / 1000
            kotlinx.coroutines.delay(1000)
        }
    }
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    val durationA11y = stringResource(R.string.call_duration_a11y, minutes.toInt(), seconds.toInt())
    Text(
        text = "%d:%02d".format(minutes, seconds),
        color = Color(0xFFB0B0B0),
        fontSize = 15.sp,
        modifier = Modifier.semantics {
            contentDescription = durationA11y
        },
    )
}

/** Audio-output selector: shows the current route, opens a menu of available routes on tap. */
@Composable
private fun AudioOutputButton(mgr: VoiceCallManager) {
    val out by mgr.audioOutput.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val currentName = stringResource(audioRouteName(out.current))
    val outputA11y = stringResource(R.string.call_audio_output, currentName)
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = { expanded = true },
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .semantics { contentDescription = outputA11y },
            ) {
                // QA2 Q2-75 (ZC-1599): a tinted vector icon, not an emoji (the earpiece emoji vanished when selected).
                Icon(imageVector = audioRouteIcon(out.current), contentDescription = null, tint = Color.White)
            }
            // QA2 Q2-75 (ZC-1599): the current route's name as the caption.
            Spacer(Modifier.height(6.dp))
            Text(
                text = currentName,
                color = Color.White,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.width(72.dp).clearAndSetSemantics {},
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            out.available.forEach { route ->
                val selected = route == out.current
                val name = stringResource(audioRouteName(route))
                val itemA11y = if (selected) stringResource(R.string.call_route_selected, name) else name
                DropdownMenuItem(
                    modifier = Modifier.semantics { contentDescription = itemA11y },
                    leadingIcon = { Icon(imageVector = audioRouteIcon(route), contentDescription = null) },
                    trailingIcon =
                        if (selected) {
                            { Icon(imageVector = Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    text = { Text(name) },
                    onClick = {
                        mgr.setAudioRoute(route)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun audioRouteIcon(r: VoiceCallManager.AudioRoute): androidx.compose.ui.graphics.vector.ImageVector =
    when (r) {
        VoiceCallManager.AudioRoute.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
        VoiceCallManager.AudioRoute.EARPIECE -> Icons.Default.PhoneInTalk
        VoiceCallManager.AudioRoute.BLUETOOTH -> Icons.Default.Bluetooth
        VoiceCallManager.AudioRoute.WIRED -> Icons.Default.Headset
    }

@StringRes
private fun audioRouteName(r: VoiceCallManager.AudioRoute): Int =
    when (r) {
        VoiceCallManager.AudioRoute.SPEAKER -> R.string.call_action_speaker
        VoiceCallManager.AudioRoute.EARPIECE -> R.string.call_action_earpiece
        VoiceCallManager.AudioRoute.BLUETOOTH -> R.string.call_action_bluetooth
        VoiceCallManager.AudioRoute.WIRED -> R.string.call_action_headset
    }
