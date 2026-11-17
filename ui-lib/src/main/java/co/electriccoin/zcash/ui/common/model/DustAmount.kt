package co.electriccoin.zcash.ui.common.model

/**
 * QA2 Q2-18 / owner item B-1 (ZC-1419, ZC-1427; W-137): a note worth the ZIP-317 marginal fee or less costs at least
 * its own value to spend, so the wallet never counts it in the balance. The one place that says which amounts those
 * are; Send and Review warn about such an amount (never refuse it) and a received detail says it is too small to
 * spend. Texts take the limit as an argument formatted from [Zip317.MARGINAL_FEE_ZATOSHI].
 */
object DustAmount {
    /** True for 1 zatoshi up to and including [Zip317.MARGINAL_FEE_ZATOSHI]; false for nothing, a negative or more. */
    fun isUnspendable(zat: Long): Boolean = zat in 1..Zip317.MARGINAL_FEE_ZATOSHI
}
