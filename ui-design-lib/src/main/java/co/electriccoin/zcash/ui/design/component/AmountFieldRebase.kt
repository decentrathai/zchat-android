package co.electriccoin.zcash.ui.design.component

import co.electriccoin.zcash.ui.design.util.AmountEditResult

/**
 * QA2 m8 (ZC-1779, ZC-1780, ZC-1781, ZC-1783; money-path rule 5): the one step an amount field takes for each callback of
 * its BasicTextField, shared by [AmountTextFieldSelection] and [NumberFieldEditor]. Pure: no Compose, no state of its own.
 *
 * BasicTextField keeps its own BUFFER between compositions: every callback is made on the last callback, not on what the
 * machine holds. This step keeps the two apart:
 * - ACCEPTED: the machine's text and the selection the field shows;
 * - BUFFER: the last callback since the last composition (what BasicTextField holds), with a MAP from each place of the
 *   buffer to a place of ACCEPTED's text (-1: no place).
 *
 * Rules for a callback N:
 * - R1: N equals BUFFER: a duplicate, nothing happens.
 * - R2: N is one replacement [p, q) -> X of BUFFER (found from BUFFER's selection, else from "the text after N's caret did
 *   not change", else from the common prefix and suffix). It is tried on ACCEPTED at [MAP p, MAP q); BUFFER's selection
 *   ends stand for ACCEPTED's. A place with no image: the callback is not taken.
 * - R3: the tried text equals ACCEPTED's text: a selection move, mapped, no machine call.
 * - R4: else the machine edits the state with the tried text; the selection before is ACCEPTED's.
 * - R5: refused: ACCEPTED stays; in MAP, X's ends map to the ends of the range it would have replaced.
 * - R6: taken: ACCEPTED's text is the machine's. Taken as it is: N's caret. Rewritten: [mappedCaret], and MAP is rebuilt from
 *   the common prefix and suffix of the tried and the machine's texts.
 */
internal object AmountFieldRebase {
    /** A field value: [text] and the selection [a]..[b] (a caret is a == b; [a] may be after [b]). */
    data class Value(
        val text: String,
        val a: Int,
        val b: Int
    ) {
        val lo: Int get() = minOf(a, b)
        val hi: Int get() = maxOf(a, b)

        companion object {
            fun coerced(
                text: String,
                a: Int,
                b: Int
            ) = Value(text, a.coerceIn(0, text.length), b.coerceIn(0, text.length))
        }
    }

    /**
     * The step's outcome: the new ACCEPTED, the machine [state] (the given one unless [taken]), and the MAP of the new
     * buffer (N's text). [ignored] is R1.
     */
    class Outcome<S>(
        val accepted: Value,
        val state: S,
        val taken: Boolean,
        val ignored: Boolean,
        val map: IntArray?
    ) {
        /** True when the field's buffer (N) is exactly ACCEPTED: nothing to remember until the next composition. */
        fun settles(callback: Value): Boolean = callback == accepted && isIdentity(map)
    }

    private fun identity(n: Int) = IntArray(n + 1) { it }

    private fun isIdentity(map: IntArray?): Boolean = map == null || map.indices.all { map[it] == it }

