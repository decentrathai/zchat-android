package co.electriccoin.zcash.ui.design.util

import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.screen.chat.util.acceptAmountInput
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import java.math.BigDecimal
import java.util.Locale
import kotlin.time.Clock

/**
 * QA2 m3 (money-path rule 5, the amount parsing path): the one place where the m3 tests reach an amount field.
 *
 * The m3 design is one state machine behind every amount field (chat fields, main Send's ZEC and USD fields, the
 * payment request, the AI top-up). Its behaviour is the same whichever field a user types in, so the tests below state
 * it once (AmountM3ItemsContract, AmountFieldMachinePropertyTest) and run it against each field through an [AmountEditor].
 *
 * What a test sees is an [AmountView]: the text the field shows, the amount that counts ("" while a question or an error
 * is open), the usable readings of an open question, and the reason of an open refusal. These four are what the
 * product exposes whatever the state type behind them is (AmountFieldState, AmountState).
 *
 * THE SEAM. Each editor is the call shape the product screens use today, in one function. When the fix changes the
 * call shape (m3 design section 6: [AmountState.edit] with the selection before the edit, the sealed AmountFieldState),
 * ONLY this file changes, with [test-change-ok]; every assertion in the m3 tests stays as written. In particular, once
 * the fields report the selection (design section 2, rule 7) the editors must pass [selectionBefore] on: the tests
 * named `..._with_the_selection` and the select-all property of AmountFieldMachinePropertyTest fail until they do.
 */
internal data class AmountView(
    /** The text the field shows (a settled amount with the phone's mark in main Send, canonical in the chat fields). */
    val shown: String,
    /** The canonical amount that counts ('.' as the decimal point), "" while a question or an error is open. */
    val settled: String,
    /** The usable readings of an open question, smaller first; null when no question is open. */
    val options: List<String>?,
    /** Why the text is refused; null when no refusal is open. */
    val error: AmountInvalidReason?,
    /** The product state behind the view, only for the editor that made it. */
    val handle: Any,
) {
    val isQuestionOpen: Boolean get() = options != null || error != null
    val isEmpty: Boolean get() = shown.isEmpty() && !isQuestionOpen
}

internal interface AmountEditor {
    val locale: Locale
    val unit: AmountUnit

    /** The empty field. */
    fun empty(): AmountView

    /**
     * The field after the text [newText] arrives. [selectionBefore] is the selection the field had before the edit
     * (start..end; a caret is start == end); null when the caller does not say. Passed on to the product (QA2 m3 fix).
     */
    fun edit(
        from: AmountView,
        newText: String,
        selectionBefore: IntRange? = null
    ): AmountView

    /** The reading [option] tapped under an open question. */
    fun choose(
        from: AmountView,
        option: String
    ): AmountView

    // ---- what a user does, in terms of edit ----

    /** A paste of [text] into the empty field. */
    fun paste(text: String): AmountView = edit(empty(), text, 0..0)

    /** [chars] typed one by one at the end, each proposing `shown + key` as a Compose field does. */
    fun AmountView.typed(chars: String): AmountView = chars.fold(this) { v, c -> edit(v, v.shown + c, v.shown.length..v.shown.length) }

    /** [count] backspaces at the end. */
    fun AmountView.backspaced(count: Int = 1): AmountView =
        (1..count).fold(this) { v, _ ->
            val end = v.shown.length
            edit(v, v.shown.dropLast(1), end..end)
        }

    /** [text] inserted at [index] (one character is a typed key, several are a paste into the text). */
    fun AmountView.insertedAt(
        index: Int,
        text: String
    ): AmountView = edit(this, shown.substring(0, index) + text + shown.substring(index), index..index)

    /** A paste (or a key) of [text] at the end. */
    fun AmountView.pastedAtEnd(text: String): AmountView = edit(this, shown + text, shown.length..shown.length)

    /** Select all, then [text] arrives (typed or pasted over the selection). */
    fun AmountView.allReplacedBy(text: String): AmountView = edit(this, text, 0..shown.length)
}

internal fun BigDecimal.plainText(): String = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

