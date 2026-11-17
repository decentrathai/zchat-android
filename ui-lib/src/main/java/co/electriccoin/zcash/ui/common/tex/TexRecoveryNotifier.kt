package co.electriccoin.zcash.ui.common.tex

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import java.util.Locale

/**
 * F3: the notice after an automatic TEX recovery, as a system notification when the phone allows ZCHAT to post one
 * (Home and the transaction detail say the same until it is read, either way). It names amounts, never addresses.
 */
class TexRecoveryNotifier(
    context: Context,
) : TexNotice {
    private val context = context.applicationContext

    @Suppress("TooGenericExceptionCaught")
    override fun shielded(status: TexStatus) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Twig.info { "TEX check: notifications are off; Home and the transaction detail carry the notice" }
            return
        }
        val texts = texTextsOf(status, Locale.getDefault()) ?: return
        val title = context.getString(texts.title.resId, *texts.title.args.toTypedArray())
        val body = context.getString(texts.body.resId, *texts.body.args.toTypedArray())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                system.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.tex_notification_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val open =
                PendingIntent.getActivity(
                    context,
                    status.key.hashCode(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_zec_round_stroke)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
            manager.notify(NOTIFICATION_TAG, status.key.hashCode(), notification)
        } catch (e: SecurityException) {
            Twig.warn { "TEX check: the notification was not allowed (${e.javaClass.simpleName})" }
        }
    }

    private companion object {
        const val CHANNEL_ID = "tex_recovery"
        const val NOTIFICATION_TAG = "tex_recovery"
    }
}
