package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 D9 (DECISIONS_NIGHT §4, p2-b54548e4c59c) — More → Security ("What ZCHAT Protects") in Spanish.
 *
 * MoreView renders WHAT_ZCHAT_PROTECTS_ITEMS (11 rows) and WHAT_ZCHAT_PROTECTS_LIMITATIONS straight from Kotlin
 * constants, with an English title, heading and button, so a Spanish phone reads the privacy limits (photo hosts,
 * relays seeing the IP address, no forward secrecy, keys not hardware-backed) in English only.
 *
 * Wanted: the rows move to ui/common/values{,-es}/security_dialog_strings.xml as numbered strings
 * (security_dialog_item_<n>_title / _body, n = 1..11, and security_dialog_limitations), and MoreView reads them.
 * The Kotlin constants stay the pinned English (UserFacingPrivacyClaimsTest), and the English resources equal them
 * byte for byte. Spanish has the same number of rows and limitation lines, and its Tor line names the Tor switch by
 * its Spanish label in Advanced settings (advanced_settings_privacy). The dialog's title, heading and button are
 * resources too.
 */
class SecurityDialogResourcesTest {
    // Android turns a resource's \n escape into a line break; the limitations are one string of lines.
    private val en by lazy { ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") } }
    private val es by lazy { ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") } }

    @Test
    fun the_english_rows_are_the_pinned_constants_byte_for_byte() {
        assertEquals(11, WHAT_ZCHAT_PROTECTS_ITEMS.size, "fixture: the dialog has 11 rows")
        WHAT_ZCHAT_PROTECTS_ITEMS.forEachIndexed { i, (title, body) ->
            val n = i + 1
            assertEquals(title, en["security_dialog_item_${n}_title"], "English title of row $n")
            assertEquals(body, en["security_dialog_item_${n}_body"], "English body of row $n")
        }
        assertEquals(WHAT_ZCHAT_PROTECTS_LIMITATIONS, en["security_dialog_limitations"], "English limitations")
        assertEquals(null, en["security_dialog_item_12_title"], "no row beyond the constants")
    }

    @Test
    fun spanish_has_every_row_and_every_limitation_line() {
        for (n in 1..WHAT_ZCHAT_PROTECTS_ITEMS.size) {
            for (part in listOf("title", "body")) {
                val name = "security_dialog_item_${n}_$part"
                val spanish = assertNotNull(es[name], "no Spanish $name")
                assertNotEquals(en[name], spanish, "$name is not translated")
            }
        }
        assertEquals(null, es["security_dialog_item_12_title"], "no Spanish row beyond the English ones")
        val limitations = assertNotNull(es["security_dialog_limitations"], "no Spanish limitations")
        assertEquals(
            WHAT_ZCHAT_PROTECTS_LIMITATIONS.lines().size,
            limitations.lines().size,
            "Spanish limitations must have one line per English line: $limitations",
        )
        assertTrue(limitations.lines().all { it.startsWith("- ") }, "each Spanish limitation is a \"- \" line: $limitations")
        for (name in listOf("security_dialog_title", "security_dialog_limitations_heading", "security_dialog_ok")) {
            assertNotNull(en[name], "no English $name")
            assertNotNull(es[name], "no Spanish $name")
        }
    }

    @Test
    fun the_spanish_double_encryption_row_carries_the_key_exchange_caveat() {
        assertEquals(
            "AES-256-GCM dentro de transacciones blindadas de Zcash (pruebas de conocimiento cero), una vez que tu " +
                "contacto respondió al intercambio de claves. Un atacante tendría que romper AMBAS capas por separado.",
            es["security_dialog_item_1_body"],
        )
        assertEquals("Doble cifrado", es["security_dialog_item_1_title"])
    }

    @Test
    fun the_spanish_tor_line_names_the_tor_switch_by_its_spanish_label() {
        val torLabel = assertNotNull(es["advanced_settings_privacy"], "fixture: the Tor switch in Advanced settings")
        assertEquals("Beta: Protección con Tor", torLabel, "fixture: the Spanish Tor switch label")
        val limitations = assertNotNull(es["security_dialog_limitations"], "no Spanish limitations")
        val torLine = limitations.lines().firstOrNull { it.startsWith("- Tor ") }
        assertNotNull(torLine, "no Spanish Tor line: $limitations")
        assertTrue("($torLabel)" in torLine, "the Spanish Tor line must name the switch \"$torLabel\": $torLine")
        assertFalse("etiqueta" in torLine, "the draft's placeholder is still there: $torLine")
    }

    @Test
    fun more_view_reads_the_resources_not_the_english_constants() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/more/MoreView.kt")
        assertFalse("WHAT_ZCHAT_PROTECTS_ITEMS" in src, "MoreView still renders the English constant rows")
        assertFalse("WHAT_ZCHAT_PROTECTS_LIMITATIONS" in src, "MoreView still renders the English constant limitations")
        for (literal in listOf("\"What ZCHAT Protects\"", "\"Known limitations:\"", "\"Got it\"")) {
            assertFalse(literal in src, "MoreView still shows the English literal $literal")
        }
        assertTrue("R.string.security_dialog_limitations" in src, "MoreView does not read security_dialog_limitations")
        assertTrue("SECURITY_DIALOG_ROWS" in src, "MoreView does not read the numbered row resources")
    }
}
