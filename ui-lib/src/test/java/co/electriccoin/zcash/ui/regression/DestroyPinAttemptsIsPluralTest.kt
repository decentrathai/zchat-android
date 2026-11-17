package co.electriccoin.zcash.ui.regression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1225: the wrong-PIN text read "1 attempts remaining before lockout". Wanted: destroy_pin_incorrect_attempts is a
 * plurals resource in English and Spanish; the English "one" item does not say "attempts", and "other" does.
 */
class DestroyPinAttemptsIsPluralTest {
    private fun file(v: String) = PolSourceFiles.res("common/$v/destroy_identity_strings.xml").readText()

    private fun items(xml: String): Map<String, String> {
        val block =
            Regex("""<plurals name="destroy_pin_incorrect_attempts">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?: return emptyMap()
        return Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `English one attempt remaining is singular`() {
        val en = items(file("values"))
        assertTrue(en.isNotEmpty(), "destroy_pin_incorrect_attempts is not a plurals resource (EN still says '1 attempts remaining')")
        assertTrue("attempts" !in en.getValue("one"), "EN 'one' item: ${en["one"]}")
        assertTrue("attempts" in en.getValue("other"), "EN 'other' item: ${en["other"]}")
    }

    @Test
    fun `Spanish has the same plurals and no plain string of that name is left`() {
        assertEquals(setOf("one", "other"), items(file("values-es")).keys.intersect(setOf("one", "other")))
        listOf("values", "values-es").forEach {
            assertTrue("""<string name="destroy_pin_incorrect_attempts"""" !in file(it), "$it still has the plain string")
        }
    }
}
