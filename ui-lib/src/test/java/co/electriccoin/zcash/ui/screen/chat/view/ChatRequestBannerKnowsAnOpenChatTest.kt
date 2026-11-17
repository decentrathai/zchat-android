package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TX-2 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §5 with §9 row 9). On the Fold after an uninstall and restore
 * (fold run U2), a chat that had been accepted over the relays (Open) on 2.12.4, with messages both ways, showed
 * "New contact — tap Accept to connect" / "This address started a secure chat with you…" once the catch-up found the
 * peer's KEX. The R2-2 (a) hold is right (nothing is paid before Accept): an accepted Open chat whose peer is not a
 * saved contact is still "first contact" (`isFirstContact`). Only the words are wrong: the user already writes to
 * this address.
 *
 * The fix keeps the hold and the Accept path and changes the text only: when the chat has an outgoing message that
 * is not a system note (`ChatRequestBanner.isKnownChat(conversation.messages)`; a stranger cannot make us send one),
 * the banner uses `chat_request_banner_short_known` and `chat_request_banner_count_known`, with today's placeholders
 * (the count and the quote) and no name: a KEX's sender address is claimed and self-signed, so the text must not
 * vouch for a sender; it points to the safety number instead. A stranger's request keeps today's text.
 *
 * Guard (4) passes today and pins the money path (written against KI-24's final `handleKEXMessage` at `7483ff1ab`):
 * `isFirstContact`, the hold line, the Accept wiring and `acceptInboundKexRequest` stay as they are.
 */
class ChatRequestBannerKnowsAnOpenChatTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun src(path: String): String =
        stripComments(File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText())

    private val detailView by lazy { src("screen/chat/view/ChatDetailView.kt") }
    private val androidChat by lazy { src("screen/chat/AndroidChat.kt") }
    private val viewModel by lazy { src("screen/chat/viewmodel/ChatViewModel.kt") }

    /** ChatDetailView's chat-request banner: from `if (awaitingChatAccept` to the Accept button's action. */
    private val bannerBlock by lazy {
        val start = detailView.indexOf("if (awaitingChatAccept")
        assertTrue(start >= 0, "ChatDetailView has no `if (awaitingChatAccept` banner")
        val end = detailView.indexOf("onClick = onAcceptChatRequest", start)
        assertTrue(end > start, "the chat-request banner has no Accept button calling onAcceptChatRequest")
        detailView.substring(start, end + "onClick = onAcceptChatRequest".length)
    }

    // (1) ChatDetailView picks the text by ChatRequestBanner.isKnownChat.

    @Test
    fun the_banner_picks_the_known_chat_text_by_an_outgoing_message() {
        assertTrue(
            "ChatRequestBanner.isKnownChat(conversation.messages, chatRequestFromSavedContact)" in bannerBlock,
            "the chat-request banner must ask ChatRequestBanner.isKnownChat(conversation.messages, …) whether we already " +
                "write to this address (ZC-822: or it is a saved contact)",
        )
        assertTrue("R.string.chat_request_banner_short_known" in bannerBlock, "a known chat must use chat_request_banner_short_known")
        assertTrue(
            Regex("""R\.string\.chat_request_banner_short\b(?!_)""").containsMatchIn(bannerBlock),
            "a stranger's request must keep chat_request_banner_short",
        )
        assertTrue("R.plurals.chat_request_banner_count_known" in bannerBlock, "a known chat must use chat_request_banner_count_known")
        assertTrue(
            Regex("""R\.plurals\.chat_request_banner_count\b(?!_)""").containsMatchIn(bannerBlock),
            "a stranger's request must keep chat_request_banner_count",
        )
        // The same count and quote as today, and no name argument.
        val args =
            Regex(
                """chatRequestTransactions,\s*chatRequestTransactions,\s*""" +
                    """PaidActionCopy\.zec\(handshakeSpendQuote\(chatRequestTransactions\),""" +
                    """\s*rememberDesiredFormatLocale\(\)\),?\s*\)""",
            )
        assertEquals(1, args.findAll(bannerBlock).count(), "the plural takes (count, count, quote) once, with no name argument")
        assertFalse("displayName" in bannerBlock, "the chat-request banner must not name the sender (a KEX does not prove it)")
    }

    // (2) and (3) The strings.

    @Test
    fun the_known_chat_short_text_has_english_and_spanish_and_names_no_sender() {
        val en = assertNotNull(strings("values")[SHORT_KNOWN], "no English $SHORT_KNOWN")
        val es = assertNotNull(strings("values-es")[SHORT_KNOWN], "no Spanish $SHORT_KNOWN")
        assertNotEquals(en, es, "$SHORT_KNOWN: the Spanish text is still English")
        for (text in listOf(en, es)) {
            assertEquals(emptySet(), placeholders(text), "$SHORT_KNOWN takes no placeholder (like chat_request_banner_short): $text")
            assertNoStrangerWords(SHORT_KNOWN, text)
        }
    }

    @Test
    fun the_known_chat_plural_has_todays_placeholders_and_points_to_the_safety_number() {
        for ((dir, safety, unpaid) in listOf(
            Triple("values", "safety number", "Nothing has been paid"),
            Triple("values-es", "número de seguridad", "No se ha pagado nada"),
        )) {
            val today = assertNotNull(plurals(dir)[COUNT], "$dir: no $COUNT")
            val known = assertNotNull(plurals(dir)[COUNT_KNOWN], "$dir: no $COUNT_KNOWN")
            assertEquals(today.keys, known.keys, "$dir: $COUNT_KNOWN must have the same quantities as $COUNT")
            for ((quantity, text) in known) {
                assertEquals(
                    placeholders(today.getValue(quantity)),
                    placeholders(text),
                    "$dir $COUNT_KNOWN[$quantity]: the placeholders must be today's (%1\$d the count, %2\$s the quote), no name",
                )
                assertNoStrangerWords("$dir $COUNT_KNOWN[$quantity]", text)
                assertTrue(safety in text, "$dir $COUNT_KNOWN[$quantity] must point to the $safety: $text")
                assertTrue(unpaid in text, "$dir $COUNT_KNOWN[$quantity] must say nothing has been paid: $text")
            }
        }
        val en = plurals("values").getValue(COUNT_KNOWN)
        val es = plurals("values-es").getValue(COUNT_KNOWN)
        for (quantity in en.keys.intersect(es.keys)) {
            assertNotEquals(en[quantity], es[quantity], "$COUNT_KNOWN[$quantity]: the Spanish text is still English")
        }
    }

    // (4) The money guard: passes today, and must keep passing.

    @Test
    fun the_accept_hold_and_the_accept_path_are_unchanged() {
        val first = functionBody(viewModel, "isFirstContact", from = 0)
        assertEquals(
            "=zchatPreferences.getE2EOurPublicKey(peerAddress)==null&&!zchatPreferences.isE2EEnabled(peerAddress)&&" +
                "!contactBook.hasContact(peerAddress)",
            first.filterNot { it.isWhitespace() },
            "isFirstContact must stay: no key of ours, E2E never on, not a saved contact",
        )

        // ZC-545: the store part of handleKEXMessage (the hold, then the store) is adoptKeyOffer; the same lines, there.
        val kex = functionBody(viewModel, "adoptKeyOffer", from = viewModel.lastIndexOf("fun adoptKeyOffer("))
        // W-62 (ZC-599): the hold also covers the awaited contact's key exchange; the line names both.
        val hold = kex.indexOf("if ((firstContact || awaited) && zchatPreferences.getSentKexAckPubkey(senderAddress) != peerPublicKey) {")
        assertTrue(hold >= 0, "handleKEXMessage's Accept hold line changed")
        val save = kex.indexOf("zchatPreferences.setInboundKexAwaitingAccept(senderAddress, peerPublicKey)", hold)
        // ZC-634 (part B): the key's one write, with its claim mark, in one commit of the key store.
        val store = kex.indexOf("zchatPreferences.storePeerKey(senderAddress, peerPublicKey, claim", hold)
        assertTrue(save > hold, "the hold must still be saved with setInboundKexAwaitingAccept(senderAddress, peerPublicKey)")
        assertTrue(store > save, "the hold must still be saved before the peer's key is stored")

        assertTrue(
            // ZC-693: the screen keeps no accepted state of its own; the banner is the hold, until the answer is out.
            Regex("""awaitingChatAccept\s*=\s*viewModel\.isInboundKexAwaitingAccept\(peerAddress\)""")
                .containsMatchIn(androidChat),
            "AndroidChat must still show the banner exactly while the hold is set",
        )
        val accept = Regex("""onAcceptChatRequest\s*=\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(androidChat)
        val acceptBody = assertNotNull(accept, "AndroidChat passes no onAcceptChatRequest").groupValues[1]
        assertTrue("viewModel.acceptInboundKexRequest(peerAddress)" in acceptBody, "Accept must still call acceptInboundKexRequest")

        val acceptFun = functionBody(viewModel, "acceptInboundKexRequest", from = 0)
        assertTrue("zchatPreferences.getInboundKexAwaitingAccept(peerAddress) ?: return@launch" in acceptFun, "Accept pays only a held KEX")
        assertTrue("payKexAckOnce(" in acceptFun, "Accept must still pay through payKexAckOnce (at most one KEXACK per key)")
    }

    @Test
    fun a_strangers_request_keeps_todays_text() {
        assertEquals("New contact — tap Accept to connect", strings("values")["chat_request_banner_short"])
        assertEquals("Contacto nuevo — toca Aceptar para conectar", strings("values-es")["chat_request_banner_short"])
        assertTrue(plurals("values").getValue(COUNT).values.all { it.startsWith("This address started a secure chat with you.") })
        assertTrue(plurals("values-es").getValue(COUNT).values.all { it.startsWith("Esta dirección inició un chat seguro contigo.") })
    }

    private fun assertNoStrangerWords(
        what: String,
        text: String,
    ) {
        for (word in STRANGER_WORDS) assertFalse(word in text, "$what says \"$word\", the stranger wording: $text")
    }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]|%[sd]""").findAll(text).map { it.value }.toSet()

    private fun xml(dir: String): String = File(repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_money_strings.xml").readText()

    private fun unescape(text: String) = text.replace("\\'", "'").replace("\\\"", "\"").trim()

    private fun strings(dir: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml(dir))
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    private fun plurals(dir: String): Map<String, Map<String, String>> =
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml(dir))
            .associate { p ->
                p.groupValues[1] to
                    Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(p.groupValues[2])
                        .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            }

    /** The body of `fun <name>(` found at or after [from], from its first `{` or `=` to the matching end. */
    private fun functionBody(
        code: String,
        name: String,
        from: Int,
    ): String {
        val start = Regex("""fun $name\(""").find(code, maxOf(from, 0))?.range?.first ?: fail("ChatViewModel has no fun $name(")
        var i = code.indexOf('(', start)
        var depth = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        val open = code.indexOf('{', i)
        val eq = code.indexOf('=', i)
        if (eq in 0 until open) {
            val end = Regex("""\n\s*\n""").find(code, eq)?.range?.first ?: code.length
            return code.substring(eq, end)
        }
        depth = 0
        var j = open
        while (j < code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, j + 1)
                }
            }
            j++
        }
        return code.substring(open)
    }

    /** Line and block comments out, string literals kept whole. */
    private fun stripComments(code: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    out.append(code, i, end)
                    i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '"' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    val end = minOf(j + 1, code.length)
                    out.append(code, i, end)
                    i = end
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '\'' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    val end = minOf(j + 1, code.length)
                    out.append(code, i, end)
                    i = end
                }
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    i = end
                }
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        const val SHORT_KNOWN = "chat_request_banner_short_known"
        const val COUNT = "chat_request_banner_count"
        const val COUNT_KNOWN = "chat_request_banner_count_known"
        val STRANGER_WORDS = listOf("New contact", "Contacto nuevo", "This address started", "Esta dirección inició")
    }
}
