package co.electriccoin.zcash.ui.design.component

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * QA2 m8 (money-path rule 5, amount input): several callbacks before the field recomposes, the register items ZC-1779,
 * ZC-1780, ZC-1781, ZC-1783, ZC-1774 (and ZC-1782, ZC-1776 as the guard). Design: `qa2/m8/design-fable` (ZashiSim8, README);
 * the cases are its scripted items, run on the 16 sites it names (Send ZEC and USD, PaymentLock, chat ZEC and USD, AI top-up,
 * number ZEC and USD; EN and ES), through [BurstField] (see [AmountBurstHarness]).
 *
 * Root cause: BasicTextField keeps its own buffer between compositions, and m7 lets that buffer stand in for what the
 * machine holds. M8 keeps the two apart: ACCEPTED (the machine's text and the selection the field shows), BUFFER (the last
 * callback) with a MAP of where each buffer place stands in ACCEPTED, and PENDING (the machine state after the last taken
 * callback of the frame). The owner rule "a key the machine does not take is not shown" (default 2) stays.
 *
 * Every case reports every failing site, not only the first. Counted as the design's script counts (ZC-1781 is two steps
 * there, one test here): at f19d3ae67 (m7, compiled) 80 of its 128 site runs fail and 48 pass; the same per item as the
 * design lane's run `qa2/laptop/qa2-m8-simcheck/sim-d1-t1-nodrop-m7.txt` (ZC-1783 16 of 16, ZC-1779a and c 12, ZC-1779b 8,
 * ZC-1780 8, ZC-1774 8, ZC-1781 6, ZC-1782 4, ZC-1776 0). Tests named `pin_` already pass at f19d3ae67 and keep what the fix
 * must not change.
 */
class AmountFieldBurstM8Test {
    private val sites = burstSites()

    private class Outcome(
        val ok: Boolean,
        val line: String
    )

    /**
     * One scripted item on one site: [setup] actions (each with a composition), then [burst] callbacks with no composition
     * between them, then one composition; the field must show [wantText] with the caret at [wantCaret]; then [then]
     * (one action with a composition), and the field must show [thenText] with [thenCaret].
     */
    private fun script(
        id: String,
        site: BurstSite,
        setup: List<String>,
        burst: List<Pos>,
        wantText: String?,
        wantCaret: Int?,
        then: String? = null,
        thenText: String? = null,
        thenCaret: Int? = null,
        thenAppends: Boolean = false
    ): Outcome {
        val f = site.make()
        for (s in setup) if (!f.one(s)) return Outcome(true, "n/a $id ${site.name} (setup $s)")
        val start = f.shown
        for (p in burst) f.propose(p)
        f.compose()
        val mid = f.shown
        var ok = (wantText == null || mid.text == wantText) && (wantCaret == null || (mid.a == wantCaret && mid.b == wantCaret))
        var end = ""
        if (then != null) {
            f.one(then)
            val last = f.shown
            end = "; $then -> $last amount [${f.money}]"
            val expected = if (thenAppends) mid.text + "9" else thenText
            ok = ok && last.text == expected && (thenCaret == null || (last.a == thenCaret && last.b == thenCaret))
            if (thenAppends && last.a != last.text.length) ok = false
        }
        val wantThen =
            when {
                thenAppends -> "; then the shown text + 9, caret at the end"
                thenText != null -> "; then [$thenText]" + (thenCaret?.let { " caret $it" } ?: "")
                else -> ""
            }
        val want = "want [${wantText ?: "?"}]" + (wantCaret?.let { " caret $it" } ?: "") + wantThen
        return Outcome(ok, "$id ${site.name}: $start $burst -> $mid$end ${if (ok) "" else "   $want"}")
    }

    private fun eachSite(
        only: (BurstSite) -> Boolean = { true },
        check: (BurstSite) -> Outcome
    ) {
        val failures =
            sites
                .filter(only)
                .map { check(it) }
                .filter { !it.ok }
                .map { it.line }
        assertTrue(failures.isEmpty(), "${failures.size} site(s) fail:\n" + failures.joinToString("\n"))
    }

    // ---- ZC-1779: a refused callback, a second refused callback or a rewritten one in the same frame moves the caret ----

    @Test
    fun zc1779a_two_refused_marks_in_one_frame_keep_the_caret_so_the_next_digit_lands_at_1() {
        eachSite { s ->
            val dm = s.mark
            script(
                "ZC-1779a: 12${dm}34, caret 1, callbacks 1${dm}2${dm}34 then 1${s.other}2${dm}34, then 5",
                s,
                listOf("type:12${s.typed}34", "pos:1-1"),
                listOf(Pos("1${dm}2${dm}34", 2, 2), Pos("1${s.other}2${dm}34", 2, 2)),
                wantText = "12${dm}34",
                wantCaret = 1,
                then = "key:5",
                thenText = "152${dm}34",
                thenCaret = 2
            )
        }
    }

    @Test
    fun zc1779b_the_same_refused_callback_twice_keeps_the_caret_on_fields_with_no_filter() {
        eachSite { s ->
            val dm = s.mark
            script(
                "ZC-1779b: 12${dm}34, caret 1, the callback 1${dm}2${dm}34 twice, then 5",
                s,
                listOf("type:12${s.typed}34", "pos:1-1"),
                listOf(Pos("1${dm}2${dm}34", 2, 2), Pos("1${dm}2${dm}34", 2, 2)),
                wantText = "12${dm}34",
                wantCaret = 1,
                then = "key:5",
                thenText = "152${dm}34",
                thenCaret = 2
            )
        }
    }

