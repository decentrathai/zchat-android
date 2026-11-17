package co.electriccoin.zcash.ui.screen.scan

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * QR-7 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §1.5): the three texts QR-7 adds exist in English and Spanish
 * with the same placeholders (none), the Spanish is not the English, and the gallery texts say what happened without
 * calling the picture an address.
 */
class ScanPictureStringsHaveSpanishTest {
    private val keys =
        mapOf(
            "scan_picture_no_code" to "ui/scan",
            "scan_picture_many_codes" to "ui/scan",
            "invite_share_code_qr" to "ui/invite",
        )

    private fun strings(
        folder: String,
        dir: String
    ): Map<String, String> {
        val xml = QrDrawFixtures.source("ui-lib/src/main/res/$folder/$dir/strings.xml")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) =
        Regex("""%(\d\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    @Test
    fun every_qr7_text_has_english_and_spanish() {
        for ((key, folder) in keys) {
            val en = assertNotNull(strings(folder, "values")[key], "no English $key")
            val es = assertNotNull(strings(folder, "values-es")[key], "no Spanish $key")
            assertNotEquals(en, es, "$key: the Spanish is still English")
            assertEquals(placeholders(en), placeholders(es), "$key: placeholders differ")
        }
    }

    @Test
    fun the_gallery_texts_do_not_call_the_picture_an_address() {
        for (key in listOf("scan_picture_no_code", "scan_picture_many_codes")) {
            val en = assertNotNull(strings("ui/scan", "values")[key], "no English $key")
            val es = assertNotNull(strings("ui/scan", "values-es")[key], "no Spanish $key")
            assertFalse("Zcash Address" in en || "dirección válida" in es, "$key reads like the bad-address text")
        }
    }
}
