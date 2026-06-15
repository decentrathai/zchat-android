package co.electriccoin.zcash.ui.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
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
 * messages with a remote peer via [CallSignalEnvelope]s delivered through NIP-17. Audio
 * only; no video in v1.
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

    // ---------- Public API (all post onto callScope) ----------

    /** Start an outbound call to [peerPubkeyHex] in audio or video [mode]. Emits RING then OFFER. */
    fun placeCall(peerPubkeyHex: String, mode: CallMode = CallMode.AUDIO) {
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

    /** Accept a Ringing call; we become the callee. */
    fun acceptIncoming() {
        callScope.launch {
            val ringing = _state.value as? CallState.Ringing ?: run {
                Log.w(TAG, "acceptIncoming: not ringing"); return@launch
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

    /** Toggle the local audio track's enabled flag. Honored across all call states. */
    fun setMuted(muted: Boolean) {
        // Reflect in the UI IMMEDIATELY (StateFlow.value is thread-safe) so the toggle is snappy and
        // not queued behind audio-routing work on callScope (Bluetooth SCO churn made it lag/need a
        // second tap). The actual track enable/disable still runs confined on callScope.
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

    /** Feed an inbound signal envelope into the call state machine. */
    fun handleSignal(senderPubkeyHex: String, envelope: CallSignalEnvelope) {
        callScope.launch {
            when (envelope.type) {
                CallSignalType.RING -> onRing(senderPubkeyHex, envelope)
                CallSignalType.OFFER -> onOffer(senderPubkeyHex, envelope)
                CallSignalType.ANSWER -> onAnswer(senderPubkeyHex, envelope)
                CallSignalType.ICE -> onIce(senderPubkeyHex, envelope)
                CallSignalType.HANGUP -> onHangup(senderPubkeyHex, envelope)
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
        when {
            // Accept a fresh RING when Idle OR during the brief Ended→Idle cooldown — a
            // legitimate second caller arriving within the 2s reset window must not be
            // auto-rejected as 'busy'.
            _state.value is CallState.Idle || _state.value is CallState.Ended -> {
                activePeer = sender
                activeCallId = env.callId
                teardownInFlight.set(false)
                bufferedOffer = null
                remoteDescriptionSet = false
                val wantsVideo = CallMode.fromWire(env.payload).isVideo
                _state.value = CallState.Ringing(sender, env.callId, wantsVideo)
                startSetupTimeout()
            }
            _state.value is CallState.Dialling -> {
                // Glare: both peers placed a call at once. Resolve deterministically by
                // both tearing down (no deadlock); the user retries and the second,
                // non-simultaneous attempt connects. Tell the ringer too.
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
            Log.w(TAG, "OFFER from non-active peer or stale callId — ignoring"); return
        }
        // Buffer (one-shot) if the user hasn't accepted yet.
        if (_state.value is CallState.Ringing) {
            if (bufferedOffer == null) bufferedOffer = env
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
        if (_state.value !is CallState.Dialling) {
            Log.w(TAG, "ANSWER while not Dialling — ignoring"); return
        }
        if (remoteDescriptionSet) {
            Log.w(TAG, "duplicate ANSWER — ignoring"); return
        }
        try {
            setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, env.payload))
            remoteDescriptionSet = true
            drainPendingCandidates()
            _state.value = CallState.Connecting(sender, env.callId, activeIsVideo)
        } catch (t: Throwable) {
            Log.e(TAG, "onAnswer failed: ${t.message}", t)
            teardownConfined(CallEndReason.SetupFailed(t.message ?: "set-answer"))
        }
    }

    private fun onIce(sender: String, env: CallSignalEnvelope) {
        if (!belongsToActiveCall(sender, env.callId)) return
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
            Log.w(TAG, "HANGUP from non-active peer or stale callId — ignoring"); return
        }
        teardownConfined(CallEndReason.RemoteHangup(env.payload))
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
                Log.w(TAG, "call setup timed out")
                hangUpConfined(CallEndReason.Timeout)
            }
        }
    }

    private fun scheduleDisconnectTimeout() {
        disconnectJob?.cancel()
        disconnectJob = callScope.launch {
            delay(DISCONNECT_GRACE_MS)
            if (_state.value is CallState.InCall) {
                Log.w(TAG, "ICE disconnected past grace — tearing down")
                teardownConfined(CallEndReason.IceFailed)
            }
        }
    }

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
     * STUN (cheap path, keeps our IP out of third-party logs) PLUS our dedicated TURN relay
     * (coturn on relay.zsend.xyz), used only when direct/STUN candidates can't connect — symmetric
     * NAT, CGNAT, or Wi-Fi AP/client isolation, which STUN alone can never solve. TURN credentials
     * are time-limited (coturn use-auth-secret / TURN REST API): username = expiry unix-seconds,
     * password = base64(HMAC-SHA1(sharedSecret, username)). The shared secret is embedded — client
     * TURN creds are inherently extractable — but the relay only ever sees DTLS-SRTP ciphertext.
     */
    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder(STUN_SERVER_URL).createIceServer(),
        )
        runCatching {
            val username = ((System.currentTimeMillis() / 1000) + TURN_CRED_TTL_SEC).toString()
            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            mac.init(javax.crypto.spec.SecretKeySpec(TURN_SHARED_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val password = android.util.Base64.encodeToString(
                mac.doFinal(username.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP,
            )
            TURN_URLS.forEach { url ->
                servers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername(username)
                        .setPassword(password)
                        .createIceServer(),
                )
            }
        }.onFailure { Log.w(TAG, "TURN credential build failed — STUN-only: ${it.message}") }
        return servers
    }

    private fun buildPeerConnection() {
        val iceServers = buildIceServers()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = factoryOrBuild().createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    // Log the candidate TYPE (host/srflx/relay) — "relay" proves TURN is reachable
                    // and working; useful for diagnosing NAT-traversal/TURN issues on-device.
                    Log.d(TAG, "local ICE candidate typ=${candidate.sdp.substringAfter("typ ", "?").substringBefore(' ')}")
                    // Encode synchronously on the WebRTC thread (native object is reclaimed
                    // after this returns), then hand the stable String to callScope.
                    val encoded = runCatching { encodeIceCandidate(candidate) }.getOrNull() ?: return
                    callScope.launch {
                        val peer = activePeer ?: return@launch
                        val id = activeCallId ?: return@launch
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
                }
                updateProximityWakeLock()
            }
            IceConnectionState.FAILED -> teardownConfined(CallEndReason.IceFailed)
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
     * Hold the proximity screen-off wake lock ONLY while in a live call on the EARPIECE route
     * (phone at the ear) — turns the screen off near the face to block cheek-taps, like the system
     * phone app. Released on speaker/BT/wired or when the call leaves InCall.
     */
    private fun updateProximityWakeLock() {
        val wantHeld = _state.value is CallState.InCall && _audioOutput.value.current == AudioRoute.EARPIECE
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
        val capturer = createCameraCapturer() ?: run {
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
        val captureStarted = runCatching { capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS) }
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
        peerConnection?.addTrack(track, listOf(STREAM_ID))
        _video.value = _video.value.copy(local = track)
        _videoEnabled.value = true
    }

    private fun createCameraCapturer(): org.webrtc.VideoCapturer? {
        val enumerator = org.webrtc.Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        // Prefer the front camera for a video call; fall back to any available.
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = front ?: names.firstOrNull() ?: return null
        return runCatching { enumerator.createCapturer(chosen, null) }.getOrNull()
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
        activePeer = null
        activeCallId = null
        activeIsVideo = false
        pendingRemoteCandidates.clear()
        bufferedOffer = null
        userMuted = false
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
        private const val STUN_SERVER_URL = "stun:stun.cloudflare.com:3478"
        // Dedicated TURN relay (coturn on relay.zsend.xyz) — UDP first, TCP fallback for networks
        // that block UDP. Only used when STUN/host candidates can't connect (symmetric NAT, CGNAT,
        // Wi-Fi AP/client isolation). Makes calls work across different networks, not just same-LAN.
        private val TURN_URLS = listOf(
            "turn:relay.zsend.xyz:3478?transport=udp",
            "turn:relay.zsend.xyz:3478?transport=tcp",
        )
        // coturn use-auth-secret (TURN REST API). Rotate by updating coturn static-auth-secret here.
        private const val TURN_SHARED_SECRET = "93e1d24408ed032b9cad428ac5aefdeffd5f2ac4aeb475492c796dd5da18de23"
        private const val TURN_CRED_TTL_SEC = 86_400L
        private const val STREAM_ID = "zchat_stream"
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
        private const val VIDEO_FPS = 30

        // PCF.initialize is a process-wide one-shot — latch across manager instances.
        private val pcfInitialized = AtomicBoolean(false)
    }
}
