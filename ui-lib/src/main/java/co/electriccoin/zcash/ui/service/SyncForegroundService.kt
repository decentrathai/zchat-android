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
import co.electriccoin.zcash.ui.call.CallLogClassifier
import co.electriccoin.zcash.ui.call.IncomingCallNotice
import co.electriccoin.zcash.ui.call.MissedCallNotice
import co.electriccoin.zcash.ui.call.OngoingCallNotice
import co.electriccoin.zcash.ui.call.callPeerLabel
import co.electriccoin.zcash.ui.common.notification.InAppNotification
import co.electriccoin.zcash.ui.common.notification.InAppNotificationManager
import co.electriccoin.zcash.ui.common.notification.OpenChat
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.nostr.NostrRelayPool
import co.electriccoin.zcash.ui.nostr.RelayCopyMarks
import co.electriccoin.zcash.ui.nostr.RelayHealthConfig
import co.electriccoin.zcash.ui.nostr.RelaySetState
import co.electriccoin.zcash.ui.nostr.RelaySetStore
import co.electriccoin.zcash.ui.nostr.RelaySinceLine
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.UnknownReason
import co.electriccoin.zcash.ui.screen.chat.model.ZATTMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import org.koin.android.ext.android.inject
import org.koin.java.KoinJavaComponent

/**
 * R9-6 (REVIEW_R8 F6): what an incoming chat message raises. One rule for the on-chain path
 * (postIncomingChatNotification) and the relay path (RelayMessageNotifier), so an Open or Tunnel message alerts
 * exactly as a Vault message does. [key] names the message (a txid, or "nostr:" and the relay event id); the system
 * notification's id is derived from it, so a second post of the same message replaces the first.
 */
internal sealed interface ChatAlert {
    val key: String

    /** App in the foreground: the in-app banner (and the message sound). */
    data class InAppBanner(
        val notification: InAppNotification,
        override val key: String,
    ) : ChatAlert

    /** App in the background: the system notification. [fullPreview] is set only at FULL_PREVIEW (MessagingStyle). */
    data class SystemNotification(
        val title: String,
        val body: String,
        val peerAddress: String?,
        val fullPreview: FullPreview?,
        override val key: String,
    ) : ChatAlert

    data class FullPreview(
        val senderLabel: String,
        val text: String,
    )
}

/**
 * R9-6: the alert for an incoming chat message from [senderAddress] (null when the sender could not be resolved),
 * or null for none. SILENT and a muted conversation raise nothing. With the app in the foreground the in-app banner
 * replaces the system notification, and a message for the chat that is open on screen ([openChatPeer]) raises
 * nothing at all: it shows in that chat. Otherwise the system notification's text follows [privacy]. Pure, so it is
 * unit-testable without an Android framework.
 */
internal fun chatAlert(
    privacy: NotificationPrivacy,
    senderAddress: String?,
    senderLabel: String?,
    messagePreview: String,
    muted: Boolean,
    isInForeground: Boolean,
    openChatPeer: String?,
    key: String,
): ChatAlert? {
    if (privacy == NotificationPrivacy.SILENT || muted) return null
    // When app is in foreground, show in-app notification banner instead of system notification
    if (isInForeground) {
        // Round 8 D9 (DECISIONS_NIGHT §11): the banner follows the notification privacy setting, as the system
        // notification does. A sender that could not be resolved gets no banner (a tap would have no chat to open).
        val peer = senderAddress ?: return null
        if (peer == openChatPeer) return null
        val banner = inAppChatBanner(privacy, senderLabel ?: peer, messagePreview, peer) ?: return null
        return ChatAlert.InAppBanner(banner, key)
    }
    val label = senderLabel ?: CHAT_ALERT_UNKNOWN_SENDER
    val (title, body) =
        when (privacy) {
            NotificationPrivacy.FULL_PREVIEW -> label to messagePreview
            NotificationPrivacy.SENDER_ONLY -> "New message from $label" to CHAT_ALERT_FALLBACK_TEXT
            NotificationPrivacy.NEW_MESSAGE -> CHAT_ALERT_GENERIC_TITLE to CHAT_ALERT_FALLBACK_TEXT
            NotificationPrivacy.SILENT -> return null
        }
    val fullPreview = if (privacy == NotificationPrivacy.FULL_PREVIEW) ChatAlert.FullPreview(label, messagePreview) else null
    return ChatAlert.SystemNotification(title, body, senderAddress, fullPreview, key)
}

