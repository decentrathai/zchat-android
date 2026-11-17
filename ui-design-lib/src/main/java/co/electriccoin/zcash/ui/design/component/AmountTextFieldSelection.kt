package co.electriccoin.zcash.ui.design.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * QA2 m4, ZC-1759 (money-path rule 5): the selection an amount field had before each edit, for every amount field whose
 * text lives in a String (main Send's ZEC and USD fields, the chat amount fields, the AI top-up custom amount). The amount
 * machine tells a select-all paste from a typed key by the selection before the edit.
 *
 * QA2 m5, ZC-1767: a key the machine does not take is not shown, and the field keeps the selection it had before it.
 *
 * QA2 m8 (ZC-1779, ZC-1780, ZC-1781, ZC-1782, ZC-1783): BasicTextField keeps its own buffer between compositions, so
 * several callbacks can arrive before the field recomposes, each made on the one before. This holder keeps what the machine
 * holds apart from that buffer and translates between them with [AmountFieldRebase.step]:
 * - ACCEPTED (Compose state): the machine's text and the selection the field shows;
 * - BUFFER and MAP: the last callback since the last composition and where its places stand in ACCEPTED;
 * - PENDING: the machine state after the last taken callback of this frame (else the composition's, [onComposition]).
 * Each edit is made by the holder on PENDING or the composition's state and reported to [onValueChange]'s `onState` only
 * when the machine took it, so no call site edits the composition's (stale) state. A callback that leaves the buffer
 * different from ACCEPTED bumps a tick that [valueFor] reads, so the owner recomposes in the frame in which BasicTextField
 * resets its buffer; that composition clears BUFFER, MAP and PENDING.
 *
 * [textOf] is the text the field shows for a machine state.
 */
@Stable
class AmountTextFieldSelection<S>(
    private val textOf: (S) -> String
) {
    private class Shown(
        val value: TextFieldValue,
        val tick: Int
    )

    private class Box<S>(
        val state: S
    )

    /** ACCEPTED and the tick, read by [valueFor]. */
    private var shown by mutableStateOf(Shown(TextFieldValue(), 0))

    /** The machine state of the last composition. Not Compose state. */
    private var composed: Box<S>? = null

    /** BUFFER, its MAP and PENDING; null right after a composition. Not Compose state. */
    private var buffer: AmountFieldRebase.Value? = null
    private var map: IntArray? = null
    private var pending: Box<S>? = null

    /** Each composition: the owner's machine [state] (what [valueFor]'s text is the text of). */
    fun onComposition(state: S) {
        composed = Box(state)
    }

    /**
     * Each composition: the value the field shows for the machine's [text]: ACCEPTED when its text is [text], else [text]
     * with ACCEPTED's selection clamped to it (an outside change of the text).
     */
    fun valueFor(text: String): TextFieldValue {
        val current = shown
        buffer = null
        map = null
        pending = null
        return peek(current.value, text)
    }

    private fun peek(
        accepted: TextFieldValue,
        text: String
    ): TextFieldValue =
        if (accepted.text == text) {
            accepted
        } else {
            val selection = accepted.selection
            TextFieldValue(text, TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length)))
        }

    private fun TextFieldValue.asValue() = AmountFieldRebase.Value(text, selection.start, selection.end)

    /**
     * The field proposes [new]. [edit] is the amount machine: it edits the state it is given (PENDING or the composition's)
     * with the new text and ACCEPTED's selection, and says whether it took the edit; [onState] gets each state it took.
     */
    fun onValueChange(
        new: TextFieldValue,
        edit: (state: S, newText: String, selectionBefore: IntRange) -> AmountEditResult<S>,
        onState: (S) -> Unit
    ) {
        val base = pending ?: composed ?: return
        val current = shown
        val accepted = peek(current.value, textOf(base.state)).asValue()
        val callback = new.asValue()
        val held = buffer
        val out =
            AmountFieldRebase.step(
                accepted = accepted,
                buffer = held ?: accepted,
                map = if (held != null) map else null,
                callback = callback,
                state = base.state,
                textOf = textOf,
                edit = edit
            )
        if (out.ignored) return
        if (out.taken) pending = Box(out.state)
        if (out.settles(callback)) {
            buffer = null
            map = null
            shown = Shown(new, current.tick)
        } else {
            buffer = callback
            map = out.map
            shown = Shown(TextFieldValue(out.accepted.text, TextRange(out.accepted.a, out.accepted.b)), current.tick + 1)
        }
        if (out.taken) onState(out.state)
    }

    /**
     * The ZashiTextField state of an amount field showing [text] (the EnhancedTextFieldState overload reports the
     * selection); [edit] and [onState] as in [onValueChange].
     */
    @Composable
    fun textFieldState(
        text: String,
        error: String?,
        isEnabled: Boolean = true,
        edit: (state: S, newText: String, selectionBefore: IntRange) -> AmountEditResult<S>,
        onState: (S) -> Unit
    ): EnhancedTextFieldState {
        val context = LocalContext.current
        val locale = rememberDesiredFormatLocale()
        return EnhancedTextFieldState(
            innerState = InnerTextFieldState(stringRes(text), TextSelection.ByTextRange(valueFor(text).selection)),
            error = error?.let { stringRes(it) },
            isEnabled = isEnabled,
            onValueChange = { inner ->
                val newText = inner.value.getString(context, locale)
                onValueChange(TextFieldValue(newText, inner.getTextRange(newText)), edit, onState)
            }
        )
    }
}

/**
 * One [AmountTextFieldSelection] per amount field, kept across recompositions; each composition hands it the owner's machine
 * [state]. [textOf] is the text the field shows for a state.
 */
@Composable
fun <S> rememberAmountTextFieldSelection(
    state: S,
    textOf: (S) -> String
): AmountTextFieldSelection<S> = remember { AmountTextFieldSelection(textOf) }.also { it.onComposition(state) }
