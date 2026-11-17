package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F11 (fix-all 2.13.0, train T3; qs-downgrade REVIEW breaks 2 and 7) — what the Quantum Shield dialogs say.
 *
 * - Break 7: a rescanned QR that does not match the key this phone stored gets its own dialog, and that dialog is
 *   neutral. Without a signed claim from the other phone (D3), this phone cannot tell another chat's QR (or an old QR
 *   of this contact) from a one-side reset, so the text names both, says nothing was changed, and never says or hints
 *   that someone changed or tampered with the code: a user who scans the wrong contact's QR must not be told they are
 *   under attack. It never claims the phones share a key.
 * - Break 2: the 6-digit code can be ground by someone who reads both QR codes (no commitment in the QR exchange), so it
 *   protects only QR codes scanned in person, straight from the other phone's screen. The code dialog and the setup
 *   dialog say so, and say that QR codes sent as pictures or in messages are not protected.
 * - The code dialog and the setup dialog read in the phone's language: their texts are resources in English and
 *   Spanish (the Spanish is an agent draft for the native-speaker pass), not English literals in AndroidChat.
 * - A mismatch is not an "Invalid QR code": AndroidChat shows the problem dialog instead of that toast.
 */
class QuantumShieldProblemStringsTest {
    private val newKeys =
        listOf(
            "quantum_shield_not_this_chat_title",
            "quantum_shield_not_this_chat_body",
            "quantum_shield_code_title",
            "quantum_shield_code_body",
            "quantum_shield_code_matches",
            "quantum_shield_setup_title",
            "quantum_shield_setup_intro",
            "quantum_shield_setup_show",
            "quantum_shield_setup_in_person",
            "quantum_shield_setup_private",
            "quantum_shield_setup_auto_on",
            "quantum_shield_setup_done",
            "quantum_shield_setup_scan",
        )

    @Test
    fun every_new_text_exists_in_english_and_spanish() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in newKeys) {
            assertTrue("EN lacks $key", !en[key].isNullOrBlank())
            assertTrue("ES lacks $key", !es[key].isNullOrBlank())
        }
    }

    @Test
    fun the_mismatch_text_is_neutral_and_names_both_causes() {
        val en = text("values", "quantum_shield_not_this_chat_title") + " " + text("values", "quantum_shield_not_this_chat_body")
        val es = text("values-es", "quantum_shield_not_this_chat_title") + " " + text("values-es", "quantum_shield_not_this_chat_body")

        for (blame in listOf("someone", "somebody", "tamper", "attack", "hack", "changed the code", "changed on the way", "intercept")) {
            assertFalse("EN mismatch text must not blame anyone ('$blame'): $en", blame in en.lowercase())
        }
        for (blame in listOf("alguien", "manipul", "ataque", "atacante", "modificado", "interceptado", "por el camino")) {
            assertFalse("ES mismatch text must not blame anyone ('$blame'): $es", blame in es.lowercase())
        }
        assertFalse("EN must not claim the phones share a key: $en", "same key" in en.lowercase())
        assertFalse("ES must not claim the phones share a key: $es", "misma clave" in es.lowercase())

        assertTrue("EN says nothing was changed: $en", "Nothing was changed" in en)
        assertTrue("EN names another chat's code: $en", "another chat" in en)
        assertTrue("EN names a one-side reset (different keys): $en", "different keys" in en)
        assertTrue("EN says how to fix it, in person: $en", "set it up again, in person" in en)
        assertTrue("ES says nothing was changed: $es", "No se ha cambiado nada" in es)
        assertTrue("ES names another chat's code: $es", "otro chat" in es)
        assertTrue("ES names a one-side reset: $es", "claves distintas" in es)
        assertTrue("ES says how to fix it, in person: $es", "en persona" in es)
    }

    @Test
    fun the_code_and_setup_texts_say_the_code_protects_qr_codes_scanned_in_person_only() {
        for (key in listOf("quantum_shield_code_body", "quantum_shield_setup_in_person")) {
            val en = text("values", key)
            val es = text("values-es", key)
            assertTrue("EN $key names in person: $en", "in person" in en)
            assertTrue("EN $key says pictures and messages are not protected: $en", "picture" in en && "message" in en)
            assertTrue("ES $key names in person: $es", "en persona" in es)
            assertTrue("ES $key says pictures and messages are not protected: $es", "imagen" in es && "mensaje" in es)
        }
    }

    @Test
    fun android_chat_reads_the_texts_from_resources_and_shows_the_mismatch_dialog() {
        val src = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt").readText()
        for (key in newKeys) {
            assertTrue("AndroidChat uses R.string.$key", "R.string.$key" in src)
        }
        for (literal in listOf(
            "\"Confirm Security Code\"",
            "Confirm this code matches on both devices",
            "\"Show this QR code to your contact. They scan it, then show you theirs.\"",
            "\"Scan Peer's QR\"",
            "\"It matches\"",
        )) {
            assertFalse("AndroidChat still has the English literal $literal", literal in src)
        }
        val start = src.indexOf("viewModel.completeQuantumShield(peerAddress, zcpskPayload)")
        assertTrue("the scan callback calls completeQuantumShield", start >= 0)
        // T3b-2: the toast is the resource quantum_shield_invalid_code ("Invalid QR code" in English), not a literal.
        val end = src.indexOf("R.string.quantum_shield_invalid_code", start)
        assertTrue("the Invalid QR code toast is still there for unreadable codes", end > start)
        assertTrue(
            "a mismatch (the confirmation carries a problem) must not also toast Invalid QR code",
            ".problem" in src.substring(start, end),
        )
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        return dir ?: error("repo root not found above the test working dir")
    }

    private fun strings(folder: String): Map<String, String> {
        val xml = File(repoRoot(), "ui-lib/src/main/res/ui/common/$folder/quantum_shield_strings.xml").readText()
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private fun text(folder: String, key: String): String = strings(folder)[key] ?: error("$key missing in $folder")
}
