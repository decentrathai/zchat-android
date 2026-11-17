package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin: every sealed-Orchard refusal tells the user to "migrate to Ironwood", and the app has no
 * migration. There is no button, screen or flow that does it, so the instruction cannot be followed.
 *
 * 2.13.0 adds the "Old balance" card on the Wallet tab (sweep spec §1.1). It is shown whenever Orchard
 * holds anything, including balances too small to move, so a refusal that points to the card is true in
 * every state. The refusals must name the card instead of a migration; the chat's no-pending
 * NeedsIronwoodMigration message becomes neutral, because the chat dialog adds its own action line.
 *
 * The chat error classifier (ZchatErrorMessage) keys on fixed phrases in these messages. Those phrases
 * must survive the rewording, or the toast path relabels one refusal as another; the guards below run
 * first and pass today.
 */
class SealedOrchardCopyNamesTheActionTest {
    private val fallback = "Couldn't send your message. Please try again."

    @Test
    fun sealed_orchard_refusals_point_to_the_old_balance_card() {
        val sealed = OrchardSealedException.ORCHARD_SEALED_MESSAGE
        val crossing = PublicCrossingException.PUBLIC_CROSSING_MESSAGE
        val confirming = OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE

        // Guards: the classifier phrases stay, and each message maps back to itself.
        assertTrue(sealed.contains(SEALED_PHRASE, ignoreCase = true), sealed)
        assertFalse(sealed.contains(CROSSING_PHRASE, ignoreCase = true), sealed)
        assertFalse(sealed.contains(CONFIRMING_PHRASE, ignoreCase = true), sealed)
        assertTrue(crossing.contains(CROSSING_PHRASE, ignoreCase = true), crossing)
        assertTrue(confirming.contains(CONFIRMING_PHRASE, ignoreCase = true), confirming)
        listOf(sealed, crossing, confirming).forEach { assertEquals(it, it.toZchatUserMessage(fallback)) }
        assertEquals(sealed, OrchardSealedException(Zatoshi(139_000)).toZchatUserMessage(fallback))
        assertEquals(
            confirming,
            OrchardSealedException(Zatoshi(139_000), ironwoodPending = Zatoshi(1_000)).toZchatUserMessage(fallback),
        )
        assertFalse(confirming.contains(OLD_BALANCE, ignoreCase = true), "waiting is the answer here: $confirming")

        assertTrue(sealed.contains(OLD_BALANCE, ignoreCase = true), "must name the \"Old balance\" card: $sealed")
        assertTrue(crossing.contains(OLD_BALANCE, ignoreCase = true), "must name the \"Old balance\" card: $crossing")
        assertTrue(
            Regex("public", RegexOption.IGNORE_CASE).findAll(crossing).count() >= 2,
            "both the refused send and the move make an amount public; say so for each: $crossing",
        )
    }

    @Test
    fun send_and_shortfall_strings_point_to_the_old_balance_card() {
        val sendEnglish = strings("send", "values")
        val sendSpanish = strings("send", "values-es")
        val shortfallEnglish = strings("insufficient_funds", "values")
        val shortfallSpanish = strings("insufficient_funds", "values-es")

        SEND_KEYS.forEach { key ->
            sendEnglish.requireContains(key, OLD_BALANCE)
            sendSpanish.requireContains(key, OLD_BALANCE_ES)
        }
        shortfallEnglish.requireContains(SHORTFALL_KEY, OLD_BALANCE)
        shortfallSpanish.requireContains(SHORTFALL_KEY, OLD_BALANCE_ES)
    }