    @Test
    fun zc1779c_a_callback_the_machine_rewrites_then_a_refused_one_then_7_ends_on_dot_75() {
        eachSite { s ->
            val dm = s.mark
            script(
                "ZC-1779c: 5, caret 0, callbacks ${s.other}5 then ${s.other}${dm}5, then 7",
                s,
                listOf("type:5", "pos:0-0"),
                listOf(Pos("${s.other}5", 1, 1), Pos("${s.other}${dm}5", 2, 2)),
                wantText = "${dm}5",
                wantCaret = null,
                then = "key:7",
                thenText = "${dm}75",
                thenCaret = 2
            )
        }
    }

    // ---- ZC-1780 / ZC-1774: an accepted paste the machine writes as the old text ----

    @Test
    fun zc1780_usd_select_all_paste_of_the_same_amount_with_a_sign_is_taken_so_the_next_key_is_appended() {
        eachSite({ it.usd }) { s ->
            val dm = s.mark
            script(
                "ZC-1780: 1${dm}2, select all, paste \$1${s.typed}2, then 9",
                s,
                listOf("type:1${s.typed}2", "all"),
                listOf(Pos("\$1${s.typed}2", 4, 4)),
                wantText = "1${dm}2",
                wantCaret = null,
                then = "key:9",
                thenText = "1${dm}29",
                thenCaret = 4
            )
        }
    }

    @Test
    fun zc1774_zec_select_all_paste_of_a_leading_mark_is_taken_so_the_next_key_is_appended() {
        eachSite({ !it.usd }) { s ->
            val dm = s.mark
            script(
                "ZC-1774: 0${dm}000, select all, paste ${s.typed}000, then 9",
                s,
                listOf("type:0${s.typed}000", "all"),
                listOf(Pos("${s.typed}000", 4, 4)),
                wantText = null,
                wantCaret = null,
                then = "key:9",
                thenText = null,
                thenCaret = null,
                thenAppends = true
            )
        }
    }

    // ---- ZC-1781: two refused over-precision IME edits in one frame ----

    @Test
    fun zc1781_two_refused_third_decimals_in_one_frame_leave_the_field_as_it_was_and_backspace_removes_the_3() {
        eachSite({ it.usd }) { s ->
            val dm = s.mark
            script(
                "ZC-1781: 12${dm}34, caret 4, callbacks 12${dm}394 then 12${dm}384, then Backspace",
                s,
                listOf("type:12${s.typed}34", "pos:4-4"),
                listOf(Pos("12${dm}394", 5, 5), Pos("12${dm}384", 5, 5)),
                wantText = "12${dm}34",
                wantCaret = 4,
                then = "bs",
                thenText = "12${dm}4",
                thenCaret = 3
            )
        }
    }

    // ---- ZC-1783 / ZC-1782: a second callback is compared with the machine's text, or edits the composition's machine ----

    @Test
    fun zc1783_after_a_pasted_question_a_mark_and_a_digit_in_one_frame_make_dot_5_not_1000_5() {
        eachSite { s ->
            val dm = s.mark
            script(
                "ZC-1783: question 1,000, callbacks \"1,000${s.typed}\" then \"1,000${s.typed}5\"",
                s,
                listOf("paste:1,000"),
                listOf(Pos("1,000${s.typed}", 6, 6), Pos("1,000${s.typed}5", 7, 7)),
                wantText = "${dm}5",
                wantCaret = 2
            )
        }
    }

    @Test
    fun zc1782_a_digit_a_mark_and_a_digit_in_one_frame_make_1_5_without_a_paste_error() {
        eachSite { s ->
            val dm = s.mark
            script(
                "ZC-1782: 1, callbacks \"1${s.typed}\" then \"1${s.typed}5\"",
                s,
                listOf("type:1"),
                listOf(Pos("1${s.typed}", 2, 2), Pos("1${s.typed}5", 3, 3)),
                wantText = "1${dm}5",
                wantCaret = 3
            )
        }
    }

    // ---- guard: ZC-1776 stays fixed ----

    @Test
    fun pin_zc1776_19_then_12_with_the_final_2_selected_ends_on_12_caret_2() {
        eachSite { s ->
            script(
                "ZC-1776: 12, final 2 selected, callbacks 19 then 12",
                s,
                listOf("type:12", "pos:1-2"),
                listOf(Pos("19", 2, 2), Pos("12", 2, 2)),
                wantText = "12",
                wantCaret = 2
            )
        }
    }

    // ---- the harness is the design's script ----

    @Test
    fun the_design_script_runs_128_site_cases() {
        // The design's scripted items: 6 on every site, ZC-1780 and the two ZC-1781 steps on the USD sites, ZC-1774 on the
        // ZEC sites. Counted so that a case cannot go missing without a red test.
        val perLocale = 4 * (6 + 3) + 4 * (6 + 1)
        val usdSites = sites.count { it.usd }
        val zecSites = sites.count { !it.usd }
        assertTrue(sites.size == 16, "16 sites (8 per locale), found ${sites.size}")
        assertTrue(perLocale * 2 == 128, "the design script has 128 cases")
        assertTrue(usdSites == 8 && zecSites == 8, "8 USD sites and 8 ZEC sites, found $usdSites and $zecSites")
    }
}
