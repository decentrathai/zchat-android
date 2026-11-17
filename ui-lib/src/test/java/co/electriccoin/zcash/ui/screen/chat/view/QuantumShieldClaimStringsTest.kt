package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D3-3 S4 (fix-all 2.13.0, train T5; t5-specs SPECS §2.5 "Strings"): the texts of the claim's refusal, rescan answer and
 * alerts, in English and Spanish (an agent draft for the native-speaker pass).
 *
 * - Every new key exists in both languages, with no placeholder (QuantumShieldConfirmationTest pins "none" for the file).
 * - Only `quantum_shield_keys_confirmed` says the two phones have the same key, and it keeps the honest limit: the check
 *   is only as strong as the chat's own encryption key, and it does not show that no one changed the QR codes
 *   (qs-downgrade REVIEW break 1: whoever replaced that key can forge the claim).
 * - The refusal names an old picture and another chat's code, not only tampering.
 * - No text states as a fact that someone changed a code: a sentence about a changed code says "may" / "does not show".
 */
class QuantumShieldClaimStringsTest {
    private val newKeys =
        listOf(
            "quantum_shield_qr_old_format_title",
            "quantum_shield_qr_old_format_body",
            "quantum_shield_set_up_again_title",
            "quantum_shield_set_up_again_body",
            "quantum_shield_keys_differ_title",
            "quantum_shield_keys_differ_body",
            "quantum_shield_downgraded_title",
            "quantum_shield_downgraded_body",
            "quantum_shield_keys_confirmed",
            "quantum_shield_turn_off_here",
        )

    @Test
    fun `every new text exists in English and Spanish with no placeholder`() {
        val en = strings("values")
        val es = strings("values-es")
        val placeholder = Regex("%(\\d+\\$)?[sdf]")
        for (key in newKeys) {
            val e = en[key]
            val s = es[key]
            assertTrue(!e.isNullOrBlank(), "EN lacks $key")
            assertTrue(!s.isNullOrBlank(), "ES lacks $key")
            assertFalse(placeholder.containsMatchIn(e), "EN $key has a placeholder")
            assertFalse(placeholder.containsMatchIn(s), "ES $key has a placeholder")
        }
        assertEquals(en.keys, es.keys, "the file has the same keys in both languages")
    }

    @Test
    fun `only keys_confirmed says the phones share a key, and it keeps the limit`() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in newKeys - "quantum_shield_keys_confirmed") {
            assertFalse("same" in en.getValue(key).lowercase(), "EN $key must not say the phones share anything: ${en[key]}")
            assertFalse("misma" in es.getValue(key).lowercase(), "ES $key must not say the phones share anything: ${es[key]}")
        }
        val confirmedEn = en.getValue("quantum_shield_keys_confirmed")
        val confirmedEs = es.getValue("quantum_shield_keys_confirmed")
        assertTrue("same Extra Security key" in confirmedEn, confirmedEn)
        assertTrue("only as strong as this chat's own encryption key" in confirmedEn, "EN keeps the limit: $confirmedEn")
        assertTrue("does not show that no one changed the QR codes" in confirmedEn, "EN keeps the limit: $confirmedEn")
        assertTrue("misma clave de seguridad extra" in confirmedEs, confirmedEs)
        assertTrue("tan fiable como la clave de cifrado de este chat" in confirmedEs, "ES keeps the limit: $confirmedEs")
        assertTrue("no demuestra que nadie haya cambiado los códigos QR" in confirmedEs, "ES keeps the limit: $confirmedEs")
    }

    @Test
    fun `the refusal names an old picture and another chat's code`() {
        val en = strings("values").getValue("quantum_shield_qr_old_format_body")
        val es = strings("values-es").getValue("quantum_shield_qr_old_format_body")
        assertTrue("old picture" in en && "another chat's code" in en, en)
        assertTrue("Nothing was set up" in en, "EN says nothing was set up: $en")
        assertTrue("in person" in en, en)
        assertTrue("imagen antigua" in es && "otro chat" in es, es)
        assertTrue("No se ha configurado nada" in es, es)
        assertTrue("en persona" in es, es)
    }

    @Test
    fun `no text states as a fact that someone changed a code`() {
        val changeEn = Regex("\\b(changed|tampered|modified|altered|hacked)\\b")
        val hedgeEn = Regex("\\b(may|might|does not show|no one)\\b", RegexOption.IGNORE_CASE)
        val changeEs = Regex("(cambiad|cambió|modific|manipul|alterad)")
        val hedgeEs = Regex("(puede|no demuestra|nadie)", RegexOption.IGNORE_CASE)
        for ((folder, change, hedge, code) in listOf(
            Check("values", changeEn, hedgeEn, Regex("\\b(code|codes|QR)\\b")),
            Check("values-es", changeEs, hedgeEs, Regex("(código|códigos|QR)")),
        )) {
            val texts = strings(folder)
            for (key in newKeys) {
                for (sentence in texts.getValue(key).split(Regex("(?<=[.!?])\\s+"))) {
                    if (change.containsMatchIn(sentence) && code.containsMatchIn(sentence)) {
                        assertTrue(hedge.containsMatchIn(sentence), "$folder $key states a changed code as a fact: \"$sentence\"")
                    }
                }
                for (blame in listOf("someone changed", "tamper", "attack", "alguien cambió", "ataque", "atacante")) {
                    assertFalse(blame in texts.getValue(key).lowercase(), "$folder $key blames: ${texts[key]}")
                }
            }
        }
    }

    @Test
    fun `the alert texts say what to do, in person`() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in listOf("quantum_shield_keys_differ_body", "quantum_shield_set_up_again_body", "quantum_shield_downgraded_body")) {
            assertTrue("Turn off here" in en.getValue(key) && "in person" in en.getValue(key), "EN $key: ${en[key]}")
            assertTrue("Desactivar aquí" in es.getValue(key) && "en persona" in es.getValue(key), "ES $key: ${es[key]}")
        }
        assertEquals("Turn off here", en["quantum_shield_turn_off_here"])
        assertEquals("Desactivar aquí", es["quantum_shield_turn_off_here"])
    }

    private data class Check(
        val folder: String,
        val change: Regex,
        val hedge: Regex,
        val code: Regex,
    )

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
}
