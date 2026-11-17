package co.electriccoin.zcash.ui.design.util

interface QrCodeGenerator {
    /**
     * @param data Data to encode into the QR code.
     * @param sizePixels Size in pixels of the QR code.
     * @return A QR code pixel matrix, represented as an array of booleans where false is white and true is black,
     * row-major: index = y * sizePixels + x, the order `Bitmap.createBitmap` reads.
     */
    fun generate(
        data: String,
        sizePixels: Int
    ): BooleanArray
}
