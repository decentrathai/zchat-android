package co.electriccoin.zcash.ui.screen.about

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2-7 (b), plan step p2-213417e3d731 (PRS-007, PRS-008, TX3e1-082): the About screen says what is true.
 *
 * At bdaa5103b res/ui/about says "No AI. No Clouds. No Tracking." and "we collect nothing" although the app
 * has an AI tab whose requests go through ZChat's server (AiApiClient, api.zsend.xyz) to an outside AI
 * service; it says "We don't have servers" although ZChat runs a Nostr relay (NostrRelayPool) and that AI
 * server; and it calls ZCHAT "Quantum-Ready", "designed with post-quantum security in mind" and "built on
 * quantum-ready Orchard" although Orchard is sealed and new funds use Ironwood.
 *
 * Must hold, in English and Spanish: none of those phrases; the AI tab is described as optional and going
 * through ZChat's server; new funds are on Ironwood; Spanish has every About key.
 */
class AboutScreenClaimsTest {
    private val aboutRes: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res/ui/about").isDirectory) dir = dir.parentFile
        File(assertNotNull(dir, "repo root not found"), "ui-lib/src/main/res/ui/about")
    }

    @Test
    fun english_about_text_makes_none_of_the_false_claims() {
        assertNoPhrase(strings("values"), FALSE_EN, "values")
    }

    @Test
    fun spanish_about_text_makes_none_of_the_false_claims() {
        assertNoPhrase(strings("values-es"), FALSE_ES, "values-es")
    }

    @Test
    fun the_ai_tab_is_described_as_optional_and_going_through_zchats_server() {
        val en = strings("values")
        val es = strings("values-es")
        val enAi = "${en["about_privacy_no_ai"]} ${en["about_privacy_no_ai_desc"]}".lowercase()
        assertTrue("optional" in enAi && "zchat's server" in enAi, "English AI line: $enAi")
        val esAi = "${es["about_privacy_no_ai"]} ${es["about_privacy_no_ai_desc"]}".lowercase()
        assertTrue("opcional" in esAi && "servidor de zchat" in esAi, "Spanish AI line: $esAi")
    }

    @Test
    fun new_funds_are_described_as_ironwood() {
        for (dir in listOf("values", "values-es")) {
            val d = strings(dir)["about_quantum_description"] ?: fail("$dir has no about_quantum_description")
            assertTrue("ironwood" in d.lowercase(), "$dir: $d")
        }
    }

    @Test
    fun spanish_has_every_about_string_translated() {
        val en = strings("values")
        val es = strings("values-es")
        assertEquals(emptySet(), en.keys - es.keys, "values-es is missing About keys")
        for (key in CHANGED_KEYS) {
            assertNotEquals(en[key]?.trim(), es[key]?.trim(), "$key is English in the Spanish file")
        }
    }

    private fun assertNoPhrase(
        strings: Map<String, String>,
        phrases: List<String>,
        dir: String
    ) {
        val hits =
            strings.flatMap { (key, text) ->
                phrases.filter { it in text.lowercase() }.map { "$key: \"$it\"" }
            }
        assertEquals(emptyList(), hits, "$dir/strings.xml still makes these claims")
    }

    private fun strings(qualifier: String): Map<String, String> {
        val f = File(aboutRes, "$qualifier/strings.xml")
        assertTrue(f.isFile, "missing ${f.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(f)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
    }

    private companion object {
        val FALSE_EN =
            listOf(
                "no ai",
                "collect nothing",
                "quantum-ready",
                "post-quantum security",
                "quantum-ready orchard",
                "we don't have servers",
                "knows nothing about you",
            )
        val FALSE_ES =
            listOf(
                "sin ia",
                "no recopilamos nada",
                "privacidad resistente a la cuántica",
                "seguridad post-cuántica",
                "orchard resistente a la cuántica",
                "no tenemos servidores",
                "no sabe nada sobre ti",
            )
        val CHANGED_KEYS =
            listOf(
                "about_privacy_no_servers",
                "about_privacy_no_servers_desc",
                "about_privacy_no_ai",
                "about_privacy_no_ai_desc",
                "about_privacy_footer",
                "about_quantum_title",
                "about_quantum_description",
                "about_quantum_feature_2",
                "about_quantum_feature_3",
                "about_quantum_badge_tooltip",
            )
    }
}
