package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R

/**
 * TX-1: the words a conversation-mode system note is DRAWN with.
 *
 * A system note is stored as finished text when it happens (R5-4, [ChatNoteCopy]), so a note written by 2.12.4 keeps
 * 2.12.4's words ("Vault (shielded on-chain)", "(free, via relay)"), and every note keeps the language and the contact
 * name it was written with. Nothing on disk is rewritten: when the chat draws a mode note, [display] recognises the
 * stored sentence against a fixed table (every template and mode label ZChat has written: 2.11.0–2.12.4's English
 * literals and today's English and Spanish resources, pinned against the XML by SystemNoteTextTest) and writes it again
 * in the phone's language today, with the name the chat header shows.
 *
 * Only notes whose id is a mode note ([SysNotes.ID_PREFIX] + `mode-` or `modeup-`) are touched; a text that matches no
 * template is shown unchanged. The mode label is recognised by its whole wording and must sit exactly where the
 * template puts it, so a contact name that contains " switched this chat to " cannot move the split.
 */
object SystemNoteText {
    enum class Kind(
        /** The resource the note is drawn with today. */
        val res: Int,
    ) {
        PEER_FOLLOWED(R.string.chat_note_peer_switched_followed),
        PEER_ASKED(R.string.chat_note_peer_asked_to_switch),
        PEER_UNCHANGED(R.string.chat_note_peer_switched_unchanged),
        YOU_SWITCHED(R.string.chat_note_you_switched),
        YOU_SWITCHED_TO_MATCH(R.string.chat_note_you_switched_to_match),
        YOU_KEPT(R.string.chat_note_you_kept_mode),
        TUNNEL(R.string.chat_note_tunnel_established),
    }

    /** A recognised mode note: its kind, and its mode (null only for [Kind.TUNNEL]). */
    data class Parsed(
        val kind: Kind,
        val mode: ConversationMode?,
    )

    private const val MODE_NOTE_PREFIX = SysNotes.ID_PREFIX + "mode-"
    private const val MODE_UPGRADE_NOTE_PREFIX = SysNotes.ID_PREFIX + "modeup-"

    /** Where the name and the label sit in a template. */
    private enum class Slots { NAME_THEN_LABEL, LABEL_THEN_NAME, LABEL_ONLY }

    private class Template(
        val kind: Kind,
        val text: String,
        val slots: Slots,
    )

    /**
     * Every mode label a stored note can hold. 2.11.0–2.12.4 (`modeLabel`, English only; `git show v2.12.4:` ChatViewModel
     * `:1569-1574`), and 2.13.0's `chat_mode_label_*` in English and Spanish.
     */
    internal val LABELS: List<Pair<String, ConversationMode>> =
        listOf(
            "Vault (shielded on-chain)" to ConversationMode.VAULT,
            "Tunnel (free, via relay)" to ConversationMode.TUNNEL,
            "Open (free, via relay)" to ConversationMode.OPEN,
            "Shielded (on-chain)" to ConversationMode.VAULT,
            "Tunnel (a secure handshake, then free via relays)" to ConversationMode.TUNNEL,
            "Open (free, via relays)" to ConversationMode.OPEN,
            "Shielded (en la cadena)" to ConversationMode.VAULT,
            "Tunnel (un intercambio de claves seguro y luego gratis por relays)" to ConversationMode.TUNNEL,
            "Open (gratis, por relays)" to ConversationMode.OPEN,
        ).sortedByDescending { it.first.length }

    /**
     * The sentences, with `%1$s` / `%2$s` where the resource has them. 2.11.0–2.12.4 wrote the English ones with the same
     * words as 2.13.0's English resources (only the labels differ), so one English set covers both.
     */
    internal val EN_TEMPLATES: Map<Kind, String> =
        mapOf(
            Kind.PEER_FOLLOWED to "🔀 %1\$s switched this chat to %2\$s — this device followed.",
            Kind.PEER_ASKED to "🔀 %1\$s asked to switch this chat to %2\$s.",
            Kind.PEER_UNCHANGED to
                "🔀 %1\$s switched this chat to %2\$s. Your side is unchanged — you can match it from the chat menu.",
            Kind.YOU_SWITCHED to "🔀 You switched this chat to %1\$s.",
            Kind.YOU_SWITCHED_TO_MATCH to "🔀 You switched this chat to %1\$s to match %2\$s.",
            Kind.YOU_KEPT to "You kept this chat on %1\$s.",
        )

    internal val ES_TEMPLATES: Map<Kind, String> =
        mapOf(
            Kind.PEER_FOLLOWED to "🔀 %1\$s ha cambiado este chat a %2\$s — este dispositivo lo ha seguido.",
            Kind.PEER_ASKED to "🔀 %1\$s pide cambiar este chat a %2\$s.",
            Kind.PEER_UNCHANGED to
                "🔀 %1\$s ha cambiado este chat a %2\$s. Tu lado no ha cambiado — puedes igualarlo desde el menú del chat.",
            Kind.YOU_SWITCHED to "🔀 Has cambiado este chat a %1\$s.",
            Kind.YOU_SWITCHED_TO_MATCH to "🔀 Has cambiado este chat al modo %1\$s para que coincida con el de %2\$s.",
            Kind.YOU_KEPT to "Has mantenido este chat en %1\$s.",
        )

