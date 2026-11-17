package co.electriccoin.zcash.ui.call

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.service.SyncForegroundService

/**
 * Posts / cancels the incoming-call notification so a RING reaches the user even when
 * the app is backgrounded or the screen is locked. The notification:
 *   - lives on a dedicated IMPORTANCE_HIGH channel with the system ringtone + vibration,
 *   - uses NotificationCompat.CallStyle.forIncomingCall (the platform calling UX),
 *   - sets a full-screen intent to IncomingCallActivity (ZC-1912: the call-only screen over the lock screen),
 *   - carries Decline wired to [CallActionReceiver] and Answer to its accept broadcast (voice, locked, microphone
 *     allowed: no unlock) or to IncomingCallActivity (CallLockScreen.answerByBroadcast).
 *
 * The foreground service drives this off VoiceCallManager.state == Ringing (only while the app is not in front,
 * IncomingCallNotice). QA2 Q2-80 (ZC-1659): [showMissed] tells of a call nobody answered, on its own quiet channel.
 * ZC-1964: [showOngoing] gives a connecting or live call whose screen is not in front its Hang up (OngoingCallNotice).
 */
class CallNotificationController(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        // ZC-1680: the channels' names and descriptions in the phone's language. Making a channel that exists again
        // takes only its new name and description (its sound and the user's settings stay), so an existing install
        // is renamed too.
        val incoming =
            nm.getNotificationChannel(CHANNEL_ID) ?: run {
                val ringtone =
                    RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.call_channel_incoming_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(
                        ringtone,
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 800, 1000, 800)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            }
        incoming.name = context.getString(R.string.call_channel_incoming_name)
        incoming.description = context.getString(R.string.call_channel_incoming_desc)
        // ZC-1659: a missed call is a normal notification: the default sound, never the ringtone.
        val missed =
            NotificationChannel(
                MISSED_CHANNEL_ID,
                context.getString(R.string.call_channel_missed_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.call_channel_missed_desc)
                setShowBadge(true)
            }
        // ZC-1964: the ongoing-call card is silent (no ringtone, no vibration) but shows on the lock screen.
        val ongoing =
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                context.getString(R.string.call_channel_ongoing_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.call_channel_ongoing_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        nm.createNotificationChannels(listOf(incoming, missed, ongoing))
    }

    /** Show the incoming-call notification for [callerDisplay]; ZC-1951: its Answer carries [callId]. */
    fun showIncoming(
        callerDisplay: String,
        isVideo: Boolean,
        callId: String,
    ) {
        ensureChannel()
        // QA2 Q2-79 (ZC-1658): the card's avatar is the ZChat icon, not a lone letter of the name.
        val caller =
            Person
                .Builder()
                .setName(callerDisplay)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher_round))
                .setImportant(true)
                .build()

        val fullScreen = activityPendingIntent()
        // ZC-1912 (owner W-148): Answer for a voice call on a locked phone is the accept broadcast, which answers
        // without an unlock. Otherwise it opens the call-only screen (a receiver may not open a screen from a
        // notification on Android 12+), which asks for the microphone first (ZC-1922) and then answers.
        val micGranted =
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val answer =
            // ZC-1964: the broadcast with or without the full-screen intent; showOngoing then carries the Hang up.
            if (CallLockScreen.answerByBroadcast(isVideo, micGranted, locked, fullScreenIntentAllowed())) {
                receiverPendingIntent(CallActionReceiver.ACTION_ACCEPT, REQ_ACCEPT_BROADCAST, callId)
            } else {
                mainActivityPendingIntent(
                    REQ_ACCEPT,
                    IncomingCallActivity.intent(context, accept = true, callId = callId).apply {
                        // ZC-1956: the call id is the intent's data, so each call's Answer is its own PendingIntent.
                        CallLockScreen.answerIdentity(callId)?.let { setData(Uri.parse(it)) }
                    },
                )
            }
        val decline = receiverPendingIntent(CallActionReceiver.ACTION_DECLINE, REQ_DECLINE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            // R9-6 (REVIEW_R8 F13): the call screen's strings, in the phone's language.
            .setContentTitle(context.getString(if (isVideo) R.string.call_incoming_video else R.string.call_incoming_voice))
            // QA2 Q2-72 (ZC-1655): CallStyle's Person shows the name; the text says what is happening.
            .setContentText(context.getString(if (isVideo) R.string.call_incoming_video else R.string.call_incoming_voice))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .build()

        post(INCOMING_NOTIFICATION_ID, notification)
        // ZC-1912: with the full-screen intent denied (HONOR's default) the card cannot open the call screen; try to open
        // it from here. Android allows this only where the phone lets ZChat open windows from the background; elsewhere
        // the start is refused and the woken screen (AndroidCallRingPlayer) shows the card.
        if (CallLockScreen.needsFullScreenIntentGuide(Build.VERSION.SDK_INT, fullScreenIntentAllowed())) {
            runCatching { context.startActivity(IncomingCallActivity.intent(context, accept = false)) }
                .onFailure { android.util.Log.w(TAG, "call screen could not open: ${it.javaClass.simpleName}") }
        }
    }

    private fun fullScreenIntentAllowed(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() }.getOrNull()
        } else {
            true
        }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
    }

    /**
     * ZC-1964 (owner W-148): the ongoing-call card for a call with [callerDisplay] whose call screen is not in front
     * (answered from the lock-screen card, or left by Home). Its Hang up is the CallActionReceiver broadcast, which ends
     * the call without an unlock; a tap opens the call-only screen. Android accepts a CallStyle card only from a
     * foreground service or with a full-screen intent; the intent is attached on the silent ongoing channel, which
     * never launches it, so the card does not pull the call screen up by itself.
     */
    fun showOngoing(
        callerDisplay: String,
        connected: Boolean,
        callId: String,
    ) {
        ensureChannel()
        val caller =
            Person
                .Builder()
                .setName(callerDisplay)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher_round))
                .setImportant(true)
                .build()
        // ZC-1982: the Hang up names its call (extra and data), so a late tap on one call's card never ends another.
        val hangUp = receiverPendingIntent(CallActionReceiver.ACTION_HANG_UP, REQ_HANG_UP, callId)
        val callScreen = activityPendingIntent()
        val text = context.getString(if (connected) R.string.call_in_call else R.string.call_connecting, callerDisplay)
        val notification =
            NotificationCompat
                .Builder(context, ONGOING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_outgoing)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(callScreen)
                .setFullScreenIntent(callScreen, false)
                .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUp))
                .build()
        post(ONGOING_NOTIFICATION_ID, notification)
    }

    /** ZC-1964: the call ended or its screen is in front again. */
    fun cancelOngoing() {
        NotificationManagerCompat.from(context).cancel(ONGOING_NOTIFICATION_ID)
    }

    /**
     * QA2 Q2-80 (ZC-1659): a call from [peerAddress] nobody answered while ZChat was not in front, titled [title]
     * (MissedCallNotice.title, which follows the privacy setting), under [notificationId] (one per contact). A tap
     * opens the chat, as a chat notification does.
     */
    internal fun showMissed(
        peerAddress: String,
        title: CallScreenLine,
        notificationId: Int,
    ) {
        ensureChannel()
        val text = title.resId?.let { context.getString(it, *title.args.toTypedArray()) } ?: title.literal.orEmpty()
        val openChat =
            Intent(context, MainActivity::class.java)
                // ZC-1659: an own action keeps this intent apart from the chat deep link under filterEquals.
                .setAction(ACTION_OPEN_MISSED_CALL)
                .putExtra(SyncForegroundService.EXTRA_NAVIGATE_TO_CONVERSATION, peerAddress)
                .putExtra(SyncForegroundService.EXTRA_FROM_NOTIFICATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification =
            NotificationCompat
                .Builder(context, MISSED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle(text)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(notificationId, openChat))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat
                        .Builder(context, MISSED_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.sym_call_missed)
                        .setContentTitle(context.getString(R.string.call_log_missed))
                        .build(),
                ).build()
        post(notificationId, notification)
    }

    private fun post(
        id: Int,
        notification: Notification,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    /** ZC-1912: the full-screen intent opens the call-only screen, never the wallet. */
    private fun activityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_FULLSCREEN,
            IncomingCallActivity.intent(context, accept = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun mainActivityPendingIntent(
        requestCode: Int,
        intent: Intent,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun receiverPendingIntent(
        action: String,
        requestCode: Int,
        callId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).setAction(action)
        // ZC-1951: the accept broadcast names its call; FLAG_UPDATE_CURRENT replaces the extra on the next card.
        callId?.let { intent.putExtra(EXTRA_CALL_ID, it) }
        // ZC-1956: extras do not tell PendingIntents apart; the call id as data does.
        CallLockScreen.answerIdentity(callId)?.let { intent.setData(Uri.parse(it)) }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "incoming_calls_v1"
        private const val MISSED_CHANNEL_ID = "missed_calls_v1"
        private const val ONGOING_CHANNEL_ID = "ongoing_calls_v1"

        /** The incoming-call card's id; MissedCallNotice.notificationId never returns it. */
        const val INCOMING_NOTIFICATION_ID = 1003

        /** ZC-1964: the ongoing-call card's id. */
        const val ONGOING_NOTIFICATION_ID = 1004
        private const val REQ_FULLSCREEN = 5301
        private const val REQ_ACCEPT = 5302
        private const val REQ_DECLINE = 5303
        private const val REQ_ACCEPT_BROADCAST = 5304
        private const val REQ_HANG_UP = 5305
        private const val TAG = "CallNotifications"

        /** Extra on the call screen's (formerly MainActivity's) launch intent: accept the ringing call. */
        const val EXTRA_ACCEPT_CALL = "co.electriccoin.zcash.call.EXTRA_ACCEPT_CALL"

        /** ZC-1951: on both Answer intents: the call the card was posted for. */
        const val EXTRA_CALL_ID = "co.electriccoin.zcash.call.EXTRA_CALL_ID"
        private const val ACTION_OPEN_MISSED_CALL = "co.electriccoin.zcash.call.ACTION_OPEN_MISSED_CALL"
    }
}
