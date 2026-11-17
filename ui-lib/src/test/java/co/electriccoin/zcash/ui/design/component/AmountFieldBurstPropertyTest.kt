package co.electriccoin.zcash.ui.design.component

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * QA2 m8 (money-path rule 5, amount input): the design lane's burst oracle ("seq", with I-a, I-b and I-d), ported from
 * `qa2/m8/design-fable/ZashiSim8.java` to the plain JVM. A burst of 2 or 3 callbacks with no composition between them must
 * leave the field exactly as the same user actions with a composition after each one:
 *
 * - **seq**: the burst equals the same actions done one by one (a key the machine refused exists only in the IME's buffer, so
 *   right after it a Backspace removes it and nothing else, a replacement of it is a key, the caret one step left stands
 *   where the shown caret is; "back" is only meant when the field shows what the IME's buffer holds);
 * - **I-a**: a burst whose every action is refused when done one by one leaves the field exactly as it was;
 * - **I-b**: after a burst a next key edits the machine with the shown text and selection;
 * - **I-d**: callback 19, then the correction back to 12 (the shown text): equals a composition between (ZC-1776).
 *
 * The bursts are the design's FIRST x SECOND (2) and FIRST x SECOND x THIRD (3) action lists from ten roots, on the 16 sites of
 * [AmountFieldBurstM8Test], every callback handed over (ZC-1784, BasicTextField dropping an equal callback, is out of scope).
 * The design's run on the compiled m7 classes of f19d3ae67 found seq off in 185,500 of 1,148,121 and I-a in 41,060 of 125,880;
 * the m8 rule set 0 of 536,285 and 0 of 51,370. This port is smaller (roots only), so it counts less; a green m8 must have
 * 0 bad, and the counts of what was checked must not fall (no vacuous pass).
 */
class AmountFieldBurstPropertyTest {
    private val first = listOf("key:5", "key:.", "key:,", "bs", "paste:12", "paste:\$1.2", "c-1")
    private val second = listOf("back", "key:5", "key:.", "bs", "repl:5", "repl:,", "repl:.", "c-1", "same")
    private val third = listOf("key:5", "key:.", "bs", "repl:5", "back", "c-1", "same")

    private fun bursts(): List<List<String>> {
        val l = mutableListOf<List<String>>()
        for (a in first) {
            for (b in second) {
                l += listOf(a, b)
                for (c in third) l += listOf(a, b, c)
            }
        }
        return l
    }

    private fun roots(site: BurstSite): List<List<String>> {
        val dm = site.typed
        return listOf(
            listOf(),
            listOf("type:12${dm}34", "pos:1-1"),
            listOf("type:12${dm}34", "pos:4-4"),
            listOf("type:12${dm}34"),
            listOf("type:1${dm}2", "pos:0-3"),
            listOf("type:5", "pos:0-0"),
            listOf("type:12", "pos:1-2"),
            listOf("type:0${dm}000", "all"),
            listOf("paste:1,000"),
            listOf("type:12${dm}34", "pos:0-1")
        )
    }

    private fun replay(
        site: BurstSite,
        root: List<String>
    ): BurstField? {
        val f = site.make()
        for (a in root) if (!f.one(a)) return null
        return f
    }

    private class Counts {
        var bursts = 0
        var seqRef = 0
        var seqBad = 0
        var ia = 0
        var iaBad = 0
        var ib = 0
        var ibBad = 0
        var id = 0
        var idBad = 0
        val kinds = sortedMapOf<String, Int>()
        val first = mutableMapOf<String, String>()

        fun kind(
            k: String,
            detail: String
        ) {
            kinds.merge(k, 1, Int::plus)
            first.putIfAbsent(k, detail)
        }
    }

    /** The same actions with a composition after each; null when an action has no meaning one by one. */
    private fun sequential(
        site: BurstSite,
        root: List<String>,
        acts: List<String>,
        cb: List<Pos>,
        x: Pos,
        allRefused: BooleanArray
    ): Res? {
        val z = replay(site, root) ?: return null
        val r0 = z.res()
        var ghost = false
        var prev: String? = null
        var all = true
        var anyText = false
        for ((i, a) in acts.withIndex()) {
            val sh = z.shown
            val collapsed = sh.a == sh.b
            val p: Pos? =
                when {
                    a == "back" -> {
                        if (sh != (if (i == 0) x else cb[i - 1])) return null
                        cb[i]
                    }
                    ghost && a == "bs" && collapsed -> null
                    ghost && a == "c-1" -> Pos(sh.text, sh.lo, sh.lo)
                    ghost && a == "same" -> null
                    ghost && a.startsWith("repl:") -> buildCallback("key:" + a.substring(5), sh, x, null)
                    a.startsWith("repl:") -> buildCallback(a, sh, x, prev) ?: return null
                    else -> buildCallback(a, sh, x, null)
                }
            val text = p != null && p.text != sh.text
            val snapBefore = z.snapshot()
            if (text) anyText = true
            if (p != null && p != sh) {
                z.propose(p)
                z.compose()
            }
            val after = z.res()
            if (a != "same") {
                ghost = text && (a.startsWith("key:") || a.startsWith("repl:")) && z.refusedDirect(snapBefore, p.text, sh.lo, sh.hi)
            }
            if (!after.same(r0)) all = false
            prev = a
        }
        allRefused[0] = all && anyText
        return z.res()
    }

    private fun check(
        site: BurstSite,
        root: List<String>,
        acts: List<String>,
        c: Counts
    ) {
        val z = replay(site, root) ?: return
        val x = z.shown
        val xKey = z.key
        val cb = burstCallbacks(x, acts) ?: return
        val where = "${site.name} root $root burst $acts shown $x callbacks $cb"
        c.bursts++
        for (p in cb) z.propose(p)
        z.compose()
        val r = z.res()
        val allRefused = booleanArrayOf(false)
        val ref = sequential(site, root, acts, cb, x, allRefused)
        if (acts.size == 2 && acts[1] == "back") {
            val t = replay(site, root)!!
            t.propose(cb[0])
            t.compose()
            if (t.shown == cb[0]) {
                t.propose(cb[1])
                t.compose()
                c.id++
                if (!r.same(t.res())) {
                    c.idBad++
                    c.kind("I-d: the correction back does not win (${r.diff(t.res())})", "$where -> $r want ${t.res()}")
                }
            }
        }
        if (ref != null) {
            c.seqRef++
            if (!r.same(ref)) {
                c.seqBad++
                val shape = acts.joinToString(">>") { it.substringBefore(':') }
                c.kind("seq off by ${r.diff(ref)} | ${site.name.substringBefore(' ')} | $shape", "$where -> $r | one by one $ref")
            }
            if (allRefused[0]) {
                c.ia++
                if (r.shown != x || r.key != xKey) {
                    c.iaBad++
                    c.kind("I-a: every action is refused one by one, but the field changed", "$where -> $r")
                }
            }
        }
        // I-b: a next key edits the machine with the shown text and selection
        val sh = z.shown
        val p = buildCallback("key:5", sh, sh, null)!!
        val snap = z.snapshot()
        val want = z.directKey(snap, p.text, sh.lo, sh.hi)
        z.propose(p)
        z.compose()
        c.ib++
        if (z.key != want) {
            c.ibBad++
            c.kind("I-b: the next key is not edited at the shown caret", "$where; shown $sh then 5 -> ${z.shown} ${z.key}")
        }
    }

    @Test
    fun a_burst_of_up_to_three_callbacks_equals_the_same_actions_with_a_composition_after_each() {
        val c = Counts()
        val all = bursts()
        for (site in burstSites()) {
            for (root in roots(site)) {
                for (acts in all) check(site, root, acts, c)
            }
        }
        val summary =
            "checked ${c.bursts} bursts: seq ${c.seqBad} off of ${c.seqRef}; I-a ${c.iaBad} of ${c.ia}; I-b ${c.ibBad} of ${c.ib}; " +
                "I-d ${c.idBad} of ${c.id}"
        println(summary)
        assertTrue(c.seqRef > 20_000 && c.ia > 500 && c.id > 500 && c.ib > 20_000, "the oracle checked too little to count: $summary")
        val report =
            c.kinds.entries.joinToString("\n") { (k, n) -> "  $n x $k\n      e.g. ${c.first[k]?.take(400)}" }
        assertTrue(c.seqBad + c.iaBad + c.ibBad + c.idBad == 0, "$summary\n$report")
    }
}
