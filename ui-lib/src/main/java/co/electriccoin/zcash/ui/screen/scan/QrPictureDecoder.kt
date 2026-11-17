package co.electriccoin.zcash.ui.screen.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * QR-7: every QR code text in a picture from the gallery, in the order found, without duplicates; empty when there
 * is none. Pure (ZXing only), so it is tested on the JVM.
 *
 * - Alpha first: a pixel that is not fully opaque is blended onto white, so a code on a transparent background (read
 *   as black by the luminance source) reads as black on white.
 * - Every reader below runs and the texts are joined, so a picture with two codes gives both, whichever reader finds
 *   which: GenericMultipleBarcodeReader over MultiFormatReader (it honours ALSO_INVERTED, so white-on-dark pictures
 *   work) on HybridBinarizer and on GlobalHistogramBinarizer, then QRCodeMultiReader on the picture and on its
 *   inverse (it separates codes that sit side by side, and ignores ALSO_INVERTED, hence the inverse).
 * - When they find nothing, the gallery's single reader of 2.12.4 and 2.13.0 runs unchanged, so a picture that
 *   imported before still imports.
 * - The readers then always run on the picture's transpose as well, and its texts are joined after the upright ones:
 *   every QR drawn before QR-DRAW (2.12.4 shares, old screenshots, photos of an old seed-backup QR) is the transpose of
 *   its code, and the upright readers can miss such a code while they find another one in the same picture (T4c fixup
 *   QR7-1). Stopping at the first hit would then give one code from a picture of two people's codes. The first text
 *   is still the one the upright readers find (the restore screen takes the first).
 *
 * It never logs, and callers must not log the texts: a picture can hold a seed backup.
 */
object QrPictureDecoder {
    private val HINTS: Map<DecodeHintType, Any> =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )

    private const val OPAQUE = 0xFF
    private const val WHITE = 0xFF

    /** [pixels] is row-major ARGB (Bitmap.getPixels with stride [width]); it is blended in place. */
    fun decode(
        pixels: IntArray,
        width: Int,
        height: Int
    ): List<String> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        val opaque = blendOntoWhite(pixels, width * height)

        val texts = LinkedHashSet<String>()
        texts += everyReader(RGBLuminanceSource(width, height, opaque))
        if (texts.isEmpty()) singleReader(RGBLuminanceSource(width, height, opaque))?.let { texts += it }
        texts += everyReader(RGBLuminanceSource(height, width, transpose(opaque, width, height)))
        return texts.toList()
    }

    private fun everyReader(source: LuminanceSource): List<String> {
        val texts = LinkedHashSet<String>()
        texts += generic(HybridBinarizer(source))
        texts += generic(GlobalHistogramBinarizer(source))
        texts += qrMulti(source)
        texts += qrMulti(source.invert())
        return texts.toList()
    }

    private fun generic(binarizer: Binarizer): List<String> =
        try {
            val reader = MultiFormatReader().apply { setHints(HINTS) }
            GenericMultipleBarcodeReader(reader)
                .decodeMultiple(BinaryBitmap(binarizer), HINTS)
                .mapNotNull { it.text }
        } catch (_: ReaderException) {
            emptyList()
        }

    private fun qrMulti(source: LuminanceSource): List<String> =
        try {
            QRCodeMultiReader()
                .decodeMultiple(BinaryBitmap(HybridBinarizer(source)), HINTS)
                .mapNotNull { it.text }
        } catch (_: ReaderException) {
            emptyList()
        }

    /** ImageUriToQrCodeConverter's reader up to QR-7, unchanged: one MultiFormatReader on HybridBinarizer. */
    private fun singleReader(source: LuminanceSource): String? =
        try {
            MultiFormatReader()
                .apply { setHints(HINTS) }
                .decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                .text
        } catch (_: ReaderException) {
            null
        }

    @Suppress("MagicNumber")
    private fun blendOntoWhite(
        pixels: IntArray,
        count: Int
    ): IntArray {
        for (i in 0 until count) {
            val p = pixels[i]
            val alpha = p ushr 24
            if (alpha != OPAQUE) {
                fun over(channel: Int) = (channel * alpha + WHITE * (OPAQUE - alpha)) / OPAQUE
                val r = over((p shr 16) and 0xFF)
                val g = over((p shr 8) and 0xFF)
                val b = over(p and 0xFF)
                pixels[i] = (OPAQUE shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun transpose(
        pixels: IntArray,
        width: Int,
        height: Int
    ): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + y] = pixels[y * width + x]
            }
        }
        return out
    }
}
