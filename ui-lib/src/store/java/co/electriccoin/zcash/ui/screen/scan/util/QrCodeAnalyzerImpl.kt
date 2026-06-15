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

    // Timestamp of when the current in-flight task started. Used as a bounded recovery:
    // if a task never reports completion (e.g. swallowed callback), isProcessing self-clears
    // after PROCESSING_TIMEOUT_MS so the scanner doesn't appear frozen after a reset.
    @Volatile private var processingStartedAtMs = 0L

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
            // Bounded recovery: if an in-flight task never reported completion, allow a new
            // frame to proceed once PROCESSING_TIMEOUT_MS has elapsed so the scanner can't
            // stay frozen after resetScanLatch().
            val processingStale =
                isProcessing &&
                    (android.os.SystemClock.elapsedRealtime() - processingStartedAtMs) > PROCESSING_TIMEOUT_MS
            if (hasScanned || (isProcessing && !processingStale)) {
                imageProxy.close()
                return
            }
            isProcessing = true
            processingStartedAtMs = android.os.SystemClock.elapsedRealtime()
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
            // Don't hard-reset isProcessing here (an in-flight MLKit task still owns its
            // imageProxy and must close it). Instead, expire the processing latch so the
            // bounded-recovery check in analyze() lets new frames through quickly after an
            // invalid scan, rather than blocking for the full task duration.
            processingStartedAtMs = 0L
        }
        Log.d("ZCHAT_QR", "MLKit scan latch RESET - scanner will accept new QR codes")
    }

    private companion object {
        // Max time a single ML Kit task is allowed to hold the processing latch before a new
        // frame may proceed. ML Kit QR decodes complete well within this bound.
        private const val PROCESSING_TIMEOUT_MS = 1_000L
    }
}