    @Test
    fun the_stranded_chat_line_names_the_card_and_the_chat_refusal_is_neutral() {
        // R5-4 (round 4 report 3): the line is a string resource now, because it had to reach a Spanish
        // phone in Spanish. The anchor is the resource at the call site, and the rule is checked on BOTH
        // values — the Spanish one has to name the card as well, which the literal never covered.
        val detail = source("src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        if (!STRANDED_LINE.containsMatchIn(detail)) {
            fail("ChatDetailView.kt no longer has an `isStranded -> chat_privacy_chip_stranded` privacy line")
        }
        val stranded = chatSurfaceStrings("values").getValue(STRANDED_CHIP)
        val strandedEs = chatSurfaceStrings("values-es").getValue(STRANDED_CHIP)
        assertTrue(stranded.contains(OLD_BALANCE, ignoreCase = true), "must name the \"Old balance\" card: $stranded")
        assertTrue(
            strandedEs.contains(OLD_BALANCE_ES, ignoreCase = true),
            "must name the \"Saldo antiguo\" card: $strandedEs",
        )

        val chat = SendMessageState.NeedsIronwoodMigration(Zatoshi(139_000)).message
        CALLS_TO_ACTION.forEach { phrase ->
            assertFalse(
                chat.contains(phrase, ignoreCase = true),
                "the no-pending chat refusal must be neutral (the dialog adds the action); found \"$phrase\": $chat",
            )
        }
    }

    /**
     * Every user-facing string in ui-lib: Kotlin string literals outside comments and log calls, and every
     * `<string>` resource. None may tell the user to migrate: the only way out is the "Old balance" card.
     */
    @Test
    fun no_user_facing_text_offers_a_migration_the_app_does_not_have() {
        val main = uiLibDir.resolve("src/main")
        val kotlinHits =
            main
                .resolve("java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filterNot { (_, line) -> line.isCommentOrLog() }
                        .flatMap { (index, line) ->
                            STRING_LITERAL
                                .findAll(line)
                                .map { it.value }
                                .filter { MIGRATE.containsMatchIn(it) }
                                .map { "${file.relativeTo(main)}:${index + 1}: $it" }
                        }
                }.toList()
        val resourceHits =
            main
                .resolve("res")
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.isValuesResource() }
                .flatMap { file ->
                    parseStrings(file)
                        .filterValues { MIGRATE.containsMatchIn(it) }
                        .map { (name, text) -> "${file.relativeTo(main)}: $name = $text" }
                }.toList()

        val hits = kotlinHits + resourceHits
        assertTrue(hits.isEmpty(), "user-facing text still offers a migration:\n${hits.joinToString("\n")}")
    }

    private fun File.isValuesResource(): Boolean = parentFile?.name?.startsWith("values") == true

    private fun String.isCommentOrLog(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("//") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("/*") ||
            contains("Log.") ||
            contains("Twig.")
    }

    private fun Map<String, String>.requireContains(
        key: String,
        needle: String
    ) {
        val text = this[key] ?: fail("no string named $key")
        assertTrue(text.contains(needle, ignoreCase = true), "$key must name the card (\"$needle\"): \"$text\"")
    }

    /** The chat surfaces R5-4 moved out of ChatDetailView.kt, which live beside the feature strings. */
    private fun chatSurfaceStrings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/common/$qualifier/chat_surface_strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        return parseStrings(file)
    }

    private fun strings(
        feature: String,
        qualifier: String
    ): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/$feature/$qualifier/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        return parseStrings(file)
    }

    private fun parseStrings(file: File): Map<String, String> {
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

    private fun source(path: String): String {
        val file = uiLibDir.resolve(path)
        assertTrue(file.isFile, "missing ${file.path}")
        return file.readText()
    }

    private companion object {
        const val OLD_BALANCE = "Old balance"
        const val OLD_BALANCE_ES = "Saldo antiguo"

        /** The phrases ZchatErrorMessage.classifyZchatErrorText keys on. */
        const val SEALED_PHRASE = "sealed Orchard pool"
        const val CROSSING_PHRASE = "funded from the sealed Orchard pool"
        const val CONFIRMING_PHRASE = "Ironwood funds are still confirming"

        val SEND_KEYS = listOf("send_dialog_error_reason_orchard_sealed", "send_dialog_error_reason_public_crossing")
        const val SHORTFALL_KEY = "insufficient_funds_orchard_description"

        val CALLS_TO_ACTION = listOf("Migrate", "to keep messaging", "Old balance", "Move your")

        const val STRANDED_CHIP = "chat_privacy_chip_stranded"
        val STRANDED_LINE = Regex("""isStranded\s*->\s*stringResource\(R\.string\.chat_privacy_chip_stranded\)""")
        val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")

        /** "migrate" as a word (not "migrating" / "migration"), and the Spanish imperative "migra". */
        val MIGRATE = Regex("""\b(migrate|migra)\b""", RegexOption.IGNORE_CASE)

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/send").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
