package co.electriccoin.zcash.ui.screen.scan

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.lang.reflect.InvocationTargetException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QR-7 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §1.3-§1.4 with §9 rows 1-2; QR-DRAW spec §6 handoff): the
 * gallery reads every QR code in a picture, the way a user saves one.
 *
 * At the parent the gallery ran one ZXing reader and returned one text or nothing: a picture of the Invite screen gave
 * whichever of its two codes ZXing found first, a code on a transparent background read as all black, and a code drawn
 * by 2.12.4 (transposed; QR-DRAW) was not found at all. `QrPictureDecoder.decode(pixels, width, height)` is the pure
 * core: alpha blended onto white, several readers, the picture's transpose when nothing is found, and today's single
 * reader kept as a fallback so nothing that imports today stops importing.
 *
 * The decoder is reached by reflection so this test compiles before it exists. Pictures are drawn here with ZXing and
 * the synthetic QR-DRAW fixtures; no text is printed on failure, only names and sizes.
 */
class QrPictureDecoderTest {
    private val address = QrDrawFixtures.F_ADDR
    private val code = QrDrawFixtures.F_CODE

    private fun decode(
        pixels: IntArray,
        width: Int,
        height: Int
    ): List<String> {
        val type =
            try {
                Class.forName("co.electriccoin.zcash.ui.screen.scan.QrPictureDecoder")
            } catch (_: ClassNotFoundException) {
                fail("QrPictureDecoder missing: the gallery reads at most one code per picture with one reader")
            }
        val instance = type.getField("INSTANCE").get(null)
        val method = type.getMethod("decode", IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        @Suppress("UNCHECKED_CAST")
        return try {
            method.invoke(instance, pixels.copyOf(), width, height) as List<String>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun label(text: String?): String =
        when (text) {
            null -> "nothing"
            address -> "the address"
            code -> "the code"
            else -> "another text (${text.length} chars)"
        }

    private fun labels(texts: List<String>): String = texts.joinToString(prefix = "[", postfix = "]") { label(it) }

    // ------------------------------------------------------------------ pictures

    /** The app's drawing today (row-major, margin 4), [dark] modules on [light]. */
    private fun standard(
        text: String,
        size: Int,
        dark: Int = QrDrawFixtures.DARK,
        light: Int = QrDrawFixtures.LIGHT
    ): IntArray = QrDrawFixtures.drawn(text, size).let { bits -> IntArray(bits.size) { if (bits[it]) dark else light } }

    /** 2.12.4's drawing: margin 2 and the column-first loop, so the picture is the code's transpose (QR-DRAW (a)). */
    private fun legacy(
        text: String,
        size: Int,
        dark: Int = QrDrawFixtures.DARK,
        light: Int = QrDrawFixtures.LIGHT
    ): IntArray {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            )
        val pixels = IntArray(size * size)
        var pos = 0
        for (x in 0 until size) {
            for (y in 0 until size) {
                pixels[pos++] = if (matrix.get(x, y)) dark else light
            }
        }
        return pixels
    }

    private class Picture(
        val name: String,
        val pixels: IntArray,
        val width: Int,
        val height: Int
    )

    /** An Invite Friend screenshot: the address QR above, the code QR below, each 200 dp at 2.625 (525 px). */
    private fun invite(
        name: String,
        dark: Boolean,
        draw: (String, Int) -> IntArray
    ): Picture {
        val width = 1080
        val height = 2200
        val qr = 525
        val pixels = IntArray(width * height) { if (dark) BACKGROUND_DARK else BACKGROUND_LIGHT }
        val x0 = (width - qr) / 2
        for ((top, text) in listOf(200 to address, 1000 to code)) {
            val drawn = draw(text, qr)
            for (y in 0 until qr) {
                System.arraycopy(drawn, y * qr, pixels, (top + y) * width + x0, qr)
            }
        }
        return Picture(name, pixels, width, height)
    }

    private fun single(
        name: String,
        pixels: IntArray,
        size: Int
    ) = Picture(name, pixels, size, size)

    // ------------------------------------------------------------------ one code

    @Test
    fun one_code_black_on_white_gives_the_code() {
        for (size in QrDrawFixtures.SIZES) {
            assertEquals(listOf(code), decode(standard(code, size), size, size), "code, black on white, $size px")
        }
    }

    @Test
    fun one_code_white_on_dark_gives_the_code() {
        for (size in QrDrawFixtures.SIZES) {
            val pixels = standard(code, size, dark = QrDrawFixtures.LIGHT, light = QrDrawFixtures.DARK)
            assertEquals(listOf(code), decode(pixels, size, size), "code, white on dark, $size px")
        }
    }

    @Test
    fun a_code_on_a_transparent_background_gives_the_code() {
        // An exported "sticker" PNG: black modules, the rest transparent black (0x00000000), which the luminance
        // source reads as black unless alpha is blended onto white first.
        for (size in listOf(525, 1280)) {
            val pixels = standard(code, size, light = TRANSPARENT_BLACK)
            assertEquals(listOf(code), decode(pixels, size, size), "code on transparent black, $size px")
        }
    }

    @Test
    fun the_shared_png_downsampled_by_two_gives_the_code() {
        val full = standard(code, 1280)
        val half = IntArray(640 * 640) { i -> full[(i / 640) * 2 * 1280 + (i % 640) * 2] }
        assertEquals(listOf(code), decode(half, 640, 640), "the 1280 px share PNG read at 640 px")
    }

    @Test
    fun noise_gives_nothing() {
        val random = Random(QR7_SEED)
        val pixels = IntArray(600 * 600) { if (random.nextBoolean()) QrDrawFixtures.DARK else QrDrawFixtures.LIGHT }
        assertEquals(emptyList(), decode(pixels, 600, 600), "a picture with no QR code")
        val blank = IntArray(400 * 300) { QrDrawFixtures.LIGHT }
        assertEquals(emptyList(), decode(blank, 400, 300), "a blank picture")
    }

    @Test
    fun a_picture_drawn_by_2_12_4_still_imports() {
        val failed =
            listOf("F-ADDR" to address, "F-CODE" to code).flatMap { (name, text) ->
                listOf(525, 1280).mapNotNull { size ->
                    val found = decode(legacy(text, size), size, size)
                    if (found == listOf(text)) null else "$name drawn by 2.12.4 at $size px gave ${labels(found)}"
                }
            }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    // ------------------------------------------------------------------ two codes

    @Test
    fun an_invite_screenshot_gives_both_codes() {
        val pictures =
            listOf(
                invite("light Invite screenshot", dark = false) { t, s -> standard(t, s) },
                invite("dark Invite screenshot", dark = true) { t, s -> standard(t, s) },
                invite("light Invite screenshot drawn by 2.12.4", dark = false) { t, s -> legacy(t, s) },
                invite("dark Invite screenshot drawn by 2.12.4", dark = true) { t, s -> legacy(t, s) },
            )
        val failed =
            pictures.mapNotNull { p ->
                val found = decode(p.pixels, p.width, p.height)
                if (found.toSet() == setOf(address, code) && found.size == 2) null else "${p.name} gave ${labels(found)}"
            }
        assertTrue(failed.isEmpty(), "a picture with two codes must give both, never one at random:\n" + failed.joinToString("\n"))
    }

    // ------------------------------------------------------------------ no regression

    /**
     * Every picture today's reader (ImageUriToQrCodeConverter's exact single MultiFormatReader, QrDrawFixtures'
     * decodeWholePicture) decodes is also decoded, with the same text among the results.
     */
    @Test
    fun whatever_imports_today_still_imports() {
        val seed = QrDrawFixtures.F_SEED
        val corpus = mutableListOf<Picture>()
        for (size in QrDrawFixtures.SIZES) {
            for ((name, text) in listOf("F-ADDR" to address, "F-CODE" to code, "F-SEED" to seed)) {
                corpus += single("$name standard $size", standard(text, size), size)
                corpus += single("$name inverted $size", standard(text, size, QrDrawFixtures.LIGHT, QrDrawFixtures.DARK), size)
                corpus += single("$name 2.12.4 $size", legacy(text, size), size)
                corpus += single("$name 2.12.4 inverted $size", legacy(text, size, QrDrawFixtures.LIGHT, QrDrawFixtures.DARK), size)
            }
        }
        for (size in QrDrawFixtures.LOGO_SIZES) {
            corpus += single("F-CODE with logo $size", QrDrawFixtures.withLogo(QrDrawFixtures.drawn(code, size), size), size)
        }
        corpus += invite("light Invite", dark = false) { t, s -> standard(t, s) }
        corpus += invite("dark Invite", dark = true) { t, s -> standard(t, s) }
        corpus += invite("light Invite 2.12.4", dark = false) { t, s -> legacy(t, s) }
        corpus += invite("dark Invite 2.12.4", dark = true) { t, s -> legacy(t, s) }

        var readToday = 0
        val lost =
            corpus.mapNotNull { p ->
                val today = todaysReader(p) ?: return@mapNotNull null
                readToday++
                val found = decode(p.pixels, p.width, p.height)
                if (today in found) null else "${p.name}: today gives ${label(today)}, now ${labels(found)}"
            }
        assertTrue(readToday > corpus.size / 2, "the corpus lost its pictures that import today ($readToday of ${corpus.size})")
        assertTrue(lost.isEmpty(), "pictures that import today no longer import:\n" + lost.joinToString("\n"))
    }

    @Test
    fun results_have_no_duplicates() {
        val p = invite("light Invite", dark = false) { t, s -> standard(t, s) }
        val found = decode(p.pixels, p.width, p.height)
        assertEquals(found.distinct(), found, "the same text twice")
    }

    private fun todaysReader(p: Picture): String? {
        if (p.width == p.height) return QrDrawFixtures.decodeWholePicture(p.pixels.copyOf(), p.width)
        return try {
            com.google.zxing
                .MultiFormatReader()
                .apply {
                    setHints(
                        mapOf(
                            com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
                            com.google.zxing.DecodeHintType.ALSO_INVERTED to true,
                            com.google.zxing.DecodeHintType.TRY_HARDER to true,
                            com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8"
                        )
                    )
                }.decodeWithState(
                    com.google.zxing.BinaryBitmap(
                        com.google.zxing.common.HybridBinarizer(
                            com.google.zxing.RGBLuminanceSource(p.width, p.height, p.pixels.copyOf())
                        )
                    )
                ).text
        } catch (_: com.google.zxing.ReaderException) {
            null
        }
    }

    private companion object {
        const val BACKGROUND_LIGHT = -0x0a0a0b // 0xFFF5F5F5
        const val BACKGROUND_DARK = -0xefeff0 // 0xFF101010
        const val TRANSPARENT_BLACK = 0x00000000
        const val QR7_SEED = 7
    }
}
