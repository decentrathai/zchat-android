package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SR-D / ZC-109 (fix-all 2.13.0, train T5b; photos-privacy SPEC §6 with its review changes, t5b SPECS §7 C-SRD-1..6):
 * the texts about what a Shielded chat sends through the relays, and about Tor and calls, say what the landed code does.
 *
 * At ee9ae69d3 (SR-A, SR-B1, SR-B2, SR-C1, SR-C2, ZC-249, D3-2, RELAY-HEALTH and TURN-CREDS landed):
 * - the Open note and its twin OPEN_DESCRIPTION say Shielded messages "don't pass through relays", while a Shielded chat
 *   still sends its chat key setup (ZBOOT, key rotation), a switch into Shielded (ZMODE) and call setup over the relays,
 *   and a contact on Tunnel or Open writes to us over them;
 * - the mode intro's Shielded row says "No voice/video" and the switch-request dialog says calls "turn off", while
 *   calls are placed in Shielded chats once the relay key is known (owner: keep them); both are English literals;
 * - More → Security: row 5 sends the user to a shield icon (the check is ⋮ → Verify safety number); row 6 is titled
 *   "Relays (Open and Tunnel)" and never says the always-on relay inbox serves Shielded users too; row 7 names photos
 *   only and says hosts see the IP address even with Tor on; row 8 says only that calls "may pass through ZChat's relay
 *   server" (Cloudflare's STUN server and ZChat's call relay see the IP address, a direct call shows it to the contact);
 *   row 11 says "switches" and "to Open" only; the Tor line says photo hosts don't use Tor (ZC-249: they do when Tor is
 *   on) and leaves out swaps, exchange rates, the AI tab and the update check;
 * - README says "Shielded mode is fully serverless"; the protocol doc says file hosts never use Tor, names relay.damus.io
 *   and says calls are not offered in Shielded chats; SECURITY.md names relay.damus.io (RELAY-HEALTH removed it) and a
 *   personal address as the security contact (owner W-20 item 12: one contact, contact@zsend.xyz, plus the GitHub
 *   advisory link).
 *
 * The English resources stay byte-identical to their Kotlin twins (UserFacingPrivacyClaimsTest pins those), Security
 * keeps 11 rows, and RELAY-HEALTH's two sentences stay (RelaySwapDisclosureTest).
 */