    private fun commonPrefix(
        a: String,
        b: String
    ): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffix(
        a: String,
        b: String,
        max: Int
    ): Int {
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /**
     * R6: where a caret at [c] of the proposed text [p] goes in the text [r] the machine wrote instead: the same distance
     * from the end when the text after the caret survived and something stands before it; else the same place when the
     * text before it survived; else the end.
     */
    fun mappedCaret(
        p: String,
        c: Int,
        r: String
    ): Int {
        val caret = c.coerceIn(0, p.length)
        val tail = p.length - caret
        if (r.endsWith(p.substring(caret)) && (caret == 0 || r.length > tail)) return r.length - tail
        if (r.startsWith(p.substring(0, caret))) return caret
        return r.length
    }

    /**
     * One callback [callback] on [accepted]. [buffer] is what the callback was made on, [map] where each place of [buffer]
     * stands in [accepted]'s text (null: the same place). [state] is the machine state the edit starts from; [textOf] is
     * the text the field shows for a state and [edit] the machine.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ReturnCount")
    fun <S> step(
        accepted: Value,
        buffer: Value,
        map: IntArray?,
        callback: Value,
        state: S,
        textOf: (S) -> String,
        edit: (S, String, IntRange) -> AmountEditResult<S>
    ): Outcome<S> {
        val nv = Value.coerced(callback.text, callback.a, callback.b)
        if (nv == buffer) return Outcome(accepted, state, taken = false, ignored = true, map = map) // R1
        val bt = buffer.text
        val nt = nv.text
        val bl = bt.length
        val nl = nt.length
        val b0 = buffer.lo
        val b1 = buffer.hi
        val m = map ?: identity(bl)

        if (nt == bt) { // R3 on the buffer itself: a selection move
            val ma = if (nv.a == buffer.a) accepted.a else m[nv.a]
            val mb = if (nv.b == buffer.b) accepted.b else m[nv.b]
            if (ma < 0 || mb < 0) return Outcome(accepted, state, taken = false, ignored = false, map = m)
            return Outcome(Value.coerced(accepted.text, ma, mb), state, taken = false, ignored = false, map = m)
        }

        // R2: the one replacement [p, q) -> X that turned the buffer into the callback
        val p: Int
        val q: Int
        if (nl >= b0 + (bl - b1) && nt.startsWith(bt.substring(0, b0)) && nt.endsWith(bt.substring(b1))) {
            p = b0
            q = b1
        } else {
            val after = nl - nv.hi
            if (after <= bl && bt.endsWith(nt.substring(nv.hi))) {
                p = minOf(commonPrefix(bt, nt), minOf(bl - after, nl - after))
                q = bl - after
            } else {
                p = commonPrefix(bt, nt)
                q = bl - commonSuffix(bt, nt, minOf(bl, nl) - p)
            }
        }
        val x = nt.substring(p, nl - (bl - q))
        val xl = x.length
        val p2 = if (p == b0) accepted.lo else m[p]
        var q2 = if (q == b1) accepted.hi else m[q]
        // R5's map: the new buffer's places when ACCEPTED stays
        val keep =
            IntArray(nl + 1) { i ->
                when {
                    i < p -> m[i]
                    i == p -> p2
                    i <= p + xl -> q2
                    else -> m[i - xl + (q - p)]
                }
            }
        if (p2 < 0 || q2 < 0) return Outcome(accepted, state, taken = false, ignored = false, map = keep) // no place
        if (q2 < p2) q2 = p2
        val tried = accepted.text.substring(0, p2) + x + accepted.text.substring(q2)
        val tl = tried.length
        val shift = xl - (q2 - p2)
        // the new buffer's places in the tried text
        val f =
            IntArray(nl + 1) { i ->
                when {
                    i < p -> if (m[i] < 0) -1 else minOf(m[i], p2)
                    i <= p + xl -> p2 + (i - p)
                    else -> m[i - xl + (q - p)].let { o -> if (o < 0) -1 else maxOf(o, q2) + shift }
                }
            }
        val ca = if (f[nv.a] < 0) tl else f[nv.a]
        val cb = if (f[nv.b] < 0) tl else f[nv.b]
        if (tried == accepted.text) { // R3: only the buffer changed (a refused character was removed)
            return Outcome(Value.coerced(accepted.text, ca, cb), state, taken = false, ignored = false, map = f)
        }
        val result = edit(state, tried, accepted.lo..accepted.hi) // R4
        if (!result.taken) return Outcome(accepted, state, taken = false, ignored = false, map = keep) // R5
        val machine = textOf(result.state)
        if (machine == tried) return Outcome(Value(machine, ca, cb), result.state, taken = true, ignored = false, map = f) // R6
        val ca2 = mappedCaret(tried, ca, machine)
        val cb2 = mappedCaret(tried, cb, machine)
        val ml = machine.length
        val cp = commonPrefix(tried, machine)
        val cs = commonSuffix(tried, machine, minOf(tl, ml) - cp)
        val triedMiddle = tl - cs - cp
        val machineMiddle = ml - cs - cp
        val g =
            IntArray(nl + 1) { i ->
                val j = f[i]
                when {
                    j < 0 -> -1
                    j <= cp -> j
                    j >= tl - cs -> j - tl + ml
                    machineMiddle == 0 -> cp
                    triedMiddle == machineMiddle -> j
                    else -> (cb2 - (cb - j)).let { v -> if (v < cp || v > ml - cs) -1 else v }
                }
            }
        return Outcome(Value(machine, ca2, cb2), result.state, taken = true, ignored = false, map = g)
    }
}
