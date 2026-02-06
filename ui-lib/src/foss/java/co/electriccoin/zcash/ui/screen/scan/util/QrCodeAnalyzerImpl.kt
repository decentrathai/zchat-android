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
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * ZXing-based QR code analyzer for FOSS builds.
 *
 * Uses 4 decode strategies in order of likelihood:
 * 1. Center crop with fast reader (most QR codes are centered in viewfinder)
 * 2. Full frame with HybridBinarizer + TRY_HARDER (handles rotation/skew)
 * 3. Full frame with GlobalHistogramBinarizer (better for screen-displayed QR codes)
 * 4. 90° rotated frame (handles camera sensor orientation mismatch)
 *
 * Reuses reader instances and byte buffers to minimize GC pressure.
 * Processes every frame for fastest possible detection.
 */
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
    @Volatile private var hasScanned = false

    // Reuse byte arrays to avoid allocation on every frame
    private var yDataBuffer: ByteArray? = null
    private var rotatedBuffer: ByteArray? = null

    // TRY_HARDER reader: thorough detection with rotation/inversion support
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        ))
    }

    // Fast reader: quick first-pass without TRY_HARDER overhead
    private val fastReader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        ))
    }

    override fun analyze(image: ImageProxy) {
        frameCount++

        if (hasScanned) {
            image.close()
            return
        }

        val plane = image.planes.firstOrNull()
        val rowStride = plane?.rowStride ?: image.width

        if (frameCount % 60 == 1) {
            Log.d(TAG, "Frame #$frameCount ${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees}")
        }

        image.use {
            if (image.format !in supportedImageFormats) {
                return@use
            }

            val buffer = plane?.buffer ?: return@use
            val width = image.width
            val height = image.height

            // Reuse byte array buffer
            val requiredSize = width * height
            if (yDataBuffer == null || yDataBuffer!!.size < requiredSize) {
                yDataBuffer = ByteArray(requiredSize)
            }
            val yData = yDataBuffer!!

            // Extract Y-plane, handling row stride padding
            if (rowStride == width) {
                buffer.rewind()
                buffer.get(yData, 0, requiredSize)
            } else {
                buffer.rewind()
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(yData, row * width, width)
                }
            }

            val result = tryDecode(yData, width, height)
            if (result != null && !hasScanned) {
                hasScanned = true
                Log.d(TAG, "QR FOUND frame #$frameCount: ${result.take(60)}...")
                onQrCodeScanned(result)
            }
        }
    }

    private fun tryDecode(yData: ByteArray, width: Int, height: Int): String? {
        // Strategy 1: 70% center crop with fast reader
        val marginX = (width * 0.15).toInt()
        val marginY = (height * 0.15).toInt()
        decodeRegion(yData, width, height, marginX, marginY,
            width - 2 * marginX, height - 2 * marginY, fastReader)
            ?.let { return it }

        // Strategy 2: Full frame with HybridBinarizer + TRY_HARDER
        decodeHybrid(yData, width, height, reader)?.let { return it }

        // Strategy 3: Full frame with GlobalHistogramBinarizer (screen-displayed QR codes)
        decodeGlobalHistogram(yData, width, height)?.let { return it }

        // Strategy 4: 90° CW rotation (camera sensor orientation mismatch)
        decodeRotated90(yData, width, height)?.let { return it }

        return null
    }

    private fun decodeRegion(
        yData: ByteArray, dataWidth: Int, dataHeight: Int,
        left: Int, top: Int, cropWidth: Int, cropHeight: Int,
        decoder: MultiFormatReader
    ): String? = runCatching {
        val source = PlanarYUVLuminanceSource(
            yData, dataWidth, dataHeight, left, top, cropWidth, cropHeight, false
        )
        decoder.reset()
        decoder.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    private fun decodeHybrid(
        yData: ByteArray, width: Int, height: Int,
        decoder: MultiFormatReader
    ): String? = runCatching {
        val source = PlanarYUVLuminanceSource(
            yData, width, height, 0, 0, width, height, false
        )
        decoder.reset()
        decoder.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    private fun decodeGlobalHistogram(
        yData: ByteArray, width: Int, height: Int
    ): String? = runCatching {
        val source = PlanarYUVLuminanceSource(
            yData, width, height, 0, 0, width, height, false
        )
        reader.reset()
        reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
    }.getOrNull()

    private fun decodeRotated90(
        yData: ByteArray, width: Int, height: Int
    ): String? = runCatching {
        val size = width * height
        if (rotatedBuffer == null || rotatedBuffer!!.size < size) {
            rotatedBuffer = ByteArray(size)
        }
        val rotated = rotatedBuffer!!

        // Rotate 90° CW: pixel at (x,y) moves to (height-1-y, x) in new coords
        for (y in 0 until height) {
            for (x in 0 until width) {
                rotated[x * height + (height - 1 - y)] = yData[y * width + x]
            }
        }

        val source = PlanarYUVLuminanceSource(
            rotated, height, width, 0, 0, height, width, false
        )
        reader.reset()
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    companion object {
        private const val TAG = "ZCHAT_QR"
    }
}
