package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * QA2 m3 (money-path rule 5, the amount parsing path): every amount field is one state machine, and this test walks it.
 * Exhaustive to depth 3 (paste seeds, then every key, insert, delete, select-all-and-type and partial paste on the
 * text on screen, 619k transitions per field before deduplication), on the chat fields, main Send's ZEC and USD fields and
 * the fields that cannot ask, in English and Spanish, ZEC (8 decimals) and dollars (2).
 *
 * The oracle below is independent of the product reader: [typedReading] is "digits, at most one mark, decimals within
 * the precision" and [plausible] lists the numbers a pasted text could mean (the last mark as decimal point, or every
 * mark as grouping). It is the Python check `prop_check.py` of the m3 design (0 violations on the designer's model at
 * depths 2 and 3), ported one to one. After every event:
 *
 * - I1 the amount that counts is canonical typed text: [typedReading] of the text shown returns it unchanged.
 * - I2 a key (one character inserted or deleted, part of the text kept) gives exactly the typing reading of the new text.
 *   On a settled field a key that cannot be taken is not taken (the state does not change) when the field knows the
 *   selection or the key is a pure one-character insertion; a replacement that typing cannot read is refused out loud
 *   when it does not (QA2 m4, ZC-1759, I9). On an open question a key empties the field and, when the selection proves
 *   it is a key, is applied to the empty field (ZC-1760); without the selection the field is just empty. On a text
 *   refused for its decimals a key reads the text on screen as typing reads it, or the new text stays refused; a text
 *   refused for anything else (a currency sign, letters) is read with paste rules once an edit makes it numeric, so
 *   "$1,000" minus the "$" asks (ZC-1762, I10).
 * - I3 a value settles only from the typing reading of the new text, or from a paste reading of it (never from nowhere).
 * - I4 a text with one mark never settles above any of its readings ("1,000" is never 1000 without a tap).
 * - I5 a question never survives a change of the text, and any question is about the text shown.
 * - I6 a field that cannot ask never asks, and no key makes a question (but the edit of a text refused for a sign or
 *   letters, which is read as the paste it was, ZC-1762).
 * - I7 a select-all paste equals the same paste into an empty field (needs the selection: THE SEAM in AmountEditors.kt).
 * - I9 (QA2 m4, ZC-1759) every field is also walked with no selection (the call shape of a field that does not know it).
 *   There, text that is not a pure one-character insertion and that typing cannot read changes the state: it is never
 *   silently dropped while the old amount stays valid.
 * - I10 (QA2 m4, ZC-1762) a text refused for a sign or letters that an edit turns into the one ambiguous shape ("1,000") asks,
 *   or, in a field that cannot ask, stays refused; it never settles.
 * - I8 the payment request's unit switch never changes a settled value, only settles a refused text to a reading of it,
 *   and a settled text comes back unchanged after a round trip.
 * - choose: a tapped reading settles exactly that value, as plain text.
 */
internal class AmountFieldMachinePropertyTest {
    private class Config(
        val name: String,
        val editor: AmountEditor,
        val maxDecimals: Int,
        val canAsk: Boolean,
        /** The unit switch of the payment request: (the editor of the other unit, its precision), chat-style fields only. */
        val other: Pair<AmountEditor, Int>?,
        /** False: the field does not know its selection (QA2 m4, ZC-1759); every edit reaches the machine with none. */
        val withSelection: Boolean = true
    )

    private class Sink {
        val count = linkedMapOf<String, Int>()
        val perConfig = sortedMapOf<String, Int>()
        val examples = linkedMapOf<String, MutableList<String>>()

        fun add(
            kind: String,
            config: Config,
            path: List<String>,
            vararg info: Any?
        ) {
            count[kind] = (count[kind] ?: 0) + 1
            perConfig["${config.name} $kind"] = (perConfig["${config.name} $kind"] ?: 0) + 1
            val list = examples.getOrPut(kind) { mutableListOf() }
            if (list.size < 4) list += "${config.name}: ${path.joinToString(" > ")} | ${info.joinToString(" | ") { describe(it) }}"
        }

