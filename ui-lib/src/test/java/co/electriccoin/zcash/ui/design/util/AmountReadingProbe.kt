package co.electriccoin.zcash.ui.design.util

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.fail

/**
 * F1 (fix-all 2.13.0, train T1): the amount reader, the field state and the helpers built on them, called by name.
 *
 * The F1 tests were written before the reader existed, so they reach it through this probe: a missing class or
 * method fails the calling test with "missing: …" (the RED reason) instead of breaking the whole test build. The
 * results are copied into the small test-side types below, so the tests read like ordinary assertions.
 */
internal object AmountReadingProbe {
    private const val PKG = "co.electriccoin.zcash.ui.design.util"
    const val UNIT = "co.electriccoin.zcash.ui.design.component.AmountUnit"

    sealed interface Read {
        data class Exact(
            val text: String
        ) : Read

        data class Ask(
            val options: List<String>,
            val raw: String
        ) : Read

        data class Invalid(
            val reason: String
        ) : Read
    }

    /** A field state: the live object plus what it holds. [ask] holds the options as plain strings. */
    data class Field(
        val handle: Any,
        val shown: String,
        val settled: String,
        val ask: List<String>?,
        val error: String?,
    ) {
        val isQuestionOpen: Boolean get() = ask != null || error != null
    }

    val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        dir ?: error("repo root not found above the test working dir")
    }

    fun source(path: String): String = File(repoRoot, path).readText()

    fun type(name: String): Class<*> =
        try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            fail("missing: $name (F1 not in the build)")
        }

    fun method(
        owner: Class<*>,
        name: String,
        paramCount: Int
    ): Method =
        owner.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
            ?: owner.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }?.also {
                it.isAccessible = true
            }
            ?: fail("missing: ${owner.name}.$name with $paramCount parameters (F1 not in the build)")

    fun call(
        method: Method,
        target: Any?,
        vararg args: Any?
    ): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private val reader: Any by lazy { requireNotNull(type("$PKG.AmountTextReader").getField("INSTANCE").get(null)) }

    fun entry(name: String): Any =
        type("$PKG.AmountEntry").enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            ?: fail("missing: AmountEntry.$name")

    /**
     * The field's unit as the fixup passes it: "ZEC", "USD", or null for a field with no currency (F12's number
     * fields). The probe's default follows the precision the F1 tests pass: 8 is a ZEC field, 2 a dollar field.
     */
    fun unitFor(maxDecimals: Int): String? =
        when (maxDecimals) {
            8 -> "ZEC"
            2 -> "USD"
            else -> null
        }

    /** AmountUnit.[name], or null for null. */
    fun unit(name: String?): Any? =
        name?.let { wanted ->
            type(UNIT).enumConstants?.firstOrNull { (it as Enum<*>).name == wanted } ?: fail("missing: AmountUnit.$wanted")
        }

    /**
     * T1 fixup (X-F1-1): the reader takes the field's [unit]. Before the fixup it has no unit parameter and is called
     * without one, so a test that passes a unit sees what that build does with it: nothing.
     */
    fun read(
        new: String,
        locale: Locale,
        maxDecimals: Int = 8,
        entry: String = "PASTE",
        old: String = "",
        ask: Boolean = true,
        unit: String? = unitFor(maxDecimals)
    ): Read {
        val withUnit = reader.javaClass.methods.firstOrNull { it.name == "read" && it.parameterCount == 7 }
        val result =
            if (withUnit != null) {
                call(withUnit, reader, new, old, entry(entry), locale, maxDecimals, unit(unit), ask)
            } else {
                call(method(reader.javaClass, "read", 6), reader, new, old, entry(entry), locale, maxDecimals, ask)
            }
        return toRead(result!!)
    }

    fun formatForField(
        value: BigDecimal,
        locale: Locale,
        maxDecimals: Int
    ): String = call(method(reader.javaClass, "formatForField", 3), reader, value, locale, maxDecimals) as String

    fun toRead(result: Any): Read =
        when (result.javaClass.simpleName) {
            "Exact" -> Read.Exact(getter(result, "getText") as String)
            "Ask" ->
                Read.Ask(
                    (getter(result, "getOptions") as List<*>).map { plain(it as BigDecimal) },
                    getter(result, "getRaw") as String
                )
            "Invalid" -> Read.Invalid(reasonName(getter(result, "getReason")!!))
            else -> fail("unexpected reader result ${result.javaClass.name}")
        }

    /** "TooManyDecimals(8)", "Unreadable", "PasteIntoAmount" or "OtherUnit(USD)" (the unit whose sign was found). */
    fun reasonName(reason: Any): String =
        when (reason.javaClass.simpleName) {
            "TooManyDecimals" -> "TooManyDecimals(${getter(reason, "getMax")})"
            "OtherUnit" -> "OtherUnit(${(getter(reason, "getFound") as Enum<*>).name})"
            else -> reason.javaClass.simpleName
        }

    fun getter(
        target: Any,
        name: String
    ): Any? = call(method(target.javaClass, name, 0), target)

    fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    // --- AmountFieldState ---

    private val fieldType: Class<*> by lazy { type("$PKG.AmountFieldState") }

    fun emptyField(): Field = field(fieldType.getConstructor().newInstance())

    fun settledField(text: String): Field {
        val companion = fieldType.getField("Companion").get(null)
        return field(call(method(companion.javaClass, "settled", 1), companion, text)!!)
    }

    fun field(handle: Any): Field =
        Field(
            handle = handle,
            shown = getter(handle, "getShown") as String,
            settled = getter(handle, "getSettled") as String,
            ask = (getter(handle, "getAsk") as List<*>?)?.map { plain(it as BigDecimal) },
            error = getter(handle, "getError")?.let(::reasonName),
        )

    /**
     * The field's next state. T1 fixup (X-F1-1): the field state takes the field's unit in place of its precision
     * (the unit has the precision); before the fixup the precision is passed and the unit is not.
     */
    fun Field.edit(
        new: String,
        locale: Locale,
        maxDecimals: Int = 8,
        unit: String? = unitFor(maxDecimals)
    ): Field {
        val edit = method(fieldType, "edit", 4)
        val third = if (edit.parameterTypes[2].name == UNIT) unit(requireNotNull(unit) { "a field needs a unit" }) else maxDecimals
        return field(call(edit, handle, new, locale, third, true)!!)
    }

    /** A keystroke-by-keystroke typing of [keys], each proposing `shown + key` as a Compose field does. */
    fun Field.type(
        locale: Locale,
        vararg keys: String,
        maxDecimals: Int = 8
    ): Field = keys.fold(this) { f, key -> f.edit(f.shown + key, locale, maxDecimals) }

    fun Field.choose(
        value: String,
        maxDecimals: Int = 8
    ): Field = field(call(method(fieldType, "choose", 2), handle, BigDecimal(value), maxDecimals)!!)

    fun Field.reread(
        locale: Locale,
        maxDecimals: Int,
        unit: String? = unitFor(maxDecimals)
    ): Field {
        val reread = method(fieldType, "reread", 2)
        val second = if (reread.parameterTypes[1].name == UNIT) unit(requireNotNull(unit) { "a field needs a unit" }) else maxDecimals
        return field(call(reread, handle, locale, second)!!)
    }
}
