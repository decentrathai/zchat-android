package co.electriccoin.zcash.ui.screen.chat.filesharing

/**
 * Pure-logic helper for computing BitmapFactory.Options.inSampleSize. Extracted here so the
 * math is JVM-testable — ChatDetailView.decodeSampledBitmap delegates to this.
 *
 * Follows the canonical Android docs pattern: pick the largest power of two such that each
 * half-dimension remains >= reqPx. This keeps the decoded image as close to reqPx as possible
 * without going below.
 */
object BitmapSampling {

    fun calculateInSampleSize(width: Int, height: Int, reqPx: Int): Int? {
        if (width <= 0 || height <= 0) return null
        var sample = 1
        if (height > reqPx || width > reqPx) {
            val halfH = height / 2
            val halfW = width / 2
            while ((halfH / sample) >= reqPx && (halfW / sample) >= reqPx) {
                sample *= 2
            }
        }
        return sample
    }
}
