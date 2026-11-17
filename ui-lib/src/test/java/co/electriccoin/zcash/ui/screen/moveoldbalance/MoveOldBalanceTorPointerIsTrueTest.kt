package co.electriccoin.zcash.ui.screen.moveoldbalance

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 R8-fixup F9 (REVIEW_R8 F9, device D-F10) — the Move-old-balance sheet tells a user whose Tor Protection is
 * off that "You can turn it on in Advanced Settings first" (ES: "Puedes activarla antes en Configuración Avanzada"),
 * but the Tor item of Advanced Settings is commented out (AdvancedSettingsVM, "Hidden for now"). No screen the user
 * can reach while Tor is off turns it on, so the sentence sends them to a menu item that does not exist.
 *
 * Wanted: while AdvancedSettingsVM lists no `advanced_settings_privacy` item, `move_old_balance_consent_tor` (English
 * and Spanish) names no settings screen at all. The rest of the note (the server sees the IP address) stays.
 */
class MoveOldBalanceTorPointerIsTrueTest {
    private val source =
        ChatResourceText.source(
            "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/AdvancedSettingsVM.kt",
        )

    /** True when a line that is not a comment lists the Tor (privacy) item. */
    private val torItemListed: Boolean =
        source
            .lines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .any { "advanced_settings_privacy" in it }

    @Test
    fun `the english Tor note names no settings screen while Advanced Settings has no Tor item`() {
        assertPointerTrue("values", listOf("Advanced Settings", "Settings"))
    }

    @Test
    fun `the spanish Tor note names no settings screen while Advanced Settings has no Tor item`() {
        assertPointerTrue("values-es", listOf("Configuración Avanzada", "Ajustes", "Configuración"))
    }

    /** Control: the note still says what Tor off means (the server sees the IP address). */
    @Test
    fun `control - the Tor note still says the server sees the IP address`() {
        val en = assertNotNull(ChatResourceText.strings("values")["move_old_balance_consent_tor"])
        val es = assertNotNull(ChatResourceText.strings("values-es")["move_old_balance_consent_tor"])
        assertTrue("IP address" in en, en)
        assertTrue("dirección IP" in es, es)
    }

    private fun assertPointerTrue(
        dir: String,
        menuWords: List<String>,
    ) {
        if (torItemListed) return
        val strings = ChatResourceText.strings(dir)
        val text = assertNotNull(strings["move_old_balance_consent_tor"], "no move_old_balance_consent_tor in $dir")
        val menu = assertNotNull(strings["settings_advanced_settings"], "no settings_advanced_settings in $dir")
        for (word in menuWords + menu) {
            assertTrue(
                word !in text,
                "$dir move_old_balance_consent_tor sends the user to \"$word\", but Advanced Settings lists no Tor item: $text",
            )
        }
    }
}