        private fun describe(x: Any?): String =
            if (x is AmountView) "[shown=\"${x.shown}\" settled=\"${x.settled}\" options=${x.options} error=${x.error}]" else x.toString()

        fun failures(vararg kinds: String): String =
            kinds
                .filter { (count[it] ?: 0) > 0 }
                .joinToString("\n") { "$it: ${count[it]} violations, e.g.\n    " + examples[it]!!.joinToString("\n    ") }
    }

    private data class Move(
        val name: String,
        val newText: String,
        val selection: IntRange,
        val inserted: Int,
        val whole: Boolean
    )

    private data class Key(
        val shown: String,
        val settled: String,
        val options: List<String>?,
        val error: AmountInvalidReason?
    )

    companion object {
        private fun AmountView.key() = Key(shown, settled, options, error)

        private val en: Locale = Locale.US
        private val es: Locale = Locale.forLanguageTag("es-ES")
        private const val ARABIC_MARK = '٫'
        private const val MARKS = ".,٫"
        private const val DIGITS = "0123456789"

        private val SEEDS =
            (
                "12|0.5|1.5|100|1000|1,234.5|12.|1 000|1,000.50|12.345|12,345|1,000|1.000|1.500|1\u066B234|12.3456|1234.567|" +
                    "$12|$1,000|1,000,000|12,34,567|0.123456789|5 USD|1,5|0.000|0.123456780|1,000 zat|ZEC 1,000|$1.500"
            ).split('|')
        private val MERGES =
            listOf("5" to "5,000", "12" to "12.34", "1" to "1,000.5", "12" to "12.345", "5" to "$5", "0." to "0.25")
        private val REPLACEMENTS = "1,000|1,500|1000|2,500|12.345|1,234.5|7|1|.|1.000,5|12|1,0005|0.5".split('|')
        private val FRAGMENTS = listOf(",000", ".50", "25", "00", ",5")

        private const val DEPTH = 3

        private val configs: List<Config> by lazy {
            fun chat(
                locale: Locale,
                unit: AmountUnit,
                otherUnit: AmountUnit,
                sel: Boolean
            ) = Config(
                "chat/$locale/$unit" + suffix(sel),
                ChatAmountEditor(locale, unit, sel),
                unit.maxDecimals,
                true,
                ChatAmountEditor(locale, otherUnit, sel) to otherUnit.maxDecimals,
                sel
            )

            fun send(
                locale: Locale,
                unit: AmountUnit,
                sel: Boolean
            ) = Config("send/$locale/$unit" + suffix(sel), SendAmountEditor(locale, unit, sel), unit.maxDecimals, true, null, sel)

            fun noAsk(
                locale: Locale,
                unit: AmountUnit,
                sel: Boolean
            ) = Config(
                "no-ask/$locale/$unit" + suffix(sel),
                FieldAmountEditor(locale, unit, canAsk = false, withSelection = sel),
                unit.maxDecimals,
                false,
                null,
                sel
            )
            // QA2 m4 (ZC-1759): the call shape of every editor whose product call passes no selection (the chat fields, main
            // Send, the AI top-up) is walked with and without the selection, and the no-selection outcomes are pinned.
            listOf(true, false).flatMap { sel ->
                listOf(
                    chat(en, AmountUnit.ZEC, AmountUnit.USD, sel),
                    chat(es, AmountUnit.USD, AmountUnit.ZEC, sel),
                    send(en, AmountUnit.ZEC, sel),
                    send(es, AmountUnit.USD, sel),
                    send(en, AmountUnit.USD, sel),
                    noAsk(en, AmountUnit.USD, sel),
                    noAsk(es, AmountUnit.ZEC, sel)
                )
            }
        }

        private fun suffix(sel: Boolean) = if (sel) "" else "/no-selection"

        private val sink: Sink by lazy {
            val sink = Sink()
            configs.forEach { explore(it, sink) }
            println("AMOUNT-MACHINE violations per field and invariant (depth $DEPTH): ${sink.perConfig}")
            sink
        }

        // ---------------- the oracle ----------------

        private fun asciiDigits(text: String): String =
            text
                .map {
                    when (it) {
                        in '٠'..'٩' -> '0' + (it - '٠')
                        in '۰'..'۹' -> '0' + (it - '۰')
                        else -> it
                    }
                }.joinToString("")

        /** Digits, at most one mark (any mark is the decimal point), decimals within [maxDecimals]; null when not readable that way. */
        fun typedReading(
            text: String,
            maxDecimals: Int
        ): String? {
            val t = asciiDigits(text)
            var at = -1
            t.forEachIndexed { i, c ->
                when {
                    c in DIGITS -> Unit
                    c in MARKS -> {
                        if (at >= 0) return null
                        at = i
                    }
                    else -> return null
                }
            }
            if (at < 0) return t
            if (t.length - at - 1 > maxDecimals || maxDecimals == 0) return null
            return t.substring(0, at) + "." + t.substring(at + 1)
        }

        /** The numbers a pasted [text] could mean, and how many marks it has. */
        fun plausible(text: String): Pair<List<BigDecimal>, Int> {
            var t = asciiDigits(text)
            for (token in listOf("US$", "USD", "ZEC", "$", "ⓩ")) t = t.replace(token, "")
            t = t.filter { it !in "    ’'٬" }
            if (t.isEmpty() || t.any { it !in DIGITS && it !in MARKS }) return emptyList<BigDecimal>() to 0
            val marks = t.indices.filter { t[it] in MARKS }
            val digits = t.filter { it in DIGITS }
            if (digits.isEmpty()) return emptyList<BigDecimal>() to marks.size
            val readings = mutableListOf(BigDecimal(digits))
            if (marks.isNotEmpty()) {
                val last = marks.last()
                val integer = t.substring(0, last).filter { it in DIGITS }.ifEmpty { "0" }
                val fraction = t.substring(last + 1).filter { it in DIGITS }
                readings += BigDecimal(integer + if (fraction.isEmpty()) "" else ".$fraction")
            }
            return readings to marks.size
        }

        private fun value(text: String): BigDecimal? = if (text.any { it in DIGITS }) text.toBigDecimalOrNull() else null

        private fun moves(t: String): List<Move> {
            val n = t.length
            val out = mutableListOf<Move>()
            for (c in "057.,$ARABIC_MARK") out += Move("+$c", t + c, n..n, 1, false)
            if (n > 0) out += Move("bs", t.dropLast(1), n..n, 0, false)
            for (i in 0 until n) {
                for (c in ".,5") out += Move("ins$c@$i", t.substring(0, i) + c + t.substring(i), i..i, 1, false)
                if (i < n - 1) out += Move("del@$i", t.removeRange(i, i + 1), (i + 1)..(i + 1), 0, false)
            }
            for (p in REPLACEMENTS) out += Move("all>$p", p, 0..n, p.length, true)
            for (f in FRAGMENTS) out += Move("paste$f@end", t + f, n..n, f.length, false)
            return out
        }

        // ---------------- the walk ----------------

        private fun explore(
            cfg: Config,
            sink: Sink
        ) {
            val ed = cfg.editor
            val seen = HashSet<Key>()
            var frontier = ArrayList<Pair<AmountView, List<String>>>()

            fun start(
                view: AmountView,
                path: List<String>
            ) {
                if (seen.add(view.key())) frontier += view to path
            }
            for (seed in SEEDS) start(ed.edit(ed.empty(), seed, 0..0), listOf("paste '$seed'"))
            for ((typed, new) in MERGES) {
                var f = ed.empty()
                for (c in typed) f = ed.edit(f, f.shown + c, f.shown.length..f.shown.length)
                start(ed.edit(f, new, typed.length..typed.length), listOf("type '$typed'", "paste-into '$new'"))
            }
            val mark = AmountTextReader.decimalMarkOf(ed.locale)
            for (depth in 0 until DEPTH) {
                val next = ArrayList<Pair<AmountView, List<String>>>()
                for ((f, path) in frontier) {
                    checkState(cfg, f, path, mark, sink)
                    for (m in moves(f.shown)) {
                        if (m.newText == f.shown) continue
                        val p = path + m.name
                        val g =
                            try {
                                ed.edit(f, m.newText, m.selection)
                            } catch (e: Exception) {
                                sink.add("crash", cfg, p, f, e.toString())
                                continue
                            }
                        checkMove(cfg, ed, f, g, m, p, sink)
                        if (seen.add(g.key())) next += g to p
                    }
                    f.options?.forEach { option ->
                        val g = ed.choose(f, option)
                        val p = path + "choose $option"
                        val wrongValue = value(g.settled)?.compareTo(BigDecimal(option)) != 0
                        val notPlain = g.settled.any { it !in DIGITS && it != '.' }
                        if (wrongValue || notPlain || g.options != null || g.error != null) sink.add("choose", cfg, p, f, g)
                        if (seen.add(g.key())) next += g to p
                    }
                    cfg.other?.let { (otherEditor, otherDecimals) -> checkSwitch(cfg, otherEditor, otherDecimals, f, path, sink) }
                }
                frontier = next
            }
        }

        private fun canonical(
            view: AmountView,
            mark: Char
        ): String = if (mark == '.') view.shown else view.shown.replace(mark, '.')

        private fun checkState(
            cfg: Config,
            f: AmountView,
            path: List<String>,
            mark: Char,
            sink: Sink
        ) {
            if (f.options == null && f.error == null && f.shown.isNotEmpty()) {
                if (f.settled != canonical(f, mark) || typedReading(f.settled, cfg.maxDecimals) != f.settled) sink.add("I1", cfg, path, f)
            }
            if (f.options != null && !cfg.canAsk) sink.add("I6", cfg, path, f)
            if (f.options != null && f.settled.isNotEmpty()) sink.add("I5", cfg, path, f)
        }

        /** The text diff the machine falls back to when the selection is unknown: [inserted] characters, [pure] = one inserted, none removed. */
        private class Diff(
            val inserted: Int,
            val keptSome: Boolean,
            val pure: Boolean
        )

        private fun diffOf(
            old: String,
            new: String
        ): Diff {
            val shorter = minOf(old.length, new.length)
            val prefix = old.commonPrefixWith(new).length
            val suffix = old.commonSuffixWith(new).length.coerceAtMost(shorter - prefix)
            val inserted = new.length - prefix - suffix
            return Diff(inserted, prefix + suffix > 0, pure = new.length == old.length + 1 && inserted == 1)
        }

        /** The one shape that reads two ways: 1 to 3 digits, one mark, exactly 3 digits. */
        private val AMBIGUOUS_SHAPE = Regex("^[1-9]\\d{0,2}[.,\u066B]\\d{3}$")

        private fun checkMove(
            cfg: Config,
            ed: AmountEditor,
            f: AmountView,
            g: AmountView,
            m: Move,
            p: List<String>,
            sink: Sink
        ) {
            val new = m.newText
            if (new.isEmpty()) {
                if (!(g.shown == "" && g.settled == "" && g.options == null && g.error == null)) sink.add("clear", cfg, p, f, g)
                return
            }
            if (g.options != null && g.shown != new) sink.add("I5", cfg, p, f, g)
            if (g.options != null && !cfg.canAsk) sink.add("I6", cfg, p, f, g)
            if (f.options != null && g.shown == f.shown && g.options == f.options) sink.add("I5", cfg, p, f, g)
            val mark = AmountTextReader.decimalMarkOf(ed.locale)
            val d = diffOf(f.shown, new)
            // With the selection a key is what the move says it is; without it the field only has the text diff.
            val key = if (cfg.withSelection) m.inserted <= 1 && !m.whole else d.keptSome && d.inserted <= 1
            // A single character that replaces everything is typed on the empty field (a select-all key).
            val freshKey = if (cfg.withSelection) m.whole && m.inserted <= 1 else !d.keptSome && d.inserted <= 1
            val typed = typedReading(new, cfg.maxDecimals)
            // A text refused for a currency sign or letters (not for its decimals) is read as the paste it was (ZC-1762).
            val refusedBySign = f.error != null && f.error !is AmountInvalidReason.TooManyDecimals
            val closed = g.options == null && g.error == null
            if (freshKey) {
                // I2b: nothing of the old text is kept, so the one character is typed on the empty field.
                val want = typed ?: ""
                if (!(g.settled == want && closed)) sink.add("I2", cfg, p, f, g, "want settled \"$want\" (a key on the emptied field)")
            }
            if (key) {
                if (g.options != null && !refusedBySign) sink.add("I6", cfg, p, f, g)
                when {
                    f.options != null -> {
                        // ZC-1760: with the selection the key is applied to the emptied field; without it the field is just empty.
                        val applied =
                            if (cfg.withSelection && m.inserted == 1) {
                                typedReading(new.substring(m.selection.first, m.selection.first + 1), cfg.maxDecimals)
                            } else {
                                null
                            }
                        val want = applied ?: ""
                        if (!(g.settled == want && closed && canonical(g, mark) == want)) {
                            sink.add("I2", cfg, p, f, g, "want a field of \"$want\" (the key applied to the emptied field)")
                        }
                    }
                    f.error == null -> {
                        when {
                            typed != null || cfg.withSelection || d.pure -> {
                                val want = typed ?: f.settled
                                if (!(g.settled == want && closed)) sink.add("I2", cfg, p, f, g, "want settled \"$want\"")
                            }
                            // I9 (ZC-1759): the selection is unknown, the change is not a pure insertion and typing cannot
                            // read it: it is refused out loud, never dropped while the old amount stays valid.
                            else ->
                                if (!(g.error != null && g.shown == new && g.settled == "" && g.options == null)) {
                                    sink.add("I9", cfg, p, f, g, "the change is dropped or taken silently")
                                }
                        }
                    }
                    refusedBySign -> Unit
                    typed != null -> if (!(g.settled == typed && closed)) sink.add("I2", cfg, p, f, g, "want settled \"$typed\"")
                    else -> if (!(g.error != null && g.shown == new && g.settled == "" && g.options == null)) sink.add("I2", cfg, p, f, g)
                }
                if (refusedBySign) {
                    // I10 is held to the plain cases only: a raw paste with a currency sign or letters that an edit makes numeric.
                    // (p.size == 2: f is a paste into the empty field.)
                    val rawSignOrLetters =
                        f.error is AmountInvalidReason.OtherUnit ||
                            (f.error == AmountInvalidReason.Unreadable && p.size == 2)
                    if (rawSignOrLetters && AMBIGUOUS_SHAPE.matches(asciiDigits(new))) {
                        // I10 (ZC-1762): the edit made the text numeric, and it reads two ways: it asks (or is refused where it cannot).
                        val ok =
                            if (cfg.canAsk) {
                                g.options != null && g.shown == new && g.settled == ""
                            } else {
                                g.error != null && g.settled == "" && g.options == null
                            }
                        if (!ok) sink.add("I10", cfg, p, f, g, "a text that reads two ways must ask")
                    } else if (g.settled.isNotEmpty()) {
                        val v = value(g.settled)
                        val (readings, _) = plausible(new)
                        if (g.settled != typed && (v == null || readings.none { it.compareTo(v) == 0 })) sink.add("I3", cfg, p, f, g)
                    }
                }
            } else if (g.settled.isNotEmpty() && g.settled != f.settled) {
                val v = value(g.settled)
                val (readings, marks) = plausible(new)
                if (g.settled != typed && (v == null || readings.none { it.compareTo(v) == 0 })) sink.add("I3", cfg, p, f, g)
                if (readings.isNotEmpty() && marks == 1 && v != null && v > readings.minOrNull()!!) sink.add("I4", cfg, p, f, g)
            }
            if (key && g.settled.isNotEmpty() && g.settled != f.settled) {
                val v = value(g.settled)
                // A key on an open question is applied to the emptied field (ZC-1760): the text it settles is the text shown.
                val (readings, marks) = plausible(if (f.options != null) g.shown else new)
                if (readings.isNotEmpty() && marks == 1 && v != null && v > readings.minOrNull()!!) sink.add("I4", cfg, p, f, g)
            }
            if (cfg.withSelection && m.whole && m.inserted >= 2) {
                val fresh = ed.paste(new)
                if (fresh.key() != g.key()) sink.add("I7", cfg, p, f, g, "an empty field gives", fresh)
            }
        }

        private fun checkSwitch(
            cfg: Config,
            otherEditor: AmountEditor,
            otherDecimals: Int,
            f: AmountView,
            path: List<String>,
            sink: Sink
        ) {
            val g = reread(otherEditor, f)
            val p = path + "switch"
            val fv = value(f.settled)
            val gv = value(g.settled)
            if (fv != null && gv != null && fv.compareTo(gv) != 0) sink.add("I8", cfg, p, f, g)
            if (gv != null && fv == null) {
                val (readings, _) = plausible(f.shown)
                if (readings.none { it.compareTo(gv) == 0 }) sink.add("I8", cfg, p, f, g)
            }
            if (g.settled.isNotEmpty() && typedReading(g.settled, otherDecimals) != g.settled) sink.add("I8", cfg, p, f, g)
            if (f.options == null && f.error == null && f.settled.isNotEmpty()) {
                val back = reread(cfg.editor, g)
                val unchanged = back.settled == f.settled && back.options == null && back.error == null
                if (!unchanged) sink.add("I8", cfg, p + "switch", f, g, back)
            }
        }

        private fun reread(
            editor: AmountEditor,
            from: AmountView
        ): AmountView =
            when (editor) {
                is ChatAmountEditor -> editor.reread(from)
                is FieldAmountEditor -> editor.reread(from)
                else -> error("no unit switch on $editor")
            }
    }

