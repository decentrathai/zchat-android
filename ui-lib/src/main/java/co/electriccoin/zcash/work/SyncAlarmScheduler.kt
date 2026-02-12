package co.electriccoin.zcash.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Schedules exact alarms every 15 minutes to trigger [SyncAlarmReceiver].
 * This complements WorkManager by using [AlarmManager.setExactAndAllowWhileIdle]
 * which fires even during Doze mode, ensuring background message delivery.
 */
object SyncAlarmScheduler {

    private const val TAG = "SyncAlarmScheduler"
    private const val REQUEST_CODE = 9001
    private val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // On Android 12+, check if we can schedule exact alarms
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission not granted, falling back to inexact alarm")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                getPendingIntent(context)
            )
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            getPendingIntent(context)
        )

        Log.d(TAG, "Scheduled next sync alarm in ${INTERVAL_MS / 60_000} minutes")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getPendingIntent(context))
        Log.d(TAG, "Cancelled sync alarm")
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SyncAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
