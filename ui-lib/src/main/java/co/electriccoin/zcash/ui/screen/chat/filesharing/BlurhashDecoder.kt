package co.electriccoin.zcash.ui.screen.chat.filesharing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.withSign

/**
 * Pure Kotlin Blurhash decoder. Decodes a Blurhash string (see https://blurha.sh/)
 * to an IntArray of ARGB pixels suitable for creating a Bitmap.
 *
 * Used as a low-resolution placeholder while the real image downloads in the chat.
 */
object BlurhashDecoder {

    // Canonical Blurhash alphabet (per https://github.com/woltapp/blurhash spec)
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /**
     * Decode a Blurhash string into an [IntArray] of ARGB pixels of size [width] * [height].
     * Returns null if the input is invalid or too short.
     */
    fun decode(blurhash: String, width: Int, height: Int, punch: Int = 1): IntArray? {
        if (blurhash.length < 6) return null

        val sizeFlag = decode83(blurhash, 0, 1) ?: return null
        val numY = (sizeFlag / 9) + 1
        val numX = (sizeFlag % 9) + 1

        if (blurhash.length != 4 + 2 * numX * numY) return null

        val quantisedMaximumValue = decode83(blurhash, 1, 2) ?: return null
        val maximumValue = (quantisedMaximumValue + 1) / 166f

        val colors = Array(numX * numY) { i ->
            if (i == 0) {
                val value = decode83(blurhash, 2, 6) ?: return null
                decodeDC(value)
            } else {
                val value = decode83(blurhash, 4 + i * 2, 6 + i * 2) ?: return null
                decodeAC(value, maximumValue * punch)
            }
        }

        return composeBitmap(width, height, numX, numY, colors)
    }

    private fun decode83(str: String, from: Int, to: Int): Int? {
        if (to > str.length) return null
        var result = 0
        for (i in from until to) {
            val index = ALPHABET.indexOf(str[i])
            if (index == -1) return null
            result = result * 83 + index
        }
        return result
    }

    private fun decodeDC(value: Int): FloatArray {
        val r = value shr 16
        val g = (value shr 8) and 255
        val b = value and 255
        return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
    }

    private fun decodeAC(value: Int, maximumValue: Float): FloatArray {
        val quantR = value / (19 * 19)
        val quantG = (value / 19) % 19
        val quantB = value % 19
        return floatArrayOf(
            signPow((quantR - 9) / 9f, 2f) * maximumValue,
            signPow((quantG - 9) / 9f, 2f) * maximumValue,
            signPow((quantB - 9) / 9f, 2f) * maximumValue,
        )
    }

    private fun signPow(value: Float, exp: Float): Float {
        val abs = if (value < 0) -value else value
        return abs.pow(exp).withSign(value.sign)
    }

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearTosRGB(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) (v * 12.92f * 255 + 0.5f).toInt()
        else ((1.055f * v.pow(1f / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }

    private fun composeBitmap(
        width: Int,
        height: Int,
        numX: Int,
        numY: Int,
        colors: Array<FloatArray>,
    ): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        val basis = (cos(PI * x * i / width) * cos(PI * y * j / height)).toFloat()
                        val color = colors[i + j * numX]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                val ri = linearTosRGB(r)
                val gi = linearTosRGB(g)
                val bi = linearTosRGB(b)
                pixels[x + y * width] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return pixels
    }
}
