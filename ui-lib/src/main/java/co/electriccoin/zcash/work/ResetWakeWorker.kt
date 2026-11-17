package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * ZC-090-R (register ZC-090; RESET-LEFTOVERS-SPEC.md revision 2d, section 4.4, A2): the one job a Reset ZCHAT enqueues
 * at its step S5. It does nothing: the start of the process it causes runs the Reset's start pass. The job library keeps
 * its requests in its own table, which a Reset keeps, and puts them back after a reboot, where the alarm of the same step
 * does not survive. The app's start cancels it once the pass has finished (`ResetWipe.cancelWakeAfterThePass`).
 */
class ResetWakeWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result = Result.success()

    companion object {
        const val UNIQUE_NAME = "co.electriccoin.zcash.reset_wake"

        fun enqueue(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ResetWakeWorker>().build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
