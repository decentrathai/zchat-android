package co.electriccoin.zcash.ui.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.UUID

/**
 * Single-call voice-call manager. Wraps a WebRTC [PeerConnection] and brokers SDP / ICE
 * messages with a remote peer via [CallSignalEnvelope]s delivered through NIP-17. Audio or
 * video; a connected audio call can switch to video (ZC-1468, [CallRenegotiation]).
 *
 * THREADING MODEL — single-thread confinement:
 *   Every mutation of call state (peerConnection, activePeer, activeCallId, bufferedOffer,
 *   pendingRemoteCandidates, remoteDescriptionSet, audio fields, userMuted) and every
 *   PeerConnection method call runs on ONE dedicated thread, [callScope]. Public API
 *   methods and WebRTC observer callbacks (which fire on WebRTC's own signalling thread)
 *   post their work onto callScope rather than touching state inline. This eliminates the
 *   whole class of data races/visibility gaps that a multi-threaded Dispatchers.IO would
 *   expose, without per-field @Volatile or locks. StateFlow.value (the only field read by
 *   the UI thread) is itself atomic.
 *
 * Owned by the foreground service so the connection survives navigation. Surfaces:
 *   - [state]    StateFlow of [CallState] for the UI.
 *   - [outbound] SharedFlow of envelopes the service must publish to the peer.
 */