/** The chat amount fields (Send Payment, time-lock, payment request, new-chat custom amount): acceptAmountInput. */
internal class ChatAmountEditor(
    override val locale: Locale,
    override val unit: AmountUnit,
    /** False: the call shape of a field that does not know its selection (QA2 m4, ZC-1759): the selection is never passed on. */
    private val withSelection: Boolean = true
) : AmountEditor {
    override fun empty(): AmountView = view(AmountFieldState())

    override fun edit(
        from: AmountView,
        newText: String,
        selectionBefore: IntRange?
    ): AmountView =
        view(acceptAmountInput(from.handle as AmountFieldState, newText, locale, unit, selectionBefore.takeIf { withSelection }))

    override fun choose(
        from: AmountView,
        option: String
    ): AmountView = view((from.handle as AmountFieldState).choose(BigDecimal(option), unit.maxDecimals))

    /** The payment request's unit switch: this field's unit is the one switched to. */
    fun reread(from: AmountView): AmountView = view((from.handle as AmountFieldState).reread(locale, unit))

    companion object {
        fun view(state: AmountFieldState) =
            AmountView(
                shown = state.shown,
                settled = state.settled,
                options = state.ask?.map { it.plainText() },
                error = state.error,
                handle = state
            )
    }
}

/** An amount field read by [AmountFieldState.edit] alone: no trailing-word rule, and a field that can't ask when [canAsk] is false. */
internal class FieldAmountEditor(
    override val locale: Locale,
    override val unit: AmountUnit,
    private val canAsk: Boolean = true,
    /** False: the call shape of a field that does not know its selection (QA2 m4, ZC-1759). */
    private val withSelection: Boolean = true
) : AmountEditor {
    override fun empty(): AmountView = ChatAmountEditor.view(AmountFieldState())

    override fun edit(
        from: AmountView,
        newText: String,
        selectionBefore: IntRange?
    ): AmountView =
        ChatAmountEditor.view(
            (from.handle as AmountFieldState).edit(
                newText,
                locale,
                unit.maxDecimals,
                unit,
                canAsk,
                selectionBefore.takeIf { withSelection }
            )
        )

    override fun choose(
        from: AmountView,
        option: String
    ): AmountView = ChatAmountEditor.view((from.handle as AmountFieldState).choose(BigDecimal(option), unit.maxDecimals))

    fun reread(from: AmountView): AmountView = ChatAmountEditor.view((from.handle as AmountFieldState).reread(locale, unit))
}

/**
 * Main Send's ZEC field ([unit] ZEC) or USD field ([unit] USD), edited the way SendView's onValueChange does it, with a
 * fresh exchange rate so a settled USD text is a Valid amount.
 */
internal class SendAmountEditor(
    override val locale: Locale,
    override val unit: AmountUnit,
    /** False: a call without the selection, the shape SendView had before QA2 m4 (ZC-1759); SendView passes it now. */
    private val withSelection: Boolean = true
) : AmountEditor {
    private val rate =
        ExchangeRateState.Data(
            isLoading = false,
            isStale = false,
            isRefreshEnabled = true,
            currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = 50.0),
            onRefresh = {}
        )

    private val mark = AmountTextReader.decimalMarkOf(locale)

    private val sendField = if (unit == AmountUnit.ZEC) AmountField.ZEC else AmountField.FIAT

    override fun empty(): AmountView = view(AmountState.newFromZec(locale, "", "", false, rate))

    override fun edit(
        from: AmountView,
        newText: String,
        selectionBefore: IntRange?
    ): AmountView {
        val next =
            AmountState.edit(
                current = from.handle as AmountState,
                field = sendField,
                newText = newText,
                selectionBefore = selectionBefore.takeIf { withSelection },
                locale = locale,
                isTransparentOrTextRecipient = false,
                exchangeRateState = rate
            )
        return view(next)
    }

    override fun choose(
        from: AmountView,
        option: String
    ): AmountView =
        view(
            AmountState.choose(
                ask = from.handle as AmountState.Ask,
                option = BigDecimal(option),
                locale = locale,
                isTransparentOrTextRecipient = false,
                exchangeRateState = rate
            )
        )

    private fun view(state: AmountState): AmountView {
        val shown = if (unit == AmountUnit.ZEC) state.value else state.fiatValue
        val canonical = if (mark == '.') shown else shown.replace(mark, '.')
        return when {
            state is AmountState.Ask && state.field == sendField ->
                AmountView(state.raw, "", state.options.map { it.plainText() }, null, state)
            state is AmountState.Invalid && state.reason != null ->
                AmountView(shown, "", null, state.reason, state)
            else -> AmountView(shown, canonical, null, null, state)
        }
    }
}
