package co.electriccoin.zcash.ui.screen.scan

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T4c fixup, QR7-1 (train T4c cross review, REVIEW_CROSS.md; register ZC-306): a gallery picture that holds one code
 * drawn today and one drawn by 2.12.4 (or any drawing before QR-DRAW, which is the code's transpose) gives both codes,
 * so the scanner's rule "two people's codes in one picture: use none" holds for it too.
 *
 * At 6f0678116 [QrPictureDecoder.decode] returns as soon as the upright readers find anything, so the transposed pass
 * never runs: the picture gives only today's code, [ScannedTexts.pick] returns One, and Wallet Send or New Chat is
 * filled with it although the picture names two people.
 *
 * The upright readers read most transposed drawings too (ZXing retries a code mirrored), so the pictures here are the
 * kind where they do not: two small codes side by side in a noisy picture (a photo or a resized screenshot), today's
 * drawing on the left and a 2.12.4 drawing of another person's code on the right. They are rebuilt exactly from
 * java.util.Random seeds; each was checked on the PC to give only today's code upright and both codes transposed.
 *
 * Wanted: the transposed pass always runs too and its texts are joined after the upright ones (no duplicates), so the
 * first text (the one the restore screen takes) is unchanged. Addresses and keys are synthetic; no text is printed on
 * failure, only names and counts.
 */
class PictureWithAnOldAndANewCodeGivesBothTest {
    private val today = QrDrawFixtures.F_CODE

    private class Picture(
        val name: String,
        val other: String,
        val pixels: IntArray,
        val width: Int,
        val height: Int
    )

    /** The code drawn row-major at margin 4 (today's drawing) or column-first at margin 2 (2.12.4's, a transpose). */
    private fun draw(
        text: String,
        size: Int,
        legacy: Boolean
    ): IntArray {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(
                    EncodeHintType.MARGIN to (if (legacy) 2 else 4),
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
                )
            )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dark = if (legacy) matrix.get(y, x) else matrix.get(x, y)
                pixels[y * size + x] = if (dark) DARK else LIGHT
            }
        }
        return pixels
    }

    /**
     * Picture [seed]: another person's synthetic code, two sizes, a gap and a noise level from java.util.Random(1000 +
     * seed); today's code on the left, the other drawn by 2.12.4 on the right, grey noise over the whole picture.
     */
    private fun picture(seed: Int): Picture {
        val random = java.util.Random(1000L + seed)
        val address = "u1" + String(CharArray(211) { BECH32[random.nextInt(32)] })
        val key = String(CharArray(64) { HEX[random.nextInt(16)] })
        val other = "zchat:c1?z=$address&n=$key&r=wss%3A%2F%2Frelay.zsend.xyz"
        val s1 = 140 + random.nextInt(220)
        val s2 = 140 + random.nextInt(220)
        val gap = random.nextInt(60)
        val noise = random.nextInt(90)
        val width = s1 + s2 + gap + 40
        val height = maxOf(s1, s2) + 40
        val pixels = IntArray(width * height) { BACKGROUND_LIGHT }
        for ((drawn, size, x0) in listOf(Triple(draw(today, s1, false), s1, 20), Triple(draw(other, s2, true), s2, 20 + s1 + gap))) {
            for (y in 0 until size) System.arraycopy(drawn, y * size, pixels, (20 + y) * width + x0, size)
        }
        for (i in pixels.indices) {
            val v = ((pixels[i] and 0xFF) + (random.nextGaussian() * noise).toInt()).coerceIn(0, 255)
            pixels[i] = OPAQUE or (v * 0x010101)
        }
        return Picture("picture $seed ($width x $height)", other, pixels, width, height)
    }

    private val pictures by lazy { SEEDS.map { picture(it) } }

    private fun decode(p: Picture): List<String> = QrPictureDecoder.decode(p.pixels.copyOf(), p.width, p.height)

    private fun labels(
        p: Picture,
        texts: List<String>
    ): String =
        texts.joinToString(prefix = "[", postfix = "]") {
            when (it) {
                today -> "today's code"
                p.other -> "the other person's code"
                else -> "another text (${it.length} chars)"
            }
        }

    @Test
    fun a_picture_with_a_code_drawn_today_and_one_drawn_by_2_12_4_gives_both() {
        val failed =
            pictures.mapNotNull { p ->
                val found = decode(p)
                if (found.toSet() == setOf(today, p.other) && found.size == 2) null else "${p.name} gave ${labels(p, found)}"
            }
        assertTrue(failed.isEmpty(), "a picture with two codes must give both, whichever way each was drawn:\n" + failed.joinToString("\n"))
    }

    @Test
    fun such_a_picture_fills_neither_wallet_send_nor_new_chat() {
        val failed =
            pictures.flatMap { p ->
                val found = decode(p)
                listOf(ScanFlow.SEND, ScanFlow.ZCHAT, ScanFlow.HOMEPAGE, null).mapNotNull { flow ->
                    val pick = ScannedTexts.pick(flow, found)
                    if (pick ==
                        ScannedTexts.Pick.Ambiguous
                    ) {
                        null
                    } else {
                        "${p.name}, flow $flow: ${pick.javaClass.simpleName} from ${labels(p, found)}"
                    }
                }
            }
        assertTrue(failed.isEmpty(), "a picture naming two people was used:\n" + failed.joinToString("\n"))
    }

    /** Guard, GREEN before and after: the first text is still the one the upright readers find (restore takes it). */
    @Test
    fun the_first_text_is_still_the_upright_one() {
        val failed =
            pictures.mapNotNull { p ->
                decode(p).let {
                    if (it.firstOrNull() ==
                        today
                    ) {
                        null
                    } else {
                        "${p.name} gave ${labels(p, it)}"
                    }
                }
            }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    /** Guard, GREEN before and after: one code, drawn either way, still gives exactly that one text. */
    @Test
    fun one_code_drawn_either_way_gives_exactly_one_text() {
        val p = picture(SEEDS.first())
        for ((name, legacy) in listOf("today" to false, "2.12.4" to true)) {
            val drawn = draw(today, QR, legacy)
            val found = QrPictureDecoder.decode(drawn.copyOf(), QR, QR)
            assertTrue(found == listOf(today), "one code drawn $name gave ${labels(p, found)}")
        }
    }

    private companion object {
        /** Checked on the PC (ZXing 3.5.3): upright only today's code, transposed both. */
        val SEEDS = listOf(4, 233, 460)
        const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        const val HEX = "0123456789abcdef"
        const val QR = 525
        const val DARK = -0x1000000 // 0xFF000000
        const val LIGHT = -0x1 // 0xFFFFFFFF
        const val OPAQUE = -0x1000000
        const val BACKGROUND_LIGHT = -0x0a0a0b // 0xFFF5F5F5
    }
}
