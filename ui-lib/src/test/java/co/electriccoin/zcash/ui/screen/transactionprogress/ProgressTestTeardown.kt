package co.electriccoin.zcash.ui.screen.transactionprogress

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeoutOrNull

private const val END_TIMEOUT_MS = 10_000L

/**
 * ZC-521: how a test that built the real progress view model ends. It cancels [viewModels]' scopes and the test's own
 * [collectors], waits until every coroutine in them has FINISHED, and only then unsets Dispatchers.Main.
 *
 * Since ZC-417 the screen's state has a child on Dispatchers.Default (`submitStateAndWaitOver`, `flowOn`), so a
 * cancelled view model is not finished when `viewModelScope.cancel()` returns: that child ends on the Default pool and
 * then resumes its parents through Dispatchers.Main. A test that unset Main right after the cancel raced it. The pool
 * thread that lost threw "Dispatchers.Main was accessed when the platform dispatcher was absent and the test
 * dispatcher was unset", and kotlinx-coroutines-test reported that in the next `runTest` of the JVM
 * (UncaughtExceptionsBeforeTest in DebugFaultSeamsBehaviourTest, SeedRevealNeedsDeviceAuthTest and
 * WalletTabSweepCardTest).
 *
 * A view model that has not finished within 10 s fails the test that built it, and Main is unset all the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun endViewModelsThenResetMain(
    viewModels: Collection<TransactionProgressVM>,
    vararg collectors: CoroutineScope,
) {
    val scopes = viewModels.map { it.viewModelScope } + collectors
    try {
        scopes.forEach { it.cancel() }
        val finished = runBlocking { withTimeoutOrNull(END_TIMEOUT_MS) { scopes.map { it.coroutineContext.job }.joinAll() } }
        check(finished != null) {
            "the progress view models' coroutines were still running ${END_TIMEOUT_MS / 1_000}s after they were cancelled"
        }
    } finally {
        Dispatchers.resetMain()
    }
}