/** A chat preview as the alerts show it: at most [CHAT_ALERT_MAX_PREVIEW_LENGTH] characters, then "...". */
internal fun truncateChatPreview(preview: String): String =
    if (preview.length > CHAT_ALERT_MAX_PREVIEW_LENGTH) preview.take(CHAT_ALERT_MAX_PREVIEW_LENGTH) + "..." else preview

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
    private val nostrInbox: NostrInboxManager =
        NostrInboxManager(
            NostrRelayPool(
                // KI-24 part B: each relay is asked only from its `since` line, kept with the seen list in
                // "zchat_nostr_seen". It moves only while no relay message waits unhandled (RelaySinceLine).
                sinceStore =
                    RelaySinceLine(
                        persistence = RelaySincePrefs { zchatPreferences },
                        isBacklogSettled = { NostrChatBridge.isRelayBacklogSettled() && nostrInbox.isDeliverySettled() },
                        // G-KI24-4: and never past a message a chat reader was handed but has not handled and marked.
                        lowestUnhandledWrapSec = { now -> NostrChatBridge.lowestUnhandledWrapSec(now) },
                    ),
                // RELAY-HEALTH (W-11/W-15): an unavailable public relay is swapped for one from RelayCatalog; a relay
                // swapped in starts at the seen list's floor + 1; the set is saved in "zchat_relay_set" on a swap only.
                health = RelayHealthConfig().apply { seenFloorSec = { zchatPreferences.nostrSeenFloorSec() } },
                relaySet = RelaySetPrefs { zchatPreferences },
            ),
        )
    private val httpClientProvider: co.electriccoin.zcash.ui.common.provider.HttpClientProvider by inject()
    private val voiceCalls: co.electriccoin.zcash.ui.call.VoiceCallManager by lazy {
        // TurnCredentialsClient (D13) fetches short-lived TURN creds via the Tor-aware HTTP client
        // instead of minting them from an APK-baked secret.
        co.electriccoin.zcash.ui.call.VoiceCallManager(
            applicationContext,
            co.electriccoin.zcash.ui.call.TurnCredentialsClient(httpClientProvider),
        )
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
    // B8 — snapshot of app foreground state for the NOSTR request-alert path (isInForeground is a Flow).
    @Volatile private var isAppForeground = false

    // QA2 Q2-74/Q2-80 (ZC-1657, ZC-1659): our own screen is in front, for the call notices. The state flow starts at
    // "in front" before any activity has started, so its first value is checked against the process's importance:
    // a service started in the background (no activity) must still post the incoming card.
    private val callScreenInFront = MutableStateFlow(false)

    private var foregroundTrackerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Which received transactions were already notified (or absorbed as history): see ReceiveNotificationPolicy.
    private val receiveNotificationState = ReceiveNotificationPolicy.State()
    private val seenReceiveTxIds: MutableSet<String> get() = receiveNotificationState.seen
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
        private const val CHAT_FALLBACK_TEXT = CHAT_ALERT_FALLBACK_TEXT
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
        const val EXTRA_OPEN_REQUESTS = "OPEN_MESSAGE_REQUESTS" // B8 — deep-link to the Requests sheet

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

    // ZC-888: this service was created while a Destroy record is on the phone; it starts nothing and makes no channel.
    private var refusedForDestroy = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        if (DestroyWipe.syncStartRefused(this)) {
            refusedForDestroy = true
            return
        }
        createNotificationChannels()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // ZC-692-S: Android restarting the service (no intent) in a process that finished a Destroy All at its
        // start: no sync notice, no sync, no further restart. A start by the app for a wallet carries an intent.
        if (intent == null && DestroyWipe.backgroundSyncHeld) {
            stopSelf()
            return START_NOT_STICKY
        }
        // ZC-888: while a Destroy record is on the phone no start is let through, with an intent or without one.
        // ZC-090-R: while a Reset of this process runs (from its step S5 on), no start is let through, with an intent
        // or without one; the next process holds a start with no intent through the pass above.
        if (refusedForDestroy ||
            DestroyWipe.syncStartRefused(this) ||
            ResetWipe.syncStartRefused(hasIntent = intent != null)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START, null -> {
                // null action handles START_STICKY restart after process death
                if (promoteToForeground("Starting sync…", 0)) {
                    startSyncMonitoring()
                } else {
                    // startForeground was refused (Android 14 DATA_SYNC FGS quota exhausted /
                    // bg-start grace lapsed). The guard already swallowed the throw so we don't
                    // crash; releasing the pending startForegroundService() obligation with
                    // stopSelf() also avoids the secondary "did not call startForeground in time"
                    // watchdog crash. WorkManager keeps the periodic sync; a later start retries.
                    Log.w(TAG, "Foreground promotion refused at start — stopping; WorkManager keeps periodic sync.")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15+ FGS timeout handler: gracefully stop when system imposes timeout. ZC-1555: from API 34 the service runs
    // as REMOTE_MESSAGING, which has no time limit, so this is reached only by a DATA_SYNC service.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout reached (Android 15+, type $fgsType), stopping service. WorkManager continues periodic sync.")
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
        foregroundTrackerJob?.cancel()
        runCatching { callNotifications.cancel() }
        runCatching { callNotifications.cancelOngoing() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterPublisher() }
        runCatching { NostrChatBridge.unregisterRumorPublisher() }
        runCatching { NostrChatBridge.unregisterKeptResender() }
        runCatching { NostrChatBridge.unregisterRelayCopyMarks() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterCallSignalHandler() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterInboxRotater() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterInboxRefresher() }
        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.unregisterRequestNotifier() }
        runCatching { NostrChatBridge.unregisterChatNotifier() }
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

    /**
     * Promote the service to the foreground. Invoked synchronously from [onStartCommand] (start
     * path) and again from the sync coroutine when re-promoting after a demote.
     *
     * On Android 14+ the [ServiceCompat.startForeground] call throws
     * ForegroundServiceStartNotAllowedException when the DATA_SYNC FGS runtime quota is exhausted
     * or the background-start grace window has lapsed. That throw MUST NOT escape (it runs on the
     * onStartCommand path with no surrounding try/catch → uncaught → whole-app crash-loop), so the
     * call is wrapped in the same defensive guard as the sibling [applyCallForegroundType].
     *
     * @return true if the service is now in the foreground; false if promotion was refused (the
     *         guard swallowed the throw). On false the caller should stop/no-op gracefully — the
     *         service stays un-promoted ([isForeground] remains false) and sync retries later.
     */
    private fun promoteToForeground(text: String, progress: Int, synced: Boolean = false): Boolean {
        val notification = createNotification(text, progress, synced)
        val result = guardForegroundPromotion {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    // ZC-1555: REMOTE_MESSAGING from API 34 (no daily limit), DATA_SYNC below.
                    SyncServiceTypes.base(Build.VERSION.SDK_INT),
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            // Only reached when startForeground did NOT throw, so isForeground reflects reality.
            isForeground = true
        }
        result.onFailure {
            Log.w(TAG, "promoteToForeground failed (FGS quota/grace); staying background, sync will retry: ${it.message}")
        }
        return result.isSuccess
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
     * call start and revert to the base type (SyncServiceTypes) when it ends. Guarded on the runtime permission (promoting
     * a MICROPHONE FGS without RECORD_AUDIO throws) and wrapped so a failure can't crash sync.
     */
    private fun applyCallForegroundType(callActive: Boolean, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val type =
            SyncServiceTypes.forCall(
                Build.VERSION.SDK_INT,
                mic = callActive && hasMicPermission(),
                cam = callActive && isVideo && hasCameraPermission(),
            )
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(lastNotifText, lastNotifProgress, lastNotifSynced),
                type,
            )
            isForeground = true
            Log.d(TAG, "FGS type -> ${if (callActive) "call(mic${if (isVideo) "+cam" else ""})" else "base"} (raw=$type)")
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
        // ZC-1559: Open/Tunnel chats and every peer whose Nostr key is held (a Shielded chat's call channel).
        val prefs = getSharedPreferences("zchat_conversation_mode", Context.MODE_PRIVATE)
        return RelayInboxKeepAlive.needed(prefs.all)
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

    /**
     * M1 — remember our relay key at every rotation index up to the current one, so no contact code or key exchange binds
     * one of our own keys to a contact. Best effort: a failure only leaves the registry as it was.
     */
    private fun registerOwnNostrKeys(seed: ByteArray) {
        runCatching {
            for (index in 0..zchatPreferences.getNostrRotationIndex()) {
                val identity =
                    co.electriccoin.zcash.ui.nostr.NOSTRIdentity
                        .fromSeed(seed, index)
                zchatPreferences.registerOwnNostrPubkey(identity.publicKey.joinToString("") { "%02x".format(it) })
            }
        }.onFailure { Log.w(TAG, "could not record our own relay keys", it) }
    }

    private suspend fun startNostrInbox() {
        Log.d(TAG, "Resolving wallet for NOSTR identity…")
        val wallet = persistableWalletProvider.requirePersistableWallet()
        Log.d(TAG, "Wallet resolved; computing BIP-39 seed…")
        val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
        Log.d(TAG, "Seed computed; deriving NOSTR identity…")
        val identity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(seed, zchatPreferences.getNostrRotationIndex())
        registerOwnNostrKeys(seed)
        nostrInbox.start(identity)
        // ZC-421: the rumor publisher (same message, same rumor) goes in right before the publisher and out right after it,
        // so "outbound ready" always means it is there.
        NostrChatBridge.registerRumorPublisher(
            publish = { plaintext, recipientPub, rumorAtSec, expectedRumorId ->
                nostrInbox.sendRumorAt(plaintext, recipientPub, rumorAtSec, expectedRumorId)?.let { r ->
                    NostrChatBridge.PublishResult(r.acks, r.rumorId, r.readableAcks)
                }
            },
            rumorIdAt = { plaintext, recipientPub, rumorAtSec -> nostrInbox.rumorIdAt(plaintext, recipientPub, rumorAtSec) },
        )
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerPublisher { plaintext, recipientPub ->
            val r = nostrInbox.send(plaintext, recipientPub)
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.PublishResult(
                acks = r.acks,
                messageId = r.rumorId,
                readableAcks = r.readableAcks,
            )
        }
        // ZC-393: beside the publisher, the kept-wrap resend a Retry of a message only a public relay took goes through.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerKeptResender { rumorId, recipientPub ->
            nostrInbox.resendKept(rumorId, recipientPub)?.let { r ->
                co.electriccoin.zcash.ui.nostr.NostrChatBridge.PublishResult(
                    acks = r.acks,
                    messageId = r.rumorId,
                    readableAcks = r.readableAcks,
                )
            }
        }
        // ZC-420: and the durable marks of free-first relay copies only a public relay took (the encrypted held-reply store).
        NostrChatBridge.registerRelayCopyMarks(RelayCopyMarksPrefs { zchatPreferences })
        // #188 rotation hot-swap: when the chat layer rotates the NOSTR identity it bumps the index
        // then calls requestInboxRotation(); we re-derive under the new index (seed is captured here
        // and immutable) and hot-swap the live inbox off the service scope so inbound delivery follows
        // the new key with no app restart. Previously the running inbox stayed on the old key.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerInboxRotater {
            serviceScope.launch {
                val rotated = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(
                    seed, zchatPreferences.getNostrRotationIndex()
                )
                zchatPreferences.registerOwnNostrPubkey(rotated.publicKey.joinToString("") { "%02x".format(it) })
                nostrInbox.rotate(rotated)
            }
        }
        // Foreground-liveness kick: ZcashApplication calls refreshInbox() on app return-to-foreground so a
        // relay that went silently quiet during background/Doze (no CLOSED frame → no auto-reconnect) is
        // revived without an app restart. Throttled + staleness-guarded inside the inbox/pool.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerInboxRefresher {
            nostrInbox.refresh()
        }
        // Plug the call signalling sub-channel into the VoiceCallManager.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerCallSignalHandler { sender, envelope ->
            voiceCalls.handleSignal(sender, envelope)
        }
        // B8 — alert on a new inbound contact request (previously invisible unless on the chat list).
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.registerRequestNotifier { req ->
            serviceScope.launch { postMessageRequestNotification(req, isAppForeground) }
        }
        // R9-6 (REVIEW_R8 F6) — a relay (Open / Tunnel) message alerts like an on-chain one. Before this, a relay
        // message raised nothing in the background, so the free default chats missed every message.
        val relayNotifier =
            RelayMessageNotifier(
                prefs = zchatPreferences,
                isInForeground = { isAppForeground },
                deliver = { alert -> postChatAlert(alert) },
            )
        NostrChatBridge.registerChatNotifier { chat -> relayNotifier.onInbound(chat) }
        // B8 — keep a live snapshot of foreground state for the request-alert path.
        foregroundTrackerJob?.cancel()
        foregroundTrackerJob = serviceScope.launch {
            var first = true
            applicationStateProvider.isInForeground.collect {
                isAppForeground = it
                callScreenInFront.value = it && (!first || processIsInFront())
                first = false
            }
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
            // QA2 Q2-74 (ZC-1657, owner B-6): the system incoming card only while the call rings and our screen is
            // not in front; posted when the app goes to the background mid-ring, cancelled when it comes back.
            launch {
                var lastRingingCallId: String? = null
                combine(voiceCalls.state, callScreenInFront) { st, inFront -> st to inFront }
                    .collect { (st, inFront) ->
                        // ZC-1657: the foreground flag can read "not in front" for a process started in the
                        // background and then opened (no emission until the first ON_STOP); ask the process too.
                        if (IncomingCallNotice.wanted(st, inFront || processIsInFront())) {
                            val ringing = st as co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ringing
                            if (ringing.callId != lastRingingCallId) {
                                lastRingingCallId = ringing.callId
                                val display = callerDisplayName(ringing.peerPubkeyHex)
                                runCatching { callNotifications.showIncoming(display, ringing.isVideo, ringing.callId) }
                            }
                        } else if (lastRingingCallId != null) {
                            lastRingingCallId = null
                            runCatching { callNotifications.cancel() }
                        }
                    }
            }
            // ZC-1964 (owner W-148): a call answered from the lock-screen card (or left by Home) has no call screen in
            // front; the ongoing-call card carries its Hang up until the call ends or our screen is in front again.
            // ZC-1981: re-evaluated on every change of the call state, the call screen's visibility and the app's
            // foreground state (Home mid-call posts it; no one-off process check at a call-state change).
            launch {
                var shownOngoing: Pair<String, Boolean>? = null
                val cards =
                    OngoingCallNotice.cardFor(
                        voiceCalls.state,
                        co.electriccoin.zcash.ui.call.CallController.callScreenShown,
                        applicationStateProvider.isInForeground,
                    )
                cards
                    .collect { card ->
                        val live = card as? co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.InCall
                        val connecting = card as? co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Connecting
                        val peer = live?.peerPubkeyHex ?: connecting?.peerPubkeyHex
                        if (peer != null) {
                            val callId = live?.callId ?: connecting?.callId.orEmpty()
                            val key = callId to (live != null)
                            if (key != shownOngoing) {
                                shownOngoing = key
                                val display = callerDisplayName(peer)
                                // ZC-1982: the card's Hang up names this call.
                                runCatching {
                                    callNotifications.showOngoing(display, connected = live != null, callId = callId)
                                }
                            }
                        } else if (shownOngoing != null) {
                            shownOngoing = null
                            runCatching { callNotifications.cancelOngoing() }
                        }
                    }
            }
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
            }
        }
        Log.d(TAG, "NOSTR inbox + voice signalling started — npub=${identity.npub.take(16)}…")

        // Forward inbound DMs into the chat pipeline. The bridge handles the
        // pubkey→peerAddress mapping; ZCALL envelopes are siphoned off internally.
        nostrInbox.inbound.collect { dm ->
            try {
                NostrChatBridge.dispatch(dm, zchatPreferences)
            } finally {
                // KI-24: this DM is now with the bridge (held, handed to a chat screen, or dropped as a replay).
                nostrInbox.onDispatched()
            }
        }
    }

    /** Map a caller's NOSTR pubkey to a friendly display name for the call notification (the call screen's label). */
    private fun callerDisplayName(pubkeyHex: String): String =
        callPeerLabel(zchatPreferences, pubkeyHex)

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
        val connected = connectedAtMs > 0L
        // ZC-1659: the classification lives in CallLogClassifier (glare double set-up is internal noise: null).
        val type = CallLogClassifier.classify(reason, outgoing, connected) ?: return
        val hex = peerHex ?: return // pre-call rejection that never identified a peer — nothing to log
        val peer = zchatPreferences.findPeerByNostrPubkey(hex) ?: return
        val durationSec =
            if (connected) ((System.currentTimeMillis() - connectedAtMs) / 1000).coerceAtLeast(0) else null
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
        notifyMissedCall(type, peer, isVideo)
    }

    /**
     * QA2 Q2-80 (ZC-1659, owner B-7): a call nobody answered while our screen was not in front gets a notification
     * that opens the chat. It follows the notification privacy setting, and a muted chat raises nothing, as its
     * messages do.
     */
    private fun notifyMissedCall(
        type: co.electriccoin.zcash.ui.screen.chat.model.CallLogType,
        peer: String,
        isVideo: Boolean,
    ) {
        val privacy = zchatPreferences.getNotificationPrivacy()
        // ZC-1659: the same importance fallback as the incoming card, so a call watched ringing out posts no notice.
        if (!MissedCallNotice.wanted(type, callScreenInFront.value || processIsInFront(), privacy)) return
        if (zchatPreferences.isConversationMuted(peer)) return
        val title = MissedCallNotice.title(privacy, zchatPreferences.getDisplayName(peer), isVideo) ?: return
        runCatching {
            callNotifications.showMissed(peer, title, MissedCallNotice.notificationId(peer))
        }
    }

    /** True while one of this process's activities is in front (checked at each incoming-card and missed-call decision). */
    private fun processIsInFront(): Boolean =
        runCatching {
            val info = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(info)
            info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun monitorIncomingChatMessages() {
        // R2-5 (d): the wallet's chain tip, with the synchronizer it belongs to. A new synchronizer (restore,
        // wallet switch) re-anchors the history the policy absorbs silently.
        var tipOwner: Synchronizer? = null
        val chainTip =
            synchronizerProvider.synchronizer.flatMapLatest { synchronizer ->
                synchronizer?.networkHeight?.map { height -> synchronizer to height?.value } ?: flowOf(null to null)
            }
        combine(
            transactionRepository.transactions.filterNotNull(),
            applicationStateProvider.isInForeground,
            chainTip,
        ) { transactions, isInForeground, tip ->
            Triple(transactions, isInForeground, tip)
        }.collectLatest { (transactions, isInForeground, tip) ->
            val (tipSynchronizer, tipHeight) = tip
            if (tipSynchronizer != null && tipSynchronizer !== tipOwner) {
                if (tipOwner != null) receiveNotificationState.resetAnchor()
                tipOwner = tipSynchronizer
            }
            val receiveTxs = transactions.filterIsInstance<ReceiveTransaction>()
            val sendTxs = transactions.filterIsInstance<SendTransaction>()
            val currentReceiveTxIds = receiveTxs.map { it.id.txIdString() }.toSet()
            Log.d(
                TAG,
                "SVC monitor: ${transactions.size} total txs (rx=${receiveTxs.size} tx=${sendTxs.size}) " +
                    "historyTip=${receiveNotificationState.historyTip} seen=${seenReceiveTxIds.size}",
            )

            val receiptsById = receiveTxs.associateBy { it.id.txIdString() }
            val receipts =
                receiveTxs.map { tx ->
                    ReceiveNotificationPolicy.Receipt(
                        txId = tx.id.txIdString(),
                        minedHeight = tx.overview.minedHeight?.value,
                        timestamp = tx.timestamp,
                    )
                }
            val newReceiveTxs =
                ReceiveNotificationPolicy
                    .receiptsToNotify(state = receiveNotificationState, receipts = receipts, chainTip = tipHeight)
                    .mapNotNull { receiptsById[it.txId] }

            if (newReceiveTxs.isEmpty()) {
                Log.v(TAG, "SVC monitor: no new receive txs (current rx=${receiveTxs.size}, seen=${seenReceiveTxIds.size})")
                return@collectLatest
            }
            Log.i(TAG, "SVC monitor: ${newReceiveTxs.size} NEW receive txs detected!")

            pruneSeenReceiveTxIds(currentReceiveTxIds)

            // When app is in foreground, we still process messages below but
            // postIncomingChatNotification will handle showing/suppressing per-message.
            // We no longer suppress ALL notifications when in foreground.

            if (!canPostNotifications()) {
                Log.w(TAG, "Notification permission unavailable or notifications disabled")
                // Nothing will be posted for these; mark them seen so we don't re-scan every emission.
                seenReceiveTxIds.addAll(newReceiveTxs.map { it.id.txIdString() })
                return@collectLatest
            }

            for (tx in newReceiveTxs) {
                postIncomingChatNotification(tx, isInForeground)
                // Mark seen only AFTER the notification is posted. collectLatest cancels this block
                // whenever the combined (transactions, isInForeground) flow re-emits (a sync tx-list
                // update, or a foreground/background toggle). Marking the whole batch seen upfront
                // meant a cancellation mid-loop left the not-yet-reached messages flagged seen but
                // never notified — permanently suppressed. Per-tx marking after the post (no
                // suspension point between the two) lets the next emission retry the ones we missed.
                seenReceiveTxIds.add(tx.id.txIdString())
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
        // ZC-1474: a free attachment (ZATT) is relay-only; the chat never shows an on-chain one, so it raises no alert.
        if (ZATTMessage.isZatt(parsed.message)) return

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

        val truncatedMessage = truncateChatPreview(previewMessage)

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

        // R9-6: the rule every chat alert follows (privacy, foreground banner, open chat), shared with relay messages.
        val alert =
            chatAlert(
                privacy = privacy,
                senderAddress = senderAddress,
                senderLabel = senderAddress?.let { zchatPreferences.getDisplayName(it) },
                messagePreview = truncatedMessage,
                muted = false,
                isInForeground = isInForeground,
                openChatPeer = OpenChat.peerAddress,
                key = txId,
            ) ?: return
        postChatAlert(alert)
        Log.d(TAG, "Posted chat ${if (alert is ChatAlert.InAppBanner) "banner" else "notification"} for tx ${txId.take(12)}...")
    }

    /**
     * R9-6: shows [alert] — the in-app banner with the message sound, or the system chat notification (with its group
     * summary). Shared by on-chain messages (postIncomingChatNotification) and relay messages (RelayMessageNotifier).
     */
    private fun postChatAlert(alert: ChatAlert) {
        when (alert) {
            is ChatAlert.InAppBanner -> {
                val inAppMgr =
                    try {
                        KoinJavaComponent.getKoin().getOrNull<InAppNotificationManager>()
                    } catch (_: Exception) {
                        null
                    } ?: return
                inAppMgr.show(alert.notification)
                // Play notification sound while in foreground (system notification channel doesn't apply here)
                if (zchatPreferences.isNotificationSoundEnabled()) {
                    playNotificationSound()
                }
            }
            is ChatAlert.SystemNotification -> postSystemChatNotification(alert)
        }
    }

    private fun postSystemChatNotification(alert: ChatAlert.SystemNotification) {
        if (!canPostNotifications()) return
        val notificationId = alert.key.hashCode()
        // Deep link: open specific conversation when notification is tapped
        val deepLinkIntent = Intent(this, MainActivity::class.java).apply {
            if (alert.peerAddress != null) {
                putExtra(EXTRA_NAVIGATE_TO_CONVERSATION, alert.peerAddress)
            }
            putExtra(EXTRA_FROM_NOTIFICATION, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            notificationId,
            deepLinkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification with lock screen privacy and optional MessagingStyle
        val builder = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
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
        val fullPreview = alert.fullPreview
        if (fullPreview != null) {
            val person = Person.Builder()
                .setName(fullPreview.senderLabel)
                .build()
            val messagingStyle = NotificationCompat.MessagingStyle(person)
                .addMessage(fullPreview.text, System.currentTimeMillis(), person)
            builder.setStyle(messagingStyle)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
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
        notificationManager.notify(notificationId, builder.build())

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
    }

    /**
     * B8 — a new inbound contact request arrived; surface it even when the user isn't on the chat list.
     * Modeled on postIncomingChatNotification. SECURITY: the claimed address is UNVERIFIED — it must never
     * appear in any notification field (it would doxx the recipient's contact graph); the lock screen only
     * ever sees the generic publicVersion.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun postMessageRequestNotification(request: ZchatPreferences.MessageRequest, isInForeground: Boolean) {
        val privacy = zchatPreferences.getNotificationPrivacy()
        if (privacy == NotificationPrivacy.SILENT) return
        // If the claimed address maps to an existing MUTED conversation, suppress the alert (the in-chat
        // sysnote still persists — mute silences noise, it must not hide security-relevant history).
        val claimed = request.senderAddress
        if (zchatPreferences.getConversationId(claimed) != null && zchatPreferences.isConversationMuted(claimed)) return

        val preview = request.firstMessage.let {
            if (it.length > MAX_NOTIFICATION_CONTENT_LENGTH) it.take(MAX_NOTIFICATION_CONTENT_LENGTH) + "..." else it
        }
        if (isInForeground) {
            val inAppMgr = try {
                org.koin.java.KoinJavaComponent.getKoin().getOrNull<co.electriccoin.zcash.ui.common.notification.InAppNotificationManager>()
            } catch (_: Exception) { null }
            inAppMgr?.show(
                co.electriccoin.zcash.ui.common.notification.InAppNotification(
                    senderName = "Contact request",
                    messagePreview = if (privacy == NotificationPrivacy.FULL_PREVIEW) preview else "New contact request",
                    peerAddress = "",
                    openRequests = true,
                )
            )
            if (zchatPreferences.isNotificationSoundEnabled()) playNotificationSound()
            return
        }
        if (!canPostNotifications()) return

        val (title, body) = when (privacy) {
            NotificationPrivacy.FULL_PREVIEW -> "New contact request" to preview
            else -> "New ZCHAT contact request" to CHAT_FALLBACK_TEXT
        }
        val openIntent = PendingIntent.getActivity(
            this,
            ("zreq" + request.senderNostrPubkeyHex).hashCode(),
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_REQUESTS, true)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_zec_round_stroke)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Adversarial-review fix: a re-INIT from the same pubkey REPLACES its notification but must NOT
            // re-ring/heads-up every time (free anonymous events could ring the phone repeatedly).
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPublicVersion(
                NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
                    .setContentTitle("ZCHAT")
                    .setContentText("New activity")
                    .setSmallIcon(R.drawable.ic_zec_round_stroke)
                    .build()
            )
        @Suppress("DEPRECATION")
        if (!zchatPreferences.isNotificationSoundEnabled()) builder.setNotificationSilent()
        if (!zchatPreferences.isNotificationVibrationEnabled()) builder.setVibrate(longArrayOf(0))
        // Same-pubkey re-INIT REPLACES its notification instead of stacking.
        NotificationManagerCompat.from(this).notify(("zreq:" + request.senderNostrPubkeyHex).hashCode(), builder.build())
        Log.d(TAG, "Posted contact-request notification from ${request.senderNostrPubkeyHex.take(8)}…")
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

/**
 * Runs the foreground-promotion [startForeground] block under a defensive guard.
 *
 * On Android 14+ a DATA_SYNC foreground service whose runtime quota is exhausted — or whose
 * background-start grace window has lapsed — makes [android.app.Service.startForeground] throw
 * ForegroundServiceStartNotAllowedException. That call runs synchronously inside
 * [SyncForegroundService.onStartCommand]; if the throw escapes it is uncaught and crash-loops the
 * whole app. This guard swallows the throw (mirroring [SyncForegroundService]'s
 * applyCallForegroundType) so the caller can stay un-promoted and let sync retry later — it MUST
 * NOT rethrow.
 *
 * Extracted as a pure top-level function so the guard is unit-testable on the JVM without an
 * Android framework (the enclosing Service can't be instantiated in a plain unit test).
 *
 * @return [Result.success] if the block completed, or [Result.failure] wrapping the swallowed
 *         throwable for the caller to log.
 */
internal fun guardForegroundPromotion(startForeground: () -> Unit): Result<Unit> =
    runCatching { startForeground() }

/** Body of a chat alert that does not show the message (the system notification and the in-app banner). */
internal const val CHAT_ALERT_FALLBACK_TEXT = "Open ZCHAT to read"

/** Title of a chat alert that names neither the contact nor the message (NotificationPrivacy.NEW_MESSAGE). */
internal const val CHAT_ALERT_GENERIC_TITLE = "New ZCHAT message"

/** Title of a chat notification whose sender could not be resolved (an on-chain message with no known sender). */
internal const val CHAT_ALERT_UNKNOWN_SENDER = "Unknown sender"

/** The longest message preview a chat alert shows before "...". */
internal const val CHAT_ALERT_MAX_PREVIEW_LENGTH = 200

/**
 * Round 8 D9 (DECISIONS_NIGHT §11, p8-11ce796a05d3): the in-app banner for an incoming chat message, shown while the
 * app is in the foreground, following the same [NotificationPrivacy] setting as the system notification:
 * FULL_PREVIEW shows the contact's name and the message, SENDER_ONLY the name and [CHAT_ALERT_FALLBACK_TEXT],
 * NEW_MESSAGE [CHAT_ALERT_GENERIC_TITLE] and [CHAT_ALERT_FALLBACK_TEXT], and SILENT no banner. A tap still opens the
 * chat. Pure, so it is unit-testable without an Android framework.
 */
internal fun inAppChatBanner(
    privacy: NotificationPrivacy,
    senderLabel: String,
    messagePreview: String,
    peerAddress: String,
): InAppNotification? =
    when (privacy) {
        NotificationPrivacy.FULL_PREVIEW -> InAppNotification(senderLabel, messagePreview, peerAddress)
        NotificationPrivacy.SENDER_ONLY -> InAppNotification(senderLabel, CHAT_ALERT_FALLBACK_TEXT, peerAddress)
        NotificationPrivacy.NEW_MESSAGE -> InAppNotification(CHAT_ALERT_GENERIC_TITLE, CHAT_ALERT_FALLBACK_TEXT, peerAddress)
        NotificationPrivacy.SILENT -> null
    }

/** ZC-420: the relay-copy marks in ZchatPreferences ("relaycopy:<slot>" in the encrypted held-reply store, wallet scope). */
private class RelayCopyMarksPrefs(
    private val prefs: () -> ZchatPreferences,
) : RelayCopyMarks {
    override fun get(slot: String): String? = prefs().getRelayCopyMark(slot)

    override fun put(
        slot: String,
        value: String,
    ): Boolean = prefs().setRelayCopyMark(slot, value)

    override fun remove(slot: String) {
        prefs().setRelayCopyMark(slot, null)
    }

    override fun slots(): Set<String> = prefs().getRelayCopyMarkSlots()
}

/** RELAY-HEALTH: the relay set in ZchatPreferences ("zchat_relay_set", app scope). */
private class RelaySetPrefs(
    private val prefs: () -> ZchatPreferences,
) : RelaySetStore {
    override fun load(): RelaySetState? = prefs().getRelaySetState()

    override fun save(state: RelaySetState?) = prefs().saveRelaySetState(state)
}

/** KI-24 part B: the relay `since` line in ZchatPreferences ("zchat_nostr_seen"), scoped to the loaded wallet. */
private class RelaySincePrefs(
    private val prefs: () -> ZchatPreferences,
) : RelaySinceLine.Persistence {
    override fun get(relayUrl: String): Long? = prefs().getNostrRelaySince(relayUrl)

    override fun advance(
        relayUrl: String,
        sinceSec: Long,
    ): Boolean = prefs().advanceNostrRelaySince(relayUrl, sinceSec)

    override fun scope(): Long = prefs().walletScopeGeneration()
}
