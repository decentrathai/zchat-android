package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.Read
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.edit
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.emptyField
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.read
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.reread
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T1 fixup, X-F1-1 (cross-family review of F1): a currency sign names a unit, so it is read only in a field of that
 * unit.
 *
 * At d27d46dba (F1) the reader strips "$", "US$", "USD", "ZEC" and "ⓩ" in every field, whatever the field's unit. A
 * pasted "$5" in a ZEC field is 5 ZEC (at $50 a ZEC that is 50 times what the paste said), and a pasted "5 ZEC" in a
 * dollar field is $5. Before F1, main Send refused '$' and the chat fields ignored a paste they could not read, so F1
 * brought this in.
 *
 * Wanted: the reader and the field state take the field's unit. The field's own signs are read as before. The other
 * unit's signs are refused with a reason (OtherUnit, naming the unit whose sign was found) and are never read as the
 * field's unit. A field with no currency (unit null, for F12's number fields) refuses every sign.
 */
class AmountOtherUnitSignTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    @Test
    fun `a dollar sign in a ZEC field is refused, never read as ZEC`() {
        for (paste in listOf("$5", "5$", "US$5", "5 USD", "USD 5", "usd5", "$1,234.56", "$ 0.5")) {
            assertEquals(Read.Invalid("OtherUnit(USD)"), read(paste, en, unit = "ZEC"), "$paste in a ZEC field")
        }
        assertEquals(Read.Invalid("OtherUnit(USD)"), read("5 US$", es, unit = "ZEC"))
    }

    @Test
    fun `a ZEC sign in a dollar field is refused, never read as dollars`() {
        for (paste in listOf("5 ZEC", "ZEC 5", "zec5", "ⓩ5", "ⓩ 5", "1,000.50 ZEC")) {
            assertEquals(
                Read.Invalid("OtherUnit(ZEC)"),
                read(paste, en, maxDecimals = 2, unit = "USD"),
                "$paste in a dollar field"
            )
        }
    }

    @Test
    fun `a paste with both units' signs is refused in either field`() {
        assertEquals(Read.Invalid("OtherUnit(USD)"), read("$5 ZEC", en, unit = "ZEC"))
        assertEquals(Read.Invalid("OtherUnit(ZEC)"), read("$5 ZEC", en, maxDecimals = 2, unit = "USD"))
        assertEquals(Read.Invalid("OtherUnit(ZEC)"), read("ZEC", en, maxDecimals = 2, unit = "USD"), "a sign alone")
    }

    @Test
    fun `the field's own signs are read as before (control)`() {
        assertEquals(Read.Exact("1234.56"), read("$1,234.56", en, maxDecimals = 2, unit = "USD"))
        assertEquals(Read.Exact("12"), read("US$12", en, maxDecimals = 2, unit = "USD"))
        assertEquals(Read.Exact("12"), read("12 USD", en, maxDecimals = 2, unit = "USD"))
        assertEquals(Read.Exact("5"), read("5 ZEC", en, unit = "ZEC"))
        assertEquals(Read.Exact("5"), read("ⓩ 5", en, unit = "ZEC"))
        assertEquals(Read.Exact("1234.56"), read("1.234,56 ZEC", es, unit = "ZEC"))
    }

    @Test
    fun `a field with no currency refuses every sign`() {
        assertEquals(Read.Invalid("OtherUnit(USD)"), read("$5", en, maxDecimals = 0, unit = null))
        assertEquals(Read.Invalid("OtherUnit(ZEC)"), read("5 ZEC", en, maxDecimals = 0, unit = null))
        assertEquals(Read.Exact("5"), read("5", en, maxDecimals = 0, unit = null), "control")
    }

    @Test
    fun `the field state refuses the other unit's sign and nothing counts`() {
        val zec = emptyField().edit("$5", en, unit = "ZEC")
        assertEquals("OtherUnit(USD)", zec.error, "a pasted \$5 in a ZEC field: $zec")
        assertEquals("", zec.settled, "nothing counts while the error is open")
        assertEquals("$5", zec.shown, "the field shows the paste as it was pasted")

        val usd = emptyField().edit("5 ZEC", en, maxDecimals = 2, unit = "USD")
        assertEquals("OtherUnit(ZEC)", usd.error, "a pasted 5 ZEC in a dollar field: $usd")
        assertEquals("", usd.settled)

        assertEquals("5", emptyField().edit("5 ZEC", en).settled, "control: ZEC in a ZEC field")
        assertEquals("12", emptyField().edit("$12", en, maxDecimals = 2).settled, "control: dollars in a dollar field")
    }

    @Test
    fun `switching the request to dollars re-reads a pasted dollar amount as dollars`() {
        val pasted = emptyField().edit("$5", en, unit = "ZEC")
        assertEquals("OtherUnit(USD)", pasted.error, "a pasted \$5 in the ZEC request: $pasted")
        val dollars = pasted.reread(en, 2)
        assertEquals("5", dollars.settled, "the same paste in the dollar request is \$5")
        assertNull(dollars.error)
    }
}
