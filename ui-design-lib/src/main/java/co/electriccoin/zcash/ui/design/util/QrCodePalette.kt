package co.electriccoin.zcash.ui.design.util

import androidx.compose.ui.graphics.Color

/**
 * The colours every QR is drawn with, in every ZCHAT theme: black modules on a white background (ISO/IEC 18004
 * dark-on-light). Fixed on purpose: a themed or phone-dark-mode colour drew the QR white on white or inverted.
 */
object QrCodePalette {
    const val DARK_ARGB: Int = -0x1000000 // 0xFF000000
    const val LIGHT_ARGB: Int = -0x1 // 0xFFFFFFFF

    val Dark = Color(DARK_ARGB)
    val Light = Color(LIGHT_ARGB)
}
