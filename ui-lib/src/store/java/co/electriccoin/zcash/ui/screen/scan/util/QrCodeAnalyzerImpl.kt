package co.electriccoin.zcash.ui.screen.scan.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.scan.QrCodeAnalyzer
import co.electriccoin.zcash.ui.screen.scankeystone.view.FramePosition
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class QrCodeAnalyzerImpl(
    private val framePosition: FramePosition,
    private val onQrCodeScanned: (String) -> Unit,
) : QrCodeAnalyzer {
    private val supportedImageFormat = Barcode.FORMAT_QR_CODE
    private var frameCount = 0
    private var hasScanned = false

    // Reuse scanner instance for better performance
    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(supportedImageFormat)
            .build()
        BarcodeScanning.getClient(options)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        frameCount++

        // Skip if already scanned
        if (hasScanned) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Log every 30th frame
            if (frameCount % 30 == 1) {
                Log.d("ZCHAT_QR", "MLKit Frame #$frameCount, size: ${mediaImage.width}x${mediaImage.height}")
            }

            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = bitmap.rotate(imageProxy.imageInfo.rotationDegrees)

            // Try full frame first for faster detection, then cropped if needed
            val image = InputImage.fromBitmap(rotatedBitmap, 0)

            scanner
                .process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { value ->
                            if (!hasScanned) {
                                hasScanned = true
                                Log.d("ZCHAT_QR", "MLKit QR FOUND at frame #$frameCount: ${value.take(50)}...")
                                Twig.debug { "Mlkit barcode value: $value" }
                                onQrCodeScanned(value)
                            }
                            return@addOnSuccessListener
                        }
                    }
                }.addOnFailureListener { e ->
                    if (frameCount % 60 == 1) {
                        Log.w("ZCHAT_QR", "MLKit scan failed: ${e.message}")
                    }
                }.addOnCompleteListener {
                    imageProxy.close()
                    // Recycle bitmaps to avoid memory issues
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (!rotatedBitmap.isRecycled && rotatedBitmap != bitmap) rotatedBitmap.recycle()
                }
        } else {
            imageProxy.close()
        }
    }
}

private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return this
    val matrix = Matrix().also {
        it.postRotate(rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
