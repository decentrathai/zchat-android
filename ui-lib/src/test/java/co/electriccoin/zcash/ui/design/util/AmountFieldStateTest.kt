package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.choose
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.edit
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.emptyField
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.reread
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.settledField
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.type
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1 (fix-all 2.13.0, train T1): one amount field's reading state, shared by the chat dialogs, the new-chat custom
 * amount and the AI top-up.
 *
 * Pinned (review holes H4, H5, H6, H8, H13):
 * - H4: the raw paste is shown but never becomes the amount that counts ([settled] stays "" while a question is open).
 * - H5: a question can't be answered by editing. m2 kept it open until a reading was tapped; m3 (W-137, ZC-1746): a key
 *   discards the paste and empties the field. Emptying and typing 1 , 0 0 0 gives 1 with no question.
 * - H6: digits pasted after a typed mark merge as typed; a piece with marks inside typed text is refused.
 * - H8: the options come smaller first and nothing is preselected; a tap only fills the field.
 * - H13: switching the payment request to dollars re-reads the amount, and 5 ZEC decimals become an error.
 */
class AmountFieldStateTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    @Test
    fun `a pasted 1,000 opens the question and the raw paste never counts`() {
        val field = emptyField().edit("1,000", en)
        assertEquals(listOf("1", "1000"), field.ask, "smaller first")
        assertEquals("1,000", field.shown, "the field shows what was pasted")
        assertEquals("", field.settled, "H4: the raw paste is not an amount")
        assertTrue(field.isQuestionOpen)
    }

    @Test
    fun `a Spanish 1_500 paste never reaches the amount as 1_5 or 1500 before a tap`() {
        val field = emptyField().edit("1.500", es)
        assertEquals(listOf("1.5", "1500"), field.ask)
        assertEquals("", field.settled)
        assertEquals("1.5", field.choose("1.5").settled)
        assertEquals("1500", field.choose("1500").settled)
    }

    @Test
    fun `a tap fills the field, closes the question and clears the paste origin`() {
        val chosen = emptyField().edit("1,000", en).choose("1")
        assertEquals("1", chosen.shown)
        assertEquals("1", chosen.settled)
        assertFalse(chosen.isQuestionOpen)
        // Typing after the tap is typing again.
        assertEquals("1.5", chosen.type(en, ",", "5").settled)
    }

    @Test
    fun `H5 editing a question discards the paste, it never answers the question (QA2 m3, ZC-1746)`() {
        // m2 kept the question open for every key, so a backspace could never leave it (ZC-1746) and the first key a user
        // typed was lost without a word. m3 (owner decision W-137): a key on an open question discards the paste; the field
        // is empty and nothing counts. The key is consumed; the keys after it type afresh.
        val pasted = emptyField().edit("1,000", en)
        val backspaced = pasted.edit("1,00", en)
        assertNull(backspaced.ask, "the question must close: ${backspaced.shown}")
        assertNull(backspaced.error)
        assertEquals("", backspaced.shown, "the field is empty")
        assertEquals("", backspaced.settled)
        val retyped = backspaced.edit(backspaced.shown + "0", en)
        assertNull(retyped.ask, "the question must not come back: ${retyped.shown}")
        assertEquals("0", retyped.settled, "the next key types afresh")
    }

    @Test
    fun `emptying the field ends the paste, and typing 1 , 0 0 0 is 1 with no question`() {
        val cleared = emptyField().edit("1,000", en).edit("", en)
        assertFalse(cleared.isQuestionOpen)
        val typed = cleared.type(en, "1", ",", "0", "0", "0")
        assertEquals("1.000", typed.settled)
        assertNull(typed.ask)
    }

    @Test
    fun `an unambiguous paste reads at once`() {
        assertEquals("1000.50", emptyField().edit("1,000.50", en).settled)
        assertEquals("1000.50", emptyField().edit("1.000,50", es).settled)
        assertEquals("0.001", emptyField().edit("0,001", en).settled)
    }

    @Test
    fun `too many decimals shows the paste with an error, and the amount stays empty`() {
        val field = emptyField().edit("0.123456789", en)
        assertEquals("TooManyDecimals(8)", field.error)
        assertEquals("0.123456789", field.shown)
        assertEquals("", field.settled)
    }

    @Test
    fun `H6 digits pasted after a typed mark merge as typed`() {
        val typed = emptyField().type(en, "1", ".")
        val merged = typed.edit("1.000", en)
        assertEquals("1.000", merged.settled)
        assertNull(merged.ask)

        val inside = emptyField().type(en, "5", "5").edit("51,05", en)
        assertEquals("PasteIntoAmount", inside.error)
        assertEquals("", inside.settled)
    }

    @Test
    fun `a refused keystroke is ignored and a deletion is always taken`() {
        val typed = emptyField().type(en, "1", ".", "2")
        assertEquals(typed, typed.edit("1.2.", en), "a second mark is ignored")
        assertEquals("1", typed.edit("1", en).settled.trimEnd('.'), "a deletion")
    }

    @Test
    fun `H13 switching to dollars re-reads the amount`() {
        val zec = settledField("0.12345")
        val usd = zec.reread(en, 2)
        assertEquals("TooManyDecimals(2)", usd.error)
        assertEquals("", usd.settled)
        assertEquals("0.12345", usd.reread(en, 8).settled, "and back to ZEC it counts again")
        assertEquals("12.5", settledField("12.5").reread(en, 2).settled)
    }
}
