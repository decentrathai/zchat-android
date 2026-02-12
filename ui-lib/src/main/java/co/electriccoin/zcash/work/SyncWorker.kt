package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.PercentDecimal
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.takeWhile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

// TODO [#1249]: Add documentation and tests on background syncing
// TODO [#1249]: https://github.com/Electric-Coin-Company/zashi-android/issues/1249
@Keep
class SyncWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters),
    KoinComponent {
    private val synchronizerProvider: SynchronizerProvider by inject()

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun doWork(): Result {
        Twig.debug { "BG Sync: starting..." }

        var lastStatus: Synchronizer.Status? = null

        synchronizerProvider.synchronizer
            .flatMapLatest {
                Twig.debug { "BG Sync: synchronizer: $it" }

                it?.status?.combine(it.progress) { status, progress ->
                    StatusAndProgress(status, progress).also {
                        Twig.debug { "BG Sync: result: $it" }
                    }
                } ?: emptyFlow()
            }.takeWhile {
                lastStatus = it.status
                it.status != Synchronizer.Status.DISCONNECTED &&
                    it.status != Synchronizer.Status.SYNCED
            }.collect()

        Twig.debug { "BG Sync: terminating with status=$lastStatus" }

        // Only report success if sync actually completed (SYNCED).
        // All other terminal states (DISCONNECTED, null/unavailable, or interrupted
        // mid-sync by synchronizer replacement) should retry with WorkManager backoff
        // so messages aren't delayed until the next 6-hour period.
        return if (lastStatus == Synchronizer.Status.SYNCED) {
            Result.success()
        } else {
            Twig.debug { "BG Sync: incomplete (status=$lastStatus) — requesting retry" }
            Result.retry()
        }
    }

    companion object {
        private val SYNC_PERIOD = 15.minutes

        fun newWorkRequest(): PeriodicWorkRequest {
            Twig.debug { "BG Sync: scheduling with period=$SYNC_PERIOD" }

            val constraints =
                Constraints
                    .Builder()
                    .setRequiresStorageNotLow(true)
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            // With a 15-minute period, use a short initial delay to start syncing quickly
            val initialDelay = 1.minutes

            return PeriodicWorkRequestBuilder<SyncWorker>(SYNC_PERIOD.toJavaDuration())
                .setConstraints(constraints)
                .setInitialDelay(initialDelay.toJavaDuration())
                .build()
        }
    }
}

// Enhancement to this implementation would be returning a better status information
private data class StatusAndProgress(
    val status: Synchronizer.Status,
    val progress: PercentDecimal
)
