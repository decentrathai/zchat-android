package co.electriccoin.zcash.ui.screen.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import co.electriccoin.zcash.spackle.Twig
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageUriToQrCodeConverter {
    companion object {
        // Max dimension to prevent OOM on high-resolution photos (8MP+)
        private const val MAX_BITMAP_DIMENSION = 1920
    }

    suspend operator fun invoke(
        context: Context,
        uri: Uri
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                uri
                    .toBitmap(context)
                    .toBinaryBitmap()
                    .toQRCode()
            }.onFailure {
                Twig.error(it) { "Failed to convert Uri to QR code" }
            }.getOrNull()
        }

    private fun Uri.toBitmap(context: Context): Bitmap {
        // First pass: decode bounds only to determine dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Note: decodeStream returns null when inJustDecodeBounds=true (by design),
        // so we must separate the null-check for openInputStream from the use{} result.
        (context.contentResolver.openInputStream(this)
            ?: throw IllegalStateException("Could not open input stream for URI: $this")
        ).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // Calculate sample size to downsample large images (prevents OOM)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false

        Twig.debug {
            "Gallery QR: original=${options.outWidth}x${options.outHeight}, sampleSize=${options.inSampleSize}"
        }

        // Second pass: decode with downsampling
        val inputStream = context.contentResolver.openInputStream(this)
            ?: throw IllegalStateException("Could not open input stream for URI: $this")
        return inputStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
                ?: throw IllegalStateException("Could not decode bitmap from stream")
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        while ((width / inSampleSize) > MAX_BITMAP_DIMENSION ||
            (height / inSampleSize) > MAX_BITMAP_DIMENSION
        ) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun Bitmap.toBinaryBitmap(): BinaryBitmap {
        val width = this.width
        val height = this.height
        val pixels = IntArray(width * height)
        this.getPixels(pixels, 0, width, 0, 0, width, height)
        this.recycle()
        val source = RGBLuminanceSource(width, height, pixels)
        return BinaryBitmap(HybridBinarizer(source))
    }

    private fun BinaryBitmap.toQRCode(): String =
        MultiFormatReader()
            .apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.ALSO_INVERTED to true,
                        DecodeHintType.TRY_HARDER to true,
                        DecodeHintType.CHARACTER_SET to "UTF-8"
                    )
                )
            }.decodeWithState(this@toQRCode)
            .text
}