class VoiceCallManager(
    private val appContext: Context,
    // Server-issued TURN credentials (D13). Nullable so existing constructions/tests keep
    // compiling; null simply means STUN-only ICE (the pre-TURN behavior).
    private val turnCredentials: TurnCredentialsClient? = null,
) {
    sealed interface CallState {
        data object Idle : CallState
        data class Ringing(val peerPubkeyHex: String, val callId: String, val isVideo: Boolean = false) : CallState
        data class Dialling(val peerPubkeyHex: String, val callId: String, val isVideo: Boolean = false) : CallState
        data class Connecting(val peerPubkeyHex: String, val callId: String, val isVideo: Boolean = false) : CallState
        data class InCall(
            val peerPubkeyHex: String,
            val callId: String,
            val isMuted: Boolean = false,
            val isVideo: Boolean = false,
        ) : CallState
        data class Ended(val reason: CallEndReason) : CallState
    }

    /** Local + remote video tracks for the renderer layer. Null until a video call attaches them. */
    data class VideoTracks(
        val local: org.webrtc.VideoTrack? = null,
        val remote: org.webrtc.VideoTrack? = null,
    )

    // Dedicated single-thread executor → confinement dispatcher. Daemon thread so it can't
    // keep the process alive; closed in shutdown().
    private val callExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zchat-voicecall").apply { isDaemon = true }
    }
    private val callScope = CoroutineScope(SupervisorJob() + callExecutor.asCoroutineDispatcher())

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _outbound = MutableSharedFlow<OutboundSignal>(replay = 0, extraBufferCapacity = 64)
    val outbound: SharedFlow<OutboundSignal> = _outbound.asSharedFlow()

    data class OutboundSignal(val peerPubkeyHex: String, val envelope: CallSignalEnvelope)

    private val _video = MutableStateFlow(VideoTracks())
    /** Local + remote video tracks for the Compose renderer layer (video calls only). */
    val video: StateFlow<VideoTracks> = _video.asStateFlow()

    private val _videoEnabled = MutableStateFlow(true)
    /** Whether the local camera track is capturing/transmitting (camera on/off, video calls). */
    val videoEnabled: StateFlow<Boolean> = _videoEnabled.asStateFlow()

    // B15: mic-mute needs a state-INDEPENDENT observable home. isMuted lived only on CallState.InCall, so
    // while the call was still Dialling/Connecting (ringing) the button couldn't reflect a mute and the
    // overlay hardcoded false — the toggle looked dead. This flow lets the user mute during ringing too.
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    // ZC-1468: one event when this phone's switch to video got no answer in time and was rolled back (the call stays
    // audio). The overlay shows R.string.call_video_not_accepted.
    private val _videoUpgradeDeclined = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val videoUpgradeDeclined: SharedFlow<Unit> = _videoUpgradeDeclined.asSharedFlow()

    // ZC-1904 (owner W-148): the other phone asks to switch this audio call to video. True while its OFFER is held and
    // the overlay asks this phone's user (Accept: acceptVideoUpgrade, Decline: declineVideoUpgrade, or a timeout).
    private val _videoUpgradeRequest = MutableStateFlow(false)
    val videoUpgradeRequest: StateFlow<Boolean> = _videoUpgradeRequest.asStateFlow()

    // ZC-1904: one event when the other phone's user declined this phone's switch to video (the call stays audio). The
    // overlay shows R.string.call_video_declined; a question that timed out there shows call_video_not_accepted instead.
    private val _videoUpgradeRejected = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val videoUpgradeRejected: SharedFlow<Unit> = _videoUpgradeRejected.asSharedFlow()

    /** Where call audio is currently played out / captured from. */
    enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

    data class AudioOutputState(
        val current: AudioRoute = AudioRoute.SPEAKER,
        val available: List<AudioRoute> = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    )

    private val _audioOutput = MutableStateFlow(AudioOutputState())
    /** Current + available audio output routes, for the in-call output selector. */
    val audioOutput: StateFlow<AudioOutputState> = _audioOutput.asStateFlow()

    /** EglBase context the renderer Views must init with. Non-null once a call is built. */
    val eglBaseContext: org.webrtc.EglBase.Context? get() = eglBase?.eglBaseContext

    // --- All fields below are confined to callScope's thread; no @Volatile needed. ---
    private var factory: PeerConnectionFactory? = null
    private var eglBase: org.webrtc.EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: org.webrtc.VideoSource? = null
    private var localVideoTrack: org.webrtc.VideoTrack? = null
    private var videoCapturer: org.webrtc.VideoCapturer? = null
    private var surfaceHelper: org.webrtc.SurfaceTextureHelper? = null
    private var activePeer: String? = null
    private var activeCallId: String? = null
    private var activeIsVideo: Boolean = false
    private val pendingRemoteCandidates: MutableList<IceCandidate> = mutableListOf()
    private var remoteDescriptionSet = false
    private val teardownInFlight = AtomicBoolean(false)
    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var userMuted: Boolean = false
    private var bufferedOffer: CallSignalEnvelope? = null
    private var setupTimeoutJob: Job? = null
    private var disconnectJob: Job? = null

    // ZC-1470: the local camera's sender (bitrate, degradation) and the periodic stats log of a video call.
    private var videoSender: org.webrtc.RtpSender? = null
    private var videoStatsJob: Job? = null

    // ZC-1468: switching a connected audio call to video by a second OFFER/ANSWER under the same callId. One
    // CallRenegotiation and one lock per call (set in placeCall and acceptIncoming); the lock keeps an upgrade's
    // offer, a remote OFFER or ANSWER and the timeout rollback from interleaving at their suspension points. The
    // remote descriptions already applied in this call, so a redelivered OFFER or ANSWER is never applied twice.
    private var renegotiation = CallRenegotiation(isCaller = false)

    // ZC-1937: the SDP of this phone's last upgrade OFFER; a VIDEO_DECLINE must name it (CallRenegotiation.declinePayload).
    private var ownUpgradeOfferSdp: String? = null
    private var renegotiationLock = Mutex()
    private var upgradeTimeoutJob: Job? = null
    private val appliedRemoteSdps: MutableList<String> = mutableListOf()

    // ZC-1904: the other phone's upgrade OFFER held while this phone's user is asked, and the question's timer.
    private var heldUpgradeOffer: CallSignalEnvelope? = null
    private var heldUpgradeSender: String? = null
    private var promptTimeoutJob: Job? = null

    // TURN-CREDS: this call gathered a local relay candidate (ZChat's call relay answered). Reset for every call.
    private var relayCandidateSeen = false

    // ZC-409: OFFER, ICE and HANGUP that arrive before the RING of their call (relay order is not guaranteed). Kept
    // across calls on purpose: a call that has not rung yet outlives another call's teardown; the store's own limits
    // (4 calls, 60 s each) bound it.
    private val earlySignals = EarlyCallSignals(MAX_PENDING_ICE, MAX_ICE_PAYLOAD)

    // ZC-1469: the callee's looping ringtone and the caller's ringback, driven by the call state on the call thread and
    // released on every teardown (disposeConfined) and on shutdown.
    private val ringController = CallRingController(AndroidCallRingPlayer(appContext))

    init {
        callScope.launch { state.collect { ringController.onState(it) } }
    }

    // ---------- Public API (all post onto callScope) ----------

    /** Start an outbound call to [peerPubkeyHex] in audio or video [mode]. Emits RING then OFFER. */
    fun placeCall(peerPubkeyHex: String, mode: CallMode = CallMode.AUDIO) {
        warmUpTurnCredentials()
        callScope.launch {
            // Allow placing during the brief Ended→Idle cooldown (teardown already disposed) — mirrors
            // onRing, so a quick hang-up-then-redial isn't silently dropped for ~2s.
            if (_state.value !is CallState.Idle && _state.value !is CallState.Ended) {
                Log.w(TAG, "placeCall: ignoring — busy"); return@launch
            }
            if (!hasMicPermission()) {
                endTransient(CallEndReason.PermissionDenied); return@launch
            }
            // Video calls additionally need CAMERA; degrade to audio rather than fail if
            // the user granted mic but not camera.
            val video = mode.isVideo && hasCameraPermission()
            val callId = CallSignalEnvelope.newCallId()
            activePeer = peerPubkeyHex
            activeCallId = callId
            activeIsVideo = video
            renegotiation = CallRenegotiation(isCaller = true)
            ownUpgradeOfferSdp = null
            renegotiationLock = Mutex()
            relayCandidateSeen = false
            teardownInFlight.set(false)
            _state.value = CallState.Dialling(peerPubkeyHex, callId, video)
            startSetupTimeout()
            try {
                emit(peerPubkeyHex, CallSignalEnvelope(callId, CallSignalType.RING, (if (video) CallMode.VIDEO else CallMode.AUDIO).wire))
                buildPeerConnection()
                attachLocalMedia(video)
                val offer = createOffer()
                setLocalDescription(offer)
                emit(peerPubkeyHex, CallSignalEnvelope(callId, CallSignalType.OFFER, offer.description))
            } catch (t: Throwable) {
                Log.e(TAG, "placeCall setup failed: ${t.message}", t)
                teardownConfined(CallEndReason.SetupFailed(t.message ?: "offer"))
            }
        }
    }

    /**
     * Accept a Ringing call; we become the callee. ZC-1935: with [expectedCallId] only that call is answered (checked
     * here on the call thread); a different call that rings by then is left ringing.
     */
    fun acceptIncoming(expectedCallId: String? = null) {
        callScope.launch {
            val ringing = _state.value as? CallState.Ringing ?: run {
                Log.w(TAG, "acceptIncoming: not ringing"); return@launch
            }
            if (expectedCallId != null && ringing.callId != expectedCallId) {
                Log.w(TAG, "acceptIncoming: another call rings now — not answered")
                return@launch
            }
            if (!hasMicPermission()) {
                hangUpConfined(CallEndReason.PermissionDenied); return@launch
            }
            // Resilient by design: an audio-config hiccup shouldn't abort answering (the setup
            // timeout + teardown restore audio state anyway). But log the failure so it's
            // diagnosable instead of silently swallowed.
            runCatching { configureAudioForCall() }
                .onFailure { Log.w(TAG, "configureAudioForCall failed on accept (continuing): ${it.message}") }
            // Honor the caller's video intent only if we can actually capture video.
            activeIsVideo = ringing.isVideo && hasCameraPermission()
            renegotiation = CallRenegotiation(isCaller = false)
            ownUpgradeOfferSdp = null
            renegotiationLock = Mutex()
            val pending = bufferedOffer
            bufferedOffer = null
            _state.value = CallState.Connecting(ringing.peerPubkeyHex, ringing.callId, activeIsVideo)
            startSetupTimeout()
            if (pending != null) onOffer(ringing.peerPubkeyHex, pending)
        }
    }

    /** Reject a ringing or hang up an active call. Sends HANGUP and resets state. */
    fun hangUp(reason: CallEndReason = CallEndReason.UserEnded) {
        callScope.launch { hangUpConfined(reason) }
    }

    /** ZC-1935: end the call [callId] with [reason] only while it is the ringing call; any other call is left alone. */
    fun hangUpIfRinging(
        callId: String,
        reason: CallEndReason,
    ) {
        callScope.launch {
            val ringing = _state.value as? CallState.Ringing
            if (ringing?.callId == callId) hangUpConfined(reason) else Log.w(TAG, "hangUpIfRinging: not that ringing call")
        }
    }

    /** ZC-1982: end the call [callId] with [reason] only while it is connecting or live; any other call stays. */
    fun hangUpIfCurrent(
        callId: String,
        reason: CallEndReason,
    ) {
        callScope.launch {
            val currentId =
                when (val s = _state.value) {
                    is CallState.Connecting -> s.callId
                    is CallState.InCall -> s.callId
                    else -> null
                }
            if (currentId == callId) hangUpConfined(reason) else Log.w(TAG, "hangUpIfCurrent: not that call")
        }
    }

    /** Toggle the local audio track's enabled flag. Honored across all call states. */
    fun setMuted(muted: Boolean) {
        // Reflect in the UI IMMEDIATELY (StateFlow.value is thread-safe) so the toggle is snappy and
        // not queued behind audio-routing work on callScope (Bluetooth SCO churn made it lag/need a
        // second tap). The actual track enable/disable still runs confined on callScope.
        _micMuted.value = muted // state-independent source of truth (works during ringing too)
        (_state.value as? CallState.InCall)?.let { _state.value = it.copy(isMuted = muted) }
        callScope.launch {
            userMuted = muted
            localAudioTrack?.setEnabled(!muted)
        }
    }

    /** Toggle the local CAMERA track (video analogue of [setMuted]). No-op for audio-only calls. */
    fun setVideoEnabled(enabled: Boolean) {
        callScope.launch {
            localVideoTrack?.setEnabled(enabled)
            _videoEnabled.value = enabled
        }
    }

    /** Flip between front and back cameras. No-op if there's no camera capturer (audio-only). */
    fun switchCamera() {
        callScope.launch {
            (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
        }
    }

    /**
     * ZC-1468: switch a connected call to video. Adds this phone's camera to the PeerConnection, then sends one upgrade
     * offer under the same callId; with no answer in CallRenegotiation.UPGRADE_TIMEOUT_MS (a 2.12.4 phone drops it) the
     * offer is rolled back, the camera removed and the call stays audio. Needs the camera permission (the overlay asks).
     */
    fun requestVideoUpgrade() {
        callScope.launch {
            if (localVideoTrack != null || !hasCameraPermission()) return@launch
            val peer = activePeer ?: return@launch
            val callId = activeCallId ?: return@launch
            renegotiationLock.withLock {
                if (activeCallId != callId || peerConnection == null) return@withLock
                if (!renegotiation.startUpgrade(_state.value, SystemClock.elapsedRealtime())) return@withLock
                try {
                    activeIsVideo = true
                    attachLocalVideo(factoryOrBuild())
                    if (localVideoTrack == null) {
                        Log.w(TAG, "video upgrade: camera did not start — call stays audio")
                        renegotiation.cancelUpgrade()
                        removeLocalVideo()
                        return@withLock
                    }
                    markInCallVideo(true)
                    val offer = createOffer()
                    setLocalDescription(offer)
                    if (activeCallId != callId) return@withLock
                    ownUpgradeOfferSdp = offer.description
                    emit(peer, CallSignalEnvelope(callId, CallSignalType.OFFER, offer.description))
                    Log.d(TAG, "video upgrade offered")
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob =
                        callScope.launch {
                            delay(CallRenegotiation.UPGRADE_TIMEOUT_MS)
                            onVideoUpgradeTimeout(callId)
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "video upgrade failed, call stays audio: ${t.message}")
                    renegotiation.cancelUpgrade()
                    rollBackPendingDescription()
                    removeLocalVideo()
                }
            }
        }
    }

    /**
     * ZC-1904: the user accepted the other phone's switch to video. The held OFFER is answered; the other phone's video
     * shows, this phone's camera stays off until the user taps Videocam (answerVideoUpgrade does not start it).
     */
    fun acceptVideoUpgrade() {
        callScope.launch {
            renegotiationLock.withLock {
                val env = heldUpgradeOffer ?: return@withLock
                val sender = heldUpgradeSender ?: return@withLock
                if (!endVideoQuestion()) return@withLock
                if (_state.value !is CallState.InCall || !belongsToActiveCall(sender, env.callId)) return@withLock
                answerVideoUpgrade(sender, env, rollBackOwnOffer = false)
            }
        }
    }

    /** ZC-1904: the user declined the other phone's switch to video: the held OFFER is dropped and the caller told. */
    fun declineVideoUpgrade() {
        callScope.launch { renegotiationLock.withLock { declineHeldUpgrade(CallRenegotiation.DECLINE_BY_USER) } }
    }

    /** Feed an inbound signal envelope into the call state machine. */
    fun handleSignal(senderPubkeyHex: String, envelope: CallSignalEnvelope) {
        callScope.launch {
            when (envelope.type) {
                CallSignalType.RING -> onRing(senderPubkeyHex, envelope)
                CallSignalType.OFFER -> onOffer(senderPubkeyHex, envelope)
                CallSignalType.ANSWER -> onAnswer(senderPubkeyHex, envelope)
                CallSignalType.ICE -> onIce(senderPubkeyHex, envelope)
                CallSignalType.HANGUP -> onHangup(senderPubkeyHex, envelope)
                CallSignalType.VIDEO_DECLINE -> onVideoDecline(senderPubkeyHex, envelope)
            }
        }
    }

    /**
     * Dispose everything and stop. Disposal runs confined (NOT on WebRTC's signalling
     * thread) and in the correct order — PeerConnection/tracks BEFORE the factory — then
     * the executor is shut down. We do NOT cancel the scope before disposal finishes
     * (the prior bug killed the disposal coroutine and leaked native objects).
     */
    fun shutdown() {
        callScope.launch {
            disposeConfined()
            runCatching { factory?.dispose() }
            factory = null
            runCatching { eglBase?.release() }
            eglBase = null
        }.invokeOnCompletion {
            callScope.cancel()
            runCatching { callExecutor.shutdown() }
        }
    }

    // ---------- Inbound signal handlers (run confined) ----------

    private fun belongsToActiveCall(sender: String, envCallId: String): Boolean {
        val activeSender = activePeer ?: return false
        val activeId = activeCallId ?: return false
        return sender == activeSender && envCallId == activeId
    }

    private fun onRing(sender: String, env: CallSignalEnvelope) {
        warmUpTurnCredentials()
        when {
            // Accept a fresh RING when Idle OR during the brief Ended→Idle cooldown — a
            // legitimate second caller arriving within the 2s reset window must not be
            // auto-rejected as 'busy'.
            _state.value is CallState.Idle || _state.value is CallState.Ended -> {
                // ZC-409: what this call sent before its RING. Its OFFER waits for Accept exactly as an OFFER that
                // arrives while ringing does; its ICE waits for the remote description; nothing is applied here.
                val early = earlySignals.take(sender, env.callId, SystemClock.elapsedRealtime())
                if (early != null && early.hungUp) {
                    Log.w(TAG, "RING of a call its caller already hung up — not rung")
                    return
                }
                activePeer = sender
                activeCallId = env.callId
                teardownInFlight.set(false)
                bufferedOffer = early?.offer
                remoteDescriptionSet = false
                relayCandidateSeen = false
                early?.ice?.forEach { raw ->
                    val candidate = parseIceCandidate(raw) ?: return@forEach
                    if (pendingRemoteCandidates.size >= MAX_PENDING_ICE) pendingRemoteCandidates.removeAt(0)
                    pendingRemoteCandidates += candidate
                }
                if (early != null) {
                    val ice = pendingRemoteCandidates.size
                    Log.d(TAG, "RING: using what was held before it (offer=${early.offer != null}, ice=$ice)")
                }
                val wantsVideo = CallMode.fromWire(env.payload).isVideo
                _state.value = CallState.Ringing(sender, env.callId, wantsVideo)
                startSetupTimeout()
            }
            _state.value is CallState.Dialling && sender == activePeer -> {
                // Glare: WE are dialling this same peer and they dialled us at the same time.
                // Resolve deterministically by both tearing down (no deadlock); the user retries
                // and the second, non-simultaneous attempt connects. Tell the ringer too.
                // Only treat a RING from the peer we are dialling as glare — a RING from a
                // DIFFERENT contact must not tear down our outgoing call; it falls through to the
                // else branch and is auto-rejected as busy.
                Log.w(TAG, "Glare detected — tearing down both sides")
                runCatching { /* best-effort notify */ }
                hangUpConfined(CallEndReason.Glare)
            }
            else -> {
                // Busy with a different call — auto-reject the new ringer.
                callScope.launch { emit(sender, CallSignalEnvelope(env.callId, CallSignalType.HANGUP, CallEndReason.Busy.wireString)) }
            }
        }
    }

    private suspend fun onOffer(sender: String, env: CallSignalEnvelope) {
        if (!belongsToActiveCall(sender, env.callId)) {
            holdEarlySignal(sender, env)
            return
        }
        // Buffer (one-shot) if the user hasn't accepted yet.
        if (_state.value is CallState.Ringing) {
            if (bufferedOffer == null) bufferedOffer = env
            return
        }
        // ZC-1468: in a connected call an OFFER is the other phone's switch to video (CallRenegotiation decides).
        if (_state.value is CallState.InCall) {
            if (env.payload in appliedRemoteSdps || env.payload == heldUpgradeOffer?.payload) {
                Log.w(TAG, "OFFER already applied or held in this call — ignoring")
                return
            }
            renegotiationLock.withLock {
                when (renegotiation.onRemoteOffer(_state.value)) {
                    CallRenegotiation.OfferDecision.ASK -> holdVideoUpgrade(sender, env)
                    CallRenegotiation.OfferDecision.IGNORE ->
                        Log.w(TAG, "upgrade OFFER ignored (not in a call, or our own upgrade wins the glare)")
                    CallRenegotiation.OfferDecision.ROLLBACK_LOCAL_THEN_ANSWER -> {
                        upgradeTimeoutJob?.cancel()
                        answerVideoUpgrade(sender, env, rollBackOwnOffer = true)
                    }
                    CallRenegotiation.OfferDecision.ANSWER -> answerVideoUpgrade(sender, env, rollBackOwnOffer = false)
                }
            }
            return
        }
        if (_state.value !is CallState.Connecting) {
            Log.w(TAG, "OFFER while not Connecting — ignoring"); return
        }
        // Idempotent: a redelivered OFFER after we've already applied one would throw in
        // the native stack (wrong signalling state). Drop it.
        if (remoteDescriptionSet) {
            Log.w(TAG, "duplicate OFFER after remote description set — ignoring"); return
        }
        try {
            if (peerConnection == null) buildPeerConnection()
            attachLocalMedia(activeIsVideo)
            setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, env.payload))
            remoteDescriptionSet = true
            rememberRemoteSdp(env.payload)
            drainPendingCandidates()
            val answer = createAnswer()
            setLocalDescription(answer)
            emit(sender, CallSignalEnvelope(env.callId, CallSignalType.ANSWER, answer.description))
        } catch (t: Throwable) {
            Log.e(TAG, "onOffer setup failed: ${t.message}", t)
            teardownConfined(CallEndReason.SetupFailed(t.message ?: "answer"))
        }
    }

    private suspend fun onAnswer(sender: String, env: CallSignalEnvelope) {
        if (!belongsToActiveCall(sender, env.callId)) {
            Log.w(TAG, "ANSWER from non-active peer or stale callId — ignoring"); return
        }
        // ZC-1468: in a connected call only the answer to our own switch to video is applied.
        if (_state.value is CallState.InCall) {
            if (env.payload in appliedRemoteSdps) {
                Log.w(TAG, "ANSWER already applied in this call — ignoring")
                return
            }
            renegotiationLock.withLock {
                if (!renegotiation.onRemoteAnswer(_state.value)) {
                    Log.w(TAG, "ANSWER with no video upgrade pending — ignoring")
                    return
                }
                upgradeTimeoutJob?.cancel()
                try {
                    setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, env.payload))
                    rememberRemoteSdp(env.payload)
                    Log.d(TAG, "video upgrade answered")
                } catch (t: Throwable) {
                    Log.w(TAG, "video upgrade answer failed, call stays audio: ${t.message}")
                    rollBackPendingDescription()
                    removeLocalVideo()
                    _videoUpgradeDeclined.tryEmit(Unit)
                }
            }
            return
        }
        if (_state.value !is CallState.Dialling) {
            Log.w(TAG, "ANSWER while not Dialling — ignoring"); return
        }
        if (remoteDescriptionSet) {
            Log.w(TAG, "duplicate ANSWER — ignoring"); return
        }
        try {
            setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, env.payload))
            remoteDescriptionSet = true
            rememberRemoteSdp(env.payload)
            drainPendingCandidates()
            _state.value = CallState.Connecting(sender, env.callId, activeIsVideo)
        } catch (t: Throwable) {
            Log.e(TAG, "onAnswer failed: ${t.message}", t)
            teardownConfined(CallEndReason.SetupFailed(t.message ?: "set-answer"))
        }
    }

    /**
     * ZC-1468: answer the other phone's switch to video (in a connected call). The remote video shows by itself; this
     * phone's camera is not turned on (the user may add it). In glare the callee first rolls its own offer back.
     * On failure the call stays as it was. Confined, under renegotiationLock.
     */
    private suspend fun answerVideoUpgrade(
        sender: String,
        env: CallSignalEnvelope,
        rollBackOwnOffer: Boolean,
    ) {
        try {
            if (rollBackOwnOffer) rollBackPendingDescription()
            setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, env.payload))
            rememberRemoteSdp(env.payload)
            val answer = createAnswer()
            setLocalDescription(answer)
            emit(sender, CallSignalEnvelope(env.callId, CallSignalType.ANSWER, answer.description))
            markInCallVideo(true)
            Log.d(TAG, "video upgrade OFFER answered (own offer rolled back: $rollBackOwnOffer)")
        } catch (t: Throwable) {
            Log.w(TAG, "video upgrade OFFER failed, call goes on: ${t.message}")
            rollBackPendingDescription()
        }
    }

    /**
     * ZC-1904: hold the other phone's upgrade OFFER and ask this phone's user; nothing is applied. Unanswered for
     * CallRenegotiation.PROMPT_TIMEOUT_MS, it is declined as a timeout. Confined, under renegotiationLock.
     */
    private fun holdVideoUpgrade(
        sender: String,
        env: CallSignalEnvelope,
    ) {
        heldUpgradeOffer = env
        heldUpgradeSender = sender
        _videoUpgradeRequest.value = true
        Log.d(TAG, "video upgrade OFFER held — asking the user")
        promptTimeoutJob?.cancel()
        promptTimeoutJob =
            callScope.launch {
                delay(CallRenegotiation.PROMPT_TIMEOUT_MS)
                renegotiationLock.withLock {
                    if (heldUpgradeOffer === env) {
                        // ZC-1940: this job runs the decline; endVideoQuestion must not cancel it before the emit.
                        promptTimeoutJob = null
                        declineHeldUpgrade(CallRenegotiation.DECLINE_TIMEOUT)
                    }
                }
            }
    }

    /** ZC-1904: ends the open question once (false when Accept, Decline or the timeout already did). Confined. */
    private fun endVideoQuestion(): Boolean {
        promptTimeoutJob?.cancel()
        promptTimeoutJob = null
        heldUpgradeOffer = null
        heldUpgradeSender = null
        _videoUpgradeRequest.value = false
        return renegotiation.finishAsk()
    }

    /** ZC-1904: drop the held OFFER (nothing was applied) and send VIDEO_DECLINE with [why]. Confined, under the lock. */
    private suspend fun declineHeldUpgrade(why: String) {
        val env = heldUpgradeOffer ?: return
        val sender = heldUpgradeSender ?: return
        if (!endVideoQuestion()) return
        // A redelivery of the declined OFFER is not asked again.
        rememberRemoteSdp(env.payload)
        if (!belongsToActiveCall(sender, env.callId)) return
        // ZC-1937: the decline names the OFFER it answers.
        val payload = CallRenegotiation.declinePayload(why, env.payload)
        emit(sender, CallSignalEnvelope(env.callId, CallSignalType.VIDEO_DECLINE, payload))
        Log.d(TAG, "video upgrade declined ($why)")
    }

    /**
     * ZC-1904: the other phone's user declined our switch to video, or its question timed out. Our pending offer is
     * rolled back and the camera freed; the audio call goes on. Confined.
     */
    private suspend fun onVideoDecline(
        sender: String,
        env: CallSignalEnvelope,
    ) {
        if (!belongsToActiveCall(sender, env.callId)) {
            Log.w(TAG, "VIDEO_DECLINE from non-active peer or stale callId — ignoring")
            return
        }
        renegotiationLock.withLock {
            if (!CallRenegotiation.declineMatchesOffer(env.payload, ownUpgradeOfferSdp)) {
                Log.w(TAG, "VIDEO_DECLINE for another upgrade OFFER — ignoring")
                return
            }
            if (!renegotiation.onRemoteDecline(_state.value)) {
                Log.w(TAG, "VIDEO_DECLINE with no video upgrade pending — ignoring")
                return
            }
            upgradeTimeoutJob?.cancel()
            rollBackPendingDescription()
            removeLocalVideo()
            if (CallRenegotiation.declineReason(env.payload) == CallRenegotiation.DECLINE_BY_USER) {
                Log.d(TAG, "video upgrade declined by the other phone's user — call stays audio")
                _videoUpgradeRejected.tryEmit(Unit)
            } else {
                Log.d(TAG, "video upgrade not answered on the other phone — call stays audio")
                _videoUpgradeDeclined.tryEmit(Unit)
            }
        }
    }

    /** ZC-1468: the upgrade timer. Rolls an unanswered upgrade back once; the audio call goes on. Confined. */
    private suspend fun onVideoUpgradeTimeout(callId: String) {
        renegotiationLock.withLock {
            if (activeCallId != callId) return
            if (!renegotiation.shouldRollBack(SystemClock.elapsedRealtime())) return
            Log.w(TAG, "video upgrade not answered in time — rolled back, call stays audio")
            rollBackPendingDescription()
            removeLocalVideo()
            _videoUpgradeDeclined.tryEmit(Unit)
        }
    }

    /** ZC-1468: back to the last stable description when an offer (ours or theirs) is still pending. Confined. */
    private suspend fun rollBackPendingDescription() {
        val pc = peerConnection ?: return
        val rollback = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        runCatching {
            when (pc.signalingState()) {
                PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> setLocalDescription(rollback)
                PeerConnection.SignalingState.HAVE_REMOTE_OFFER -> setRemoteDescription(rollback)
                else -> Unit
            }
        }.onFailure { Log.w(TAG, "rollback failed: ${it.message}") }
    }

    /** ZC-1468: take this phone's camera out of the call and free it; the layout follows the remaining video. Confined. */
    private fun removeLocalVideo() {
        val pc = peerConnection
        val sender = videoSender
        val track = localVideoTrack
        val source = localVideoSource
        val capturer = videoCapturer
        val helper = surfaceHelper
        videoSender = null
        localVideoTrack = null
        localVideoSource = null
        videoCapturer = null
        surfaceHelper = null
        activeIsVideo = false
        _video.value = _video.value.copy(local = null)
        _videoEnabled.value = true
        if (sender != null) runCatching { pc?.removeTrack(sender) }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        markInCallVideo(_video.value.remote != null)
    }

    /** ZC-1468: the connected call's isVideo, for the overlay's layout. */
    private fun markInCallVideo(video: Boolean) {
        val inCall = _state.value as? CallState.InCall ?: return
        if (inCall.isVideo != video) {
            _state.value = inCall.copy(isVideo = video)
            // ZC-1567: a call that turns into video keeps its screen; one that leaves video goes dark at the ear again.
            updateProximityWakeLock()
        }
    }

    /** ZC-1468: a remote description applied in this call (bounded), so its redelivery is dropped. Confined. */
    private fun rememberRemoteSdp(sdp: String) {
        if (appliedRemoteSdps.size >= MAX_APPLIED_SDPS) appliedRemoteSdps.removeAt(0)
        appliedRemoteSdps += sdp
    }

    private fun onIce(sender: String, env: CallSignalEnvelope) {
        if (!belongsToActiveCall(sender, env.callId)) {
            holdEarlySignal(sender, env)
            return
        }
        val candidate = parseIceCandidate(env.payload) ?: return
        val pc = peerConnection
        if (pc != null && remoteDescriptionSet) {
            runCatching { pc.addIceCandidate(candidate) }
        } else {
            // Buffer candidates that arrive BEFORE the PeerConnection exists or the remote
            // description is applied. Critically this includes the CALLER's candidates — including
            // the slower-to-gather TURN relay candidate — arriving while the callee is still Ringing
            // (no pc built until the user accepts). drainPendingCandidates() flushes them right after
            // setRemoteDescription. Previously a null peerConnection here DROPPED the candidate, so
            // the caller's relay candidate was lost, no ICE pair could form, and calls FAILED in the
            // caller->callee direction (worked only when accept won the race). This makes it
            // deterministic in both directions.
            if (pendingRemoteCandidates.size >= MAX_PENDING_ICE) {
                // Buffer full: evict the OLDEST rather than dropping the NEW one — the slower TURN relay
                // candidate arrives late, so silently dropping the newest could discard the only one that
                // forms a working ICE pair on a restrictive network.
                if (pendingRemoteCandidates.isNotEmpty()) pendingRemoteCandidates.removeAt(0)
                Log.w(TAG, "ICE buffer full ($MAX_PENDING_ICE) — evicted oldest to keep newest candidate")
            }
            pendingRemoteCandidates += candidate
            Log.d(TAG, "buffered early ICE candidate (pc=${pc != null}, remoteSet=$remoteDescriptionSet, pending=${pendingRemoteCandidates.size})")
        }
    }

    private fun onHangup(sender: String, env: CallSignalEnvelope) {
        if (!belongsToActiveCall(sender, env.callId)) {
            holdEarlySignal(sender, env)
            return
        }
        teardownConfined(CallEndReason.RemoteHangup(env.payload))
    }

    /**
     * ZC-409: an OFFER, ICE or HANGUP that is not for the active call is usually one that overtook its RING (the
     * caller's RING and OFFER are separate, concurrent publishes). It is held for that RING, never applied, rung or
     * answered on its own; [onRing] takes it. Confined.
     */
    private fun holdEarlySignal(
        sender: String,
        env: CallSignalEnvelope,
    ) {
        val held = earlySignals.hold(sender, env, SystemClock.elapsedRealtime())
        val what = if (held) "held" else "ignoring"
        Log.d(TAG, "${env.type.code} before its RING (or for a call that is not active) — $what")
    }

    private fun drainPendingCandidates() {
        if (pendingRemoteCandidates.isEmpty()) return
        val pc = peerConnection ?: run { pendingRemoteCandidates.clear(); return }
        pendingRemoteCandidates.forEach { runCatching { pc.addIceCandidate(it) } }
        pendingRemoteCandidates.clear()
    }

    // ---------- timeouts ----------

    private fun startSetupTimeout() {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = callScope.launch {
            delay(SETUP_TIMEOUT_MS)
            if (_state.value is CallState.Dialling ||
                _state.value is CallState.Ringing ||
                _state.value is CallState.Connecting
            ) {
                Log.w(TAG, "call setup timed out (relay candidate seen: $relayCandidateSeen)")
                hangUpConfined(CallFailure.reasonFor(CallFailure.Kind.SETUP_TIMEOUT, stageOf(_state.value), relaySeenOrNotTried()))
            }
        }
    }

    private fun scheduleDisconnectTimeout() {
        disconnectJob?.cancel()
        disconnectJob = callScope.launch {
            delay(DISCONNECT_GRACE_MS)
            if (_state.value is CallState.InCall) {
                Log.w(TAG, "ICE disconnected past grace — tearing down")
                teardownConfined(CallFailure.reasonFor(CallFailure.Kind.ICE_LOST, stageOf(_state.value), relaySeenOrNotTried()))
            }
        }
    }

    /** TURN-CREDS: the stage [CallFailure] maps from. Idle or Ended (a late ICE event) keeps IceFailed, as before. */
    private fun stageOf(state: CallState): CallFailure.Stage =
        when (state) {
            is CallState.Dialling -> CallFailure.Stage.DIALLING
            is CallState.Ringing -> CallFailure.Stage.RINGING
            is CallState.Connecting -> CallFailure.Stage.CONNECTING
            else -> CallFailure.Stage.IN_CALL
        }

    /**
     * TURN-CREDS: false only when this call's ICE ran (a PeerConnection exists) and gathered no relay candidate. With
     * no PeerConnection the offer or answer never arrived, ICE never started, and the relay is not what failed.
     */
    private fun relaySeenOrNotTried(): Boolean = relayCandidateSeen || peerConnection == null

    // ---------- WebRTC plumbing ----------

    private fun factoryOrBuild(): PeerConnectionFactory = factory ?: buildFactory().also { factory = it }

    private fun ensureEgl(): org.webrtc.EglBase = eglBase ?: org.webrtc.EglBase.create().also { eglBase = it }

    private fun buildFactory(): PeerConnectionFactory {
        if (pcfInitialized.compareAndSet(false, true)) {
            val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
        }
        // Hardware-accelerated VP8/VP9/H264 via the shared EglBase context. Video is now a
        // real feature, so the codec factories are justified (the audio-only build dropped
        // them to shrink attack surface; that trade-off no longer applies).
        val egl = ensureEgl()
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /**
     * Fire-and-forget prefetch of the TURN credential so it is (normally) already cached by the
     * time [buildIceServers] runs inside call setup. TurnCredentialsClient serializes concurrent
     * fetches and bounds each to 4 s, so this can neither double-fetch nor delay anything.
     */
    private fun warmUpTurnCredentials() {
        val client = turnCredentials ?: return
        callScope.launch { client.get() }
    }

    /**
     * STUN (cheap path, keeps our IP out of third-party logs) PLUS our dedicated TURN relay
     * (coturn on relay.zsend.xyz), used only when direct/STUN candidates can't connect — symmetric
     * NAT, CGNAT, or Wi-Fi AP/client isolation, which STUN alone can never solve. TURN credentials
     * are short-lived coturn TURN-REST creds fetched from our backend (D13:
     * GET /api/v1/turn/credentials via [TurnCredentialsClient]) — no shared secret ships in the
     * APK anymore, so it can be rotated server-side without an app release. If the fetch
     * fails/times out (bounded to 4 s inside the client) we degrade to STUN-only, exactly the old
     * failure behavior: a backend hiccup can never brick calling, worst case symmetric-NAT/CGNAT
     * pairs don't connect. The relay only ever sees DTLS-SRTP ciphertext either way.
     */
    private suspend fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder(STUN_SERVER_URL).createIceServer(),
        )
        val creds = turnCredentials?.get()
        if (creds != null) {
            creds.uris.forEach { url ->
                servers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername(creds.username)
                        .setPassword(creds.password)
                        .createIceServer(),
                )
            }
        } else {
            Log.w(TAG, "TURN credential fetch failed — STUN-only")
        }
        return servers
    }

    private suspend fun buildPeerConnection() {
        // The call this PeerConnection belongs to (confined read), so a late candidate never speaks for another call.
        val builtForCallId = activeCallId
        val iceServers = buildIceServers()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType =
                if (FaultSeams.isArmed(FaultSeam.TURN_RELAY_ONLY)) {
                    PeerConnection.IceTransportsType.RELAY // TURN-CREDS debug seam; a release build reads "not armed"
                } else {
                    PeerConnection.IceTransportsType.ALL
                }
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = factoryOrBuild().createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    // Log the candidate TYPE (host/srflx/relay) — "relay" proves TURN is reachable
                    // and working; useful for diagnosing NAT-traversal/TURN issues on-device.
                    val type = candidate.sdp.substringAfter("typ ", "?").substringBefore(' ')
                    Log.d(TAG, "local ICE candidate typ=$type")
                    // Encode synchronously on the WebRTC thread (native object is reclaimed
                    // after this returns), then hand the stable String to callScope.
                    val encoded = runCatching { encodeIceCandidate(candidate) }.getOrNull() ?: return
                    callScope.launch {
                        val peer = activePeer ?: return@launch
                        val id = activeCallId ?: return@launch
                        // TURN-CREDS: ZChat's call relay answered for this call (CallFailure's "no relay" test).
                        if (type == "relay" && id == builtForCallId) relayCandidateSeen = true
                        emit(peer, CallSignalEnvelope(id, CallSignalType.ICE, encoded))
                    }
                }
                override fun onIceConnectionChange(s: IceConnectionState) {
                    Log.d(TAG, "ICE state = $s")
                    callScope.launch { onIceState(s) }
                }
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: org.webrtc.DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out MediaStream>) {
                    val remote = receiver.track()
                    if (remote is org.webrtc.VideoTrack) {
                        remote.setEnabled(true)
                        // StateFlow.value is thread-safe; the renderer attaches its sink
                        // on the UI thread (the standard WebRTC pattern).
                        _video.value = _video.value.copy(remote = remote)
                    }
                }
            },
        )
    }

    /** Runs confined. Reacts to ICE connectivity transitions. */
    private fun onIceState(s: IceConnectionState) {
        when (s) {
            IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                setupTimeoutJob?.cancel()
                disconnectJob?.cancel()
                val peer = activePeer ?: return
                val id = activeCallId ?: return
                // Only promote from a live negotiating state — never resurrect a torn-down
                // call (Ended/Idle), which the prior code could do from the WebRTC thread.
                if (_state.value is CallState.Connecting ||
                    _state.value is CallState.Dialling ||
                    _state.value is CallState.InCall
                ) {
                    // Preserve an already-set mute across an ICE flap: setMuted writes _state
                    // optimistically off-callScope, so rebuilding from the possibly-stale userMuted
                    // could clobber the UI to "unmuted" while the mic track is actually muted.
                    val muted = (_state.value as? CallState.InCall)?.isMuted ?: userMuted
                    _state.value = CallState.InCall(peer, id, muted, activeIsVideo)
                    applyVideoSenderParameters()
                    startVideoStatsProbe()
                }
                updateProximityWakeLock()
            }
            IceConnectionState.FAILED ->
                teardownConfined(CallFailure.reasonFor(CallFailure.Kind.ICE_FAILED, stageOf(_state.value), relaySeenOrNotTried()))
            IceConnectionState.DISCONNECTED -> scheduleDisconnectTimeout()
            else -> Unit
        }
    }

    // ---------- audio ----------

    @Suppress("DEPRECATION")
    private fun configureAudioForCall() {
        if (audioFocusRequest != null) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode
        savedSpeakerphoneOn = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        refreshAudioRoutes()
        // Prefer an ALREADY-connected headset over the loudspeaker at call start — otherwise a user
        // on a Bluetooth/wired headset gets no audio in/out (the call would force the speaker). The
        // mid-call AudioDeviceCallback only fires for devices added DURING a call, so the initial
        // pick must honor what's already connected (this is what Telegram/WhatsApp do).
        applyAudioRoute(bestInitialRoute())
        registerAudioDeviceCallback()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { focusChange ->
                // Listener fires on a binder thread — re-confine before touching state.
                callScope.launch {
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            localAudioTrack?.setEnabled(false)
                        AudioManager.AUDIOFOCUS_GAIN ->
                            if (!userMuted) localAudioTrack?.setEnabled(true)
                    }
                }
            }
            .build()
            .also { am.requestAudioFocus(it) }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        unregisterAudioDeviceCallback()
        releaseProximityLock()
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            runCatching { am.stopBluetoothSco() }
        }
        savedSpeakerphoneOn?.let { am.isSpeakerphoneOn = it }
        savedAudioMode?.let { am.mode = it }
        savedSpeakerphoneOn = null
        savedAudioMode = null
        _audioOutput.value = AudioOutputState()
    }

    private fun audioManager(): AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Switch where call audio plays out / is captured. Confined; updates [audioOutput]. */
    fun setAudioRoute(route: AudioRoute) {
        callScope.launch { applyAudioRoute(route) }
    }

    @Suppress("DEPRECATION")
    private fun applyAudioRoute(route: AudioRoute) {
        val am = audioManager()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val wantType = when (route) {
                AudioRoute.EARPIECE -> android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                AudioRoute.SPEAKER -> android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioRoute.BLUETOOTH -> android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                AudioRoute.WIRED -> android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            val dev = am.availableCommunicationDevices.firstOrNull { it.type == wantType }
                ?: am.availableCommunicationDevices.firstOrNull {
                    route == AudioRoute.WIRED && it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }
            if (dev != null) {
                runCatching { am.setCommunicationDevice(dev) }
                    .onFailure { Log.w(TAG, "setCommunicationDevice($route) failed: ${it.message}") }
            }
        } else {
            when (route) {
                AudioRoute.SPEAKER -> { runCatching { am.stopBluetoothSco() }; am.isBluetoothScoOn = false; am.isSpeakerphoneOn = true }
                AudioRoute.EARPIECE, AudioRoute.WIRED -> { runCatching { am.stopBluetoothSco() }; am.isBluetoothScoOn = false; am.isSpeakerphoneOn = false }
                AudioRoute.BLUETOOTH -> { am.isSpeakerphoneOn = false; runCatching { am.startBluetoothSco() }; am.isBluetoothScoOn = true }
            }
        }
        _audioOutput.value = _audioOutput.value.copy(current = route)
        Log.d(TAG, "audio route -> $route")
        updateProximityWakeLock()
    }

    /** Populate the list of currently-available output routes (earpiece+speaker always; BT/wired if present). */
    private fun refreshAudioRoutes() {
        val routes = linkedSetOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager().availableCommunicationDevices.forEach {
                when (it.type) {
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> routes.add(AudioRoute.BLUETOOTH)
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> routes.add(AudioRoute.WIRED)
                }
            }
        }
        _audioOutput.value = _audioOutput.value.copy(available = routes.toList())
    }

    /** Pick the initial output route: a connected headset (BT > wired) over the loudspeaker. */
    private fun bestInitialRoute(): AudioRoute {
        val available = _audioOutput.value.available
        return when {
            AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
            AudioRoute.WIRED in available -> AudioRoute.WIRED
            else -> AudioRoute.SPEAKER
        }
    }

    // ---------- proximity wake lock + auto-route (confined to callScope) ----------

    /**
     * Hold the proximity screen-off wake lock while a live voice call plays from the phone itself (earpiece or, ZC-1567,
     * the loudspeaker a call starts on) — turns the screen off near the face to block cheek-taps, like the system phone
     * app. Released for video, on BT/wired, or when the call leaves InCall ([CallProximity]).
     */
    private fun updateProximityWakeLock() {
        val inCall = _state.value as? CallState.InCall
        val wantHeld =
            CallProximity.screenOffNearFace(
                inCall = inCall != null,
                isVideo = inCall?.isVideo == true,
                route = _audioOutput.value.current,
            )
        if (wantHeld) acquireProximityLock() else releaseProximityLock()
    }

    private fun acquireProximityLock() {
        if (proximityWakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            Log.d(TAG, "proximity wake lock unsupported on this device")
            return
        }
        val lock = proximityWakeLock
            ?: pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ZCHAT::CallProximity")
                .also { proximityWakeLock = it }
        runCatching { lock.acquire() }
            .onSuccess { Log.d(TAG, "proximity wake lock acquired") }
            .onFailure { Log.w(TAG, "proximity acquire failed: ${it.message}") }
    }

    private fun releaseProximityLock() {
        proximityWakeLock?.let {
            // WAIT_FOR_NO_PROXIMITY: don't snap the screen on while still at the ear.
            if (it.isHeld) runCatching { it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        }
        proximityWakeLock = null
    }

    /** Register a device callback so a headset/BT connected mid-call auto-routes to it. */
    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
                val hasBt = added.any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val hasWired = added.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }
                callScope.launch { onCallAudioDevicesChanged(addedBt = hasBt, addedWired = hasWired, removed = false) }
            }

            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) {
                callScope.launch { onCallAudioDevicesChanged(addedBt = false, addedWired = false, removed = true) }
            }
        }
        audioDeviceCallback = cb
        runCatching { audioManager().registerAudioDeviceCallback(cb, null) }
            .onSuccess { Log.d(TAG, "AudioDeviceCallback registered") }
            .onFailure {
                Log.w(TAG, "registerAudioDeviceCallback failed: ${it.message}")
                audioDeviceCallback = null
            }
    }

    private fun unregisterAudioDeviceCallback() {
        audioDeviceCallback?.let { runCatching { audioManager().unregisterAudioDeviceCallback(it) } }
        audioDeviceCallback = null
    }

    /** Confined. Auto-route to a newly-connected headset; fall back when one is removed. */
    private fun onCallAudioDevicesChanged(addedBt: Boolean, addedWired: Boolean, removed: Boolean) {
        if (localAudioTrack == null) return // no active media — don't fight the system pre/post call
        refreshAudioRoutes()
        when {
            addedWired -> applyAudioRoute(AudioRoute.WIRED)
            addedBt -> applyAudioRoute(AudioRoute.BLUETOOTH)
            removed -> {
                val available = _audioOutput.value.available
                if (_audioOutput.value.current !in available) {
                    val fallback = when {
                        AudioRoute.WIRED in available -> AudioRoute.WIRED
                        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
                        else -> AudioRoute.EARPIECE
                    }
                    applyAudioRoute(fallback)
                }
            }
        }
    }

    private fun attachLocalMedia(video: Boolean) {
        val f = factoryOrBuild()
        if (localAudioTrack == null) {
            configureAudioForCall()
            val constraints = MediaConstraints().apply {
                optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            localAudioSource = f.createAudioSource(constraints)
            localAudioTrack = f.createAudioTrack("zchat_audio_${UUID.randomUUID()}", localAudioSource)
            localAudioTrack?.setEnabled(!userMuted)
            peerConnection?.addTrack(localAudioTrack, listOf(STREAM_ID))
        }
        if (video && localVideoTrack == null) {
            attachLocalVideo(f)
        }
    }

    private fun attachLocalVideo(f: PeerConnectionFactory) {
        val (capturer, captureSize) =
            createCameraCapturer() ?: run {
                Log.w(TAG, "no camera available — continuing audio-only")
                activeIsVideo = false
                return
            }
        val egl = ensureEgl()
        val helper = org.webrtc.SurfaceTextureHelper.create("zchat-capture", egl.eglBaseContext)
        surfaceHelper = helper
        videoCapturer = capturer
        val source = f.createVideoSource(capturer.isScreencast)
        localVideoSource = source
        capturer.initialize(helper, appContext, source.capturerObserver)
        // ZC-1470: the camera's supported 16:9 size nearest 1280x720, at 30 fps (VideoQualityConfig).
        val (width, height) = captureSize
        Log.d(TAG, "video capture ${width}x$height@${VideoQualityConfig.FPS}")
        val captureStarted =
            runCatching { capturer.startCapture(width, height, VideoQualityConfig.FPS) }
                .onFailure { Log.w(TAG, "startCapture failed: ${it.message}") }
                .isSuccess
        if (!captureStarted) {
            // Capture never started: do NOT create/enable/advertise a video track — the remote peer
            // would see a dead, black track with no frames flowing (an enabled track is expected to
            // carry media). Fall back to audio-only, mirroring the no-camera path above. The already-
            // allocated capturer/source/surfaceHelper stay referenced and are disposed by
            // teardownConfined; activeIsVideo=false keeps the SDP audio-only.
            activeIsVideo = false
            return
        }
        val track = f.createVideoTrack("zchat_video_${UUID.randomUUID()}", source)
        track.setEnabled(true)
        localVideoTrack = track
        val sender = peerConnection?.addTrack(track, listOf(STREAM_ID))
        videoSender = sender
        preferVideoCodecs(f, sender)
        applyVideoSenderParameters()
        _video.value = _video.value.copy(local = track)
        _videoEnabled.value = true
    }

    /** The capturer of the front camera (else any camera) and the size to capture from it (ZC-1470). */
    private fun createCameraCapturer(): Pair<org.webrtc.VideoCapturer, Pair<Int, Int>>? {
        val enumerator = org.webrtc.Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        // Prefer the front camera for a video call; fall back to any available.
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = front ?: names.firstOrNull() ?: return null
        val capturer = runCatching { enumerator.createCapturer(chosen, null) }.getOrNull() ?: return null
        val supported =
            runCatching { enumerator.getSupportedFormats(chosen).orEmpty().map { it.width to it.height } }
                .getOrDefault(emptyList())
        return capturer to VideoQualityConfig.pickCaptureSize(supported)
    }

    /**
     * ZC-1470: the video transceiver offers hardware H264 first and VP8 second (VideoQualityConfig.PREFERRED_CODECS),
     * other codecs after them in WebRTC's order. Only when this phone can both send and receive H264; otherwise
     * WebRTC's default order stays. Must run before the offer or answer is created. Confined.
     */
    private fun preferVideoCodecs(
        f: PeerConnectionFactory,
        sender: org.webrtc.RtpSender?,
    ) {
        val pc = peerConnection ?: return
        if (sender == null) return
        runCatching {
            val video = org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            val sendNames =
                f
                    .getRtpSenderCapabilities(video)
                    .codecs
                    .map { it.name.uppercase() }
                    .toSet()
            val receive = f.getRtpReceiverCapabilities(video).codecs
            if ("H264" !in sendNames || receive.none { it.name.equals("H264", ignoreCase = true) }) {
                Log.d(TAG, "video codecs: no H264 on this phone, default order kept")
                return
            }
            val rank = VideoQualityConfig.PREFERRED_CODECS
            val ordered = receive.sortedBy { codec -> rank.indexOf(codec.name.uppercase()).let { if (it < 0) rank.size else it } }
            val transceiver = pc.transceivers.firstOrNull { it.sender.id() == sender.id() } ?: return
            transceiver.setCodecPreferences(ordered)
            Log.d(TAG, "video codecs preferred: ${ordered.map { it.name }.distinct()}")
        }.onFailure { Log.w(TAG, "setCodecPreferences failed (default order kept): ${it.message}") }
    }

    /**
     * ZC-1470: up to VideoQualityConfig.MAX_BITRATE_BPS on the camera's sender and MAINTAIN_RESOLUTION (the frame rate
     * drops first under pressure). Applied when the track is added and again once the call connects, because a sender
     * may report no encodings before negotiation. Confined.
     */
    private fun applyVideoSenderParameters() {
        val sender = videoSender ?: return
        runCatching {
            val params = sender.parameters
            params.degradationPreference = VideoQualityConfig.DEGRADATION_PREFERENCE
            params.encodings.forEach {
                it.maxBitrateBps = VideoQualityConfig.MAX_BITRATE_BPS
                it.maxFramerate = VideoQualityConfig.FPS
            }
            val applied = sender.setParameters(params)
            Log.d(TAG, "video sender parameters applied=$applied encodings=${params.encodings.size}")
        }.onFailure { Log.w(TAG, "video sender parameters failed: ${it.message}") }
    }

    /**
     * ZC-1470: every VideoQualityConfig.STATS_INTERVAL_MS while a video call is up, log what is really sent and
     * received (frame size, frames per second, why the encoder is limited, which encoder runs). No key, id or address.
     */
    private fun startVideoStatsProbe() {
        if (videoStatsJob?.isActive == true) return
        videoStatsJob =
            callScope.launch {
                while (true) {
                    delay(VideoQualityConfig.STATS_INTERVAL_MS)
                    if (_state.value !is CallState.InCall) break
                    val pc = peerConnection ?: break
                    if (localVideoTrack == null && _video.value.remote == null) continue
                    runCatching { pc.getStats { report -> logVideoStats(report) } }
                }
            }
    }

    private fun logVideoStats(report: org.webrtc.RTCStatsReport) {
        fun describe(type: String): String? {
            val stats =
                report.statsMap.values.firstOrNull { it.type == type && it.members["kind"] == "video" } ?: return null
            val m = stats.members
            val size = "${m["frameWidth"] ?: "?"}x${m["frameHeight"] ?: "?"}@${m["framesPerSecond"] ?: "?"}"
            return if (type == "outbound-rtp") {
                "$size limit=${m["qualityLimitationReason"] ?: "?"} encoder=${m["encoderImplementation"] ?: "?"}"
            } else {
                "$size decoder=${m["decoderImplementation"] ?: "?"}"
            }
        }
        Log.i(TAG, "video stats out=${describe("outbound-rtp") ?: "none"} in=${describe("inbound-rtp") ?: "none"}")
    }

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private suspend fun createOffer(): SessionDescription = sdpAwait { obs ->
        val pc = peerConnection ?: throw IllegalStateException("no peerConnection for createOffer")
        pc.createOffer(obs, MediaConstraints())
    }

    private suspend fun createAnswer(): SessionDescription = sdpAwait { obs ->
        val pc = peerConnection ?: throw IllegalStateException("no peerConnection for createAnswer")
        pc.createAnswer(obs, MediaConstraints())
    }

    private suspend fun setLocalDescription(desc: SessionDescription): Unit = ackAwait { obs ->
        val pc = peerConnection ?: throw IllegalStateException("no peerConnection for setLocalDescription")
        pc.setLocalDescription(obs, desc)
    }

    private suspend fun setRemoteDescription(desc: SessionDescription): Unit = ackAwait { obs ->
        val pc = peerConnection ?: throw IllegalStateException("no peerConnection for setRemoteDescription")
        pc.setRemoteDescription(obs, desc)
    }

    // sdpAwait/ackAwait: the `call` lambda may throw synchronously (peerConnection null) —
    // wrap so the CompletableDeferred is completed exceptionally instead of suspending
    // forever (the prior permanent-coroutine-leak bug).
    private suspend fun sdpAwait(call: (SdpObserver) -> Unit): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()
        try {
            call(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) { deferred.complete(desc) }
                override fun onCreateFailure(reason: String) { deferred.completeExceptionally(IllegalStateException(reason)) }
                override fun onSetSuccess() {}
                override fun onSetFailure(reason: String) { deferred.completeExceptionally(IllegalStateException(reason)) }
            })
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
        }
        return deferred.await()
    }

    private suspend fun ackAwait(call: (SdpObserver) -> Unit) {
        val deferred = CompletableDeferred<Unit>()
        try {
            call(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {}
                override fun onCreateFailure(reason: String) {}
                override fun onSetSuccess() { deferred.complete(Unit) }
                override fun onSetFailure(reason: String) { deferred.completeExceptionally(IllegalStateException(reason)) }
            })
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
        }
        deferred.await()
    }

    // ---------- teardown ----------

    /** Send HANGUP to the active peer, then tear down. Confined. */
    private fun hangUpConfined(reason: CallEndReason) {
        // Only the first hang-up emits a HANGUP envelope. Once teardown is in-flight,
        // rapid repeat taps (Dialling→Connecting→InCall) must not queue duplicate emits.
        val peer = activePeer
        val id = activeCallId
        if (peer != null && id != null && !teardownInFlight.get()) {
            callScope.launch { emit(peer, CallSignalEnvelope(id, CallSignalType.HANGUP, reason.wireString)) }
        }
        teardownConfined(reason)
    }

    /** Dispose the active call's resources + reset state. Confined; idempotent. */
    private fun teardownConfined(reason: CallEndReason) {
        if (!teardownInFlight.compareAndSet(false, true)) return
        setupTimeoutJob?.cancel(); setupTimeoutJob = null
        disconnectJob?.cancel(); disconnectJob = null
        relayCandidateSeen = false
        disposeConfined()
        _state.value = CallState.Ended(reason)
        callScope.launch {
            delay(IDLE_RESET_DELAY_MS)
            // Clear the flag BEFORE flipping to Idle so a placeCall racing the transition
            // can't see Idle while teardown is still marked active.
            teardownInFlight.set(false)
            if (_state.value is CallState.Ended) _state.value = CallState.Idle
        }
    }

    /**
     * Dispose native objects + restore audio. Confined → runs on the call thread, NOT the
     * WebRTC signalling thread, so dispose() can't deadlock. Disposes the PeerConnection
     * (and its tracks) but NOT the factory (that's only for shutdown()).
     */
    private fun disposeConfined() {
        val pc = peerConnection
        val aTrack = localAudioTrack
        val aSource = localAudioSource
        val vTrack = localVideoTrack
        val vSource = localVideoSource
        val capturer = videoCapturer
        val helper = surfaceHelper
        peerConnection = null
        localAudioTrack = null
        localAudioSource = null
        localVideoTrack = null
        localVideoSource = null
        videoCapturer = null
        surfaceHelper = null
        videoSender = null
        videoStatsJob?.cancel()
        videoStatsJob = null
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        // ZC-1904: a question still open ends with the call (the other phone is gone too).
        promptTimeoutJob?.cancel()
        promptTimeoutJob = null
        heldUpgradeOffer = null
        heldUpgradeSender = null
        _videoUpgradeRequest.value = false
        appliedRemoteSdps.clear()
        activePeer = null
        activeCallId = null
        activeIsVideo = false
        pendingRemoteCandidates.clear()
        bufferedOffer = null
        userMuted = false
        _micMuted.value = false // don't leak mute into the next call
        remoteDescriptionSet = false
        _video.value = VideoTracks()
        _videoEnabled.value = true
        // Close the PeerConnection first, then stop+dispose capture, then sources/tracks.
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { vTrack?.dispose() }
        runCatching { vSource?.dispose() }
        runCatching { aTrack?.dispose() }
        runCatching { aSource?.dispose() }
        runCatching { ringController.release() }
        runCatching { restoreAudio() }
    }

    /** Transient Ended→Idle for pre-call rejections (permission denied) that never built a PC. */
    private fun endTransient(reason: CallEndReason) {
        _state.value = CallState.Ended(reason)
        callScope.launch {
            delay(IDLE_RESET_DELAY_MS)
            if (_state.value is CallState.Ended) _state.value = CallState.Idle
        }
    }

    private suspend fun emit(peer: String, env: CallSignalEnvelope) {
        _outbound.emit(OutboundSignal(peer, env))
    }

    private fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------- ICE candidate serialization (NIP-17 payload) ----------

    private fun encodeIceCandidate(c: IceCandidate): String {
        val mid = c.sdpMid ?: ""
        require(mid.none { isIllegalMidChar(it) }) { "sdpMid contains illegal delimiter chars" }
        return "$mid|${c.sdpMLineIndex}|${c.sdp}"
    }

    private fun isIllegalMidChar(c: Char): Boolean =
        c == '|' || c == '\r' || c == '\n' || c == ' ' || c.code == 0

    private fun parseIceCandidate(raw: String): IceCandidate? {
        if (raw.length > MAX_ICE_PAYLOAD) return null
        val first = raw.indexOf('|'); if (first < 0) return null
        val second = raw.indexOf('|', first + 1); if (second < 0) return null
        val mid = raw.substring(0, first)
        if (mid.length > MAX_SDP_MID_LEN || mid.any { isIllegalMidChar(it) }) return null
        val idx = raw.substring(first + 1, second).toIntOrNull() ?: return null
        if (idx !in 0..MAX_MLINE_INDEX) return null
        val sdp = raw.substring(second + 1)
        if (!sdp.startsWith("candidate:")) return null
        // Printable ASCII only (spaces allowed) — reject C0 controls, DEL, and non-ASCII.
        if (sdp.any { it.code < 0x20 || it.code > 0x7E }) return null
        return IceCandidate(mid, idx, sdp)
    }

    companion object {
        private const val TAG = "VoiceCallManager"
        private const val IDLE_RESET_DELAY_MS = 2_000L
        private const val SETUP_TIMEOUT_MS = 45_000L
        // Shorter grace so when a peer hangs up while our app is backgrounded (HANGUP signal may be
        // delayed by a stale relay sub), the call still tears down promptly via ICE-disconnect
        // instead of lingering ~15s. Still long enough to ride out a brief network blip.
        private const val DISCONNECT_GRACE_MS = 8_000L
        private const val MAX_ICE_PAYLOAD = 1_024
        private const val MAX_SDP_MID_LEN = 32
        private const val MAX_MLINE_INDEX = 32
        private const val MAX_PENDING_ICE = 64
        private const val MAX_APPLIED_SDPS = 8
        private const val STUN_SERVER_URL = "stun:stun.cloudflare.com:3478"
        // TURN relay URIs + credentials come from TurnCredentialsClient (D13) — the shared secret
        // is no longer baked into the APK; default URIs live in TurnCredentialsClient.DEFAULT_TURN_URIS.
        private const val STREAM_ID = "zchat_stream"
        // ZC-1470: capture size, frame rate, bitrate and codec order live in VideoQualityConfig.

        // PCF.initialize is a process-wide one-shot — latch across manager instances.
        private val pcfInitialized = AtomicBoolean(false)
    }
}
