package co.electriccoin.zcash.ui.design.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.AmountEditor
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.design.util.AmountView
import co.electriccoin.zcash.ui.design.util.ChatAmountEditor
import co.electriccoin.zcash.ui.design.util.SendAmountEditor
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.ai.acceptUsdAmountInput
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.math.BigDecimal
import java.util.Locale

/**
 * QA2 m8 (money-path rule 5, amount input): the test harness of the burst tests, ported from the design lane's model
 * (`qa2/m8/design-fable/ZashiSim8.java`, `ZashiSim7.java`, `HolderSim.java`) to the plain JVM.
 *
 * THE FIELD. BasicTextField keeps its own buffer between compositions. A callback is built on that BUFFER (the last
 * callback), not on what the last composition showed; a composition resets the buffer to the value it is given. A burst is
 * several callbacks with no composition between them. Every callback is handed over (BasicTextField dropping a callback
 * equal to the composition's value is ZC-1784, a known limit and out of scope).
 *
 * THE WIRES, as the shipped call sites are wired:
 * - [Wire.SEND] (SendView): ZashiTextField with the filter [ZashiTextFieldProposals]; the call site's lambda holds the
 *   composition's text and the composition's machine state, both stale inside a burst.
 * - [Wire.LOCK] (PaymentLockSettings): a raw OutlinedTextField, no filter; the machine state is a parameter, stale.
 * - [Wire.CHAT] (the chat fields, the new-chat custom amount, the AI top-up): a raw OutlinedTextField; the machine is a
 *   `var by mutableStateOf` read at the callback: fresh.
 * - [Wire.NUMBER] (Swap, Pay, slippage, restore height): ZashiTextField + filter + [NumberFieldEditor]; the owner stores
 *   the state the editor returns.
 *
 * THE SEAM (the only code that knows the shape of the holder). The holder is reached by reflection, so this module compiles
 * at the RED commit and at the fix:
 * - at f19d3ae67 (m7) it is `AmountTextFieldSelection.onValueChange(shown: String, new: TextFieldValue, onEdit: (String,
 *   IntRange) -> Unit)` and the call site edits its own machine state;
 * - at m8 (design section 2) it is `AmountTextFieldSelection<S>.onValueChange(state: S, shown: String, new: TextFieldValue,
 *   edit: (S, String, IntRange) -> AmountEditResult<S>, onState: (S) -> Unit)` with (as built: the holder is made with
 *   `textOf: (S) -> String` and gets the composition's state from `onComposition(state)`, so `onValueChange(new, edit,
 *   onState)`; the call sites then never name the composition's state inside the call)
 *   `data class AmountEditResult<S>(val state: S, val taken: Boolean)` in `co.electriccoin.zcash.ui.design.util`.
 *   The parameters are found by type (String = shown, TextFieldValue = the callback, Function3 = edit, Function1 =
 *   onState, the remaining Object = the composition's state), so a different order does not matter; a different shape
 *   changes [HolderApi] ONLY, with [test-change-ok], and no assertion in the tests.
 * The number editor [NumberFieldEditor] (`shown(state, locale)`, `proposed(proposal, reading, locale)`) is called as it is.
 */
internal data class Pos(
    val text: String,
    val a: Int,
    val b: Int
) {
    val lo: Int get() = minOf(a, b)
    val hi: Int get() = maxOf(a, b)

    fun sameSelection(other: Pos) = a == other.a && b == other.b

    override fun toString() = "[$text] $a..$b"
}

internal fun coercedPos(
    text: String,
    a: Int,
    b: Int
) = Pos(text, a.coerceIn(0, text.length), b.coerceIn(0, text.length))

internal enum class Wire { SEND, LOCK, CHAT, NUMBER }

/** The holder of the amount fields, found by name; see the class comment of this file. */
internal class HolderApi {
    private val type: Class<*> = Class.forName("co.electriccoin.zcash.ui.design.component.AmountTextFieldSelection")

    // m8 as built: the holder is made with the text a field shows for a state (`AmountTextFieldSelection<S>(textOf)`), and
    // each composition hands it the owner's state (`onComposition(state)`); m7 had a no-arg constructor.
    private val textOf: (Any?) -> String = { (it as AmountView).shown }
    private val instance: Any =
        runCatching { type.getDeclaredConstructor(Function1::class.java).newInstance(textOf) }
            .getOrElse { type.getDeclaredConstructor().newInstance() }
    private val onComposition: Method? = type.methods.firstOrNull { it.name == "onComposition" && it.parameterCount == 1 }
    private val valueFor: Method = type.getMethod("valueFor", String::class.java)
    private val onValueChange: Method =
        type.methods.filter { it.name == "onValueChange" }.maxByOrNull { it.parameterCount }
            ?: error("AmountTextFieldSelection.onValueChange is missing")
    private val resultType: Class<*>? =
        runCatching { Class.forName("co.electriccoin.zcash.ui.design.util.AmountEditResult") }.getOrNull()

