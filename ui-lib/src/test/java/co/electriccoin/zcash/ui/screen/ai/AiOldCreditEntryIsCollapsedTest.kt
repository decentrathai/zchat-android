package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-1265: the AI tab shows "Look for AI credit from before 2.13" and its fingerprint note to everyone, at the top, in
 * every state. They stand behind a small entry (text key `ai_old_credit_entry`, EN and ES, which stays in every state)
 * and open by themselves only when they matter: a parked key, or a notice of the access.
 *
 * Seam, by reflection so the module's other tests compile before it exists:
 * `object AiOldCreditRow { fun startsOpen(parkedAccounts: List<String>, notice: String?): Boolean }`, in this package.
 * The notice set is the one AiTabView already treats as "no error": the `ai_notice_*` of `SIGN_IN_TEXTS`, and every value
 * of `OTHER_ACCOUNT_TEXTS` and `LINK_AGAIN_TEXTS`. The nesting and the tap are read from the source: a JVM test cannot
 * compose the tab.
 */
class AiOldCreditEntryIsCollapsedTest {
    private val view by lazy { AiSignInApi.read(AiSignInApi.mainAiFile("AiTabView.kt")) }

    private fun startsOpen(
        parked: List<String>,
        notice: String?,
    ): Boolean {
        val type = runCatching { Class.forName("co.electriccoin.zcash.ui.screen.ai.AiOldCreditRow") }.getOrNull()
        assertNotNull("AiOldCreditRow does not exist", type)
        val method = type!!.methods.firstOrNull { it.name == "startsOpen" }
        assertNotNull("AiOldCreditRow.startsOpen does not exist", method)
        return method!!.invoke(type.getField("INSTANCE").get(null), parked, notice) as Boolean
    }

    @Test
    fun `with nothing parked and no notice the row stays closed`() {
        assertFalse(startsOpen(emptyList(), null))
        assertFalse("an ordinary error is no notice", startsOpen(emptyList(), "Something went wrong."))
    }

    @Test
    fun `a parked key opens the row`() {
        assertTrue(startsOpen(listOf("account"), null))
    }

    @Test
    fun `every notice of the access opens the row`() {
        val notices =
            AiApiClient.SIGN_IN_TEXTS.filterKeys { it.startsWith("ai_notice_") }.values +
                AiApiClient.OTHER_ACCOUNT_TEXTS.values +
                AiApiClient.LINK_AGAIN_TEXTS.values
        assertTrue(notices.isNotEmpty())
        notices.forEach { notice -> assertTrue(notice, startsOpen(emptyList(), notice)) }
    }

    @Test
    fun `the entry stands before the row and the row is behind a condition`() {
        val entry = view.indexOf("R.string.ai_old_credit_entry")
        val row = view.indexOf("R.string.ai_look_for_old_credit)")
        val note = view.indexOf("R.string.ai_look_for_old_credit_note")
        assertTrue("the small entry is not in AiTabView", entry >= 0)
        assertTrue("the row is gone", row >= 0 && note >= 0)
        assertTrue("the entry must come first", entry < row && row < note)
        assertTrue("the row is not behind a condition", "if (" in view.substring(entry, row))
        assertTrue("the row does not read AiOldCreditRow", "AiOldCreditRow.startsOpen(" in view)
    }

    /** Opening the row sends nothing: the fingerprint still goes only on the tap of the link itself (AiTabVM). */
    @Test
    fun `the entry itself sends nothing`() {
        val entry = view.indexOf("R.string.ai_old_credit_entry")
        val row = view.indexOf("R.string.ai_look_for_old_credit)")
        assertTrue(entry in 0 until row)
        assertFalse("the entry calls the tap of the link", "onLookForOldCredit" in view.substring(entry, row))
        val note = view.indexOf("R.string.ai_look_for_old_credit_note")
        assertTrue("the link lost its tap", "onLookForOldCredit" in view.substring(row, note))
    }

    @Test
    fun `the entry is in both languages and the words the notices use stay`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val entry = en["ai_old_credit_entry"]
        assertNotNull("no English ai_old_credit_entry", entry)
        assertNotNull("no Spanish ai_old_credit_entry", es["ai_old_credit_entry"])
        assertNotEquals(entry, es["ai_old_credit_entry"])
        assertEquals("Look for AI credit from before 2.13", en["ai_look_for_old_credit"])
        assertEquals("Buscar saldo de IA anterior a la 2.13", es["ai_look_for_old_credit"])
        assertTrue(
            "a notice names a link that changed",
            (AiApiClient.SIGN_IN_TEXTS.values + AiApiClient.OTHER_ACCOUNT_TEXTS.values)
                .any { "Look for AI credit from before 2.13" in it },
        )
    }
}
