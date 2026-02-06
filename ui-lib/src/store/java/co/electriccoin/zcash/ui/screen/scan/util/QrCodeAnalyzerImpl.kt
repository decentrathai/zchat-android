package co.electriccoin.zcash.ui.screen.scan.util

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

/**
 * ML Kit-based QR code analyzer for Store builds.
 *
 * Optimized for performance:
 * - Uses InputImage.fromMediaImage() directly (no bitmap conversion)
 * - ML Kit handles YUV format and rotation internally
 * - Reuses scanner instance across frames
 * - Thread-safe state management with synchronization
 */
class QrCodeAnalyzerImpl(
    private val framePosition: FramePosition,
    private val onQrCodeScanned: (String) -> Unit,
) : QrCodeAnalyzer {
    private val supportedImageFormat = Barcode.FORMAT_QR_CODE
    private var frameCount = 0

    // Thread-safe state management - MLKit callbacks run on different threads
    private val stateLock = Any()
    @Volatile private var hasScanned = false
    @Volatile private var isProcessing = false

    // Reuse scanner instance for better performance
    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(supportedImageFormat)
            .build()
        BarcodeScanning.getClient(options)
    }

    @Suppress("TooGenericExceptionCaught")
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        frameCount++

        // Thread-safe check and set of processing state
        synchronized(stateLock) {
            if (hasScanned || isProcessing) {
                imageProxy.close()
                return
            }
            isProcessing = true
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Log every 30th frame
            if (frameCount % 30 == 1) {
                Log.d("ZCHAT_QR", "MLKit Frame #$frameCount, size: ${mediaImage.width}x${mediaImage.height}")
            }

            try {
                // OPTIMIZED: Use fromMediaImage directly - no bitmap conversion needed!
                // ML Kit handles YUV_420_888 format and rotation internally
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                scanner
                    .process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            barcode.rawValue?.let { value ->
                                synchronized(stateLock) {
                                    if (!hasScanned) {
                                        hasScanned = true
                                        Log.d("ZCHAT_QR", "MLKit QR FOUND at frame #$frameCount: ${value.take(50)}...")
                                        Twig.debug { "Mlkit barcode value: $value" }
                                        onQrCodeScanned(value)
                                    }
                                }
                                return@addOnSuccessListener
                            }
                        }
                    }.addOnFailureListener { e ->
                        if (frameCount % 60 == 1) {
                            Log.w("ZCHAT_QR", "MLKit scan failed: ${e.message}")
                        }
                    }.addOnCompleteListener {
                        synchronized(stateLock) {
                            isProcessing = false
                        }
                        imageProxy.close()
                    }
            } catch (e: Exception) {
                // InputImage.fromMediaImage() or scanner.process() threw before listeners attached
                // Must close imageProxy here to prevent resource leak and camera stall
                Log.w("ZCHAT_QR", "MLKit pre-process error: ${e.message}")
                synchronized(stateLock) {
                    isProcessing = false
                }
                imageProxy.close()
            }
        } else {
            synchronized(stateLock) {
                isProcessing = false
            }
            imageProxy.close()
        }
    }

    override fun resetScanLatch() {
        synchronized(stateLock) {
            hasScanned = false
            // Don't reset isProcessing - let any in-flight MLKit task finish naturally
        }
        Log.d("ZCHAT_QR", "MLKit scan latch RESET - scanner will accept new QR codes")
    }
}
