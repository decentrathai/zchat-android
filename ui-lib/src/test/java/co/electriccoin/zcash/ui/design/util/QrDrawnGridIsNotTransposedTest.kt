package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.SIZES
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.differences
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.dim
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.drawn
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.grid
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.layoutSha256
import com.google.zxing.ReaderException
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QR-DRAW (fix-all 2.13.0, train T4c) (a): every QR the app draws must be the standard symbol, not its transpose.
 *
 * At HEAD `JvmQrCodeGenerator.generate` fills its array column by column (outer x loop, index x * size + y), while
 * `Bitmap.createBitmap` in AndroidQrCodeImageGenerator reads it row by row (index y * size + x). So the pixel at
 * (column c, row r) holds module (r, c): the drawn QR is flipped across its main diagonal. Camera readers with a
 * mirror retry cope; strict readers and ZXing's whole-picture detector (our gallery import) often do not.
 */
class QrDrawnGridIsNotTransposedTest {
    @Test
    fun drawn_grid_matches_the_standard_row_by_row() {
        assertEquals(245, QrDrawFixtures.F_SEED.length, "F-SEED must be the 245-character seed-backup JSON")
        assertEquals(QrDrawFixtures.F_SEED_EXPECTED, QrDrawFixtures.F_SEED)
        assertEquals(213, QrDrawFixtures.F_ADDR.length)
        assertEquals(321, QrDrawFixtures.F_CODE.length)

        val wrong =
            QrDrawFixtures.all.flatMap { fixture ->
                SIZES.mapNotNull { size ->
                    val count = differences(grid(drawn(fixture.text, size), size, dim(fixture.text)), fixture.text)
                    if (count == 0) null else "${fixture.name} at $size px: $count modules differ from the standard"
                }
            }
        assertTrue(
            wrong.isEmpty(),
            "the drawn QR is not the standard symbol read row by row:\n" + wrong.joinToString("\n")
        )
    }

    @Test
    fun drawn_grid_matches_the_pinned_golden() {
        val wrong =
            QrDrawFixtures.all.mapNotNull { fixture ->
                val sha = layoutSha256(grid(drawn(fixture.text, GOLDEN_SIZE), GOLDEN_SIZE, dim(fixture.text)))
                if (sha == fixture.layoutSha256) null else "${fixture.name}: layout hash differs from the pinned golden"
            }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun strict_decoder_reads_it_without_mirroring() {
        val wrong =
            QrDrawFixtures.all.mapNotNull { fixture ->
                val grid = grid(drawn(fixture.text, GOLDEN_SIZE), GOLDEN_SIZE, dim(fixture.text))
                // The decoder unmasks (and, on a retry, mirrors) the matrix in place: hand it a copy.
                val result =
                    try {
                        Decoder().decode(grid.clone())
                    } catch (e: ReaderException) {
                        return@mapNotNull "${fixture.name}: the strict decoder cannot read it (${e.javaClass.simpleName})"
                    }
                val mirrored = (result.other as? QRCodeDecoderMetaData)?.isMirrored == true
                when {
                    result.text != fixture.text -> "${fixture.name}: decoded to another text"
                    mirrored -> "${fixture.name}: decoded only as a mirrored symbol (isMirrored = true)"
                    else -> null
                }
            }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private companion object {
        const val GOLDEN_SIZE = 525
    }
}
