package co.electriccoin.zcash.ui.design.util

import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.component.NumberFieldReading
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.screen.ai.acceptUsdAmountInput
import co.electriccoin.zcash.ui.screen.chat.util.acceptAmountInput
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * QA2 m8 (money-path rule 5, amount input), design section 1: the amount machines report whether they TOOK an edit.
 *
 * Today a refusal is only visible as "the machine returned the same instance"; the field holder cannot tell a refusal from
 * an accepted edit whose new state equals the old one (ZC-1780: USD 1.2, select all, paste "$1.2" is read as 1.2, the same
 * state; the holder takes it for a refusal and keeps select-all, so the next 9 gives 9, not 1.29; ZC-1774 is the same in
 * the ZEC field). The design adds, in AmountTextReader.kt (package `co.electriccoin.zcash.ui.design.util`):
 *
 *     data class AmountEditResult<S>(val state: S, val taken: Boolean)
 *
 * and one `...Result` variant per machine (the existing function becomes `result.state`, so no other caller changes):
 * `AmountFieldState.editResult(...)`, `AmountState.Companion.editResult(...)`, `acceptAmountInputResult(...)`,
 * `acceptUsdAmountInputResult(...)` and `NumberTextFieldInnerState.editResult(...)`, each with the parameters of the function
 * it wraps. `taken` is false for exactly the three `return this` exits of [AmountFieldState.edit] (the new text equals the
 * shown text, a key that cannot be taken, a read that asks) and true for every other exit, also when the new state equals the
 * old one.
 *
 * The variants are found by reflection (the name, with the parameters of the plain function), so this module compiles at the
 * RED commit; every test fails with "... is missing" until the fix adds them. Each test also checks that `edit` still gives
 * `editResult(...).state`, and that the case table agrees with what the plain function does today (a refusal is the same
 * instance). Tests named `pin_` already pass at f19d3ae67.
 */
class AmountEditResultTest {
    private val en: Locale = Locale.US

