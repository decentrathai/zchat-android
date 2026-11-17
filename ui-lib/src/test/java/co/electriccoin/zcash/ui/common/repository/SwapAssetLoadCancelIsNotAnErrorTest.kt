package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1458 (c) (OT213b owner finding, Fold, 2 Nov 2026; lane L1): a Retry that cancels the asset load in flight stores
 * the cancellation as the load's error.
 *
 * `SwapRepositoryImpl.requestRefreshAssets` cancels the running load job and starts a new one. At 669cf19e1
 * `refreshAssetsInternal` catches `Exception`, which includes [CancellationException], and writes
 * `error = <CancellationException>, isLoading = false` while the new load is still running. The next load's
 * `copy(isLoading = true)` keeps that error, so the screens show a failure for a load nobody refused.
 *
 * What it must be: a cancelled load writes nothing (the cancellation is rethrown); the state belongs to the load now
 * running. The data source here answers nothing: each load waits until it is cancelled. No network.
 */
class SwapAssetLoadCancelIsNotAnErrorTest {
    @Test
    fun `a Retry that cancels the load in flight does not store the cancellation as the error`() =
        runBlocking {
            val source = ParkedTokens()
            val repository = SwapRepositoryImpl(source)
            try {
                repository.requestRefreshAssets()
                withTimeout(TIMEOUT_MS) { source.entered.receive() }

                repository.requestRefreshAssets()
                withTimeout(TIMEOUT_MS) { source.firstCancelled.await() }
                withTimeout(TIMEOUT_MS) { source.entered.receive() }

                // The first load's catch block runs right after its cancellation; give it the time to write.
                withTimeoutOrNull(SETTLE_MS) {
                    while (repository.assets.value.error == null) delay(5)
                }
                val state = repository.assets.value
                assertFalse(
                    state.error is CancellationException,
                    "the cancelled load was stored as the load error: ${state.error?.javaClass?.name}",
                )
                assertTrue(state.isLoading, "the second load is running but the state says it is not loading: $state")
            } finally {
                repository.clear()
            }
        }

    /** Every load waits until cancelled; the first one reports its cancellation. */
    private class ParkedTokens(
        other: SwapDataSource = unexpected(),
    ) : SwapDataSource by other {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val firstCancelled = CompletableDeferred<Unit>()
        private val calls = AtomicInteger(0)

        override suspend fun getSupportedTokens(): List<SwapAsset> {
            val call = calls.incrementAndGet()
            entered.trySend(call)
            try {
                awaitCancellation()
            } finally {
                if (call == 1) firstCancelled.complete(Unit)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_000L

        fun unexpected(): SwapDataSource =
            Proxy.newProxyInstance(SwapDataSource::class.java.classLoader, arrayOf(SwapDataSource::class.java)) { p, m, a ->
                when (m.name) {
                    "toString" -> "ParkedTokens"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.get(0)
                    else -> error("unexpected SwapDataSource call ${m.name}")
                }
            } as SwapDataSource
    }
}
