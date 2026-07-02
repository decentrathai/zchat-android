package co.electriccoin.zcash.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen overlay rendered on top of any current screen when there's an active or
 * incoming call. Three layouts:
 *   - Ringing  : caller info + Accept / Decline buttons
 *   - Dialling / Connecting / InCall : peer info + Mic toggle + Hang up button
 *   - Ended    : 2-second toast then dismiss
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
    }
    when (val s = state) {
        is VoiceCallManager.CallState.Idle -> Unit
        is VoiceCallManager.CallState.Ringing -> RingingScreen(s, mgr)
        is VoiceCallManager.CallState.Dialling -> CallScreen("Calling… ${s.peerPubkeyHex.take(12)}…", muted, s.isVideo, mgr, isConnected = false)
        is VoiceCallManager.CallState.Connecting -> CallScreen("Connecting… ${s.peerPubkeyHex.take(12)}…", muted, s.isVideo, mgr, isConnected = false)
        is VoiceCallManager.CallState.InCall -> CallScreen("On call with ${s.peerPubkeyHex.take(12)}…", muted, s.isVideo, mgr, isConnected = true)
        is VoiceCallManager.CallState.Ended -> EndedToast(s.reason)
    }
}

/** Routes to the video layout (renderers + controls) or the audio layout based on [isVideo]. */
@Composable
private fun CallScreen(label: String, isMuted: Boolean, isVideo: Boolean, mgr: VoiceCallManager, isConnected: Boolean) {
    if (isVideo) VideoCallScreen(label, isMuted, mgr, isConnected) else InCallScreen(label, isMuted, mgr, isConnected)
}

@Composable
private fun RingingScreen(state: VoiceCallManager.CallState.Ringing, mgr: VoiceCallManager) {
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
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Mic is mandatory for any call; camera may be declined but a video call needs it too.
        if (result[android.Manifest.permission.RECORD_AUDIO] == true) {
            mgr.acceptIncoming()
        } else {
            mgr.hangUp(CallEndReason.PermissionDenied)
        }
    }
    val onAccept: () -> Unit = {
        if (hasAllPerms()) mgr.acceptIncoming() else permissionLauncher.launch(neededPerms)
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.9f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.isVideo) "Incoming video call" else "Incoming voice call",
                color = NightwireColors.AccentPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.peerPubkeyHex.take(24) + "…",
                color = NightwireColors.TextSecondary,
                fontSize = 14.sp,
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
                    contentDesc = "Decline",
                    onClick = { mgr.hangUp(CallEndReason.Declined) },
                )
                CallButton(
                    icon = Icons.Default.Call,
                    tint = Color.White,
                    background = Color(0xFF00B97A),
                    contentDesc = "Accept",
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
        color = Color.Black.copy(alpha = 0.92f),
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
                    contentDesc = if (isMuted) "Unmute" else "Mute",
                    onClick = { mgr.setMuted(!isMuted) },
                )
                AudioOutputButton(mgr)
                CallButton(
                    icon = Icons.Default.CallEnd,
                    tint = Color.White,
                    background = Color(0xFFCC0033),
                    contentDesc = "Hang up",
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
private fun VideoCallScreen(label: String, isMuted: Boolean, mgr: VoiceCallManager, @Suppress("UNUSED_PARAMETER") isConnected: Boolean) {
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
                // No remote frames yet — show the connecting label centred.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = label, color = NightwireColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                        .padding(16.dp)
                        .width(108.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    tint = if (isMuted) Color(0xFFCC0033) else Color.White,
                    background = Color.DarkGray,
                    contentDesc = if (isMuted) "Unmute" else "Mute",
                    onClick = { mgr.setMuted(!isMuted) },
                )
                AudioOutputButton(mgr)
                EmojiCallButton(emoji = "🔄", contentDesc = "Switch camera", onClick = { mgr.switchCamera() })
                EmojiCallButton(
                    emoji = if (videoOn) "📷" else "🚫",
                    contentDesc = if (videoOn) "Turn video off" else "Turn video on",
                    onClick = { mgr.setVideoEnabled(!videoOn) },
                )
                CallButton(
                    icon = Icons.Default.CallEnd,
                    tint = Color.White,
                    background = Color(0xFFCC0033),
                    contentDesc = "Hang up",
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
private fun EndedToast(reason: CallEndReason) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 64.dp),
        ) {
            Text(
                text = reason.displayLabel,
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
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(background),
    ) {
        Icon(imageVector = icon, contentDescription = contentDesc, tint = tint)
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
    Text(
        text = "%d:%02d".format(minutes, seconds),
        color = Color(0xFFB0B0B0),
        fontSize = 15.sp,
        modifier = Modifier.semantics {
            contentDescription = "Call duration $minutes minutes $seconds seconds"
        },
    )
}

/** Audio-output selector: shows the current route, opens a menu of available routes on tap. */
@Composable
private fun AudioOutputButton(mgr: VoiceCallManager) {
    val out by mgr.audioOutput.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
                .semantics { contentDescription = "Audio output: ${audioRouteName(out.current)}" },
        ) {
            Text(text = audioRouteEmoji(out.current), fontSize = 26.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            out.available.forEach { route ->
                val selected = route == out.current
                DropdownMenuItem(
                    modifier = Modifier.semantics {
                        contentDescription =
                            audioRouteName(route) + if (selected) ", selected" else ""
                    },
                    text = {
                        Text(
                            "${audioRouteEmoji(route)}  ${audioRouteName(route)}" +
                                if (selected) "  ✓" else "",
                        )
                    },
                    onClick = {
                        mgr.setAudioRoute(route)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Circular call-control button with an emoji glyph (camera switch / on-off). */
@Composable
private fun EmojiCallButton(emoji: String, contentDesc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.DarkGray)
            .semantics { contentDescription = contentDesc },
    ) {
        Text(text = emoji, fontSize = 26.sp)
    }
}

private fun audioRouteEmoji(r: VoiceCallManager.AudioRoute): String = when (r) {
    VoiceCallManager.AudioRoute.SPEAKER -> "🔊"
    VoiceCallManager.AudioRoute.EARPIECE -> "📞"
    VoiceCallManager.AudioRoute.BLUETOOTH -> "🎧"
    VoiceCallManager.AudioRoute.WIRED -> "🎧"
}

private fun audioRouteName(r: VoiceCallManager.AudioRoute): String = when (r) {
    VoiceCallManager.AudioRoute.SPEAKER -> "Speaker"
    VoiceCallManager.AudioRoute.EARPIECE -> "Earpiece"
    VoiceCallManager.AudioRoute.BLUETOOTH -> "Bluetooth"
    VoiceCallManager.AudioRoute.WIRED -> "Headset"
}
