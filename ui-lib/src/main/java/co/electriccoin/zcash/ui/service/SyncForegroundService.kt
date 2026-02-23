package co.electriccoin.zcash.ui.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var messageNotificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val seenReceiveTxIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var hasSeededReceiveTxIds = false

    companion object {
        private const val TAG = "SyncForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val SUMMARY_NOTIFICATION_ID = 1002
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
                val notification = createNotification("Starting sync...", 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
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

    private fun createNotification(text: String, progress: Int): Notification {
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

        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("ZCHAT Wallet")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_zec_round_stroke)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lock screen
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text, progress))
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
                            Synchronizer.Status.DISCONNECTED -> "Disconnected"
                            Synchronizer.Status.INITIALIZING -> "Initializing..."
                            Synchronizer.Status.SYNCING -> "Syncing... $progressPercent%"
                            Synchronizer.Status.SYNCED -> "Synced"
                        }

                        Log.d(TAG, "Sync status: $statusText")
                        updateNotification(statusText, progressPercent)

                        // Release WakeLock once synced — CPU keep-awake is only needed during active sync
                        if (status == Synchronizer.Status.SYNCED) {
                            Log.d(TAG, "Sync complete, releasing WakeLock. Service stays for message monitoring.")
                            releaseWakeLock()
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

        // Truncate message content for notification display
        val truncatedMessage = if (parsed.message.length > MAX_NOTIFICATION_CONTENT_LENGTH) {
            parsed.message.take(MAX_NOTIFICATION_CONTENT_LENGTH) + "..."
        } else {
            parsed.message
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
                Log.w(TAG, "Skipping convID mapping: hash mismatch for ${parsed.conversationId?.take(4)}...")
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
        try {
            MediaPlayer().apply {
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
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play notification sound", e)
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
