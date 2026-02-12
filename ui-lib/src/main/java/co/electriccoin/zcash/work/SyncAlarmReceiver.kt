package co.electriccoin.zcash.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import co.electriccoin.zcash.ui.service.SyncForegroundService

/**
 * BroadcastReceiver that triggers a foreground sync via [SyncForegroundService]
 * and reschedules the next alarm. Used as an AlarmManager-based fallback to ensure
 * timely message delivery even when WorkManager is throttled by Doze or OEM battery
 * optimizations.
 */
class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm fired — starting FGS sync")

        try {
            SyncForegroundService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FGS from alarm", e)
        }

        // Reschedule the next alarm
        SyncAlarmScheduler.schedule(context)
    }

    companion object {
        private const val TAG = "SyncAlarmReceiver"
    }
}
