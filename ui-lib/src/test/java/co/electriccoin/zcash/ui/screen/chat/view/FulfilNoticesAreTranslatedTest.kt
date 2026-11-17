package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C4 (round 7 adversarial review of B2, item 6) — the notices a payment-request fulfil reports show in English
 * on a Spanish phone.
 *
 * `fulfilPaymentRequestToOutcome` reports "Payment may have been sent — check your transactions before paying
 * again" (a private constant in ChatViewModel), `fulfillPaymentRequest` reports "This request is already being
 * paid — check your transactions before paying again", a confirmed fulfil says "Payment sent", and a fulfil that
 * proved nothing was sent says "Failed to fulfill payment request" (the toZchatUserMessage fallback). The chat
 * screen shows every send notice through ChatSendCopy.localizedResource, which knows none of them, so the
 * English text is shown. B2 added a second path to the first one (a paid marker that did not reach the disk).
 *
 * Wanted: all of them are ChatSendCopy notices, with an English resource equal to the text and a Spanish one; and
 * the fulfil code reports no other hard-coded English notice.
 */
class FulfilNoticesAreTranslatedTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private val fulfilNotices =
        listOf(
            "Payment may have been sent — check your transactions before paying again",
            "This request is already being paid — check your transactions before paying again",
            "Payment sent",
            // C3 left the proven-nothing-sent fallback English ("Failed to fulfill payment request").
            "Payment not sent — nothing left your wallet. You can try again.",
        )

    @Test
    fun every_fulfil_notice_has_a_spanish_translation() {
        val en = allStrings("values")
        val es = allStrings("values-es")
        for (text in fulfilNotices) {
            val id = assertNotNull(ChatSendCopy.localizedResource(text), "a Spanish phone shows this in English: $text")
            val name = resourceName(id)
            assertEquals(text, en[name], "English resource $name must be the exact text the app reports")
            val spanish = assertNotNull(es[name], "Spanish resource $name missing")
            assertTrue(spanish.isNotBlank() && spanish != text, "Spanish resource $name is not translated")
        }
    }

    @Test
    fun the_fulfil_code_reports_no_other_hard_coded_english_notice() {
        val vm = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt").readText()
        val body = functionBody(vm, "fulfillPaymentRequest") + functionBody(vm, "fulfilPaymentRequestToOutcome")
        val literals =
            Regex("""SendMessageState\.(?:Error|Success)\(\s*"([^"]+)"""")
                .findAll(body)
                .map { it.groupValues[1] }
                .toList() +
                Regex("""sent\s*=\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList() +
                Regex("""toZchatUserMessage\(\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(literals.isEmpty(), "the fulfil still reports hard-coded English notices: $literals")
        assertTrue(
            "private const val PAYMENT_MAY_HAVE_BEEN_SENT" !in vm,
            "the may-have-been-sent notice is still a private English constant in ChatViewModel",
        )
    }

    // ---- helpers ----

    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val start = source.indexOf("fun $name(")
        assertTrue(start >= 0, "no function $name")
        val next =
            Regex("""\n    (?:@[A-Za-z(".]+[^\n]*\n    )*(?:private |internal |override )*(?:suspend )?fun [A-Za-z]""")
                .find(source, start + 1)
                ?.range
                ?.first ?: source.length
        return source.substring(start, next)
    }

    private fun resourceName(id: Int): String =
        assertNotNull(
            R.string::class.java.fields
                .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
                ?.name,
            "no string resource with id $id",
        )

    private fun allStrings(dir: String): Map<String, String> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList()
            }.fold(emptyMap()) { acc, f -> acc + parse(f.readText()) }

    private fun parse(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
            }
}
