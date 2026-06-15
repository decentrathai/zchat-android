package co.electriccoin.zcash.ui.design.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

const val QR_CODE_IMAGE_MARGIN_IN_PIXELS = 2

object JvmQrCodeGenerator : QrCodeGenerator {
    override fun generate(data: String, sizePixels: Int): BooleanArray {
        val bitMatrix =
            QRCodeWriter().encode(
                data,
                BarcodeFormat.QR_CODE,
                sizePixels,
                sizePixels,
                mapOf(
                    EncodeHintType.MARGIN to QR_CODE_IMAGE_MARGIN_IN_PIXELS,
                    // M (15%), NOT H. A unified address is ~213 chars; at H the QR jumps to version ~15
                    // (77 modules) with tiny modules that a phone CAMERA can't resolve off a phone SCREEN
                    // (and fine modules suffer screen-to-screen moiré). M keeps it at version ~10 (57
                    // modules, ~35% larger) — far more camera-scannable. The small center logo is kept
                    // decodable by giving it a white quiet-zone ring in ZashiQr (occlusion << M's 15%).
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                )
            )

        return BooleanArray(sizePixels * sizePixels).apply {
            var booleanArrayPosition = 0
            for (bitMatrixX in 0 until sizePixels) {
                for (bitMatrixY in 0 until sizePixels) {
                    this[booleanArrayPosition] = bitMatrix.get(bitMatrixX, bitMatrixY)
                    booleanArrayPosition++
                }
            }
        }
    }
}