    private fun assertNone(vararg kinds: String) {
        val failures = sink.failures(*kinds)
        assertTrue(failures.isEmpty(), "the amount machine broke ${kinds.joinToString()} :\n$failures")
    }

    @Test
    fun i1_the_amount_that_counts_is_canonical_typed_text() = assertNone("I1")

    @Test
    fun i2_a_key_gives_the_typing_reading_and_on_a_question_empties_the_field_and_is_applied_to_it_when_the_selection_proves_it() =
        assertNone("I2", "clear")

    @Test
    fun i9_without_the_selection_a_change_that_typing_cannot_read_is_refused_out_loud_never_dropped() = assertNone("I9")

    @Test
    fun i10_a_text_refused_for_a_sign_or_letters_that_an_edit_makes_ambiguous_asks() = assertNone("I10")

    @Test
    fun i3_i4_a_settled_value_comes_only_from_a_reading_of_the_new_text_and_never_above_any_reading() = assertNone("I3", "I4")

    @Test
    fun i5_a_question_never_survives_a_change_of_the_text_and_is_about_the_text_shown() = assertNone("I5")

    @Test
    fun i6_a_field_that_cannot_ask_never_asks_and_no_key_makes_a_question() = assertNone("I6")

    @Test
    fun i7_a_select_all_paste_is_the_same_as_a_paste_into_an_empty_field() = assertNone("I7")

    @Test
    fun i8_a_unit_switch_never_changes_a_settled_value_and_round_trips() = assertNone("I8")

    @Test
    fun no_edit_ever_throws() = assertNone("crash")

    @Test
    fun choose_a_tapped_reading_settles_exactly_that_value_as_plain_text() = assertNone("choose")
}
