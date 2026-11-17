package co.electriccoin.zcash.ui.screen.scan

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SCAN-GALLERY (fix-all 2.13.0, train T4c; fold-now spec SCAN-GALLERY §5 with review R3): the gallery button and the
 * paste hint exist in English and Spanish, the camera-refused text names the gallery and uses the app name it is given
 * (`%1$s`, passed by ScanView since Zashi but never used), and the English typo "turn in on" is gone.
 */
class ScanGalleryStringsHaveSpanishTest {
    private fun strings(dir: String): Map<String, String> {
        val xml = QrDrawFixtures.source("ui-lib/src/main/res/ui/scan/$dir/strings.xml")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en by lazy { strings("values") }
    private val es by lazy { strings("values-es") }

    @Test
    fun the_gallery_button_and_paste_hint_have_english_and_spanish() {
        for (key in listOf("scan_gallery_button", "scan_paste_hint")) {
            val e = assertNotNull(en[key], "no English $key")
            val s = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(e, s, "$key: the Spanish is still English")
        }
        // Review R3: the Send field takes an address or a payment link, not a contact code, so the hint names neither.
        assertTrue(
            "paste it instead" in en.getValue("scan_paste_hint"),
            "the English paste hint is not the reviewed text"
        )
    }

    @Test
    fun the_camera_refused_text_names_the_gallery_and_the_app() {
        val e = assertNotNull(en["scan_state_permission"], "no English scan_state_permission")
        val s = assertNotNull(es["scan_state_permission"], "no Spanish scan_state_permission")
        assertEquals(1, e.split(APP_NAME).size - 1, "English: %1\$s (the app name) exactly once")
        assertEquals(1, s.split(APP_NAME).size - 1, "Spanish: %1\$s (the app name) exactly once")
        assertTrue("gallery" in e.lowercase(), "English does not name the gallery")
        assertTrue("galería" in s.lowercase(), "Spanish does not name the gallery")
        assertFalse("turn in on" in e, "the English typo is still there")
    }

    @Test
    fun every_spanish_scan_key_exists_in_english() {
        assertEquals(emptySet(), es.keys - en.keys, "Spanish keys with no English base")
    }

    private companion object {
        const val APP_NAME = "%1\$s"
    }
}
