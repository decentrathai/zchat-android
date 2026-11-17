package co.electriccoin.zcash.ui.screen.scan

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SCAN-GALLERY (fix-all 2.13.0, train T4c; fold-now spec SCAN-GALLERY §3 with review R1-R6): when the camera is refused
 * (Permission) or failed to start (Failed), the scanner offers "Choose from Gallery" through the same launcher as the
 * gallery icon, and the answer to a picture ("not a valid address", QR-7's "no QR code found" / "more than one code")
 * shows above the camera line instead of being overwritten by it.
 *
 * At the parent the gallery is reachable only from the icon drawn while the camera runs (Scanning), and in Permission
 * and Failed the bottom text is always the camera text.
 *
 * (A) behaviour: the pure `ScanScreenFeedback` object, reached by reflection so this compiles before it exists
 * (M1 RED precedent). Values are compared by enum name. The event rule runs over every `ScanValidationState`, so a state
 * added later is covered too.
 * (B) wiring, source scan of `ScanView.kt`, matching the fix steps: `ScanBottomItems` takes `onGalleryClick` and
 * `showPasteHint`, reads its lines and button from `ScanScreenFeedback`, draws a secondary "Choose from Gallery" button
 * only under `showGalleryButton`, and the paste hint never in Scanning; `ScanMainContent` passes the one gallery
 * launcher.
 * (C) `ScanZashiAddressScreen` passes the paste hint for its flow; guards: one launcher, the Keystone scanner and the
 * generic scanner untouched.
 */
class ScanOffersGalleryWithoutCameraTest {
    private val feedbackClassName = "co.electriccoin.zcash.ui.screen.scan.ScanScreenFeedback"

    private val scanView by lazy { QrDrawFixtures.source("$SCAN_DIR/ScanView.kt") }

    // ------------------------------------------------------------------ (A) behaviour

    private fun feedback(): Pair<Class<*>, Any> {
        val cls =
            try {
                Class.forName(feedbackClassName)
            } catch (e: ClassNotFoundException) {
                fail(
                    "ScanScreenFeedback missing: with the camera refused the scanner offers no gallery and hides the " +
                        "picture result ($e)"
                )
            }
        val instance = checkNotNull(cls.getField("INSTANCE").get(null)) { "ScanScreenFeedback.INSTANCE is null" }
        return cls to instance
    }

    private fun call(
        name: String,
        vararg args: Any
    ): Any? {
        val (cls, instance) = feedback()
        val method =
            cls.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: fail("ScanScreenFeedback.$name(${args.size} parameters) missing")
        return try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun lines(
        state: ScanScreenState,
        validation: ScanValidationState
    ): List<String> {
        val result = call("lines", state, validation) as? List<*> ?: fail("lines(...) does not return a List")
        return result.map { (it as? Enum<*>)?.name ?: fail("lines(...) holds a non-enum value: $it") }
    }

    private fun isEvent(validation: ScanValidationState) =
        validation != ScanValidationState.NONE && validation != ScanValidationState.VALID

    private fun cameraLine(state: ScanScreenState): List<String> =
        when (state) {
            ScanScreenState.Permission -> listOf("PERMISSION")
            ScanScreenState.Failed -> listOf("CAMERA_FAILED")
            ScanScreenState.Scanning -> emptyList()
        }

    @Test
    fun the_picture_answer_shows_on_top_and_the_camera_line_always_under_it() {
        for (state in ScanScreenState.values()) {
            for (validation in ScanValidationState.values()) {
                val expected = (if (isEvent(validation)) listOf(validation.name) else emptyList()) + cameraLine(state)
                assertEquals(expected, lines(state, validation), "lines($state, $validation)")
            }
        }
    }

    @Test
    fun the_refused_camera_no_longer_hides_a_bad_picture() {
        // The Fold/HONOR case: camera refused, a picture with no code chosen from the gallery.
        assertEquals(
            listOf("NO_CODE_IN_PICTURE", "PERMISSION"),
            lines(ScanScreenState.Permission, ScanValidationState.NO_CODE_IN_PICTURE)
        )
        assertEquals(
            listOf("INVALID", "CAMERA_FAILED"),
            lines(ScanScreenState.Failed, ScanValidationState.INVALID)
        )
    }

    @Test
    fun the_gallery_button_shows_whenever_the_camera_is_not_running() {
        assertEquals(true, call("showGalleryButton", ScanScreenState.Permission), "Permission")
        assertEquals(true, call("showGalleryButton", ScanScreenState.Failed), "Failed")
        assertEquals(false, call("showGalleryButton", ScanScreenState.Scanning), "Scanning keeps the icon only")
    }

    @Test
    fun the_paste_hint_is_only_for_flows_with_a_paste_field_one_screen_back() {
        val withField = setOf(ScanFlow.ZCHAT, ScanFlow.SEND)
        for (flow in ScanFlow.values()) {
            assertEquals(flow in withField, call("showPasteHint", flow), "showPasteHint($flow)")
        }
    }

    // ------------------------------------------------------------------ (B) wiring

    /** From [start] (an opening [open]) to its matching [close], both included. */
    private fun matched(
        text: String,
        start: Int,
        open: Char,
        close: Char
    ): String {
        require(text[start] == open) { "expected '$open' at $start" }
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        fail("no matching '$close' from $start")
    }

    private fun parameters(
        text: String,
        signature: String
    ): String {
        val at = text.indexOf(signature)
        assertTrue(at >= 0, "'$signature' not found")
        return matched(text, at + signature.length - 1, '(', ')')
    }

    private fun body(
        text: String,
        signature: String
    ): String {
        val params = parameters(text, signature)
        val at = text.indexOf(signature) + signature.length - 1 + params.length
        return matched(text, text.indexOf('{', at), '{', '}')
    }

    private fun block(
        text: String,
        head: String
    ): String {
        val at = text.indexOf(head)
        assertTrue(at >= 0, "'$head' not found")
        return matched(text, text.indexOf('{', at + head.length), '{', '}')
    }

    @Test
    fun scan_bottom_items_takes_the_gallery_click_and_the_paste_hint() {
        val params = parameters(scanView, "fun ScanBottomItems(")
        assertTrue("onGalleryClick: () -> Unit" in params, "ScanBottomItems has no onGalleryClick")
        assertTrue("showPasteHint: Boolean" in params, "ScanBottomItems has no showPasteHint")
        assertTrue(
            "showPasteHint: Boolean = false" in parameters(scanView, "fun ScanView("),
            "ScanView has no showPasteHint defaulting to false"
        )
    }

    @Test
    fun scan_bottom_items_reads_its_lines_and_button_from_scan_screen_feedback() {
        val body = body(scanView, "fun ScanBottomItems(")
        assertTrue(
            "ScanScreenFeedback.lines(scanState, validationResult)" in body,
            "the bottom text does not come from ScanScreenFeedback.lines"
        )
        val gallery = block(body, "if (ScanScreenFeedback.showGalleryButton(scanState))")
        val needles =
            listOf(
                "ZashiButton(",
                "R.string.scan_gallery_button",
                "ScanTag.GALLERY_BUTTON",
                "onClick = onGalleryClick",
                "ZashiButtonDefaults.secondaryColors()"
            )
        for (needle in needles) {
            assertTrue(needle in gallery, "the gallery button block lacks $needle")
        }
        val hint = block(body, "if (showPasteHint && scanState != ScanScreenState.Scanning)")
        assertTrue("R.string.scan_paste_hint" in hint, "the paste hint block does not show scan_paste_hint")
        assertFalse(
            "ScanScreenState.Scanning -> failureText" in scanView,
            "the camera text still overwrites the picture answer"
        )
    }

    @Test
    fun the_button_uses_the_same_launcher_as_the_gallery_icon() {
        val call = scanView.indexOf("ScanBottomItems(", scanView.indexOf("fun ScanMainContent("))
        assertTrue(call >= 0, "ScanMainContent does not call ScanBottomItems")
        val args = matched(scanView, call + "ScanBottomItems".length, '(', ')')
        assertTrue(
            "onGalleryClick = { galleryLauncher.launch(\"image/*\") }" in args,
            "ScanMainContent does not pass the gallery launcher to ScanBottomItems"
        )
        assertTrue("showPasteHint = showPasteHint" in args, "ScanMainContent does not pass showPasteHint down")
        assertEquals(
            1,
            Regex("""rememberLauncherForActivityResult\(""").findAll(scanView).count(),
            "exactly one gallery launcher and result handler"
        )
    }

    @Test
    fun the_gallery_button_has_its_own_test_tag() {
        val field =
            runCatching { ScanTag::class.java.getField("GALLERY_BUTTON") }.getOrNull()
                ?: fail("ScanTag.GALLERY_BUTTON missing")
        assertEquals("gallery_button", field.get(null))
    }

    // ------------------------------------------------------------------ (C) callers and guards

    @Test
    fun the_zashi_scanner_passes_the_paste_hint_for_its_flow() {
        val screen = QrDrawFixtures.source("$SCAN_DIR/ScanZashiAddressScreen.kt")
        assertTrue(
            "showPasteHint = ScanScreenFeedback.showPasteHint(args.flow)" in screen,
            "ScanZashiAddressScreen does not pass the paste hint"
        )
    }

    @Test
    fun the_generic_and_keystone_scanners_are_untouched() {
        val generic = QrDrawFixtures.source("$SCAN_DIR/ScanGenericAddressScreen.kt")
        assertFalse("showPasteHint" in generic, "the generic scanner must keep the default (no paste hint)")
        val keystone =
            QrDrawFixtures.source(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/scankeystone/view/ScanKeystoneView.kt"
            )
        assertFalse("scan_gallery_button" in keystone, "the Keystone scanner must not get a gallery button")
        assertFalse("GetContent(" in keystone, "the Keystone scanner must not get a gallery launcher")
    }

    private companion object {
        const val SCAN_DIR = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/scan"
    }
}
