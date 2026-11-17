package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * DESTROY-ADV-W (ZC-722; Destroy Advanced spec revision 2c, B10): the one-time job a Destroy All that kept the list of
 * names enqueues, beside one sync alarm. It does nothing: whichever of the two fires first starts ZCHAT's process,
 * whose start pass finishes the wipe and cancels both. No input data, no output.
 */
class DestroyFinishWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
