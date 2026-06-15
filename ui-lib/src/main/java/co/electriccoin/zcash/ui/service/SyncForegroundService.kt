package co.electriccoin.zcash.ui.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.UnknownReason
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import java.time.Instant
import java.util.Collections
import org.koin.android.ext.android.inject

/**
 * Foreground Service that keeps the wallet syncing when the app is in the background.
 * Shows a persistent notification with sync progress.
 */
class SyncForegroundService : Service() {

    private val synchronizerProvider: SynchronizerProvider by inject()
    private val transactionRepository: TransactionRepository by inject()
    private val zchatPreferences: ZchatPreferences by inject()
    private val addressCache: AddressCache by inject()
    private val applicationStateProvider: ApplicationStateProvider by inject()
    private val persistableWalletProvider: co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider by inject()
    private val nostrInbox: co.electriccoin.zcash.ui.nostr.NostrInboxManager =
        co.electriccoin.zcash.ui.nostr.NostrInboxManager()
    private val voiceCalls: co.electriccoin.zcash.ui.call.VoiceCallManager by lazy {
        co.electriccoin.zcash.ui.call.VoiceCallManager(applicationContext)
    }
    private val callNotifications: co.electriccoin.zcash.ui.call.CallNotificationController by lazy {
        co.electriccoin.zcash.ui.call.CallNotificationController(applicationContext)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var messageNotificationJob: Job? = null
    private var dismissJob: Job? = null
    private var nostrInboxJob: Job? = null
    private var voiceCallOutboundJob: Job? = null
    private var callNotificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val seenReceiveTxIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var hasSeededReceiveTxIds = false
    // Tracks whether the service currently holds foreground status. Used to avoid
    // re-promoting on every emission and to know whether stopForeground is safe.
    @Volatile private var isForeground = false
    // Last notification content, so a call-driven foreground-type change can re-promote the same
    // notification without losing the sync status display.
    private var lastNotifText: String = "Syncing…"
    private var lastNotifProgress: Int = 0
    private var lastNotifSynced: Boolean = false
    private var callForegroundActive = false

    companion object {
        private const val TAG = "SyncForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val SUMMARY_NOTIFICATION_ID = 1002

        // Call signaling is real-time + useless after the setup window; tag it with a 10-min NIP-40
        // expiration so the relay auto-deletes it (privacy + no indefinite backlog). Chat DMs are
        // NOT given a TTL (they must persist for offline delivery).
        private const val CALL_SIGNAL_TTL_SEC = 600L
        private const val SYNC_CHANNEL_ID_V1 = "sync_channel"
        private const val SYNC_CHANNEL_ID = "sync_channel_v2"
        private const val SYNC_CHANNEL_NAME = "Wallet Sync"
        private const val CHAT_CHANNEL_ID_V1 = "chat_messages_channel"
        private const val CHAT_CHANNEL_ID_V2 = "chat_messages_v2"
        private const val CHAT_CHANNEL_ID = "chat_messages_v3"
        private const val CHAT_CHANNEL_NAME = "ZCHAT Messages"
        private const val CHAT_FALLBACK_TEXT = "Open ZCHAT to read"
        private const val NOTIFICATION_GROUP_KEY = "zchat_messages"
        private const val MAX_TRACKED_RECEIVE_TX_IDS = 5_000
        private const val MAX_NOTIFICATION_CONTENT_LENGTH = 200
        // How long the "Wallet synced ✓" notification stays visible before we hide it.
        // 2.5s — long enough to read on a glance, short enough that it doesn't feel sticky.
        private const val NOTIFICATION_DISMISS_DELAY_MS = 2_500L

        const val ACTION_START = "co.electriccoin.zcash.ACTION_START_SYNC"
        const val ACTION_STOP = "co.electriccoin.zcash.ACTION_STOP_SYNC"
        const val EXTRA_NAVIGATE_TO_CONVERSATION = "NAVIGATE_TO_CONVERSATION"
        const val EXTRA_FROM_NOTIFICATION = "FROM_NOTIFICATION"

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannels()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START, null -> {
                // null action handles START_STICKY restart after process death
                promoteToForeground("Starting sync…", 0)
                startSyncMonitoring()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15+ FGS timeout handler: gracefully stop when system imposes timeout
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout reached (Android 15+), stopping service. WorkManager continues periodic sync.")
        stopSelf(startId)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        syncJob?.cancel()
        messageNotificationJob?.cancel()
        dismissJob?.cancel()
        nostrInboxJob?.cancel()
        voiceCallOutboundJob?.cancel()
        callNotificationJob?.cancel()
        runCatching { callNotifications.cancel() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterPublisher() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterCallSignalHandler() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterInboxRotater() }
        runCatching { co.electriccoin.zcash.ui.call.CallController.unregister() }
        runCatching { voiceCalls.shutdown() }
        runCatching { nostrInbox.stop() }
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Delete old immutable channels — Android channels are immutable after creation,
            // so we must version-bump and delete old ones to apply new settings
            notificationManager.deleteNotificationChannel(CHAT_CHANNEL_ID_V1)
            notificationManager.deleteNotificationChannel(CHAT_CHANNEL_ID_V2)
            notificationManager.deleteNotificationChannel(SYNC_CHANNEL_ID_V1)

            val syncChannel = NotificationChannel(
                SYNC_CHANNEL_ID,
                SYNC_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows wallet sync progress"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val chatChannel = NotificationChannel(
                CHAT_CHANNEL_ID,
                CHAT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for new incoming chat messages"
                setSound(
                    Uri.parse("android.resource://${packageName}/${R.raw.zchat_message}"),
                    audioAttrs
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250) // Double-pulse
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(chatChannel)
        }
    }

    private fun createNotification(text: String, progress: Int, synced: Boolean): Notification {
        lastNotifText = text
        lastNotifProgress = progress
        lastNotifSynced = synced
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SyncForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("ZCHAT Wallet")
            .setContentText(text)
            // Samsung One UI hides setContentText in the collapsed row but always shows
            // setSubText next to the timestamp — guarantees the status is visible even
            // when the notification isn't expanded.
            .setSubText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_zec_round_stroke)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lock screen

        // Only render a progress bar while we're actively syncing — once SYNCED we omit
        // it so the notification doesn't look stuck at 100%.
        if (!synced) {
            builder.setProgress(100, progress, progress == 0)
        }
        return builder.build()
    }

    /**
     * Push the latest text/progress to the live notification. If we're not in foreground
     * (e.g. demoted after a previous SYNCED), this is a no-op since stopForeground removed
     * the slot — call [promoteToForeground] instead in that case.
     */
    private fun updateNotification(text: String, progress: Int, synced: Boolean) {
        if (!isForeground) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text, progress, synced))
    }

    private fun promoteToForeground(text: String, progress: Int, synced: Boolean = false) {
        val notification = createNotification(text, progress, synced)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    /**
     * Demote the service out of the foreground and remove the notification. The service
     * keeps running (message-monitoring job continues) but loses the ongoing notification
     * slot, which is what gives us the "appears, shows status, then disappears" UX the
     * user asked for on Samsung Fold.
     */
    private fun demoteFromForeground() {
        if (!isForeground) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    /**
     * While a voice/video call is live, the RUNNING service's foreground-service type must include
     * the microphone (and camera for video) — otherwise on Android 14+ a callee that accepts the
     * call from the background has its mic capture blocked and the call connects but stays silent
     * (the earlier "device2 nothing happens"/silent-call class). Re-promote with the right type on
     * call start and revert to DATA_SYNC when it ends. Guarded on the runtime permission (promoting
     * a MICROPHONE FGS without RECORD_AUDIO throws) and wrapped so a failure can't crash sync.
     */
    private fun applyCallForegroundType(callActive: Boolean, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (callActive) {
            if (hasMicPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (isVideo && hasCameraPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(lastNotifText, lastNotifProgress, lastNotifSynced),
                type,
            )
            isForeground = true
            Log.d(TAG, "FGS type -> ${if (callActive) "call(mic${if (isVideo) "+cam" else ""})" else "dataSync"} (raw=$type)")
        }.onFailure { Log.w(TAG, "applyCallForegroundType(active=$callActive, video=$isVideo) failed: ${it.message}") }
    }

    private fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Does the user have at least one conversation in TUNNEL or OPEN mode? When yes, we
     * keep the foreground notification visible so MagicOS / OEM background restrictions
     * don't kill the NOSTR subscription.
     *
     * Scans the SharedPreferences-backed mode store directly to avoid reaching across
     * Koin into the conversation list (which would require a flow snapshot).
     */
    private fun hasAnyNostrConversation(): Boolean {
        // SharedPreferences key format from ZchatPreferencesImpl: "mode:<peer>"
        val prefs = getSharedPreferences("zchat_conversation_mode", Context.MODE_PRIVATE)
        return prefs.all.any { (k, v) ->
            k.startsWith("mode:") &&
                (v as? String).let {
                    it == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL.name ||
                        it == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN.name
                }
        }
    }

    private fun startSyncMonitoring() {
        syncJob?.cancel()
        messageNotificationJob?.cancel()

        syncJob = serviceScope.launch {
            try {
                synchronizerProvider.synchronizer.collectLatest { synchronizer ->
                    if (synchronizer == null) {
                        Log.d(TAG, "No synchronizer available")
                        return@collectLatest
                    }

                    Log.d(TAG, "Monitoring sync progress")

                    synchronizer.status.combine(synchronizer.progress) { status, progress ->
                        Pair(status, progress)
                    }.collect { (status, progress) ->
                        val progressPercent = (progress.decimal * 100).toInt()
                        val statusText = when (status) {
                            Synchronizer.Status.STOPPED -> "Stopped"
                            Synchronizer.Status.DISCONNECTED -> "Disconnected — reconnecting…"
                            Synchronizer.Status.INITIALIZING -> "Initializing…"
                            Synchronizer.Status.SYNCING -> "Syncing wallet… $progressPercent%"
                            Synchronizer.Status.SYNCED -> "Wallet synced ✓"
                        }
                        val synced = status == Synchronizer.Status.SYNCED

                        Log.d(TAG, "Sync status: $statusText")

                        if (synced) {
                            // Release WakeLock; show "synced" briefly, then dismiss the
                            // notification so the user isn't stuck staring at a persistent
                            // bar. The service stays running for message monitoring.
                            Log.d(TAG, "Sync complete, releasing WakeLock + scheduling notification dismissal.")
                            releaseWakeLock()
                            if (isForeground) {
                                updateNotification(statusText, progressPercent, synced = true)
                            } else {
                                // We were demoted before but status re-emitted SYNCED — nothing to show.
                            }
                            dismissJob?.cancel()
                            dismissJob = serviceScope.launch {
                                kotlinx.coroutines.delay(NOTIFICATION_DISMISS_DELAY_MS)
                                // Stay foreground if the user has TUNNEL/OPEN conversations
                                // active — Honor's MagicOS kills demoted services within
                                // seconds, which would stop our NOSTR inbox subscription.
                                if (hasAnyNostrConversation()) {
                                    Log.d(TAG, "Tunnel/Open conversations active — keeping foreground notification alive for NOSTR reception.")
                                    updateNotification("Listening for NOSTR messages…", 0, synced = true)
                                } else {
                                    demoteFromForeground()
                                }
                            }
                        } else {
                            // Cancel any pending dismissal — sync left SYNCED, the
                            // notification needs to keep showing the new state.
                            dismissJob?.cancel()
                            if (isForeground) {
                                updateNotification(statusText, progressPercent, synced = false)
                            } else {
                                // Re-promote so the user sees the new sync attempt. This is
                                // legal because we're inside a foreground-service lifecycle
                                // (Android does NOT count this as a background-start).
                                promoteToForeground(statusText, progressPercent, synced = false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync monitoring failed", e)
            }
        }

        messageNotificationJob = serviceScope.launch {
            try {
                monitorIncomingChatMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Message monitoring failed", e)
            }
        }

        // NOSTR inbox: derive identity from the wallet seed and subscribe to NIP-17 DMs.
        // Started exactly once per process. A second ACTION_START (START_STICKY redelivery
        // or stop→start) must NOT relaunch it: the NostrInboxManager owns a single relay
        // pool whose scope can't be revived after stop(), and a second inbound collector
        // would double-dispatch every DM + call signal. The isActive guard keeps the
        // first live job as the singleton.
        if (nostrInboxJob?.isActive != true) {
            Log.d(TAG, "Launching NOSTR inbox job…")
            nostrInboxJob = serviceScope.launch {
                try {
                    Log.d(TAG, "NOSTR inbox job coroutine entered")
                    startNostrInbox()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "NOSTR inbox start failed", e)
                }
            }
        } else {
            Log.d(TAG, "NOSTR inbox already running — not relaunching")
        }
    }

    private suspend fun startNostrInbox() {
        Log.d(TAG, "Resolving wallet for NOSTR identity…")
        val wallet = persistableWalletProvider.requirePersistableWallet()
        Log.d(TAG, "Wallet resolved; computing BIP-39 seed…")
        val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
        Log.d(TAG, "Seed computed; deriving NOSTR identity…")
        val identity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(seed, zchatPreferences.getNostrRotationIndex())
        nostrInbox.start(identity)
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerPublisher { plaintext, recipientPub ->
            val r = nostrInbox.send(plaintext, recipientPub)
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.PublishResult(acks = r.acks, messageId = r.rumorId)
        }
        // #188 rotation hot-swap: when the chat layer rotates the NOSTR identity it bumps the index
        // then calls requestInboxRotation(); we re-derive under the new index (seed is captured here
        // and immutable) and hot-swap the live inbox off the service scope so inbound delivery follows
        // the new key with no app restart. Previously the running inbox stayed on the old key.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerInboxRotater {
            serviceScope.launch {
                val rotated = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(
                    seed, zchatPreferences.getNostrRotationIndex()
                )
                nostrInbox.rotate(rotated)
            }
        }
        // Plug the call signalling sub-channel into the VoiceCallManager.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerCallSignalHandler { sender, envelope ->
            voiceCalls.handleSignal(sender, envelope)
        }
        co.electriccoin.zcash.ui.call.CallController.register(voiceCalls)
        // Outbound voice-call envelopes → NIP-17 publish.
        voiceCallOutboundJob?.cancel()
        voiceCallOutboundJob = serviceScope.launch {
            voiceCalls.outbound.collect { sig ->
                // Launch each publish independently: nostrInbox.send fans out to multiple relays
                // and a slow/non-acking relay must NOT serialize-block later signals (ICE bursts,
                // ANSWER, HANGUP). Collecting inline previously wedged the whole outbound pipeline.
                launch { runCatching { nostrInbox.send(sig.envelope.serialize(), sig.peerPubkeyHex, ttlSeconds = CALL_SIGNAL_TTL_SEC) } }
            }
        }
        // Incoming-call notification: ring + full-screen intent when state==Ringing so a
        // backgrounded/locked device actually surfaces the call; cancel otherwise.
        callNotificationJob?.cancel()
        callNotificationJob = serviceScope.launch {
            var lastRingingCallId: String? = null
            // Call-log lifecycle tracking: capture peer/direction/connect-time while the call is
            // live (the Ended state carries only the reason — the manager has cleared its peer by
            // then). On Ended, classify + persist + inject a call-log entry into the conversation.
            var trackedCallId: String? = null
            var trackedPeerHex: String? = null
            var trackedOutgoing = false
            var trackedIsVideo = false
            var connectedAtMs = 0L
            voiceCalls.state.collect { st ->
                // Foreground-service type: include microphone (+camera for video) while a call is
                // live so Android 14+ allows capture even when the callee accepts from background.
                val callActive =
                    st is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ringing ||
                        st is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Dialling ||
                        st is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Connecting ||
                        st is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.InCall
                val callIsVideo =
                    (st as? co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ringing)?.isVideo
                        ?: (st as? co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Dialling)?.isVideo
                        ?: (st as? co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Connecting)?.isVideo
                        ?: (st as? co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.InCall)?.isVideo
                        ?: false
                if (callActive != callForegroundActive) {
                    callForegroundActive = callActive
                    applyCallForegroundType(callActive, callIsVideo)
                } else if (callActive) {
                    applyCallForegroundType(true, callIsVideo)
                }
                // Call-log: track the live call's peer/direction/connect-time; on Ended, record it.
                when (st) {
                    is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Dialling -> {
                        if (st.callId != trackedCallId) {
                            trackedCallId = st.callId
                            trackedPeerHex = st.peerPubkeyHex
                            trackedOutgoing = true
                            trackedIsVideo = st.isVideo
                            connectedAtMs = 0L
                        }
                    }
                    is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ringing -> {
                        if (st.callId != trackedCallId) {
                            trackedCallId = st.callId
                            trackedPeerHex = st.peerPubkeyHex
                            trackedOutgoing = false
                            trackedIsVideo = st.isVideo
                            connectedAtMs = 0L
                        }
                    }
                    is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Connecting ->
                        trackedIsVideo = st.isVideo
                    is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.InCall -> {
                        if (connectedAtMs == 0L) connectedAtMs = System.currentTimeMillis()
                        trackedIsVideo = st.isVideo
                    }
                    is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ended -> {
                        recordCallLog(st.reason, trackedPeerHex, trackedOutgoing, trackedIsVideo, connectedAtMs)
                        trackedCallId = null
                        trackedPeerHex = null
                        connectedAtMs = 0L
                    }
                    is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Idle -> Unit
                }
                if (st is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ringing) {
                    if (st.callId != lastRingingCallId) {
                        lastRingingCallId = st.callId
                        val display = callerDisplayName(st.peerPubkeyHex)
                        runCatching { callNotifications.showIncoming(display, st.isVideo) }
                    }
                } else {
                    if (lastRingingCallId != null) {
                        lastRingingCallId = null
                        runCatching { callNotifications.cancel() }
                    }
                }
            }
        }
        Log.d(TAG, "NOSTR inbox + voice signalling started — npub=${identity.npub.take(16)}…")

        // Forward inbound DMs into the chat pipeline. The bridge handles the
        // pubkey→peerAddress mapping; ZCALL envelopes are siphoned off internally.
        nostrInbox.inbound.collect { dm ->
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.dispatch(dm, zchatPreferences)
        }
    }

    /** Map a caller's NOSTR pubkey to a friendly display name for the call notification. */
    private fun callerDisplayName(pubkeyHex: String): String {
        val peer = zchatPreferences.findPeerByNostrPubkey(pubkeyHex)
        return when {
            peer != null -> zchatPreferences.getDisplayName(peer)
            else -> "${pubkeyHex.take(8)}…"
        }
    }

    /**
     * Classify a finished call + persist + inject a local call-log entry into the peer's
     * conversation (incoming/outgoing/missed/declined, with duration if it connected).
     */
    private fun recordCallLog(
        reason: co.electriccoin.zcash.ui.call.CallEndReason,
        peerHex: String?,
        outgoing: Boolean,
        isVideo: Boolean,
        connectedAtMs: Long,
    ) {
        // Glare double-setup is internal noise — don't spam the log with it.
        if (reason is co.electriccoin.zcash.ui.call.CallEndReason.Glare) return
        val hex = peerHex ?: return // pre-call rejection that never identified a peer — nothing to log
        val peer = zchatPreferences.findPeerByNostrPubkey(hex) ?: return
        val connected = connectedAtMs > 0L
        val durationSec =
            if (connected) ((System.currentTimeMillis() - connectedAtMs) / 1000).coerceAtLeast(0) else null
        val type = when {
            connected ->
                if (outgoing) {
                    co.electriccoin.zcash.ui.screen.chat.model.CallLogType.OUTGOING
                } else {
                    co.electriccoin.zcash.ui.screen.chat.model.CallLogType.INCOMING
                }
            reason is co.electriccoin.zcash.ui.call.CallEndReason.Declined ||
                reason is co.electriccoin.zcash.ui.call.CallEndReason.Busy ->
                co.electriccoin.zcash.ui.screen.chat.model.CallLogType.DECLINED
            outgoing -> co.electriccoin.zcash.ui.screen.chat.model.CallLogType.OUTGOING // no answer
            else -> co.electriccoin.zcash.ui.screen.chat.model.CallLogType.MISSED
        }
        val nowMs = System.currentTimeMillis()
        val id = "calllog-${hex.take(8)}-$nowMs"
        zchatPreferences.addCallLogMessage(
            co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.CallLogMessageData(
                id = id,
                peerAddress = peer,
                timestampMillis = nowMs,
                type = type.name,
                isVideo = isVideo,
                durationSec = durationSec,
                isOutgoing = outgoing,
            ),
        )
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.emitLocalMessage(
            co.electriccoin.zcash.ui.screen.chat.model.ChatMessage(
                id = id,
                txId = null,
                text = "",
                timestamp = java.time.Instant.ofEpochMilli(nowMs),
                isOutgoing = outgoing,
                peerAddress = peer,
                isPending = false,
                status = co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENT,
                callLog = co.electriccoin.zcash.ui.screen.chat.model.CallLogInfo(
                    type = type,
                    isVideo = isVideo,
                    durationSec = durationSec,
                ),
            ),
        )
        Log.d(TAG, "call-log: ${type.name}${if (durationSec != null) " ${durationSec}s" else ""} peer=${peer.take(12)}…")
    }

    private suspend fun monitorIncomingChatMessages() {
        combine(
            transactionRepository.transactions.filterNotNull(),
            applicationStateProvider.isInForeground
        ) { transactions, isInForeground ->
            transactions to isInForeground
        }.collectLatest { (transactions, isInForeground) ->
            val receiveTxs = transactions.filterIsInstance<ReceiveTransaction>()
            val sendTxs = transactions.filterIsInstance<SendTransaction>()
            val currentReceiveTxIds = receiveTxs.map { it.id.txIdString() }.toSet()
            Log.d(TAG, "SVC monitor: ${transactions.size} total txs (rx=${receiveTxs.size} tx=${sendTxs.size}) seeded=$hasSeededReceiveTxIds seen=${seenReceiveTxIds.size}")

            if (!hasSeededReceiveTxIds) {
                seenReceiveTxIds.addAll(currentReceiveTxIds)
                hasSeededReceiveTxIds = true
                Log.d(TAG, "Seeded receive tx tracker with ${seenReceiveTxIds.size} existing transactions")
                return@collectLatest
            }

            val newReceiveTxs = receiveTxs
                .filter { it.id.txIdString() !in seenReceiveTxIds }
                .sortedBy { it.timestamp ?: Instant.EPOCH }

            if (newReceiveTxs.isEmpty()) {
                Log.v(TAG, "SVC monitor: no new receive txs (current rx=${receiveTxs.size}, seen=${seenReceiveTxIds.size})")
                return@collectLatest
            }
            Log.i(TAG, "SVC monitor: ${newReceiveTxs.size} NEW receive txs detected!")

            seenReceiveTxIds.addAll(newReceiveTxs.map { it.id.txIdString() })
            pruneSeenReceiveTxIds(currentReceiveTxIds)

            // When app is in foreground, we still process messages below but
            // postIncomingChatNotification will handle showing/suppressing per-message.
            // We no longer suppress ALL notifications when in foreground.

            if (!canPostNotifications()) {
                Log.w(TAG, "Notification permission unavailable or notifications disabled")
                return@collectLatest
            }

            for (tx in newReceiveTxs) {
                postIncomingChatNotification(tx, isInForeground)
            }
        }
    }

    private suspend fun postIncomingChatNotification(tx: ReceiveTransaction, isInForeground: Boolean = false) {
        val txId = tx.id.txIdString()

        val memos = transactionRepository.getMemos(tx)
        if (memos.isEmpty()) return

        val memoText = memos.joinToString("\n").trim()
        if (memoText.isBlank()) return

        if (memoText.startsWith(ZMSGConstants.REMOTE_KILL_PREFIX) ||
            ZMSGProtocol.isStatus(memoText) ||
            ZMSGProtocol.isReaction(memoText) ||
            ZMSGProtocol.isReadReceipt(memoText) ||
            ZMSGProtocol.isKEXMessage(memoText) ||
            ZMSGProtocol.isKEXAckMessage(memoText) ||
            ZMSGGroupProtocol.isGroupMessage(memoText) ||
            ZMSGProtocol.isUnlock(memoText)
        ) {
            return
        }

        val parsed = if (memos.any { ZMSGProtocol.isChunkedMemo(it) }) {
            ZMSGProtocol.reassembleChunks(memos, addressCache)
        } else {
            ZMSGProtocol.parseMemo(memoText, addressCache)
        } ?: return

        if (parsed.message.isBlank()) return
        if (parsed.reason == UnknownReason.NOT_ZMSG_FORMAT || parsed.reason == UnknownReason.MALFORMED_MESSAGE) return

        val privacy = zchatPreferences.getNotificationPrivacy()
        if (privacy == NotificationPrivacy.SILENT) return

        // Show a friendly preview for protocol payloads ("📎 Image · 149 KB" / "🔐 Secure
        // connection request") instead of leaking the raw "ZFILE|…"/"ZBOOT|…" string into the notification.
        // Time-locked + payment-request envelopes get a GENERIC label on purpose: a time-lock's content
        // is meant to stay hidden until it unlocks, and a payment request's free-text reason is
        // sender-authored and may be sensitive — neither should reach a lock screen even in FULL_PREVIEW.
        val previewMessage =
            when {
                ZMSGProtocol.isTimeLock(memoText) -> "🔒 Time-locked message"
                ZMSGProtocol.isPaymentRequest(memoText) -> "💸 Payment request"
                ZFILEMessage.isFileMessage(parsed.message) ->
                    ZFILEMessage.parse(parsed.message)?.let { "📎 ${it.displayText}" } ?: parsed.message
                ZBootMessage.isBootMessage(parsed.message) -> "🔐 Secure connection request"
                else -> parsed.message
            }

        // Truncate message content for notification display
        val truncatedMessage = if (previewMessage.length > MAX_NOTIFICATION_CONTENT_LENGTH) {
            previewMessage.take(MAX_NOTIFICATION_CONTENT_LENGTH) + "..."
        } else {
            previewMessage
        }

        val senderAddress = parsed.senderAddress
            ?: parsed.conversationId?.let { convId ->
                zchatPreferences.getPeerByConversationId(convId)?.also { resolvedPeer ->
                    // Cache hash→address (validated) since convID is a high-confidence source
                    if (parsed.senderHash != null) {
                        addressCache.cacheAddressValidated(parsed.senderHash, resolvedPeer)
                    }
                }
            }
            ?: parsed.senderHash?.let { addressCache.getAddress(it) }

        if (parsed.conversationId != null && senderAddress != null) {
            // Validate hash consistency before writing convID mapping to prevent
            // stale cache entries from corrupting conversation routing
            val hashConsistent = if (parsed.senderHash != null && parsed.senderAddress != null) {
                val expectedHash = ZMSGProtocol.generateAddressHash(parsed.senderAddress)
                expectedHash == parsed.senderHash
            } else {
                true // No hash to validate against, or address resolved via cache (already validated)
            }
            if (hashConsistent) {
                zchatPreferences.setConversationMapping(parsed.conversationId, senderAddress)
                addressCache.addConversationPartner(senderAddress)
            } else {
                Log.w(TAG, "Skipping convID mapping: hash mismatch for ${parsed.conversationId.take(4)}...")
            }
        } else if (senderAddress != null && parsed.senderHash != null) {
            // Even without convID, register as conversation partner for future lookups
            addressCache.addConversationPartner(senderAddress)
        }

        // Check per-conversation mute
        if (senderAddress != null && zchatPreferences.isConversationMuted(senderAddress)) {
            Log.d(TAG, "Conversation muted for ${senderAddress.take(12)}..., skipping notification")
            return
        }

        // When app is in foreground, show in-app notification banner instead of system notification
        if (isInForeground) {
            Log.d(TAG, "App in foreground, showing in-app banner for tx ${txId.take(12)}...")
            val inAppMgr = try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<co.electriccoin.zcash.ui.common.notification.InAppNotificationManager>() } catch (_: Exception) { null }
            if (inAppMgr != null && senderAddress != null) {
                val senderLabel = zchatPreferences.getDisplayName(senderAddress)
                inAppMgr.show(
                    co.electriccoin.zcash.ui.common.notification.InAppNotification(
                        senderName = senderLabel,
                        messagePreview = truncatedMessage,
                        peerAddress = senderAddress
                    )
                )
                // Play notification sound while in foreground (system notification channel doesn't apply here)
                if (zchatPreferences.isNotificationSoundEnabled()) {
                    playNotificationSound()
                }
            }
            return
        }

        val senderLabel = senderAddress?.let { zchatPreferences.getDisplayName(it) } ?: "Unknown sender"
        val (title, body) = when (privacy) {
            NotificationPrivacy.FULL_PREVIEW -> senderLabel to truncatedMessage
            NotificationPrivacy.SENDER_ONLY -> "New message from $senderLabel" to CHAT_FALLBACK_TEXT
            NotificationPrivacy.NEW_MESSAGE -> "New ZCHAT message" to CHAT_FALLBACK_TEXT
            NotificationPrivacy.SILENT -> return
        }

        // Deep link: open specific conversation when notification is tapped
        val deepLinkIntent = Intent(this, MainActivity::class.java).apply {
            if (senderAddress != null) {
                putExtra(EXTRA_NAVIGATE_TO_CONVERSATION, senderAddress)
            }
            putExtra(EXTRA_FROM_NOTIFICATION, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            txId.hashCode(),
            deepLinkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification with lock screen privacy and optional MessagingStyle
        val builder = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_zec_round_stroke)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
                    .setContentTitle("ZCHAT")
                    .setContentText("New message")
                    .setSmallIcon(R.drawable.ic_zec_round_stroke)
                    .build()
            )

        // Use MessagingStyle for FULL_PREVIEW, BigTextStyle otherwise
        if (privacy == NotificationPrivacy.FULL_PREVIEW) {
            val person = Person.Builder()
                .setName(senderLabel)
                .build()
            val messagingStyle = NotificationCompat.MessagingStyle(person)
                .addMessage(truncatedMessage, System.currentTimeMillis(), person)
            builder.setStyle(messagingStyle)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        // Sound/vibration toggles from preferences
        @Suppress("DEPRECATION")
        if (!zchatPreferences.isNotificationSoundEnabled()) {
            builder.setNotificationSilent()
        }
        if (!zchatPreferences.isNotificationVibrationEnabled()) {
            builder.setVibrate(longArrayOf(0))
        }

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(txId.hashCode(), builder.build())

        // Post/update summary notification for grouping
        val summaryIntent = PendingIntent.getActivity(
            this,
            SUMMARY_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val summaryNotification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setContentTitle("ZCHAT")
            .setContentText("New messages")
            .setSmallIcon(R.drawable.ic_zec_round_stroke)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(summaryIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.InboxStyle()
                .setSummaryText("New messages"))
            .build()
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)

        Log.d(TAG, "Posted chat notification for tx ${txId.take(12)}...")
    }

    private fun pruneSeenReceiveTxIds(currentReceiveTxIds: Set<String>) {
        if (seenReceiveTxIds.size <= MAX_TRACKED_RECEIVE_TX_IDS) return

        seenReceiveTxIds.retainAll(currentReceiveTxIds)
        Log.d(TAG, "Pruned receive tx tracker to ${seenReceiveTxIds.size} entries")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun playNotificationSound() {
        var player: MediaPlayer? = null
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                setDataSource(
                    this@SyncForegroundService,
                    android.net.Uri.parse("android.resource://${packageName}/${R.raw.zchat_message}")
                )
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play notification sound", e)
            player?.release()
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            Log.w(TAG, "PowerManager unavailable, skipping wake lock")
            return
        }
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ZCHAT::SyncWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
