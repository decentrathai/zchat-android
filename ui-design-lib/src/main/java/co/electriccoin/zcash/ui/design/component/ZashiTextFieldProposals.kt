package co.electriccoin.zcash.ui.design.component

import androidx.compose.ui.text.TextRange
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * The filter of ZashiTextField's onValueChange (ZC-1772, ZC-1773, ZC-1776): decides which proposals of the BasicTextField
 * reach the owner of the field.
 *
 * A proposal is reported when its text or selection differs from the last state the filter knows: what the field shows,
 * set on every composition by [displayed], or the last reported proposal since then. So after the owner refuses a proposal
 * and keeps the old caret, a tap to the place the refused proposal had moved the caret to is still a change and is
 * reported; two proposals before a recomposition ("19", then the correction back to the shown "12") are both reported; and
 * two identical proposals with no composition between them are reported once.
 */
class ZashiTextFieldProposals {
    private var lastText: String = ""
    private var lastRange: TextRange = TextRange.Zero

    /** Called on every composition with the text and selection the BasicTextField is given. */
    fun displayed(
        text: String,
        start: Int,
        end: Int
    ) {
        lastText = text
        lastRange = TextRange(start, end)
    }

    /** Called from onValueChange; returns the state to report, or null when nothing changed. */
    fun propose(
        text: String,
        start: Int,
        end: Int
    ): InnerTextFieldState? {
        val range = TextRange(start, end)
        val changed = text != lastText || range != lastRange
        lastText = text
        lastRange = range
        return if (changed) {
            InnerTextFieldState(
                value = stringRes(text),
                selection = TextSelection.ByTextRange(range)
            )
        } else {
            null
        }
    }
}