class ShieldedTextsSayWhatTouchesRelaysTest {
    // As getString returns them: "\n" in the XML is a line break.
    private val en by lazy { ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") } }
    private val es by lazy { ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") } }

    private val root: File get() = ChatResourceText.repoRoot

    private fun read(path: String): String = File(root, path).readText()

    /** Every values / values-es XML file of every module (raw text, XML escapes kept). */
    private fun rawResourceFiles(): List<File> =
        root
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                val res = File(module, "src/main/res")
                if (res.isDirectory) res.walkTopDown().toList() else emptyList()
            }.filter { it.isFile && it.extension == "xml" && it.parentFile?.name in setOf("values", "values-es") }

    /** A Kotlin file with adjacent string literals joined (`"a " +\n    "b"` reads "a b"), so a split sentence is found. */
    private fun joinedKotlin(path: String): String = read(path).replace(Regex("\"\\s*\\+\\s*\""), "")

    /** The source of top-level `fun [name](…)` up to its closing brace at column 0, or null. */
    private fun functionBody(
        src: String,
        name: String,
    ): String? {
        val at = src.indexOf("fun $name(").takeIf { it >= 0 } ?: return null
        val end = src.indexOf("\n}\n", at).takeIf { it >= 0 } ?: return null
        return src.substring(at, end + 2)
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    // ---- 1. keys: English and Spanish, same placeholders, Spanish translated ----

    @Test
    fun every_key_of_the_batch_has_english_and_spanish_with_the_same_placeholders() {
        for (key in KEYS) {
            val english = assertNotNull(en[key], "values has no $key")
            val spanish = assertNotNull(es[key], "values-es has no $key")
            assertNotEquals(english, spanish, "$key is English in the Spanish file")
            assertEquals(placeholders(english), placeholders(spanish), "$key: the Spanish placeholders differ from the English")
        }
    }

    // ---- 2. the false sentences are gone, with and without the XML apostrophe escape ----

    @Test
    fun the_false_sentences_are_gone_from_resources_kotlin_twins_and_docs() {
        val hits = mutableListOf<String>()
        for (file in rawResourceFiles()) {
            val raw = file.readText()
            val plain = raw.replace("\\'", "'")
            for (phrase in BANNED) {
                if (phrase in raw || phrase in plain || phrase.replace("'", "\\'") in raw) {
                    hits += "${file.relativeTo(root)}: \"$phrase\""
                }
            }
        }
        for (path in listOf(CONVERSATION_MODE_UI, PRIVACY_COPY)) {
            val src = joinedKotlin(path)
            for (phrase in BANNED) if (phrase in src) hits += "$path: \"$phrase\""
        }
        for (path in listOf("README.md", "docs/ZMSG_PROTOCOL_SPEC.md", "SECURITY.md")) {
            val text = read(path)
            for (phrase in BANNED) if (phrase in text) hits += "$path: \"$phrase\""
        }
        assertTrue(hits.isEmpty(), "false privacy sentences still shipped:\n" + hits.joinToString("\n"))
    }

    @Test
    fun the_scan_finds_an_escaped_sentence() {
        // The resources write don\'t; a scan for "don't" alone would read past them.
        val raw = """<string name="x">Shielded messages travel inside Zcash transactions and don\'t pass through relays.</string>"""
        val phrase = "don't pass through relays"
        assertFalse(phrase in raw, "fixture: the raw XML keeps the escape")
        assertTrue(phrase.replace("'", "\\'") in raw || phrase in raw.replace("\\'", "'"))
    }

    // ---- 3. the two mode dialogs read string resources; their English is the pinned twin ----

    @Test
    fun the_switch_request_dialog_and_the_shielded_intro_row_read_string_resources() {
        val src = read(CONVERSATION_MODE_UI)
        val dialog = assertNotNull(functionBody(src, "ModeSwitchRequestDialog"), "no fun ModeSwitchRequestDialog")
        for (key in listOf(
            "chat_mode_switch_request_title",
            "chat_mode_switch_request_body",
            "chat_mode_switch_request_accept",
            "chat_mode_switch_request_keep",
        )) {
            assertTrue("R.string.$key" in dialog, "ModeSwitchRequestDialog does not read R.string.$key")
        }
        for (literal in listOf("\"Switch to \$", "\"Keep \$", "modeSwitchRequestBody(")) {
            assertFalse(literal in dialog, "ModeSwitchRequestDialog still shows the English literal $literal")
        }
        val intro = assertNotNull(functionBody(src, "ConversationModeIntroDialog"), "no fun ConversationModeIntroDialog")
        // ZC-1457 (owner point 1): the rows' resource ids moved into ModeIntroCopy; the dialog reads ModeIntroCopy.rows.
        val copy = read("$UI/screen/chat/model/ModeIntroCopy.kt")
        assertTrue("ModeIntroCopy.rows" in intro, "the intro dialog does not read ModeIntroCopy.rows")
        assertTrue(
            Regex("""ConversationMode\.VAULT[^)]*R\.string\.chat_mode_intro_shielded_body""").containsMatchIn(copy),
            "the Shielded intro row does not read its resource",
        )
        assertFalse("body = MODE_INTRO_SHIELDED_BODY" in intro, "the Shielded intro row still shows the English constant")
    }

    @Test
    fun the_english_resources_are_the_kotlin_twins_byte_for_byte() {
        assertEquals(MODE_INTRO_SHIELDED_BODY, en["chat_mode_intro_shielded_body"], "chat_mode_intro_shielded_body")
        val body = assertNotNull(en["chat_mode_switch_request_body"], "values has no chat_mode_switch_request_body")
        assertEquals(
            modeSwitchRequestBody("Ana", "Shielded", "Tunnel"),
            String.format(Locale.ROOT, body, "Ana", "Shielded", "Tunnel"),
            "chat_mode_switch_request_body must format to modeSwitchRequestBody",
        )
        assertEquals("Switch to Shielded?", en["chat_mode_switch_request_title"]?.let { String.format(Locale.ROOT, it, "Shielded") })
        assertEquals("Switch to Shielded", en["chat_mode_switch_request_accept"]?.let { String.format(Locale.ROOT, it, "Shielded") })
        assertEquals("Keep Tunnel", en["chat_mode_switch_request_keep"]?.let { String.format(Locale.ROOT, it, "Tunnel") })
    }

    // ---- what the texts must say now ----

    @Test
    fun the_open_note_says_what_a_shielded_chat_still_sends_through_the_relays() {
        val english = en.getValue("chat_mode_note_open_body")
        for (sentence in listOf(OPEN_NOTE_SHIELDED_EN, OPEN_NOTE_RELAYS_EN, RELAY_HEALTH_OPEN_EN)) {
            assertTrue(sentence in english, "EN Open note lacks \"$sentence\": $english")
            assertTrue(sentence in OPEN_DESCRIPTION, "OPEN_DESCRIPTION lacks \"$sentence\"")
        }
        val spanish = es.getValue("chat_mode_note_open_body")
        for (sentence in listOf(OPEN_NOTE_SHIELDED_ES, OPEN_NOTE_RELAYS_ES, RELAY_HEALTH_OPEN_ES)) {
            assertTrue(sentence in spanish, "ES Open note lacks \"$sentence\": $spanish")
        }
    }

    @Test
    fun the_shielded_intro_and_the_switch_request_say_calls_are_set_up_over_the_relays() {
        val intro = en.getValue("chat_mode_intro_shielded_body")
        assertTrue(INTRO_LINKS_EN in intro, intro)
        assertTrue(INTRO_CALLS_EN in intro, intro)
        assertTrue("0.00017 ZEC" in intro, "the cost line stays: $intro")
        val introEs = es.getValue("chat_mode_intro_shielded_body")
        assertTrue("las llamadas se configuran por los relays" in introEs, introEs)
        assertTrue("0,00017 ZEC" in introEs, "the Spanish amount uses the decimal comma: $introEs")

        val body = modeSwitchRequestBody("Ana", "Shielded", "Tunnel")
        assertTrue("Calls are still set up over the relays." in body, body)
        assertTrue("2.13" in body, "the stay-in-your-own-mode promise names the version: $body")
        val bodyEs = es.getValue("chat_mode_switch_request_body")
        assertTrue("las llamadas se siguen configurando por los relays" in bodyEs.lowercase(), bodyEs)
        assertTrue("los dos usen" in bodyEs, "Latin American Spanish (los dos usen): $bodyEs")
        assertFalse("uséis" in bodyEs, bodyEs)
    }

    @Test
    fun the_first_message_question_says_the_relay_identity_goes_free_through_the_relays() {
        assertTrue("through the relays, free" in en.getValue("paid_action_first_message"), en.getValue("paid_action_first_message"))
        assertTrue("por los relays, gratis" in es.getValue("paid_action_first_message"), es.getValue("paid_action_first_message"))
    }

    @Test
    fun security_row_5_names_the_menu_entry_the_chat_really_has() {
        val enLabel = en.getValue("chat_menu_verify_safety_number")
        val esLabel = es.getValue("chat_menu_verify_safety_number")
        assertTrue("⋮ → $enLabel" in en.getValue("security_dialog_item_5_body"), en.getValue("security_dialog_item_5_body"))
        assertTrue("⋮ → $esLabel" in es.getValue("security_dialog_item_5_body"), es.getValue("security_dialog_item_5_body"))
        val view = read("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        assertTrue("R.string.chat_menu_verify_safety_number" in view, "fixture: the ⋮ menu has the Verify safety number entry")
    }

    @Test
    fun security_row_6_says_what_relays_see_from_a_shielded_user() {
        assertEquals("Relays", en["security_dialog_item_6_title"])
        val english = en.getValue("security_dialog_item_6_body")
        for (sentence in listOf(ROW6_INBOX_EN, ROW6_SHIELDED_EN, ROW6_CONTACT_EN)) {
            assertTrue(sentence in english, "row 6 lacks \"$sentence\"")
        }
        assertTrue(english.endsWith("Relays may keep logs. $RELAY_HEALTH_SECURITY_EN"), "RELAY-HEALTH's sentence stays last: $english")
        val spanish = es.getValue("security_dialog_item_6_body")
        assertTrue("buzón" in spanish && "configuración de las llamadas" in spanish && "mensajes de grupo" in spanish, spanish)
        assertTrue(RELAY_HEALTH_SECURITY_ES in spanish, spanish)
    }

    @Test
    fun security_row_7_covers_files_and_voice_notes_tor_and_the_shielded_link() {
        assertEquals("Photos, files and voice notes", en["security_dialog_item_7_title"])
        val english = en.getValue("security_dialog_item_7_body")
        assertTrue("your IP address unless Tor is on" in english, english)
        assertTrue(ROW7_LINK_EN in english, english)
        val spanish = es.getValue("security_dialog_item_7_body")
        assertTrue("si Tor no está activado" in spanish && "transacción Shielded" in spanish, spanish)
    }

    @Test
    fun security_row_8_says_who_sees_a_call_and_that_it_never_uses_tor() {
        val english = en.getValue("security_dialog_item_8_body")
        for (part in listOf(
            "Calls are set up over the Nostr relays in every chat mode, Shielded included.",
            "Cloudflare's STUN server and ZChat's call relay server can see your IP address in every call.",
            "DTLS-SRTP",
            "your contact sees your IP address too",
            "The call itself never uses Tor.",
        )) {
            assertTrue(part in english, "row 8 lacks \"$part\": $english")
        }
        val spanish = es.getValue("security_dialog_item_8_body")
        assertTrue("STUN de Cloudflare" in spanish && "nunca usa Tor" in spanish && "en todos los modos" in spanish, spanish)
        // The facts the row names, at the code: the STUN server and ZChat's call relay (TURN) server.
        assertTrue("stun:stun.cloudflare.com" in read("$UI/call/VoiceCallManager.kt"), "fixture: the STUN server")
        assertTrue("turn:relay.zsend.xyz" in read("$UI/call/TurnCredentialsClient.kt"), "fixture: ZChat's call relay server")
    }

    @Test
    fun security_row_11_says_may_switch_and_names_tunnel_and_open() {
        val english = en.getValue("security_dialog_item_11_body")
        assertTrue("may switch to Tunnel when the contact's secure handshake arrives" in english, english)
        assertTrue("(the side that started the handshake stays Shielded)" in english, english)
        assertTrue("follow your switch to Tunnel or Open" in english, english)
        val spanish = es.getValue("security_dialog_item_11_body")
        assertTrue("puede pasar a Tunnel" in spanish && "Tunnel u Open" in spanish, spanish)
    }

    @Test
    fun the_tor_line_names_what_tor_covers_and_what_never_uses_it() {
        val english = en.getValue("security_dialog_limitations").lines().first { it.startsWith("- Tor") }
        assertEquals(TOR_LINE_EN, english)
        val spanish = es.getValue("security_dialog_limitations").lines().first { it.startsWith("- Tor ") }
        assertTrue("(Beta: Protección con Tor)" in spanish, spanish)
        assertTrue("fotos y archivos" in spanish && "intercambios" in spanish && "tipo de cambio" in spanish, spanish)
        assertTrue("la pestaña de IA y la comprobación de actualizaciones no usan Tor" in spanish, spanish)
        // The clients behind it: through the Tor-aware HttpClientProvider, or not.
        for (path in listOf("nostr/FileUploadManager.kt", "common/provider/NearApiProvider.kt", "common/provider/CMCApiProvider.kt")) {
            assertTrue("HttpClientProvider" in read("$UI/$path"), "fixture: $path goes through the Tor-aware provider")
        }
        for (path in listOf("nostr/RelayClient.kt", "screen/ai/AiApiClient.kt", "screen/update/UpdateChecker.kt")) {
            assertFalse("HttpClientProvider" in read("$UI/$path"), "fixture: $path has its own client, never Tor")
        }
    }

    @Test
    fun security_keeps_eleven_rows_in_english_and_spanish() {
        val rows = { m: Map<String, String> -> (1..20).count { m["security_dialog_item_${it}_title"] != null } }
        assertEquals(11, rows(en))
        assertEquals(rows(en), rows(es))
        assertEquals(11, WHAT_ZCHAT_PROTECTS_ITEMS.size)
    }

    // ---- docs (English only) ----

    @Test
    fun readme_protocol_doc_and_security_policy_say_what_the_app_does() {
        val readme = read("README.md")
        assertTrue("ZChat keeps a relay inbox open for calls, your chat key setup and contacts on Tunnel or Open" in readme)
        val spec = read("docs/ZMSG_PROTOCOL_SPEC.md")
        assertTrue("file uploads and downloads use Tor when it is on" in spec, "protocol doc: file transfers and Tor")
        assertTrue("RelayCatalog.kt" in spec, "protocol doc: the built-in relay list is named")
        assertFalse("relay.damus.io" in spec, "protocol doc still names relay.damus.io")
        val security = read("SECURITY.md")
        assertFalse("relay.damus.io" in security, "SECURITY.md still names relay.damus.io")
        assertTrue("relay.primal.net" in security && "list built into the app" in security, "SECURITY.md: today's relays")
        val emails = EMAIL.findAll(security).map { it.value }.toSet()
        assertTrue("contact@zsend.xyz" in emails, "SECURITY.md does not name contact@zsend.xyz")
        // Counted, never printed: only the project address may stay (owner W-20 item 12).
        assertEquals(1, emails.size, "SECURITY.md names ${emails.size} e-mail addresses; only contact@zsend.xyz may stay")
        assertTrue("github.com/decentrathai/zchat-android/security/advisories" in security, "SECURITY.md links the GitHub advisory form")
        // The upload hosts it names are the app's (ZC-249 left the list).
        val hosts = Regex("\"https://([a-z0-9.-]+)").findAll(read("$UI/nostr/FileUploadManager.kt")).map { it.groupValues[1] }.toSet()
        assertTrue(hosts.isNotEmpty(), "fixture: FileUploadManager lists its hosts")
        for (host in hosts) assertTrue("`$host`" in security, "SECURITY.md does not name the upload host $host")
    }

    // ---- what other batches landed, checked here (C-SRD-5) and D3-2's rule S-D5 relies on ----

    @Test
    fun the_landed_rules_the_texts_rely_on_still_hold() {
        val claims = read("ui-lib/src/test/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/QsClaimCarrierTest.kt")
        assertTrue("C2 a Shielded chat sends no claim" in claims, "D3-2's no-claims-in-Shielded test is gone")
        assertTrue("C2 a chat with no stored mode counts as Shielded and sends no claim" in claims)
        val cvm = read("$UI/screen/chat/viewmodel/ChatViewModel.kt")
        assertFalse("Couldn’t send the photo —" in cvm, "ChatViewModel still builds the English photo error")
        val noFunds = es.getValue("chat_attachment_no_funds_body")
        assertTrue("Agrega ZEC" in noFunds && "Añade" !in noFunds, "Latin American Spanish (Agrega ZEC): $noFunds")
    }

    private companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
        const val CONVERSATION_MODE_UI = "$UI/screen/chat/view/ConversationModeUi.kt"
        const val PRIVACY_COPY = "$UI/screen/chat/view/PrivacyCopy.kt"

        val PLACEHOLDER = Regex("%(\\d+\\$)?[sd]")
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

        val KEYS =
            listOf(
                // SR-D
                "chat_mode_note_open_body",
                "chat_mode_intro_shielded_body",
                "chat_mode_switch_request_title",
                "chat_mode_switch_request_body",
                "chat_mode_switch_request_accept",
                "chat_mode_switch_request_keep",
                "paid_action_first_message",
                "security_dialog_item_5_body",
                "security_dialog_item_6_title",
                "security_dialog_item_6_body",
                "security_dialog_item_7_title",
                "security_dialog_item_7_body",
                "security_dialog_item_8_body",
                "security_dialog_item_11_body",
                "security_dialog_limitations",
                // landed with SR-A, SR-B2, SR-C1, SR-C2 and ZC-393 (checked only)
                "paid_action_shielded_attachment",
                "paid_action_shielded_attachment_memos",
                "paid_action_cost_exact",
                "chat_attachment_price_changed",
                "chat_attachment_no_funds_title",
                "chat_attachment_no_funds_body",
                "chat_attachment_no_funds_switch_tunnel",
                "chat_attachment_no_funds_not_now",
                "chat_attachment_still_confirming",
                "chat_attachment_no_funds_row",
                "chat_attachment_price_unavailable",
                "chat_attachment_needs_kex_shielded_body",
                "chat_send_failed_switched_to_shielded",
                "chat_attachment_legacy_title",
                "chat_attachment_legacy_body",
                "chat_attachment_legacy_send_anyway",
                "chat_attachment_legacy_cancel",
                "chat_attachment_needs_kex_title",
                "chat_attachment_needs_kex_body",
                "chat_attachment_needs_kex_switch_shielded",
                "chat_attachment_needs_kex_not_now",
                "chat_attachment_retry_copy_gone",
                "chat_send_failed_only_public_relay",
            )

        val BANNED =
            listOf(
                "don't pass through relays",
                "No voice/video",
                "calls turn off",
                "fully serverless",
                "Relays (Open and Tunnel)",
                "relays, photo hosts and calls don't use Tor",
                "file hosts and calls never use Tor",
                "Tap the shield icon",
                "Calls may pass through ZChat's relay server.",
                "calls are not offered in Shielded",
                "relay.damus.io",
                // Spanish
                "no pasan por relays",
                "Relays (Open y Tunnel)",
                "los servidores de fotos y las llamadas no usan Tor",
                "Toca el icono del escudo",
                "Las llamadas pueden pasar por el servidor relay de ZChat.",
                "uséis",
            )

        const val OPEN_NOTE_SHIELDED_EN =
            "Shielded messages you send, and the links to their photos, files and voice notes, travel inside Zcash " +
                "transactions, not through the relays."
        const val OPEN_NOTE_RELAYS_EN =
            "The relays still carry your chat key setup, call setup, a switch of this chat's mode, and messages from a " +
                "contact who uses Tunnel or Open."
        const val OPEN_NOTE_SHIELDED_ES =
            "Los mensajes Shielded que envías, y los enlaces de sus fotos, archivos y notas de voz, viajan dentro de " +
                "transacciones de Zcash, no por los relays."
        const val OPEN_NOTE_RELAYS_ES =
            "Los relays siguen llevando la configuración de tu clave de chat, la configuración de las llamadas, un " +
                "cambio de modo de este chat y los mensajes de un contacto que use Tunnel u Open."
        const val RELAY_HEALTH_OPEN_EN =
            "If one of those two stops working, ZChat switches to another public relay from a list built into the app."
        const val RELAY_HEALTH_OPEN_ES =
            "Si uno de esos dos deja de funcionar, ZChat cambia a otro relay público de una lista incluida en la app."

        const val INTRO_LINKS_EN =
            "Photo, file and voice-note links travel inside a shielded transaction too; the files themselves go, " +
                "encrypted, to public file hosts."
        const val INTRO_CALLS_EN =
            "Once your contact's relay key is known, calls are set up over the relays, as in Tunnel and Open."

        const val ROW6_INBOX_EN =
            "ZChat keeps a relay inbox open for Tunnel, Open and calls, so relays see this even if all your chats are Shielded."
        const val ROW6_SHIELDED_EN =
            "In a Shielded chat, what you send through the relays is limited to your chat key setup, a mode change and " +
                "call setup; your profile photo is not sent to Shielded contacts."
        const val ROW6_CONTACT_EN = "What your contact sends from Tunnel or Open, and group messages, still use the relays."
        const val RELAY_HEALTH_SECURITY_EN =
            "If one of the two other relays stops working, ZChat replaces it with another public relay from a list " +
                "built into the app; that relay can then see the same things."
        const val RELAY_HEALTH_SECURITY_ES =
            "Si uno de los otros dos relays deja de funcionar, ZChat lo reemplaza por otro relay público de una lista " +
                "incluida en la app; ese relay podrá ver lo mismo."

        const val ROW7_LINK_EN =
            "In a Shielded chat their link goes inside a Shielded transaction; in Tunnel and Open it goes through the relays."
        const val TOR_LINE_EN =
            "- Tor Protection (Advanced settings) covers sending transactions and fetching transaction data through the " +
                "wallet's server, photo and file transfers, swaps and exchange rates; relays, calls, the AI tab and the " +
                "update check don't use Tor."
    }
}