    /** True when the holder is handed the machine state and its edit returns an AmountEditResult (m8). */
    val passesState: Boolean = onValueChange.parameterTypes.any { it == Function3::class.java }

    init {
        if (passesState) checkNotNull(resultType) { "AmountEditResult is missing" }
    }

    fun valueFor(text: String): TextFieldValue =
        try {
            valueFor.invoke(instance, text) as TextFieldValue
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /**
     * The field proposes [value] while the composition shows [shown] of [state]. [editSeen] edits the machine state it is
     * given (m8) or the call site's own state (m7, [editOwn]); [onState] stores a state.
     */
    fun proposed(
        state: AmountView,
        shown: String,
        value: TextFieldValue,
        editOwn: (String, IntRange) -> Unit,
        edit: (AmountView, String, IntRange) -> AmountView,
        onState: (AmountView) -> Unit
    ) {
        val editSeen: (Any?, String, IntRange) -> Any? = { s, text, range ->
            val from = s as AmountView
            val next = edit(from, text, range)
            resultType!!.constructors.first().newInstance(next, next.handle !== from.handle)
        }
        val store: (Any?) -> Unit = { onState(it as AmountView) }
        val own: (String, IntRange) -> Unit = { text, range -> editOwn(text, range) }
        val args =
            onValueChange.parameterTypes.map { p ->
                when {
                    p == String::class.java -> shown
                    p == TextFieldValue::class.java -> value
                    p == Function3::class.java -> editSeen
                    p == Function2::class.java -> own
                    p == Function1::class.java -> store
                    else -> state
                }
            }
        try {
            // the state of the composition in which this callback arrives (constant within a burst)
            onComposition?.invoke(instance, state)
            onValueChange.invoke(instance, *args.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** What a test sees of a field after an action. */
internal data class Res(
    val key: String,
    val money: String,
    val shown: Pos
) {
    fun same(other: Res) = key == other.key && shown == other.shown

    fun diff(other: Res) =
        when {
            money != other.money -> "AMOUNT"
            key != other.key -> "text or state, same amount"
            else -> "selection only"
        }

    override fun toString() = "$shown $key"
}

internal abstract class BurstField(
    val name: String,
    private val filtered: Boolean
) {
    private val filter = ZashiTextFieldProposals()

    /** What the composition shows. */
    lateinit var shown: Pos
        private set

    /** What BasicTextField's own buffer holds. */
    lateinit var buf: Pos
        private set

    protected abstract fun frame(): Pos

    protected abstract fun handle(
        p: Pos,
        report: InnerTextFieldState?
    )

    abstract val machineText: String
    abstract val money: String
    abstract val key: String

    /** The machine state, for [directKey] and [refusedDirect]. */
    abstract fun snapshot(): Any

    abstract fun directKey(
        snap: Any,
        newText: String,
        a: Int,
        b: Int
    ): String

    /** True when the machine keeps [snap] for this edit (a refusal). */
    abstract fun refusedDirect(
        snap: Any,
        newText: String,
        a: Int,
        b: Int
    ): Boolean

    /** The owner recomposes: the field is given the value the owner shows. */
    fun compose() {
        shown = frame()
        filter.displayed(shown.text, shown.a, shown.b)
        buf = shown
    }

    /** BasicTextField proposes [p]; false when the filter does not report it. */
    fun propose(p: Pos): Boolean {
        buf = p
        var report: InnerTextFieldState? = null
        if (filtered) {
            report = filter.propose(p.text, p.a, p.b) ?: return false
        }
        handle(p, report)
        return true
    }

    fun res() = Res(key, money, shown)
}

internal fun machineKey(view: AmountView) = "${view.shown}|${view.settled}|${view.options}|${view.error}|${view.handle}"

internal class HolderBurstField(
    name: String,
    private val wire: Wire,
    private val editor: AmountEditor
) : BurstField(name, filtered = wire == Wire.SEND) {
    private val api = HolderApi()
    private var st: AmountView = editor.empty()
    private var capState: AmountView = st
    private var capShown: String = ""

    init {
        compose()
    }

    override fun frame(): Pos {
        capState = st
        capShown = st.shown
        val v = api.valueFor(capShown)
        return coercedPos(v.text, v.selection.start, v.selection.end)
    }

    override fun handle(
        p: Pos,
        report: InnerTextFieldState?
    ) {
        val value = TextFieldValue(p.text, TextRange(p.a, p.b))
        if (wire == Wire.CHAT) {
            api.proposed(
                state = capState,
                shown = st.shown,
                value = value,
                editOwn = { text, range -> st = editor.edit(st, text, range) },
                edit = { s, text, range -> editor.edit(s, text, range) },
                onState = { st = it }
            )
        } else {
            api.proposed(
                state = capState,
                shown = capShown,
                value = value,
                editOwn = { text, range -> st = editor.edit(capState, text, range) },
                edit = { s, text, range -> editor.edit(s, text, range) },
                onState = { st = it }
            )
        }
    }

    override val machineText: String get() = st.shown
    override val money: String get() = st.settled
    override val key: String get() = machineKey(st)

    override fun snapshot(): Any = st

    override fun directKey(
        snap: Any,
        newText: String,
        a: Int,
        b: Int
    ) = machineKey(editor.edit(snap as AmountView, newText, a..b))

    override fun refusedDirect(
        snap: Any,
        newText: String,
        a: Int,
        b: Int
    ) = editor.edit(snap as AmountView, newText, a..b).handle === snap.handle
}

internal class NumberBurstField(
    name: String,
    private val reading: NumberFieldReading,
    private val locale: Locale
) : BurstField(name, filtered = true) {
    private val editor = NumberFieldEditor()
    private var vm = NumberTextFieldInnerState()

    init {
        compose()
    }

    private fun plain(state: InnerTextFieldState) = (state.value as StringResource.ByString).value

    override fun frame(): Pos {
        val inner = editor.shown(vm, locale)
        val text = plain(inner)
        val range = inner.getTextRange(text)
        return coercedPos(text, range.start, range.end)
    }

    override fun handle(
        p: Pos,
        report: InnerTextFieldState?
    ) {
        vm = editor.proposed(report!!, reading, locale)
    }

    private fun storedOf(state: NumberTextFieldInnerState) = (state.innerTextFieldState.value as StringResource.ByString).value

    private fun textOf(state: NumberTextFieldInnerState) = state.shownText(storedOf(state), locale)

    private fun keyOf(state: NumberTextFieldInnerState) =
        "${storedOf(state)}|${state.amount}|${state.readings}|" +
            "${state.readError}|${state.exact}|lv=${state.lastValidAmount}"

    override val machineText: String get() = textOf(vm)
    override val money: String get() = vm.amount?.toPlainString().orEmpty()
    override val key: String get() = keyOf(vm)

    override fun snapshot(): Any = vm

    override fun directKey(
        snap: Any,
        newText: String,
        a: Int,
        b: Int
    ): String {
        val v = snap as NumberTextFieldInnerState
        return keyOf(v.edit(newText, textOf(v), locale, reading, a..b))
    }

    override fun refusedDirect(
        snap: Any,
        newText: String,
        a: Int,
        b: Int
    ): Boolean {
        val v = snap as NumberTextFieldInnerState
        return v.edit(newText, textOf(v), locale, reading, a..b) === v
    }
}

/** A site of the 22 the design lane covered (without the token, slippage and height number fields). */
internal class BurstSite(
    val name: String,
    val locale: Locale,
    val wire: Wire,
    val usd: Boolean,
    val make: () -> BurstField
) {
    /** The decimal mark the field shows. */
    val mark: String = if (locale.language == "es" && (wire == Wire.SEND || wire == Wire.NUMBER)) "," else "."

    /** The mark a user types. */
    val typed: String = if (locale.language == "es") "," else "."

    /** The mark of the other kind (a refused second mark). */
    val other: String = if (mark == ",") "." else ","
}

private class BurstAiUsdEditor(
    override val locale: Locale
) : AmountEditor {
    override val unit: AmountUnit = AmountUnit.USD

    override fun empty(): AmountView = ChatAmountEditor.view(AmountFieldState())

    override fun edit(
        from: AmountView,
        newText: String,
        selectionBefore: IntRange?
    ): AmountView = ChatAmountEditor.view(acceptUsdAmountInput(from.handle as AmountFieldState, newText, locale, selectionBefore))

    override fun choose(
        from: AmountView,
        option: String
    ): AmountView = ChatAmountEditor.view((from.handle as AmountFieldState).choose(BigDecimal(option), unit.maxDecimals))
}

internal val burstLocales: List<Locale> = listOf(Locale.US, Locale.forLanguageTag("es-ES"))

internal fun burstSites(): List<BurstSite> =
    burstLocales.flatMap { l ->
        val g = l.language

        fun holder(
            label: String,
            wire: Wire,
            usd: Boolean,
            editor: () -> AmountEditor
        ) = BurstSite(label, l, wire, usd) { HolderBurstField(label, wire, editor()) }

        fun number(
            label: String,
            usd: Boolean,
            reading: NumberFieldReading
        ) = BurstSite(label, l, Wire.NUMBER, usd) { NumberBurstField(label, reading, l) }

        listOf(
            holder("SEND $g ZEC", Wire.SEND, false) { SendAmountEditor(l, AmountUnit.ZEC) },
            holder("SEND $g USD", Wire.SEND, true) { SendAmountEditor(l, AmountUnit.USD) },
            holder("LOCK $g ZEC", Wire.LOCK, false) { ChatAmountEditor(l, AmountUnit.ZEC) },
            holder("CHAT $g ZEC", Wire.CHAT, false) { ChatAmountEditor(l, AmountUnit.ZEC) },
            holder("CHAT $g USD", Wire.CHAT, true) { ChatAmountEditor(l, AmountUnit.USD) },
            holder("AI   $g USD", Wire.CHAT, true) { BurstAiUsdEditor(l) },
            number("NUMBER $g ZEC", false, NumberFieldReading.ZEC),
            number("NUMBER $g USD", true, NumberFieldReading.USD)
        )
    }

// ---------------- actions ----------------

/**
 * The callback the action [act] makes on the buffer [from]; null when it does not apply. [x] is what the composition shows,
 * [first] the action before this one in a burst.
 */
internal fun buildCallback(
    act: String,
    from: Pos,
    x: Pos,
    first: String?
): Pos? {
    val t = from.text
    val n = t.length
    val s0 = from.lo
    val s1 = from.hi
    return when {
        act.startsWith("key:") || act.startsWith("paste:") -> {
            val ins = act.substring(act.indexOf(':') + 1)
            Pos(t.substring(0, s0) + ins + t.substring(s1), s0 + ins.length, s0 + ins.length)
        }
        act == "bs" ->
            when {
                s0 != s1 -> Pos(t.substring(0, s0) + t.substring(s1), s0, s0)
                s0 > 0 -> Pos(t.substring(0, s0 - 1) + t.substring(s0), s0 - 1, s0 - 1)
                else -> null
            }
        act == "comp" || act == "same" -> from
        act == "all" -> if (n == 0 || (s0 == 0 && s1 == n)) null else Pos(t, 0, n)
        // the correction back to the shown text, the caret where it is
        act == "back" -> if (t == x.text) null else Pos(x.text, minOf(s0, x.text.length), minOf(s0, x.text.length))
        // the IME replaces the character it just wrote
        act.startsWith("repl:") -> {
            val k = act.substring(5)
            if (first == null || !first.startsWith("key:") || s0 != s1 || s0 < 1 || first == "key:$k") {
                null
            } else {
                Pos(t.substring(0, s0 - 1) + k + t.substring(s0), s0, s0)
            }
        }
        act.startsWith("c") -> {
            val p =
                when (act) {
                    "cend" -> n
                    "c+1" -> s1 + 1
                    "c-1" -> s0 - 1
                    else -> act.substring(1).toInt()
                }
            if (p < 0 || p > n || (p == s0 && p == s1)) null else Pos(t, p, p)
        }
        else -> throw IllegalArgumentException(act)
    }
}

/** One step with a composition after it: "type:TEXT", "pos:a-b", or one action of [buildCallback]. False when it does not apply. */
internal fun BurstField.one(act: String): Boolean {
    if (act.startsWith("type:")) {
        for (c in act.substring(5)) {
            propose(buildCallback("key:$c", shown, shown, null)!!)
            compose()
        }
        return true
    }
    if (act.startsWith("pos:")) {
        val (a, b) = act.substring(4).split("-").map { it.toInt() }
        if (b > shown.text.length) return false
        propose(Pos(shown.text, a, b))
        compose()
        return true
    }
    val p = buildCallback(act, shown, shown, null) ?: return false
    propose(p)
    compose()
    return true
}

/** The callbacks of a burst: each one is made on the buffer the one before left. */
internal fun burstCallbacks(
    x: Pos,
    acts: List<String>
): List<Pos>? {
    val result = mutableListOf<Pos>()
    var from = x
    var prev: String? = null
    for (act in acts) {
        val p = buildCallback(act, from, x, prev) ?: return null
        result += p
        from = p
        prev = act
    }
    return result
}
