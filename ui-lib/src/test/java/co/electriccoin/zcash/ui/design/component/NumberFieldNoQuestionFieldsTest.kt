package co.electriccoin.zcash.ui.design.component

import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.ask
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.paste
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.plain
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.readError
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.type
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.screen.restore.height.RestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.height.RestoreBDHeightVM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F12 (fix-all 2.13.0, train T3; review hole H7): the two number fields that can't ask, the restore height and the
 * swap slippage, read through the same reader with askOnAmbiguity = false: a paste with two usable readings is refused,
 * never guessed, and a paste with one usable reading is read.
 *
 * - Restore height (NumberFieldReading.BLOCK_HEIGHT: 0 decimals, no unit): "3,428,143" is 3428143 on any phone, and a
 *   Spanish "3,428" is 3428 (its decimal reading 3.428 is no block height), where 657570d65 reads it as 3.428 and says
 *   "not a whole number". A typed mark is refused.
 * - Slippage (NumberFieldReading.SLIPPAGE_PERCENT: 2 decimals, the quote's basis points; no unit): "1,500" is 1.5 % or
 *   1500 % and is refused; "0.555" is refused instead of being cut to 55 basis points.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NumberFieldNoQuestionFieldsTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun restoreVm(): RestoreBDHeightVM =
        RestoreBDHeightVM(
            restoreBDHeight = RestoreBDHeight(seed = "unused in this test"),
            navigationRouter = Zc101RecordingNavigationRouter(),
            // Never called here: nothing is restored in this test.
            restoreWallet =
                RestoreWalletUseCase(
                    walletRepository = unused(),
                    context = android.content.ContextWrapper(null),
                    isTorEnabledStorageProvider = unused()
                )
        )

    private inline fun <reified T> unused(): T =
        java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("unused in this test: ${method.name}")
        } as T

    @Test
    fun the_presets_never_ask() {
        assertEquals(NumberFieldProbe.Reading(0, null, false, null), NumberFieldProbe.preset("BLOCK_HEIGHT"))
        assertEquals(NumberFieldProbe.Reading(2, null, false, null), NumberFieldProbe.preset("SLIPPAGE_PERCENT"))
    }

    @Test
    fun the_restore_height_field_reads_a_grouped_height_on_any_phone() {
        val vm = restoreVm()
        val field = vm.state.value.blockHeight
        assertEquals(NumberFieldProbe.preset("BLOCK_HEIGHT"), NumberFieldProbe.reading(field))
        for (locale in listOf(en, es)) {
            assertEquals("3428143", plain(paste(field, "3,428,143", locale).innerState.amount), "$locale")
            assertEquals("3428143", plain(paste(field, "3.428.143", locale).innerState.amount), "$locale")
            assertEquals("3428143", plain(paste(field, "3 428 143", locale).innerState.amount), "$locale")
        }
        // QA2 m3 (ZC-1739, owner decision W-137): "3,428" is 3.428 or 3428 and this field cannot ask, so it is refused on the
        // shape (it used to read 3428 because 3.428 is no block height); "3,428,143" reads one way and still reads.
        assertNull(paste(field, "3,428", es).innerState.amount, "an ambiguous shape is refused in a field that cannot ask")
        assertEquals("Unreadable", readError(paste(field, "3,428", es).innerState))

        // The screen's state is shared while the screen collects it (WhileSubscribed): collect it as the screen does.
        val screen = CoroutineScope(Dispatchers.Main).launch { vm.state.collect {} }
        val entered = paste(field, "3,428,143", es)
        field.onValueChange(entered.innerState)
        assertTrue(vm.state.value.restore.isEnabled, "a pasted grouped height can be restored from")
        screen.cancel()
    }

    @Test
    fun the_restore_height_refuses_a_decimal_and_a_typed_mark() {
        val field = restoreVm().state.value.blockHeight
        val half = paste(field, "1000000.5", en)
        assertNull(half.innerState.amount)
        assertNull(ask(half.innerState), "the height field never asks")
        assertEquals("1000000", plain(type(field, en, "1", "0", "0", "0", "0", "0", "0", ".").innerState.amount))
    }

    @Test
    fun the_slippage_field_refuses_two_readings_and_a_third_decimal() {
        val field = NumberFieldProbe.field(NumberFieldProbe.presetHandle("SLIPPAGE_PERCENT"))
        val twoWays = paste(field, "1,500", en)
        assertNull(twoWays.innerState.amount)
        assertNull(ask(twoWays.innerState))
        assertEquals("Unreadable", readError(twoWays.innerState))
        assertEquals("0.5", plain(paste(field, "0.5", es).innerState.amount))
        assertEquals("0.5", plain(paste(field, "0,5", en).innerState.amount))
        assertEquals("TooManyDecimals(2)", readError(paste(field, "0.555", en).innerState))
        assertEquals("0.55", plain(type(field, en, "0", ".", "5", "5", "5").innerState.amount))
    }

    @Test
    fun the_slippage_picker_and_the_number_field_use_the_reader() {
        val picker =
            AmountReadingProbe.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/swap/slippage/SlippagePicker.kt")
        assertTrue("NumberFieldReading.SLIPPAGE_PERCENT" in picker, "the slippage field must not ask and must keep 2 decimals")
        assertFalse("stringResByNumber(amount" in picker, "the picker writes a formatted number into the field")
        val field =
            AmountReadingProbe.source(
                "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/ZashiNumberTextField.kt"
            )
        val composable = field.substringAfter("private fun createTextFieldState").substringBefore("@Immutable")
        assertFalse("normalizeInput" in composable, "the field still reads with its own rule, not the amount reader")
    }
}