    private val rate =
        ExchangeRateState.Data(
            isLoading = false,
            isStale = false,
            isRefreshEnabled = true,
            currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = 50.0),
            onRefresh = {}
        )

    /** One edit of a machine: the state before it, the text arriving and the selection before. */
    private class Case(
        val title: String,
        val shown: String,
        val newText: String,
        val selection: IntRange,
        val taken: Boolean
    )

    /** The cases, all in a USD field (two decimals). Each says whether the machine takes the edit. */
    private val cases =
        listOf(
            // the three `return this` exits of AmountFieldState.edit that are reachable
            Case("the new text equals the shown text", "12.34", "12.34", 5..5, taken = false),
            Case("a third dollar decimal is not taken", "12.34", "12.345", 5..5, taken = false),
            Case("a letter typed at the end is not taken", "12.34", "12.34x", 5..5, taken = false),
            // everything else is taken
            Case("a digit is typed", "12.3", "12.34", 4..4, taken = true),
            Case("a backspace", "12.34", "12.3", 4..4, taken = true),
            Case("the field is emptied", "12.34", "", 0..5, taken = true),
            Case("an accepted paste into the empty field", "", "1.2", 0..0, taken = true),
            Case("ZC-1780: \$1.2 over 1.2 (select all) reads as 1.2: the same state, taken", "1.2", "\$1.2", 0..3, taken = true),
            Case("a refused paste is still an edit that was taken (it shows the error)", "", "1.2.3", 0..0, taken = true)
        )

    private fun resultOf(
        owner: Class<*>,
        target: Any?,
        name: String,
        vararg args: Any?
    ): Pair<Any?, Boolean> {
        val method =
            owner.methods.firstOrNull { it.name.startsWith(name) && !it.name.contains("\$default") && it.parameterCount == args.size }
                ?: fail("$name(${args.size} parameters) is missing on ${owner.simpleName}: the machine cannot say whether it took an edit")
        val result =
            try {
                method.invoke(target, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        val type = result!!.javaClass
        assertEquals("AmountEditResult", type.simpleName, "$name returns ${type.simpleName}, not AmountEditResult")
        return type.getMethod("getState").invoke(result) to (type.getMethod("getTaken").invoke(result) as Boolean)
    }

    private fun chatFieldWith(text: String): AmountFieldState {
        val chat = ChatAmountEditor(en, AmountUnit.USD)
        return chat.edit(chat.empty(), text, 0..0).handle as AmountFieldState
    }

    private fun eachCase(check: (Case) -> String?) {
        val failures = cases.mapNotNull { case -> check(case)?.let { "${case.title}: $it" } }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- AmountFieldState ----

    @Test
    fun the_field_machine_says_whether_it_took_the_edit() {
        eachCase { case ->
            val before = AmountFieldState.settled("").let { it.edit(case.shown, en, AmountUnit.USD) }
            val plain = before.edit(case.newText, en, 2, AmountUnit.USD, true, case.selection)
            val (state, taken) =
                resultOf(AmountFieldState::class.java, before, "editResult", case.newText, en, 2, AmountUnit.USD, true, case.selection)
            when {
                (plain !== before) != case.taken -> "the case table says taken=${case.taken}, the plain edit says ${plain !== before}"
                taken != case.taken -> "taken=$taken, wanted ${case.taken}"
                state != plain -> "the state differs from edit(): $state vs $plain"
                else -> null
            }
        }
    }

    @Test
    fun zc1780_an_accepted_paste_that_reads_as_the_old_state_is_taken_although_the_state_is_equal() {
        val before = AmountFieldState.settled("1.2")
        val (state, taken) = resultOf(AmountFieldState::class.java, before, "editResult", "\$1.2", en, 2, AmountUnit.USD, true, 0..3)
        assertEquals(before, state, "ZC-1780: \$1.2 over 1.2 reads as 1.2")
        assertTrue(taken, "ZC-1780: the paste was accepted; the holder must not take it for a refusal and keep select-all")
    }

    // ---- AmountState (main Send) ----

    @Test
    fun the_send_machine_says_whether_it_took_the_edit() {
        val editor = SendAmountEditor(en, AmountUnit.USD)
        eachCase { case ->
            val from = editor.edit(editor.empty(), case.shown, 0..0)
            val current = from.handle as AmountState
            val plain = AmountState.edit(current, AmountField.FIAT, case.newText, case.selection, en, false, rate)
            val (state, taken) =
                resultOf(
                    AmountState.Companion::class.java,
                    AmountState.Companion,
                    "editResult",
                    current,
                    AmountField.FIAT,
                    case.newText,
                    case.selection,
                    en,
                    false,
                    rate
                )
            when {
                (plain !== current) != case.taken -> "the case table says taken=${case.taken}, the plain edit says ${plain !== current}"
                taken != case.taken -> "taken=$taken, wanted ${case.taken}"
                state != plain -> "the state differs from edit(): $state vs $plain"
                else -> null
            }
        }
    }

    // ---- acceptAmountInput (chat fields) and acceptUsdAmountInput (AI top-up) ----

    @Test
    fun the_chat_amount_input_says_whether_it_took_the_edit() {
        val file = Class.forName("co.electriccoin.zcash.ui.screen.chat.util.PaymentAmountInputKt")
        eachCase { case ->
            val field = chatFieldWith(case.shown)
            val plain = acceptAmountInput(field, case.newText, en, AmountUnit.USD, case.selection)
            val (state, taken) =
                resultOf(file, null, "acceptAmountInputResult", field, case.newText, en, AmountUnit.USD, case.selection)
            when {
                (plain !== field) != case.taken -> "the case table says taken=${case.taken}, the plain edit says ${plain !== field}"
                taken != case.taken -> "taken=$taken, wanted ${case.taken}"
                state != plain -> "the state differs from acceptAmountInput(): $state vs $plain"
                else -> null
            }
        }
    }

    @Test
    fun the_ai_topup_amount_input_says_whether_it_took_the_edit() {
        val file = Class.forName("co.electriccoin.zcash.ui.screen.ai.UsdAmountInputKt")
        eachCase { case ->
            val field = chatFieldWith(case.shown)
            val plain = acceptUsdAmountInput(field, case.newText, en, case.selection)
            val (state, taken) = resultOf(file, null, "acceptUsdAmountInputResult", field, case.newText, en, case.selection)
            when {
                (plain !== field) != case.taken -> "the case table says taken=${case.taken}, the plain edit says ${plain !== field}"
                taken != case.taken -> "taken=$taken, wanted ${case.taken}"
                state != plain -> "the state differs from acceptUsdAmountInput(): $state vs $plain"
                else -> null
            }
        }
    }

    // ---- the number field ----

    @Test
    fun the_number_machine_says_whether_it_took_the_edit() {
        val reading = NumberFieldReading.USD
        eachCase { case ->
            val current = NumberTextFieldInnerState().edit(case.shown, "", en, reading, 0..0)
            val plain = current.edit(case.newText, case.shown, en, reading, case.selection)
            val (state, taken) =
                resultOf(
                    NumberTextFieldInnerState::class.java,
                    current,
                    "editResult",
                    case.newText,
                    case.shown,
                    en,
                    reading,
                    case.selection
                )
            when {
                (plain !== current) != case.taken -> "the case table says taken=${case.taken}, the plain edit says ${plain !== current}"
                taken != case.taken -> "taken=$taken, wanted ${case.taken}"
                state != plain -> "the state differs from edit(): $state vs $plain"
                else -> null
            }
        }
    }

    // ---- the case table is what the plain machines do today (a refusal is the same instance) ----

    @Test
    fun pin_the_case_table_agrees_with_the_plain_machines_at_f19d3ae67() {
        val send = SendAmountEditor(en, AmountUnit.USD)
        val chat = ChatAmountEditor(en, AmountUnit.USD)
        val number = NumberFieldReading.USD
        val problems = mutableListOf<String>()
        for (case in cases) {
            val field = AmountFieldState.settled("").edit(case.shown, en, AmountUnit.USD)
            val viaField = field.edit(case.newText, en, 2, AmountUnit.USD, true, case.selection) !== field
            val sendState = send.edit(send.empty(), case.shown, 0..0).handle as AmountState
            val viaSend = AmountState.edit(sendState, AmountField.FIAT, case.newText, case.selection, en, false, rate) !== sendState
            val chatState = chat.edit(chat.empty(), case.shown, 0..0).handle as AmountFieldState
            val viaChat = acceptAmountInput(chatState, case.newText, en, AmountUnit.USD, case.selection) !== chatState
            val viaAi = acceptUsdAmountInput(chatState, case.newText, en, case.selection) !== chatState
            val numberState = NumberTextFieldInnerState().edit(case.shown, "", en, number, 0..0)
            val viaNumber = numberState.edit(case.newText, case.shown, en, number, case.selection) !== numberState
            val got = mapOf("field" to viaField, "send" to viaSend, "chat" to viaChat, "ai" to viaAi, "number" to viaNumber)
            for ((machine, taken) in got) {
                if (taken != case.taken) problems += "${case.title}: $machine says taken=$taken, the table ${case.taken}"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---- the result type itself ----

    @Test
    fun the_result_type_carries_the_state_and_the_flag() {
        val type =
            runCatching { Class.forName("co.electriccoin.zcash.ui.design.util.AmountEditResult") }.getOrNull()
                ?: fail(
                    "AmountEditResult is missing (data class AmountEditResult<S>(val state: S, val taken: Boolean) in AmountTextReader.kt)"
                )
        val a = type.constructors.first().newInstance("x", true)
        val b = type.constructors.first().newInstance("x", true)
        assertEquals(a, b, "a data class")
        assertEquals("x", type.getMethod("getState").invoke(a))
        assertEquals(true, type.getMethod("getTaken").invoke(a))
    }
}
