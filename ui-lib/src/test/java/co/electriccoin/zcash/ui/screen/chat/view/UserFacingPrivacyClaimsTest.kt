package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bridge 4 (FINAL_PLAN 2.6, "Honest labels"; wording from COPY_DECK.md 2.4 and 2.6): the A6 copy test,
 * extended to every string literal and string resource the app ships.
 *
 * - Open and Tunnel messages go through three Nostr relays (NostrRelayPool.DEFAULT_RELAYS), one of them
 *   ZChat's. Relays can't read them but see the chat key, IP address, online times and which keys are
 *   messaged, so no text may call the network private, say no server sees or stores messages, promise
 *   maximum metadata privacy, or claim gift-wrapping hides who you talk to.
 * - About said "Military-Grade Encryption ... zero-knowledge cryptography" (round 2 report 4 item 15), which
 *   overstates what Open and Tunnel use.
 * - "No native Tor" is wrong: Tor can protect the wallet's server connection (Advanced settings).
 * - The failed-upgrade screen (A3) pointed to "ZCHAT support", which has no channel; help is zsend.xyz/update.
 */
class UserFacingPrivacyClaimsTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    // ---- the phrase list (FINAL_PLAN 2.6 + round 2 report 4 item 15 + the Spanish equivalents) ----

    @Test
    fun no_false_privacy_phrase_in_any_string_literal() {
        val hits = mutableListOf<String>()
        for (file in mainSourceFiles()) {
            for (literal in kotlinStringLiterals(file.readText())) {
                for (phrase in bannedIn(literal)) hits += "${file.relativeTo(repoRoot)}: \"$phrase\" in \"${literal.take(160)}\""
            }
        }
        assertTrue(hits.isEmpty(), "false privacy claims in Kotlin string literals:\n" + hits.joinToString("\n"))
    }

    @Test
    fun no_false_privacy_phrase_in_any_string_resource() {
        val hits = mutableListOf<String>()
        for (file in resourceFiles()) {
            for ((name, value) in resourceStrings(file.readText())) {
                for (phrase in bannedIn(value)) hits += "${file.relativeTo(repoRoot)} $name: \"$phrase\""
            }
        }
        assertTrue(hits.isEmpty(), "false privacy claims in string resources:\n" + hits.joinToString("\n"))
    }

    @Test
    fun the_scanner_finds_the_phrases_it_is_meant_to_find() {
        // Guards the two scans above against passing because they read nothing.
        assertTrue(mainSourceFiles().size > 100, "too few Kotlin sources scanned")
        assertTrue(resourceFiles().size > 20, "too few resource files scanned")
        val literals = kotlinStringLiterals("val a = \"No native \" +\n    \"Tor here\" // private relay\n/* private network */")
        assertEquals(listOf("No native Tor here"), literals)
        assertEquals(listOf("No native Tor"), bannedIn("x No native Tor y"))
        assertTrue(bannedIn("older ZChat wrongly says gift-wrapping hides this").isEmpty(), "the share text quotes the old claim as wrong")
    }

    // ---- About (round 2 report 4 item 15) ----

    @Test
    fun about_encryption_item_makes_no_military_grade_or_zero_knowledge_claim() {
        for (dir in listOf("values", "values-es")) {
            val strings = resourceStrings(File(repoRoot, "ui-lib/src/main/res/ui/about/$dir/strings.xml").readText())
            val title = assertNotNull(strings["about_privacy_encryption"], "$dir: title missing")
            val desc = assertNotNull(strings["about_privacy_encryption_desc"], "$dir: description missing")
            val text = "$title $desc".lowercase()
            for (bad in listOf("military", "militar", "zero-knowledge", "conocimiento cero", "every message is encrypted using")) {
                assertFalse(bad in text, "$dir: $text")
            }
            assertTrue("relay" in text, "$dir: the encryption item must say where Open and Tunnel messages go: $text")
        }
    }

    // ---- A3 help channel ----

    @Test
    fun failed_upgrade_screen_points_to_zsend_update_in_english_and_spanish() {
        val en = resourceStrings(File(repoRoot, "ui-lib/src/main/res/ui/error/values/strings.xml").readText())
        val es = resourceStrings(File(repoRoot, "ui-lib/src/main/res/ui/error/values-es/strings.xml").readText())
        val enMsg = en.getValue("wallet_startup_failed_message")
        val esMsg = es.getValue("wallet_startup_failed_message")
        assertTrue("Then get help at zsend.xyz/update." in enMsg, enMsg)
        assertFalse("ZCHAT support" in enMsg, enMsg)
        assertTrue("Luego busca ayuda en zsend.xyz/update." in esMsg, esMsg)
        assertFalse("soporte de ZCHAT" in esMsg, esMsg)
    }

    // ---- 2.6 rows ----

    @Test
    fun empty_chat_list_says_what_relays_see() {
        assertEquals(
            "Shielded messages travel inside Zcash transactions. Open and Tunnel messages are end-to-end encrypted " +
                "and pass through three Nostr relays, one of which ZChat runs. Relays can't read them, but they can " +
                "see your chat key, your IP address, when you're online, and which chat keys you send to. Relays " +
                "may keep logs.",
            emptyChatPrivacyBody(),
        )
    }

    @Test
    fun open_description_and_switch_note_say_what_relays_see() {
        val (_, body) = assertNotNull(modeSecurityNoteCopy(ConversationMode.OPEN))
        for (sentence in OPEN_DESCRIPTION) assertTrue(sentence in body, "Open note lacks \"$sentence\": $body")
        assertFalse("rotate your key" in body.lowercase(), "key rotation is not privacy advice for 2.13.0: $body")
        val intro = MODE_INTRO_OPEN_BODY
        assertTrue("three Nostr relays" in intro, intro)
        assertTrue("Relays can't read them" in intro, intro)
        assertTrue("your chat key" in intro && "your IP address" in intro, intro)
    }

    @Test
    fun tunnel_description_names_the_same_relays_and_the_version_it_needs() {
        val tunnel =
            "Tunnel: a secure handshake, then free messages through the same Nostr relays as Open. If you never " +
                "finished a handshake with this contact, switching to Tunnel costs two or three on-chain payments, " +
                "each at least 0.00017 ZEC; the exact number is shown before you pay, and both of you need ZChat 2.13 " +
                "or newer."
        assertEquals(tunnel, MODE_INTRO_TUNNEL_BODY)
        val (_, body) = assertNotNull(modeSecurityNoteCopy(ConversationMode.TUNNEL))
        assertTrue(tunnel in body, body)
        assertFalse("rotate your key" in body.lowercase(), body)
        assertFalse("a key only you and your contact share" in body.lowercase(), body)
    }

    @Test
    fun greyed_out_open_hint_says_tunnel_costs_zec_and_needs_an_update() {
        assertTrue(
            "switch to Tunnel to exchange it (costs ZEC; a contact on an older ZChat can't finish it until they update)" in
                OPEN_NEEDS_KEY_HINT,
            OPEN_NEEDS_KEY_HINT,
        )
    }

    @Test
    fun shielded_description_carries_the_cost_line_and_who_sees_what() {
        val body = MODE_INTRO_SHIELDED_BODY
        assertTrue("0.00017 ZEC" in body, body)
        assertTrue("ZChat's fee wallet sees when fees arrive and how many, not who sent them." in body, body)
        assertTrue("The server your wallet syncs from sees your IP address when you send." in body, body)
        val blurbs = ConversationMode.entries.map { modeBlurb(it).first } + ConversationMode.entries.map { it.composeBlurb() }
        for (b in blurbs) assertFalse("max privacy" in b.lowercase(), b)
    }

    @Test
    fun tunnel_system_note_names_the_handshake_and_the_relays() {
        assertEquals("Tunnel (a secure handshake, then free via relays)", modeNoteLabel(ConversationMode.TUNNEL))
        assertFalse("via relay)" in modeNoteLabel(ConversationMode.OPEN), "Open uses three relays, not one")
    }

    @Test
    fun ticks_say_where_the_message_is() {
        assertEquals("Sent to the network", messageStatusDescription(MessageStatus.SENT))
        assertEquals("On the blockchain", messageStatusDescription(MessageStatus.CONFIRMED))
    }

    @Test
    fun unverified_sender_chip_says_what_was_not_checked() {
        assertEquals("Sender not verified: this message wasn't signed with your contact's secure key.", UNVERIFIED_SENDER_CHIP)
    }

    @Test
    fun handshake_consent_warns_about_older_versions_and_when_the_fee_is_paid() {
        val en = allStrings("values").getValue("handshake_consent_footer")
        assertTrue("A contact still on an older ZChat can't answer a new handshake until they update." in en, en)
        assertTrue("The fee is paid when the handshake is sent." in en, en)
        val es = allStrings("values-es").getValue("handshake_consent_footer")
        assertTrue("hasta que actualice" in es, es)
        assertTrue("La comisión se paga al enviar el intercambio de claves." in es, es)
    }

    @Test
    fun key_rotation_reminder_is_hidden_and_the_confirm_warns_about_older_versions() {
        val src = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt").readText()
        assertFalse("showRotationReminder = true" in src, "the weekly rotation reminder must not be shown in 2.13.0")
        assertTrue(
            "Rotation hasn't been tested with contacts on older ZChat versions. If a contact hasn't updated, wait." in
                ROTATION_CONFIRM_BODY,
            ROTATION_CONFIRM_BODY,
        )
    }

    @Test
    fun onboarding_what_is_zec_names_the_relays_not_a_private_network() {
        val body = WHAT_IS_ZEC_BODY.lowercase()
        assertFalse("private network" in body, body)
        assertFalse("maximum" in body, body)
        assertTrue("relays" in body, body)
    }

    @Test
    fun what_zchat_protects_lists_relays_photo_hosts_calls_the_capability_tag_fees_and_tor() {
        val text = (WHAT_ZCHAT_PROTECTS_ITEMS.joinToString("\n") { "${it.first} ${it.second}" } + "\n" + WHAT_ZCHAT_PROTECTS_LIMITATIONS)
        for (sentence in listOf(
            "Open and Tunnel messages are end-to-end encrypted and pass through three Nostr relays, one of which ZChat runs.",
            "Relays can't read them, but they can see your chat key, your IP address, when you're online, and which chat keys you send to.",
            "Relays may keep logs.",
            "They are encrypted, then uploaded to public file hosts (such as nostr.download), which see your chat key, size and time, and your IP address unless Tor is on.",
            "Calls are set up over the Nostr relays in every chat mode, Shielded included.",
            "ZChat tells your contacts, inside encrypted messages, that your app supports the upgraded Zcash network.",
            "ZChat's fee wallet sees when fees arrive and how many, not who sent them.",
            "The server your wallet syncs from sees your IP address when you send.",
            "A Shielded chat you never set by hand may switch to Tunnel when the contact's secure handshake arrives (the side " +
                "that started the handshake stays Shielded), and a contact's phone may follow your switch to Tunnel or Open; you " +
                "can change either in Conversation mode.",
            "Tor Protection (Advanced settings) covers sending transactions and fetching transaction data through the wallet's " +
                "server, photo and file transfers, swaps and exchange rates; relays, calls, the AI tab and the update check don't use Tor.",
            // Round 8 D9 (DECISIONS_NIGHT §3/§4): the first Shielded message to a new contact has no ZChat layer, so
            // "Double encryption" holds only once the contact has answered the key exchange. Ironwood is a pool of
            // the Orchard protocol, proved with Halo 2 (SDK 3.0.0 ships halo2_proofs and orchard only), so that stays.
            "AES-256-GCM inside Zcash shielded transactions (Halo 2 zk-SNARKs), once your contact has answered the key exchange.",
        )) {
            assertTrue(sentence in text, "What ZCHAT Protects lacks \"$sentence\"")
        }
        assertFalse("impossible to construct" in text, "the contact graph is visible to relays in Open and Tunnel: $text")
    }

    // ---- helpers ----

    private fun bannedIn(text: String): List<String> {
        val lower = text.lowercase()
        return BANNED.filter { phrase ->
            val p = phrase.lowercase()
            var i = lower.indexOf(p)
            var found = false
            while (i >= 0 && !found) {
                // The share text quotes the old claim as wrong ("older ZChat wrongly says gift-wrapping hides this").
                val before = lower.substring(maxOf(0, i - 20), i)
                if ("wrongly says" !in before && "por error, que el" !in before) found = true
                i = lower.indexOf(p, i + 1)
            }
            found
        }
    }

    private fun mainSourceFiles(): List<File> =
        repoRoot
            .listFiles()
            .orEmpty()
            .flatMap { module -> walk(File(module, "src/main")) }
            .filter { it.isFile && it.extension == "kt" }

    private fun resourceFiles(): List<File> =
        repoRoot
            .listFiles()
            .orEmpty()
            .flatMap { module -> walk(File(module, "src/main/res")) }
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name?.startsWith("values") == true }

    private fun walk(dir: File): List<File> = if (dir.isDirectory) dir.walkTopDown().toList() else emptyList()

    private fun allStrings(dir: String): Map<String, String> =
        File(repoRoot, "ui-lib/src/main/res/ui")
            .listFiles()
            .orEmpty()
            .flatMap { File(it, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .fold(emptyMap()) { acc, f -> acc + resourceStrings(f.readText()) }

    private fun resourceStrings(xml: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .forEach { out[it.groupValues[1]] = unescape(it.groupValues[2]) }
        Regex("""<(plurals|string-array) name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .forEach { block ->
                Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(block.groupValues[3])
                    .forEachIndexed { i, item -> out["${block.groupValues[2]}[$i]"] = unescape(item.groupValues[1]) }
            }
        return out
    }

    private fun unescape(s: String): String =
        s
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    /**
     * The string literals of a Kotlin file, comments skipped, with literals joined by `+` merged into one, so a
     * sentence split across source lines is still one string.
     */
    private fun kotlinStringLiterals(src: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var lastEnd = -1

        fun add(text: String, end: Int) {
            val between = if (lastEnd >= 0) src.substring(lastEnd, i) else ""
            if (out.isNotEmpty() && lastEnd >= 0 && between.isNotBlank() && between.trim() == "+") {
                out[out.size - 1] = out.last() + text
            } else {
                out += text
            }
            lastEnd = end
        }
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it }
                    val text = src.substring(i + 3, end)
                    val stop = minOf(src.length, end + 3)
                    add(text, stop)
                    i = stop
                }
                src[i] == '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    var depth = 0
                    while (j < src.length) {
                        val c = src[j]
                        if (depth == 0 && c == '\\' && j + 1 < src.length) {
                            sb.append(if (src[j + 1] == 'n') '\n' else src[j + 1])
                            j += 2
                            continue
                        }
                        if (depth == 0 && c == '"') break
                        if (c == '$' && j + 1 < src.length && src[j + 1] == '{') {
                            depth++
                            j += 2
                            continue
                        }
                        if (depth > 0 && c == '}') {
                            depth--
                            j++
                            continue
                        }
                        if (depth == 0) sb.append(c)
                        if (c == '\n' && depth == 0) break
                        j++
                    }
                    val stop = minOf(src.length, j + 1)
                    val start = i
                    i = start
                    add(sb.toString(), stop)
                    i = stop
                }
                src[i] == '\'' -> {
                    // char literal, including '"' and '\''
                    val end = src.indexOf('\'', i + if (src.startsWith("'\\", i)) 3 else 2)
                    i = if (end < 0 || end - i > 8) i + 1 else end + 1
                }
                else -> i++
            }
        }
        return out
    }

    private companion object {
        val OPEN_DESCRIPTION =
            listOf(
                "Open: free, end-to-end encrypted messages through three Nostr relays, one run by ZChat and two by other people.",
                "Relays can't read them.",
                "But each relay can see your chat key (one key for all your chats), your IP address, when you're online, and which chat keys you send to.",
                "Together that can show who you talk to and when.",
                "ZChat's relay deletes messages after 30 days; other relays may keep them longer.",
                "ZChat drops free messages older than 30 days, so your contact should open ZChat within about 3 weeks.",
                "Shielded messages you send, and the links to their photos, files and voice notes, travel inside Zcash transactions, not through the relays.",
                "The relays still carry your chat key setup, call setup, a switch of this chat's mode, and messages from a contact who uses Tunnel or Open.",
                "Your contact's phone may switch its side of this chat to Open too, and older ZChat versions don't explain relays.",
                "How to go back: tap the mode under the contact's name → Shielded.",
            )

        val BANNED =
            listOf(
                "No server stores your messages",
                "private relay",
                "only you and your contact share",
                "No Central Server",
                "Maximum metadata privacy",
                "Max metadata privacy",
                "Gift-wrapping hides",
                "private network",
                "No server ever sees",
                "directly to the blockchain",
                "on-chain metadata privacy",
                "No native Tor",
                "Military-Grade",
                "zero-knowledge cryptography",
                // Spanish (COPY_DECK 2.6 note; checked against values-es)
                "relay privado",
                "Máxima privacidad de metadatos",
                "Ningún servidor",
                "red privada",
                "directamente a la blockchain",
                "Sin servidor central",
                "Grado Militar",
                "criptografía de conocimiento cero",
                "Sin Tor nativo",
            )
    }
}
