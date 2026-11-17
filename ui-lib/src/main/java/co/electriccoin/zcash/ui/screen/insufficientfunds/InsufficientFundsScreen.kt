package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsufficientFundsScreen(args: InsufficientFundsArgs) {
    val vm = koinViewModel<InsufficientFundsVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    InsufficientFundsView(state)
}

/**
 * [reason] and the zatoshi figures describe WHY the send fell short (see [ShortfallReason]); null means
 * the caller did not classify it and the sheet uses its neutral copy. Flat, nullable primitives so the
 * route stays a plain navigation argument.
 */
@Serializable
data class InsufficientFundsArgs(
    val context: InsufficientFundsContext = InsufficientFundsContext.PAYMENT,
    val reason: InsufficientFundsReason? = null,
    val haveZatoshi: Long? = null,
    val needZatoshi: Long? = null,
    val pendingZatoshi: Long? = null,
    val orchardZatoshi: Long? = null,
) {
    companion object {
        fun of(
            context: InsufficientFundsContext,
            shortfall: ShortfallReason,
        ): InsufficientFundsArgs =
            when (shortfall) {
                is ShortfallReason.StillConfirming ->
                    InsufficientFundsArgs(
                        context = context,
                        reason = InsufficientFundsReason.STILL_CONFIRMING,
                        needZatoshi = shortfall.need.value,
                        pendingZatoshi = shortfall.pending.value,
                    )
                is ShortfallReason.OrchardStranded ->
                    InsufficientFundsArgs(
                        context = context,
                        reason = InsufficientFundsReason.ORCHARD_STRANDED,
                        haveZatoshi = shortfall.have.value,
                        needZatoshi = shortfall.need.value,
                        orchardZatoshi = shortfall.orchard.value,
                    )
                is ShortfallReason.NotEnough ->
                    InsufficientFundsArgs(
                        context = context,
                        reason = InsufficientFundsReason.NOT_ENOUGH,
                        haveZatoshi = shortfall.have.value,
                        needZatoshi = shortfall.need?.value,
                        pendingZatoshi = shortfall.pending.value,
                    )
            }
    }

    /** The [ShortfallReason] these arguments carry, or null when the caller did not classify it. */
    fun shortfall(): ShortfallReason? {
        val have = haveZatoshi?.let(::Zatoshi)
        val need = needZatoshi?.let(::Zatoshi)
        val pending = Zatoshi(pendingZatoshi ?: 0)
        val orchard = orchardZatoshi?.let(::Zatoshi)
        return when (reason) {
            InsufficientFundsReason.STILL_CONFIRMING ->
                need?.let { ShortfallReason.StillConfirming(pending = pending, need = it) }
            InsufficientFundsReason.ORCHARD_STRANDED ->
                if (have != null && need != null && orchard != null) {
                    ShortfallReason.OrchardStranded(orchard = orchard, have = have, need = need)
                } else {
                    null
                }
            InsufficientFundsReason.NOT_ENOUGH ->
                have?.let { ShortfallReason.NotEnough(have = it, need = need, pending = pending) }
            null -> null
        }
    }
}

@Serializable
enum class InsufficientFundsReason {
    STILL_CONFIRMING,
    ORCHARD_STRANDED,
    NOT_ENOUGH
}

@Serializable
enum class InsufficientFundsContext {
    PAYMENT,
    MESSAGE,
    SWAP
}
