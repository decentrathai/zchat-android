package co.electriccoin.zcash.ui.screen.scan

import androidx.camera.core.ImageAnalysis

interface QrCodeAnalyzer : ImageAnalysis.Analyzer {
    /** Reset the scan latch so the analyzer can detect another QR code. */
    fun resetScanLatch()
}
