package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.JVM_GENERATOR
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.SHARE_QR
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.SIZES
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.border
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.callArguments
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.drawn
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.productKotlinFiles
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.source
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.squash
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.topLevelArguments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QR-DRAW (fix-all 2.13.0, train T4c) (c): the light border inside the drawn bitmap must be the standard 4 modules
 * (ISO/IEC 18004). ZXing's EncodeHintType.MARGIN counts modules; at HEAD it is 2, so the shared PNG (no container)
 * and the full-screen view (6 dp padding) can show only 2-3.5 modules of light border.
 */
class QrQuietZoneIsFourModulesTest {
    /** Read at run time: the `const val` would be folded into this test at compile time. */
    @Test
    fun margin_is_at_least_four_modules() {
        val margin =
            try {
                Class
                    .forName("co.electriccoin.zcash.ui.design.util.JvmQrCodeGeneratorKt")
                    .getField("QR_CODE_IMAGE_MARGIN_IN_PIXELS")
                    .getInt(null)
            } catch (e: ReflectiveOperationException) {
                fail("QR_CODE_IMAGE_MARGIN_IN_PIXELS not found: $e")
            }
        assertTrue(margin >= MIN_QUIET_ZONE, "the QR margin is $margin modules; the standard quiet zone is 4")
    }

    @Test
    fun every_side_has_four_light_modules() {
        val thin =
            QrDrawFixtures.all.flatMap { f ->
                SIZES.mapNotNull { size ->
                    val light = border(drawn(f.text, size), size)
                    if (light >= MIN_QUIET_ZONE) null else "${f.name} at $size px: %.2f modules".format(light)
                }
            }
        assertTrue(thin.isEmpty(), "a side of the drawn QR has less than 4 light modules:\n" + thin.joinToString("\n"))
    }

    /**
     * Source guard: one margin for every QR. No caller may draw with another margin (no three-argument generate, no
     * margin parameter on ShareQRUseCase), and the generator sets MARGIN once, from the constant.
     */
    @Test
    fun no_caller_draws_with_another_margin() {
        val calls =
            productKotlinFiles.flatMap { (path, text) ->
                callArguments(text, "JvmQrCodeGenerator.generate(").map { path to topLevelArguments(it) }
            }
        assertTrue(calls.isNotEmpty(), "no JvmQrCodeGenerator.generate( call found: the guard reads the wrong files")
        val odd = calls.filter { (_, args) -> args.size != 2 }
        assertTrue(
            odd.isEmpty(),
            "JvmQrCodeGenerator.generate must be called with (data, sizePixels) only:\n" +
                odd.joinToString("\n") { (path, args) -> "$path: ${args.size} arguments" }
        )

        val generator = source(JVM_GENERATOR)
        assertEquals(1, Regex("""EncodeHintType\.MARGIN\b""").findAll(generator).count(), "MARGIN must be set once")
        assertTrue(
            Regex("""EncodeHintType\.MARGIN\s+to\s+QR_CODE_IMAGE_MARGIN_IN_PIXELS\b""").containsMatchIn(generator),
            "MARGIN must come from QR_CODE_IMAGE_MARGIN_IN_PIXELS"
        )
        assertEquals(1, Regex("""\bfun\s+generate\(""").findAll(generator).count(), "one generate, no margin overload")

        val invoke = callArguments(squash(source(SHARE_QR)), "operator fun invoke(").single()
        assertTrue(!invoke.contains("margin", ignoreCase = true), "ShareQRUseCase.invoke must not take a margin")
    }

    private companion object {
        const val MIN_QUIET_ZONE = 4.0
    }
}
