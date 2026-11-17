package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe

object WorkIds {
    const val WORK_ID_BACKGROUND_SYNC = "co.electriccoin.zcash.background_sync"

    fun enableBackgroundSynchronization(context: Context) {
        // ZC-692-S: the app turns background sync on for a wallet, so the hold a finished Destroy set ends here.
        DestroyWipe.backgroundSyncHeld = false
        // ZC-090-R: a Reset of this process is ending it: nothing is enqueued or armed, no start is let through.
        if (ResetWipe.resetRunning) return
        val workManager = WorkManager.getInstance(context)

        Twig.debug { "BG Sync: enqueuing periodic work with UPDATE policy" }

        workManager.enqueueUniquePeriodicWork(
            WORK_ID_BACKGROUND_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            SyncWorker.newWorkRequest()
        )

        Twig.debug { "BG Sync: periodic work enqueued successfully" }

        // Also schedule AlarmManager fallback for reliable delivery during Doze
        SyncAlarmScheduler.schedule(context)
    }

    fun disableBackgroundSynchronization(context: Context) {
        val workManager = WorkManager.getInstance(context)

        workManager.cancelUniqueWork(WORK_ID_BACKGROUND_SYNC)
        SyncAlarmScheduler.cancel(context)
    }
}
