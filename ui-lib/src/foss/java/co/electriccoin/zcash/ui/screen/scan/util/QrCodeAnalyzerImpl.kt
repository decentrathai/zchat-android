package co.electriccoin.zcash.ui.screen.scan.util

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.scan.QrCodeAnalyzer
import co.electriccoin.zcash.ui.screen.scankeystone.view.FramePosition
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import java.nio.ByteBuffer

class QrCodeAnalyzerImpl(
    private val framePosition: FramePosition,
    private val onQrCodeScanned: (String) -> Unit,
) : QrCodeAnalyzer {
    private val supportedImageFormats =
        listOf(
            ImageFormat.YUV_420_888,
            ImageFormat.YUV_422_888,
            ImageFormat.YUV_444_888
        )

    private var frameCount = 0
    private var hasScanned = false
    private val instanceId = System.identityHashCode(this)

    // Reuse reader instance for better performance
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        ))
    }

    init {
        Twig.debug { "QrCodeAnalyzerImpl[$instanceId] created with framePosition: $framePosition" }
    }

    override fun analyze(image: ImageProxy) {
        frameCount++

        // Skip if already scanned
        if (hasScanned) {
            image.close()
            return
        }

        val plane = image.planes.firstOrNull()
        val rowStride = plane?.rowStride ?: image.width

        // Log every 30th frame
        if (frameCount % 30 == 1) {
            Log.d("ZCHAT_QR", "ZXing Frame #$frameCount, size: ${image.width}x${image.height}")
        }

        image.use {
            if (image.format !in supportedImageFormats) {
                if (frameCount == 1) {
                    Log.w("ZCHAT_QR", "Unsupported format: ${image.format}")
                }
                return@use
            }

            val buffer = plane?.buffer ?: return@use

            val width = image.width
            val height = image.height
            val yData: ByteArray

            if (rowStride == width) {
                yData = buffer.toByteArray()
            } else {
                yData = ByteArray(width * height)
                buffer.rewind()
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(yData, row * width, width)
                }
            }

            // Try multiple decode strategies
            val result = tryDecode(yData, width, height)
            if (result != null && !hasScanned) {
                hasScanned = true
                Log.d("ZCHAT_QR", "ZXing QR FOUND at frame #$frameCount: ${result.take(50)}...")
                onQrCodeScanned(result)
            } else if (frameCount % 60 == 1) {
                Log.d("ZCHAT_QR", "No QR in frame #$frameCount")
            }
        }
    }

    private fun tryDecode(yData: ByteArray, width: Int, height: Int): String? {
        // Strategy 1: Full frame with HybridBinarizer + TRY_HARDER
        tryDecodeWithParams(yData, width, height, 0, 0, width, height, true, false)?.let { return it }

        // Strategy 2: Full frame with GlobalHistogramBinarizer (better for screens)
        tryDecodeWithParams(yData, width, height, 0, 0, width, height, false, false)?.let { return it }

        // Strategy 3: Center crop (60% of frame) - QR might be in center
        val cropMarginX = width / 5
        val cropMarginY = height / 5
        val cropW = width - 2 * cropMarginX
        val cropH = height - 2 * cropMarginY
        tryDecodeWithParams(yData, width, height, cropMarginX, cropMarginY, cropW, cropH, true, false)?.let { return it }

        // Strategy 4: Try inverted (white on black)
        tryDecodeWithParams(yData, width, height, 0, 0, width, height, true, true)?.let { return it }

        return null
    }

    private fun tryDecodeWithParams(
        yData: ByteArray,
        dataWidth: Int,
        dataHeight: Int,
        left: Int,
        top: Int,
        cropWidth: Int,
        cropHeight: Int,
        useHybrid: Boolean,
        inverted: Boolean
    ): String? {
        return runCatching {
            val source = PlanarYUVLuminanceSource(
                yData, dataWidth, dataHeight, left, top, cropWidth, cropHeight, false
            )
            val finalSource = if (inverted) source.invert() else source
            val binarizer = if (useHybrid) HybridBinarizer(finalSource) else GlobalHistogramBinarizer(finalSource)
            val bitmap = BinaryBitmap(binarizer)
            reader.reset()
            reader.decodeWithState(bitmap).text
        }.getOrNull()
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(remaining()).also {
            get(it)
        }
    }
}
