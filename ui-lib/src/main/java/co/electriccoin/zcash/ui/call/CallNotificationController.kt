package co.electriccoin.zcash.ui.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import co.electriccoin.zcash.ui.MainActivity

/**
 * Posts / cancels the incoming-call notification so a RING reaches the user even when
 * the app is backgrounded or the screen is locked. The notification:
 *   - lives on a dedicated IMPORTANCE_HIGH channel with the system ringtone + vibration,
 *   - uses NotificationCompat.CallStyle.forIncomingCall (the platform calling UX),
 *   - sets a full-screen intent to MainActivity so it surfaces over the lockscreen,
 *   - carries Accept / Decline actions wired to [CallActionReceiver] (work while locked).
 *
 * The foreground service drives this off VoiceCallManager.state == Ringing.
 */
class CallNotificationController(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ringtone = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming ZCHAT voice calls"
            setSound(
                ringtone,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 800, 1000, 800)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    /** Show the incoming-call notification for [callerDisplay]. */
    fun showIncoming(callerDisplay: String, isVideo: Boolean = false) {
        ensureChannel()
        val caller = Person.Builder().setName(callerDisplay).setImportant(true).build()

        val fullScreen = activityPendingIntent()
        // Answer launches the ACTIVITY directly (not a background broadcast → startActivity, which
        // Android 10+ blocks with "can't open from notification"). The activity accepts the call.
        val answer = acceptActivityPendingIntent()
        val decline = receiverPendingIntent(CallActionReceiver.ACTION_DECLINE, REQ_DECLINE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(if (isVideo) "Incoming ZCHAT video call" else "Incoming ZCHAT call")
            .setContentText(callerDisplay)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .build()

        NotificationManagerCompat.from(context).also { nm ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                nm.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun activityPendingIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            REQ_FULLSCREEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Launch MainActivity to accept the call — avoids the BroadcastReceiver→startActivity block. */
    private fun acceptActivityPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_ACCEPT_CALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQ_ACCEPT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun receiverPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "incoming_calls_v1"
        private const val CHANNEL_NAME = "Incoming Calls"
        private const val NOTIFICATION_ID = 1003
        private const val REQ_FULLSCREEN = 5301
        private const val REQ_ACCEPT = 5302
        private const val REQ_DECLINE = 5303

        /** Extra on the MainActivity launch intent telling it to accept the active incoming call. */
        const val EXTRA_ACCEPT_CALL = "co.electriccoin.zcash.call.EXTRA_ACCEPT_CALL"
    }
}
