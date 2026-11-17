package co.electriccoin.zcash.ui.design.component

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.StringResource
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.fail

/**
 * F12 (fix-all 2.13.0, train T3): the number field (Swap, Pay, slippage, restore height) read through the one amount
 * reader of F1, called by name.
 *
 * The F12 tests were written before the field had a reading, so they reach it through this probe: a missing member
 * fails the calling test with "missing: …" (the RED reason) instead of breaking the whole test build.
 */
internal object NumberFieldProbe {
    private const val READING = "co.electriccoin.zcash.ui.design.component.NumberFieldReading"

    /** What a field's reading says: its precision, its unit ("ZEC", "USD" or null), whether it asks, its ticker. */
    data class Reading(
        val maxDecimals: Int,
        val unit: String?,
        val askOnAmbiguity: Boolean,
        val ticker: String?
    )

    private fun call(
        target: Any,
        name: String,
        vararg args: Any?
    ): Any? = AmountReadingProbe.call(AmountReadingProbe.method(target.javaClass, name, args.size), target, *args)

    private fun toReading(handle: Any): Reading =
        Reading(
            maxDecimals = call(handle, "getMaxDecimals") as Int,
            unit = (call(handle, "getUnit") as Enum<*>?)?.name,
            askOnAmbiguity = call(handle, "getAskOnAmbiguity") as Boolean,
            ticker = call(handle, "getTicker") as String?
        )

    /** The field's reading (F12: every number field says how it reads). */
    fun reading(state: NumberTextFieldState): Reading = toReading(requireNotNull(call(state, "getReading")))

    /** A preset of NumberFieldReading's companion (BLOCK_HEIGHT, SLIPPAGE_PERCENT). */
    fun preset(name: String): Reading = toReading(presetHandle(name))

    /** The live preset object. */
    fun presetHandle(name: String): Any {
        val type = AmountReadingProbe.type(READING)
        val companion =
            runCatching { type.getField("Companion").get(null) }.getOrNull()
                ?: fail("missing: $READING.Companion")
        val getter = "get" + name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }
        val found =
            companion.javaClass.methods.firstOrNull { (it.name == getter || it.name == "get$name") && it.parameterCount == 0 }
                ?: fail("missing: $READING.$name")
        return requireNotNull(AmountReadingProbe.call(found, companion))
    }

    /** A new NumberFieldReading(maxDecimals, unit, askOnAmbiguity, ticker). */
    fun newReading(
        maxDecimals: Int,
        unit: String?,
        ask: Boolean = true,
        ticker: String? = null
    ): Any {
        val ctor =
            AmountReadingProbe.type(READING).constructors.firstOrNull {
                it.parameterCount == 4 && it.parameterTypes.none { p -> p.name.endsWith("DefaultConstructorMarker") }
            } ?: fail("missing: NumberFieldReading(maxDecimals, unit, askOnAmbiguity, ticker)")
        return ctor.newInstance(maxDecimals, AmountReadingProbe.unit(unit), ask, ticker)
    }

    /** An empty field with [reading] (a NumberFieldReading), as a screen builds it. */
    fun field(reading: Any): NumberTextFieldState {
        val ctor =
            NumberTextFieldState::class.java.constructors.firstOrNull { c ->
                c.parameterTypes.any { it.name.endsWith("NumberFieldReading") } &&
                    c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") }
            } ?: fail("missing: NumberTextFieldState(…, reading, …) (F12 not in the build)")
        var resources = 0
        val args =
            ctor.parameterTypes.map { p ->
                when {
                    p.name.endsWith("NumberTextFieldInnerState") -> NumberTextFieldInnerState()
                    p.name.endsWith("NumberFieldReading") -> reading
                    p == Boolean::class.javaPrimitiveType -> true
                    // explicitError (none), then defaultNumberError.
                    p.name.endsWith("StringResource") ->
                        if (resources++ == 0) {
                            null
                        } else {
                            co.electriccoin.zcash.ui.design.util
                                .stringRes("")
                        }
                    p.name.startsWith("kotlin.jvm.functions") -> { _: NumberTextFieldInnerState -> }
                    else -> null
                }
            }
        return ctor.newInstance(*args.toTypedArray()) as NumberTextFieldState
    }

    /** The inner state after the text [new] arrives in the field (a keystroke or a paste), as the field reads it. */
    fun edit(
        state: NumberTextFieldState,
        new: String,
        locale: Locale
    ): NumberTextFieldInnerState = call(state, "edit", new, locale) as NumberTextFieldInnerState

    /** The reading the user tapped under the field. */
    fun choose(
        state: NumberTextFieldState,
        value: String
    ): NumberTextFieldInnerState = call(state, "choose", BigDecimal(value)) as NumberTextFieldInnerState

    /** [keys] typed one by one, each proposing `shown + key` as a Compose field does. */
    fun type(
        state: NumberTextFieldState,
        locale: Locale,
        vararg keys: String
    ): NumberTextFieldState =
        keys.fold(state) { s, key ->
            s.copy(innerState = edit(s, shown(s.innerState, locale) + key, locale))
        }

    /** A paste of [text] into [state] (the whole field replaced), then the new state. */
    fun paste(
        state: NumberTextFieldState,
        text: String,
        locale: Locale
    ): NumberTextFieldState = state.copy(innerState = edit(state, text, locale))

    /** The field's stored text. F12: the app writes only plain text into a number field, never a formatted number. */
    fun text(inner: NumberTextFieldInnerState): String =
        when (val value = inner.innerTextFieldState.value) {
            is StringResource.ByString -> value.value
            else -> fail("the field holds a formatted ${value.javaClass.simpleName} ($value), not the text it shows")
        }

    /** The text the field shows: the stored canonical text with the phone's decimal mark, or an open paste as pasted. */
    fun shown(
        inner: NumberTextFieldInnerState,
        locale: Locale
    ): String {
        val stored = text(inner)
        if (ask(inner) != null || readError(inner) != null) return stored
        val mark = java.text.DecimalFormatSymbols(locale).decimalSeparator
        return if (mark == ',' || mark == '.') stored.replace('.', mark) else stored
    }

    fun ask(inner: NumberTextFieldInnerState): List<String>? =
        (call(inner, "getAsk") as List<*>?)?.map { AmountReadingProbe.plain(it as BigDecimal) }

    fun readError(inner: NumberTextFieldInnerState): String? = call(inner, "getReadError")?.let(AmountReadingProbe::reasonName)

    /** NumberTextFieldInnerState.fromAmount(amount, maxDecimals): an amount from outside (a QR), never rounded. */
    fun fromAmount(
        amount: String,
        maxDecimals: Int
    ): NumberTextFieldInnerState = companionCall("fromAmount", BigDecimal(amount), maxDecimals)

    /** NumberTextFieldInnerState.fromComputed(amount, maxDecimals): a figure the app worked out, at the field's precision. */
    fun fromComputed(
        amount: String,
        maxDecimals: Int
    ): NumberTextFieldInnerState = companionCall("fromComputed", BigDecimal(amount), maxDecimals)

    private fun companionCall(
        name: String,
        vararg args: Any
    ): NumberTextFieldInnerState {
        val companion: Any = NumberTextFieldInnerState.Companion
        val found =
            NumberTextFieldInnerState.Companion::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: fail("missing: NumberTextFieldInnerState.$name with ${args.size} parameters (F12 not in the build)")
        return AmountReadingProbe.call(found, companion, *args) as NumberTextFieldInnerState
    }

    fun plain(value: BigDecimal?): String? = value?.let(AmountReadingProbe::plain)
}
