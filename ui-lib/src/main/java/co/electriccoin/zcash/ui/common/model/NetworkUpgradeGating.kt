package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.exception.CompactBlockProcessorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Support details only: branch IDs are unavailable for the node's submit verdict. */
data class NetworkUpgradeNotice(
    val clientBranchId: String?,
    val serverBranchId: String?,
    val source: NetworkUpgradeSource,
)

enum class NetworkUpgradeSource {
    SETUP,
    SUBMIT,
}

/** A refusal before a proposal is built or stored, never a transient wait for the next block. */
class NetworkUpgradeRequiredException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE =
            "Nothing was sent or paid: this version of ZChat can't send on the upgraded Zcash network. " +
                "Update ZChat to send. Your balance is safe."
    }
}

/**
 * Process-local latch for the SDK's branch mismatch or the node's exact consensus verdict. A fresh
 * synchronizer clears it before registering callbacks; ordinary sync errors and resolution do not.
 */
object NetworkUpgradeGuard {
    private const val MAX_CAUSE_DEPTH = 5
    private const val NODE_BRANCH_REFUSAL = "incorrect consensus branch id"
    private val branchId = Regex("[0-9a-fA-F]{1,8}")
    private val notice = MutableStateFlow<NetworkUpgradeNotice?>(null)
    val state: StateFlow<NetworkUpgradeNotice?> = notice.asStateFlow()

    fun onSetupError(error: Throwable?) {
        if (error is CancellationException) return
        val mismatch =
            generateSequence(error) { it.cause }
                .take(MAX_CAUSE_DEPTH + 1)
                .filterIsInstance<CompactBlockProcessorException.MismatchedConsensusBranch>()
                .firstOrNull {
                    branchId.matches(it.serverBranchId) && !it.serverBranchId.equals(it.clientBranchId, ignoreCase = true)
                } ?: return
        notice.compareAndSet(
            null,
            NetworkUpgradeNotice(mismatch.clientBranchId, mismatch.serverBranchId, NetworkUpgradeSource.SETUP),
        )
    }

    fun onSubmitResult(result: SubmitResult) {
        val incompatible =
            when (result) {
                is SubmitResult.Failure -> result.description?.contains(NODE_BRANCH_REFUSAL, ignoreCase = true) == true
                is SubmitResult.Partial -> result.statuses.any { it.contains(NODE_BRANCH_REFUSAL, ignoreCase = true) }
                else -> false
            }
        if (incompatible) {
            notice.compareAndSet(null, NetworkUpgradeNotice(null, null, NetworkUpgradeSource.SUBMIT))
        }
    }

    fun onSynchronizerStarted() {
        notice.value = null
    }

    fun refusal(): NetworkUpgradeRequiredException? = state.value?.let { NetworkUpgradeRequiredException() }

    internal fun resetForTest() = onSynchronizerStarted()
}
