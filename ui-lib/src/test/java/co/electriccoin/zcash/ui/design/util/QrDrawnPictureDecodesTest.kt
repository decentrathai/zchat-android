package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.IMAGE_GENERATOR
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.LOGO_SIZES
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.SIZES
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.argb
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.decodeWholePicture
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.drawn
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.fixture
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.source
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.squash
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.withLogo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QR-DRAW (fix-all 2.13.0, train T4c) (a): the drawn picture must decode as a whole, the way the gallery import
 * (ImageUriToQrCodeConverter) reads a saved or shared QR. At HEAD the transposed drawing of the address and the
 * contact code is not found at any size. The seed-backup QR decodes before and after (a control: it must keep
 * decoding).
 */
class QrDrawnPictureDecodesTest {
    @Test
    fun code_and_address_decode_from_the_whole_picture() {
        val failed =
            listOf("F-ADDR", "F-CODE").map(::fixture).flatMap { f ->
                SIZES.mapNotNull { size ->
                    val text = decodeWholePicture(argb(drawn(f.text, size)), size)
                    val verdict = if (text == null) "not found" else "wrong text"
                    if (text == f.text) null else "${f.name} at $size px: $verdict"
                }
            }
        assertTrue(failed.isEmpty(), "the gallery reader cannot read the drawn QR:\n" + failed.joinToString("\n"))
    }

    @Test
    fun seed_backup_qr_decodes_from_the_whole_picture() {
        val f = fixture("F-SEED")
        val failed =
            SIZES.mapNotNull { size ->
                if (decodeWholePicture(argb(drawn(f.text, size)), size) == f.text) null else "F-SEED at $size px"
            }
        assertTrue(failed.isEmpty(), "the seed-backup QR must keep decoding:\n" + failed.joinToString("\n"))
    }

    @Test
    fun logo_qr_decodes_from_the_whole_picture() {
        val failed =
            listOf("F-ADDR", "F-CODE").map(::fixture).flatMap { f ->
                LOGO_SIZES.mapNotNull { size ->
                    val text = decodeWholePicture(withLogo(drawn(f.text, size), size), size)
                    if (text == f.text) null else "${f.name} with the centre logo at $size px"
                }
            }
        assertTrue(failed.isEmpty(), "the drawn QR with its centre logo does not decode:\n" + failed.joinToString("\n"))
    }

    /**
     * Source guard: the bitmap builder maps the array index for index, and both overloads hand it to
     * `Bitmap.createBitmap` as a sizePixels-wide row-major array. So the orientation cannot be "compensated" in a
     * second place, and a later change there fails here.
     */
    @Test
    fun the_bitmap_builder_maps_index_for_index() {
        val text = squash(source(IMAGE_GENERATOR))
        assertTrue(
            text.contains("IntArray(size) { if (this[it]) foreground else background }"),
            "AndroidQrCodeImageGenerator must build IntArray(size) { if (this[it]) foreground else background }"
        )
        assertEquals(
            2,
            Regex("""\.createBitmap\(colorArray, sizePixels, sizePixels,""").findAll(text).count(),
            "both generate overloads must pass (colorArray, sizePixels, sizePixels, …) to Bitmap.createBitmap"
        )
        assertEquals(
            3,
            Regex("""toThemeColorArray\(""").findAll(text).count(),
            "both overloads (and the one definition) must go through toThemeColorArray"
        )
    }
}