    /**
     * The tunnel note as each version wrote it: 2.11.0–2.12.4's literal (`v2.12.4` ChatViewModel `:1050`), 2.13.0 test
     * builds' literal, and `chat_note_tunnel_established` in English and Spanish.
     */
    internal val TUNNEL_TEXTS: List<String> =
        listOf(
            "🚇 Secure tunnel established — this chat switched from Vault (on-chain) to Tunnel: free and " +
                "instant, still end-to-end encrypted. Change it anytime from the chat menu.",
            "🚇 Secure tunnel established — this chat now uses Tunnel: free and instant, still end-to-end encrypted. " +
                "Change it anytime from the chat menu.",
            "🚇 Túnel seguro establecido: este chat ahora usa Tunnel, gratis e instantáneo y con cifrado de extremo a " +
                "extremo. Puedes cambiarlo cuando quieras desde el menú del chat.",
        )

    // Most specific first: PEER_UNCHANGED and PEER_FOLLOWED end in more words than PEER_ASKED, and
    // YOU_SWITCHED_TO_MATCH is YOU_SWITCHED with more words, so each is tried before the shorter one.
    private val ORDER =
        listOf(
            Kind.PEER_UNCHANGED,
            Kind.PEER_FOLLOWED,
            Kind.PEER_ASKED,
            Kind.YOU_SWITCHED_TO_MATCH,
            Kind.YOU_SWITCHED,
            Kind.YOU_KEPT,
        )

    private val TEMPLATES: List<Template> =
        listOf(EN_TEMPLATES, ES_TEMPLATES).flatMap { set ->
            ORDER.map { kind ->
                val text = set.getValue(kind)
                val slots =
                    when (kind) {
                        Kind.PEER_FOLLOWED, Kind.PEER_ASKED, Kind.PEER_UNCHANGED -> Slots.NAME_THEN_LABEL
                        Kind.YOU_SWITCHED_TO_MATCH -> Slots.LABEL_THEN_NAME
                        else -> Slots.LABEL_ONLY
                    }
                Template(kind, text, slots)
            }
        }

    /** True for the ids of the notes [parse] may rewrite: conversation-mode notes and the tunnel note. */
    fun isModeNoteId(id: String): Boolean = id.startsWith(MODE_NOTE_PREFIX) || id.startsWith(MODE_UPGRADE_NOTE_PREFIX)

    /** The kind and mode of a stored mode note, or null when [id] is not a mode note or [text] matches no template. */
    fun parse(
        id: String,
        text: String,
    ): Parsed? {
        if (!isModeNoteId(id)) return null
        if (text in TUNNEL_TEXTS) return Parsed(Kind.TUNNEL, null)
        for (template in TEMPLATES) {
            match(template, text)?.let { return it }
        }
        return null
    }

    /**
     * The note as the chat draws it: a recognised mode note in today's words from [copy] (the phone's language), with
     * [name] (the chat header's name for this contact) where the note names the contact; any other note unchanged.
     */
    fun display(
        id: String,
        text: String,
        name: String,
        copy: ChatNoteCopy,
    ): String {
        val parsed = parse(id, text) ?: return text
        val mode = parsed.mode
        return when (parsed.kind) {
            Kind.TUNNEL -> copy.get(Kind.TUNNEL.res)
            Kind.PEER_FOLLOWED, Kind.PEER_ASKED, Kind.PEER_UNCHANGED ->
                copy.get(parsed.kind.res, name, copy.get(labelRes(mode!!)))
            Kind.YOU_SWITCHED_TO_MATCH -> copy.get(parsed.kind.res, copy.get(labelRes(mode!!)), name)
            Kind.YOU_SWITCHED, Kind.YOU_KEPT -> copy.get(parsed.kind.res, copy.get(labelRes(mode!!)))
        }
    }

    private fun labelRes(mode: ConversationMode): Int =
        when (mode) {
            ConversationMode.VAULT -> R.string.chat_mode_label_vault
            ConversationMode.TUNNEL -> R.string.chat_mode_label_tunnel
            ConversationMode.OPEN -> R.string.chat_mode_label_open
        }

    private fun match(
        template: Template,
        text: String,
    ): Parsed? {
        val t = template.text
        return when (template.slots) {
            Slots.LABEL_ONLY -> {
                val (prefix, suffix) = split1(t)
                if (!text.startsWith(prefix) || !text.endsWith(suffix) || text.length < prefix.length + suffix.length) {
                    return null
                }
                val label = text.substring(prefix.length, text.length - suffix.length)
                modeOf(label)?.let { Parsed(template.kind, it) }
            }
            Slots.NAME_THEN_LABEL -> {
                val (prefix, mid, suffix) = split2(t)
                if (!text.startsWith(prefix) || !text.endsWith(suffix) || text.length < prefix.length + suffix.length) {
                    return null
                }
                val inner = text.substring(prefix.length, text.length - suffix.length)
                LABELS.firstNotNullOfOrNull { (label, mode) ->
                    val tail = mid + label
                    if (inner.endsWith(tail) && inner.length > tail.length) Parsed(template.kind, mode) else null
                }
            }
            Slots.LABEL_THEN_NAME -> {
                val (prefix, mid, suffix) = split2(t)
                if (!text.startsWith(prefix) || !text.endsWith(suffix) || text.length < prefix.length + suffix.length) {
                    return null
                }
                val inner = text.substring(prefix.length, text.length - suffix.length)
                LABELS.firstNotNullOfOrNull { (label, mode) ->
                    val head = label + mid
                    if (inner.startsWith(head) && inner.length > head.length) Parsed(template.kind, mode) else null
                }
            }
        }
    }

    private fun modeOf(label: String): ConversationMode? = LABELS.firstOrNull { it.first == label }?.second

    private fun split1(t: String): Pair<String, String> {
        val at = t.indexOf("%1\$s")
        return t.substring(0, at) to t.substring(at + 4)
    }

    private fun split2(t: String): Triple<String, String, String> {
        val a = t.indexOf("%1\$s")
        val b = t.indexOf("%2\$s")
        return Triple(t.substring(0, a), t.substring(a + 4, b), t.substring(b + 4))
    }
}
