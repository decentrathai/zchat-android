package co.electriccoin.zcash.ui.common.tex

/**
 * F3: what one pass of the checker knows about one TEX send before any lookup.
 *
 * @param scannedTip the wallet's fully scanned height as [readWithScannedTip] read it around the pass (null:
 *   unknown). The network's tip is deliberately NOT a proof of anything: a transaction mined in a block the wallet
 *   has not scanned yet is still unseen (correction 2, the "network tip > expiry but scanned < expiry" case).
 * @param networkTip carried only so a test can show it is ignored.
 * @param leg1NotSentProven [TexNotSentProof] says leg 1 can no longer be mined (two passes, [TexRules.REORG_MARGIN]).
 */
data class TexStateInput(
    val leg1Mined: Boolean,
    val leg2Mined: Boolean,
    val expiryHeight: Long,
    val scannedTip: Long?,
    val networkTip: Long? = null,
    val leg1NotSentProven: Boolean = false,
)

/**
 * The state a TEX send is in as far as the chain can say: [TexState.DONE], [TexState.SENDING_STEP1],
 * [TexState.NOT_SENT], [TexState.SENDING_STEP2] or [TexState.CHECKING] (the lookup is due; the recovery decides
 * what follows). Pure: no clock, no network.
 */
fun deriveTexSendState(input: TexStateInput): TexState {
    if (input.leg2Mined) return TexState.DONE
    val tip = input.scannedTip
    if (!input.leg1Mined) {
        return if (input.leg1NotSentProven && tip != null && tip >= input.expiryHeight + TexRules.reorgMargin(input.expiryHeight)) {
            TexState.NOT_SENT
        } else {
            TexState.SENDING_STEP1
        }
    }
    if (tip == null || tip < input.expiryHeight + TexRules.CHECK_MARGIN) return TexState.SENDING_STEP2
    return TexState.CHECKING
}

/**
 * Correction 2 (PLAN.md §2): "not sent" is proven only when the scanned tip is at least [margin] (of the expiry height;
 * ZC-1554: kept in time across NU7) blocks past the expiry in two passes, the second no lower than the first. An unknown tip, a tip below the bound, or a tip lower
 * than the pass before (a rewind) starts the count again. In memory: after a restart the proof is made again.
 */
class TexNotSentProof(
    /** A fixed margin in blocks for a test; null (always in the app): [TexRules.reorgMargin] of the expiry height. */
    private val fixedMargin: Long? = null,
) {
    private val lastQualifying = mutableMapOf<String, Long>()

    /** Records this pass for [key]; true when this pass and the one before both qualify, non-decreasing. */
    @Synchronized
    fun observe(
        key: String,
        scannedTip: Long?,
        expiryHeight: Long,
    ): Boolean {
        if (scannedTip == null || scannedTip < expiryHeight + (fixedMargin ?: TexRules.reorgMargin(expiryHeight))) {
            lastQualifying.remove(key)
            return false
        }
        val before = lastQualifying[key]
        if (before != null && scannedTip < before) {
            // A rewind between the passes: this pass is the first again.
            lastQualifying[key] = scannedTip
            return false
        }
        lastQualifying[key] = scannedTip
        return before != null
    }

    @Synchronized
    fun forget(key: String) {
        lastQualifying.remove(key)
    }
}

/** A value read in one pass, with the scanned tip that was read around it. */
data class TexScannedRead<T>(
    val tip: Long?,
    val value: T,
)

/**
 * Correction 2's `ScannedTipReader`: reads the fully scanned height, then [read], then the height again, and keeps
 * the lower of the two. Null (unknown) when either read gives none or throws: an incomplete pass proves nothing.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> readWithScannedTip(
    readTip: suspend () -> Long?,
    read: suspend () -> T,
): TexScannedRead<T> {
    val before = readTipOrNull(readTip)
    val value = read()
    val after = readTipOrNull(readTip)
    val tip = if (before == null || after == null) null else minOf(before, after)
    return TexScannedRead(tip = tip, value = value)
}

@Suppress("TooGenericExceptionCaught")
private suspend fun readTipOrNull(readTip: suspend () -> Long?): Long? =
    try {
        readTip()?.takeIf { it > 0 }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
