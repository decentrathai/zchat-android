package co.electriccoin.zcash.ui.screen.insufficientfunds

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1591 (QA2 Phase 0 UI/UX read, row 1; skills app-texts rule 2, money-path rule 1): the insufficient-funds sheet a
 * chat send opens (InsufficientFundsContext.MESSAGE, chosen by CreateChunkedMessageProposalUseCase for a dust memo)
 * reads "Please Wait — Your previous message is still being shielded on the blockchain. Please wait a few more
 * seconds…" in English, whatever the shortfall, and Spanish says "Fondos insuficientes … reduce el monto", a
 * different message.
 *
 * InsufficientFundsVM's own contract: the sheet never says a transaction is pending unless the shortfall is
 * StillConfirming, which has its own title and body. So the MESSAGE texts (the ones the VM shows when the shortfall
 * is not StillConfirming) must:
 * - not invent a pending or shielding message, nor promise seconds (deposits need 10 confirmations, change 3);
 * - say the wallet does not have enough ZEC that can be sent, in English and in Spanish alike.
 *
 * The keys are read from InsufficientFundsVM.kt, so a renamed key is still checked; resources are read from the XML.
 */
class MessageShortfallSheetSaysNotEnoughTest {
    private val vm: String by lazy {
        uiLibDir.resolve("src/main/java/co/electriccoin/zcash/ui/screen/insufficientfunds/InsufficientFundsVM.kt").readText()
    }
    private val en by lazy { strings("values") }
    private val es by lazy { strings("values-es") }

    @Test
    fun the_message_title_and_body_say_the_wallet_lacks_sendable_zec_in_english_and_spanish() {
        val (titleKey, bodyKey) = messageKeys()
        val enTitle = en[titleKey] ?: fail("values has no $titleKey")
        val esTitle = es[titleKey] ?: fail("values-es has no $titleKey")
        val enBody = en[bodyKey] ?: fail("values has no $bodyKey")
        val esBody = es[bodyKey] ?: fail("values-es has no $bodyKey")

        assertTrue(
            Regex("""(?i)\bnot enough\b""").containsMatchIn(enTitle),
            "the MESSAGE sheet title must say there is not enough to send: \"$enTitle\"",
        )
        assertTrue(
            Regex("""(?i)\b(no alcanza|falta|insuficiente)""").containsMatchIn(esTitle),
            "the Spanish MESSAGE sheet title must say there is not enough to send: \"$esTitle\"",
        )
        assertTrue(
            Regex("""(?i)\benough\b""").containsMatchIn(enBody) &&
                Regex("""(?i)(can be sent|sendable|spendable)""").containsMatchIn(enBody),
            "the MESSAGE sheet body must say the wallet lacks enough ZEC that can be sent: \"$enBody\"",
        )
        assertTrue(
            Regex("""(?i)suficiente""").containsMatchIn(esBody) &&
                Regex("""(?i)(se pueda enviar|se puede enviar|disponible para enviar)""").containsMatchIn(esBody),
            "the Spanish MESSAGE sheet body must say the same as the English (not enough ZEC that can be sent): \"$esBody\"",
        )
        assertNotEquals(enTitle, esTitle, "$titleKey: the Spanish file holds the English title")
        assertNotEquals(enBody, esBody, "$bodyKey: the Spanish file holds the English body")
    }

    @Test
    fun the_message_texts_invent_no_pending_message_and_promise_no_seconds() {
        val (titleKey, bodyKey) = messageKeys()
        for (key in listOf(titleKey, bodyKey)) {
            val enText = en[key] ?: fail("values has no $key")
            val esText = es[key] ?: fail("values-es has no $key")
            for (claim in ENGLISH_PENDING_CLAIMS) {
                assertFalse(
                    claim.containsMatchIn(enText),
                    "$key claims a pending message or a wait the code never checked (${claim.pattern}): \"$enText\"",
                )
            }
            for (claim in SPANISH_PENDING_CLAIMS) {
                assertFalse(
                    claim.containsMatchIn(esText),
                    "Spanish $key claims a pending message or a wait the code never checked (${claim.pattern}): \"$esText\"",
                )
            }
        }
    }

    @Test
    fun only_the_still_confirming_case_has_its_own_confirming_title() {
        val titleFn = block(vm, "private fun title(")
        assertTrue(
            Regex("""is\s+ShortfallReason\.StillConfirming\)\s*\{\s*stringRes\(R\.string\.insufficient_funds_confirming_title\)""")
                .containsMatchIn(titleFn),
            "fixture: StillConfirming no longer picks insufficient_funds_confirming_title first:\n$titleFn",
        )
        val (titleKey, _) = messageKeys()
        assertNotEquals("insufficient_funds_confirming_title", titleKey, "the MESSAGE title must not be the confirming one")
    }

    /** The (title, body) keys InsufficientFundsVM shows in the MESSAGE context when the shortfall is not StillConfirming. */
    private fun messageKeys(): Pair<String, String> {
        val pattern = Regex("""InsufficientFundsContext\.MESSAGE\s*->\s*stringRes\(R\.string\.(\w+)\)""")
        val title = pattern.find(block(vm, "private fun title("))?.groupValues?.get(1)
        val body = pattern.find(block(vm, "private fun contextDescription("))?.groupValues?.get(1)
        if (title == null || body == null) fail("fixture: InsufficientFundsVM no longer maps MESSAGE to a title and a body")
        assertEquals(2, setOf(title, body).size, "fixture: the MESSAGE title and body are one key")
        return title to body
    }

    private fun block(
        source: String,
        anchor: String,
    ): String {
        val start = source.indexOf(anchor)
        if (start < 0) fail("fixture: \"$anchor\" not found in InsufficientFundsVM.kt")
        val next = source.indexOf("private fun ", start + anchor.length).let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/insufficient_funds/$qualifier/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        val ENGLISH_PENDING_CLAIMS =
            listOf(
                Regex("""(?i)please wait"""),
                Regex("""(?i)previous message"""),
                Regex("""(?i)still being"""),
                Regex("""(?i)being shielded"""),
                Regex("""(?i)\bseconds?\b"""),
            )

        val SPANISH_PENDING_CLAIMS =
            listOf(
                Regex("""(?i)espera"""),
                Regex("""(?i)mensaje anterior"""),
                Regex("""(?i)\bsegundos?\b"""),
            )

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/insufficient_funds").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
