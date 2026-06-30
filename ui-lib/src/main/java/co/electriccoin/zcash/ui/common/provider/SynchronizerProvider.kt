package co.electriccoin.zcash.ui.common.provider

import android.os.SystemClock
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface SynchronizerProvider {
    val error: StateFlow<SynchronizerError?>

    val synchronizer: StateFlow<Synchronizer?>

    /**
     * Get synchronizer and wait for it to be ready.
     */
    suspend fun getSynchronizer(): Synchronizer

    fun resetSynchronizer()
}

class SynchronizerProviderImpl(
    private val walletCoordinator: WalletCoordinator
) : SynchronizerProvider {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val error = MutableStateFlow<SynchronizerError?>(null)

    // Consecutive watchdog-forced reconnects with NO intervening forward progress. Shared across
    // synchronizer instances (each reset spawns a fresh watchdog) so a server that accepts the
    // connection but never advances can't make us churn the SDK forever — we give up after the cap
    // and re-arm only once real progress is seen again.
    private val consecutiveWatchdogResets = AtomicInteger(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val synchronizer: StateFlow<Synchronizer?> =
        walletCoordinator
            .synchronizer
            .flatMapLatest { synchronizer ->
                channelFlow {
                    if (synchronizer != null) {
                        val pipeline = initializeErrorHandling(synchronizer)

                        launch {
                            pipeline.collect { new ->
                                error.update { new }
                            }
                        }

                        // Auto-recover a wedged sync without forcing the user to restart the app.
                        launchSyncLivenessWatchdog(synchronizer)
                    }

                    send(synchronizer)
                    awaitClose {
                        synchronizer?.onProcessorErrorHandler = null
                        synchronizer?.onProcessorErrorResolved = null
                        synchronizer?.onSetupErrorHandler = null
                        synchronizer?.onChainErrorHandler = null
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = walletCoordinator.synchronizer.value
            )

    override suspend fun getSynchronizer(): Synchronizer =
        withContext(Dispatchers.IO) {
            synchronizer
                .filterNotNull()
                .first()
        }

    override fun resetSynchronizer() {
        walletCoordinator.resetSynchronizer()
    }

    private fun initializeErrorHandling(synchronizer: Synchronizer): Flow<SynchronizerError?> {
        val pipeline = MutableStateFlow<SynchronizerError?>(null)

        // synchronizer.onCriticalErrorHandler = { error ->
        //     Twig.error { "WALLET - Error Critical: $error" }
        //     pipeline.update { SynchronizerError.Critical(error)}
        //     false
        // }
        synchronizer.onProcessorErrorHandler = { error ->
            Twig.error { "WALLET - Error Processor: $error" }
            pipeline.update { SynchronizerError.Processor(error) }
            true
        }
        synchronizer.onProcessorErrorResolved = {
            Twig.error { "WALLET - Processor error resolved" }
            pipeline.update { null }
        }
        synchronizer.onSetupErrorHandler = { error ->
            Twig.error { "WALLET - Error Setup: $error" }
            pipeline.update { SynchronizerError.Setup(error) }
            false
        }
        synchronizer.onChainErrorHandler = { x, y ->
            Twig.error { "WALLET - Error Chain: $x, $y" }
            pipeline.update { SynchronizerError.Chain(x, y) }
        }

        return pipeline
    }

    /**
     * Auto-recover a wedged wallet sync without an app restart.
     *
     * Symptom this fixes: while the app stays foregrounded the lightwalletd gRPC channel can go
     * stale (a network change, doze, or a long-idle socket). The SDK keeps reporting SYNCING/SYNCED
     * but the network tip and scan progress freeze, so `areFundsSpendable` and the balances never
     * refresh — the user has to force-stop and relaunch before they can spend their available funds.
     *
     * We watch the two liveness signals that MUST keep moving on a healthy connection — the network
     * block height (Zcash mints a block ~every 75s) and scan progress — and if NEITHER advances for
     * [STALL_THRESHOLD_MS] we recreate the synchronizer in-process. The SDK reuses the persisted
     * compact-block cache, so this is a cheap reconnect (not a re-scan): the automatic equivalent of
     * the manual restart. resetSynchronizer() swaps in a fresh Synchronizer, which cancels this
     * watchdog (flatMapLatest → awaitClose) and starts a new one with fresh timers.
     */
    private fun CoroutineScope.launchSyncLivenessWatchdog(synchronizer: Synchronizer) {
        // `status` and `progress` are plain Flows (no `.value`), and a frozen sync stops emitting
        // entirely — so we can't poll for the timeout. Instead an observer stamps a liveness clock
        // whenever a signal genuinely advances, and an independent timer judges staleness.
        val lastForwardAt = AtomicLong(SystemClock.elapsedRealtime())
        val latestStatus = MutableStateFlow(Synchronizer.Status.INITIALIZING)

        // Observer: bump the liveness clock only on a real forward move in tip height or scan progress.
        launch {
            var lastHeight: Long? = synchronizer.networkHeight.value?.value
            var lastProgressBps = -1
            combine(
                synchronizer.networkHeight,
                synchronizer.status,
                synchronizer.progress
            ) { height, status, progress ->
                Triple(height?.value, status, (progress.decimal * 10_000).toInt())
            }.collect { (height, status, progressBps) ->
                latestStatus.value = status
                val advanced = (height != null && height != lastHeight) || progressBps != lastProgressBps
                if (advanced) {
                    lastHeight = height ?: lastHeight
                    lastProgressBps = progressBps
                    lastForwardAt.set(SystemClock.elapsedRealtime())
                    // The sync is alive and moving — clear any "resets aren't helping" backoff.
                    consecutiveWatchdogResets.set(0)
                }
            }
        }

        // Timer: independent of emissions, so a fully-frozen sync still trips it.
        launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)

                // Only the silent-wedge signature warrants a forced reconnect: the SDK reports it's
                // working (SYNCING/SYNCED) yet tip + scan are frozen. STOPPED is intentional;
                // DISCONNECTED/INITIALIZING mean the SDK is already (re)connecting on its own — forcing
                // a reset then would just churn the synchronizer while genuinely offline. In those
                // states we hold the clock and let the SDK recover.
                val status = latestStatus.value
                val sdkBelievesActive =
                    status == Synchronizer.Status.SYNCING || status == Synchronizer.Status.SYNCED
                if (!sdkBelievesActive) {
                    lastForwardAt.set(SystemClock.elapsedRealtime())
                    continue
                }

                val stalledMs = SystemClock.elapsedRealtime() - lastForwardAt.get()
                if (stalledMs < STALL_THRESHOLD_MS) continue

                if (consecutiveWatchdogResets.get() >= MAX_CONSECUTIVE_RESETS) {
                    // Reconnecting hasn't restored progress (e.g. a misbehaving lightwalletd that
                    // accepts the connection but never serves new blocks). Stop churning the SDK;
                    // a genuine forward move later clears the counter and re-arms us.
                    lastForwardAt.set(SystemClock.elapsedRealtime())
                    continue
                }

                val resetN = consecutiveWatchdogResets.incrementAndGet()
                Twig.error {
                    "WALLET - sync-liveness watchdog: no tip/progress advance for " +
                        "${stalledMs / 1000}s (status=$status) — recreating synchronizer " +
                        "(reset #$resetN) to recover spendability without an app restart"
                }
                resetSynchronizer()
                return@launch // a fresh synchronizer + watchdog take over via flatMapLatest
            }
        }
    }

    private companion object {
        // How often the liveness watchdog samples sync progress.
        private const val WATCHDOG_TICK_MS = 30_000L

        // No network-tip or scan-progress advance for this long ⇒ wedged connection ⇒ recreate.
        // Generously above Zcash's ~75s block cadence so normal inter-block gaps never trip it.
        private const val STALL_THRESHOLD_MS = 6 * 60 * 1000L

        // Stop forcing reconnects after this many consecutive fruitless ones (~24 min of trying),
        // until real progress resumes — so a broken server can't churn the SDK indefinitely.
        private const val MAX_CONSECUTIVE_RESETS = 4
    }
}
