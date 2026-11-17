package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P10 (PHONE_TRIAGE ES-8, the two pointers) — two strings new in 2.13.0 send the user to "Ajustes avanzados",
 * but the item on the More screen is called "Configuración Avanzada" (settings_advanced_settings), so a Spanish user
 * looks for a menu item that does not exist:
 * - move_old_balance_consent_tor (the Tor note before moving an old balance) — R8-fixup F9: no longer a pointer. The
 *   Tor item of Advanced Settings is hidden, so the note names no menu at all (MoveOldBalanceTorPointerIsTrueTest);
 * - restore_bd_error_different_wallet_on_device (the restore refusal that says where to save the recovery phrase).
 *
 * Wanted: each pointer contains the exact value of settings_advanced_settings in its language.
 */
class AdvancedSettingsPointerTest {
    private val pointers = listOf("restore_bd_error_different_wallet_on_device")

    @Test
    fun the_spanish_pointers_name_the_menu_item() {
        assertPointers("values-es")
        val es = ChatResourceText.strings("values-es")
        for (key in pointers) {
            assertTrue("Ajustes avanzados" !in es.getValue(key), "$key still says \"Ajustes avanzados\"")
        }
    }

    @Test
    fun the_english_pointers_name_the_menu_item() {
        assertPointers("values")
    }

    private fun assertPointers(dir: String) {
        val strings = ChatResourceText.strings(dir)
        val item = assertNotNull(strings["settings_advanced_settings"], "no settings_advanced_settings in $dir")
        for (key in pointers) {
            val text = assertNotNull(strings[key], "no $key in $dir")
            assertTrue(item in text, "$dir $key does not name the menu item \"$item\": $text")
        }
    }
}
