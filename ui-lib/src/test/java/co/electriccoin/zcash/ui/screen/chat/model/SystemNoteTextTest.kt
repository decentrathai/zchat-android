package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TX-1 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §4.2 table): [SystemNoteText] draws a stored mode note in
 * today's words, in the phone's language, with the chat header's name; the stored text is never changed.
 *
 * The notes 2.12.4 wrote are built by [Frozen2124], a verbatim copy of the 2.12.4 code; the notes 2.13.0 writes are
 * built from the app's own XML. The strings the app draws with come from the XML too, so this test pins the parser's
 * table against the resources.
 */
class SystemNoteTextTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val en by lazy { strings("values") }
    private val es by lazy { strings("values-es") }
    private val enCopy by lazy { copy(en) }
    private val esCopy by lazy { copy(es) }

    private val modeId = SysNotes.ID_PREFIX + "mode-0123456789ab-1759000000000"
    private val tunnelId = SysNotes.modeUpgradeNoteId("usyntheticsystemnotetext00000000000001", 7L)
    private val oldName = "u1lqy0vp...vh55cu"
    private val name = "Seeker213"

    @Test
    fun the_fold_u1_note_reads_shielded_with_the_contact_name_in_english_and_spanish() {
        val u1 = "🔀 u1lqy0vp...vh55cu asked to switch this chat to Vault (shielded on-chain)."
        assertEquals(u1, Frozen2124.peerAsked(oldName, ConversationMode.VAULT), "fixture: U1 is what 2.12.4 wrote")
        assertEquals("🔀 Seeker213 asked to switch this chat to Shielded (on-chain).", SystemNoteText.display(modeId, u1, name, enCopy))
        assertEquals("🔀 Seeker213 pide cambiar este chat a Shielded (en la cadena).", SystemNoteText.display(modeId, u1, name, esCopy))
    }

    @Test
    fun every_2124_mode_note_is_drawn_in_2130_words() {
        for (mode in ConversationMode.entries) {
            val label = en.getValue(labelKey(mode))
            val cases =
                listOf(
                    Frozen2124.peerFollowed(oldName, mode) to fmt("chat_note_peer_switched_followed", name, label),
                    Frozen2124.peerAsked(oldName, mode) to fmt("chat_note_peer_asked_to_switch", name, label),
                    Frozen2124.peerUnchanged(oldName, mode) to fmt("chat_note_peer_switched_unchanged", name, label),
                    Frozen2124.youSwitched(mode) to fmt("chat_note_you_switched", label),
                    Frozen2124.youSwitchedToMatch(mode, oldName) to fmt("chat_note_you_switched_to_match", label, name),
                    Frozen2124.youKept(mode) to fmt("chat_note_you_kept_mode", label),
                )
            for ((stored, shown) in cases) {
                assertEquals(shown, SystemNoteText.display(modeId, stored, name, enCopy), "2.12.4 note: $stored")
                assertTrue("Vault" !in shown && "via relay)" !in shown, "fixture: today's words: $shown")
            }
        }
    }

    @Test
    fun the_tunnel_note_of_2124_and_of_2130_test_builds_reads_todays_words() {
        val head =
            "🚇 Secure tunnel established — this chat now uses Tunnel: free and instant, still end-to-end encrypted. " +
                "Change it anytime from the chat menu."
        for (stored in listOf(
            Frozen2124.TUNNEL_NOTE,
            head,
            en.getValue("chat_note_tunnel_established"),
            es.getValue("chat_note_tunnel_established")
        )) {
            assertEquals(en.getValue("chat_note_tunnel_established"), SystemNoteText.display(tunnelId, stored, name, enCopy), stored)
            assertEquals(es.getValue("chat_note_tunnel_established"), SystemNoteText.display(tunnelId, stored, name, esCopy), stored)
        }
    }

    @Test
    fun a_2130_note_in_either_language_is_drawn_in_the_phones_language_with_the_header_name() {
        val peerKinds =
            mapOf(
                "chat_note_peer_switched_followed" to SystemNoteText.Kind.PEER_FOLLOWED,
                "chat_note_peer_asked_to_switch" to SystemNoteText.Kind.PEER_ASKED,
                "chat_note_peer_switched_unchanged" to SystemNoteText.Kind.PEER_UNCHANGED,
            )
        for (stored in listOf(en, es)) {
            for (mode in ConversationMode.entries) {
                val storedLabel = stored.getValue(labelKey(mode))
                for ((key, kind) in peerKinds) {
                    val text = String.format(stored.getValue(key), oldName, storedLabel)
                    assertEquals(SystemNoteText.Parsed(kind, mode), SystemNoteText.parse(modeId, text), text)
                    assertEquals(
                        String.format(en.getValue(key), name, en.getValue(labelKey(mode))),
                        SystemNoteText.display(modeId, text, name, enCopy)
                    )
                    assertEquals(
                        String.format(es.getValue(key), name, es.getValue(labelKey(mode))),
                        SystemNoteText.display(modeId, text, name, esCopy)
                    )
                }
                val match = String.format(stored.getValue("chat_note_you_switched_to_match"), storedLabel, oldName)
                assertEquals(
                    String.format(es.getValue("chat_note_you_switched_to_match"), es.getValue(labelKey(mode)), name),
                    SystemNoteText.display(modeId, match, name, esCopy),
                )
                for (key in listOf("chat_note_you_switched", "chat_note_you_kept_mode")) {
                    val text = String.format(stored.getValue(key), storedLabel)
                    assertEquals(
                        String.format(en.getValue(key), en.getValue(labelKey(mode))),
                        SystemNoteText.display(modeId, text, name, enCopy)
                    )
                }
            }
        }
    }

    @Test
    fun a_text_that_matches_no_template_is_shown_unchanged() {
        for (
        text in listOf(
            "",
            "🔀 Seeker213 asked to switch this chat to Carrier pigeon.",
            "🔀 asked to switch this chat to Vault (shielded on-chain).",
            "🔀 You switched this chat to Open (free, via relays) to match .",
            "Hello",
        )
        ) {
            assertNull(SystemNoteText.parse(modeId, text), text)
            assertEquals(text, SystemNoteText.display(modeId, text, name, enCopy))
        }
    }

    @Test
    fun only_mode_notes_are_rewritten() {
        val old = Frozen2124.peerAsked(oldName, ConversationMode.VAULT)
        val others =
            listOf(
                "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
                "nostr-abc123",
                SysNotes.rotationNoteId("usyntheticsystemnotetext00000000000001"),
                SysNotes.ttlNoteId("usyntheticsystemnotetext00000000000001", 1L),
                SysNotes.zcashOnlyNoteId("usyntheticsystemnotetext00000000000001"),
                SysNotes.ID_PREFIX + "late-0123456789ab",
            )
        for (id in others) {
            assertNull(SystemNoteText.parse(id, old), id)
            assertEquals(old, SystemNoteText.display(id, old, name, enCopy), id)
            assertEquals(Frozen2124.TUNNEL_NOTE, SystemNoteText.display(id, Frozen2124.TUNNEL_NOTE, name, enCopy), id)
        }
        assertTrue(SystemNoteText.isModeNoteId(modeId) && SystemNoteText.isModeNoteId(tunnelId))
    }

    @Test
    fun a_name_that_holds_template_words_does_not_move_the_split() {
        val tricky = "Bob switched this chat to Open (free, via relay). Ha"
        val stored = Frozen2124.peerUnchanged(tricky, ConversationMode.VAULT)
        assertEquals(
            SystemNoteText.Parsed(SystemNoteText.Kind.PEER_UNCHANGED, ConversationMode.VAULT),
            SystemNoteText.parse(modeId, stored)
        )
        assertEquals(
            fmt("chat_note_peer_switched_unchanged", name, en.getValue("chat_mode_label_vault")),
            SystemNoteText.display(modeId, stored, name, enCopy)
        )
        val asked = Frozen2124.peerAsked("Ann asked to switch this chat to Tunnel (free, via relay).", ConversationMode.OPEN)
        assertEquals(SystemNoteText.Parsed(SystemNoteText.Kind.PEER_ASKED, ConversationMode.OPEN), SystemNoteText.parse(modeId, asked))
        val matched = Frozen2124.youSwitchedToMatch(ConversationMode.TUNNEL, "Open (free, via relay) to match Eve")
        assertEquals(
            SystemNoteText.Parsed(SystemNoteText.Kind.YOU_SWITCHED_TO_MATCH, ConversationMode.TUNNEL),
            SystemNoteText.parse(modeId, matched)
        )
    }

    @Test
    fun the_parser_table_is_the_apps_resources_and_2124s_code() {
        val keys =
            mapOf(
                SystemNoteText.Kind.PEER_FOLLOWED to "chat_note_peer_switched_followed",
                SystemNoteText.Kind.PEER_ASKED to "chat_note_peer_asked_to_switch",
                SystemNoteText.Kind.PEER_UNCHANGED to "chat_note_peer_switched_unchanged",
                SystemNoteText.Kind.YOU_SWITCHED to "chat_note_you_switched",
                SystemNoteText.Kind.YOU_SWITCHED_TO_MATCH to "chat_note_you_switched_to_match",
                SystemNoteText.Kind.YOU_KEPT to "chat_note_you_kept_mode",
            )
        for ((kind, key) in keys) {
            assertEquals(en.getValue(key), SystemNoteText.EN_TEMPLATES[kind], "EN $key")
            assertEquals(es.getValue(key), SystemNoteText.ES_TEMPLATES[kind], "ES $key")
            assertEquals(R.string::class.java.getField(key).getInt(null), kind.res, "$kind draws with $key")
        }
        assertEquals(R.string.chat_note_tunnel_established, SystemNoteText.Kind.TUNNEL.res)
        val labels = SystemNoteText.LABELS.toMap()
        for (mode in ConversationMode.entries) {
            for (set in listOf(en, es)) assertEquals(mode, labels[set.getValue(labelKey(mode))], "label of $mode in the XML")
            assertEquals(mode, labels[Frozen2124.modeLabel(mode)], "2.12.4 label of $mode")
        }
        assertEquals(9, SystemNoteText.LABELS.size)
        assertTrue(Frozen2124.TUNNEL_NOTE in SystemNoteText.TUNNEL_TEXTS)
        assertTrue(en.getValue("chat_note_tunnel_established") in SystemNoteText.TUNNEL_TEXTS)
        assertTrue(es.getValue("chat_note_tunnel_established") in SystemNoteText.TUNNEL_TEXTS)
        // 2.12.4 wrote its English sentences with 2.13.0's English words; only the labels changed.
        assertEquals(
            String.format(en.getValue("chat_note_peer_asked_to_switch"), oldName, Frozen2124.modeLabel(ConversationMode.OPEN)),
            Frozen2124.peerAsked(oldName, ConversationMode.OPEN),
        )
    }

    private fun labelKey(mode: ConversationMode) =
        when (mode) {
            ConversationMode.VAULT -> "chat_mode_label_vault"
            ConversationMode.TUNNEL -> "chat_mode_label_tunnel"
            ConversationMode.OPEN -> "chat_mode_label_open"
        }

    private fun fmt(
        key: String,
        vararg args: Any
    ) = String.format(en.getValue(key), *args)

    private fun strings(dir: String): Map<String, String> {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_surface_strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private fun copy(texts: Map<String, String>): ChatNoteCopy {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return ChatNoteCopy { id, args ->
            val key = names[id] ?: fail("unknown string id $id")
            String.format(texts[key] ?: fail("no text for $key"), *args)
        }
    }

    /**
     * 2.12.4's note words, copied verbatim from `git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/
     * chat/viewmodel/ChatViewModel.kt`: `modeLabel` (`:1569-1574`), "You switched" (`:1611`), the peer notes
     * (`:1704-1706`), "to match" (`:1862`), "You kept" (`:1878`) and the tunnel note (`:1050-1051`). 2.11.0–2.12.3 wrote
     * the same words.
     */
    private object Frozen2124 {
        fun modeLabel(mode: ConversationMode): String =
            when (mode) {
                ConversationMode.VAULT -> "Vault (shielded on-chain)"
                ConversationMode.TUNNEL -> "Tunnel (free, via relay)"
                ConversationMode.OPEN -> "Open (free, via relay)"
            }

        fun youSwitched(newMode: ConversationMode) = "🔀 You switched this chat to ${modeLabel(newMode)}."

        fun peerFollowed(
            name: String,
            newMode: ConversationMode
        ) = "🔀 $name switched this chat to ${modeLabel(newMode)} — this device followed."

        fun peerAsked(
            name: String,
            newMode: ConversationMode
        ) = "🔀 $name asked to switch this chat to ${modeLabel(newMode)}."

        fun peerUnchanged(
            name: String,
            newMode: ConversationMode
        ) = "🔀 $name switched this chat to ${modeLabel(newMode)}. Your side is unchanged — you can match it from the chat menu."

        fun youSwitchedToMatch(
            newMode: ConversationMode,
            peerName: String
        ) = "🔀 You switched this chat to ${modeLabel(newMode)} to match $peerName."

        fun youKept(currentMode: ConversationMode) = "You kept this chat on ${modeLabel(currentMode)}."

        const val TUNNEL_NOTE =
            "🚇 Secure tunnel established — this chat switched from Vault (on-chain) to Tunnel: free and " +
                "instant, still end-to-end encrypted. Change it anytime from the chat menu."
    }
}
