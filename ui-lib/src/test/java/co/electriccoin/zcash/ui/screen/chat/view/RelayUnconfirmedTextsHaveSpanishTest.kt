package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-420-G (fix-all 2.13.0, train T6b; ZC-420-SPEC §8; skill app-texts) — the two texts of the group relay-copy marks, in
 * English and Spanish.
 *
 * `group_send_relay_unconfirmed` is the group banner after a send (and again at each group open) when only a public relay
 * took some members' copies: it names the members (%1$s), says "not confirmed", names ZChat 2.12.4 (the version that does
 * not read that relay), and never says "delivered" or "failed": the code knows neither. `group_invite_not_confirmed_status`
 * is the badge of an invite only a public relay took. Both exist in values and values-es with the same placeholders, and
 * groupSendBannerText maps GroupSendResult.RelayUnconfirmed to the banner with the names in it.
 */
class RelayUnconfirmedTextsHaveSpanishTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    @Test
    fun `both keys exist in English and Spanish with the same placeholders`() {
        for (key in listOf(BANNER, BADGE)) {
            val english = en[key]
            val spanish = es[key]
            assertNotNull("values has no $key", english)
            assertNotNull("values-es has no $key", spanish)
            assertEquals("$key: the placeholders differ", placeholders(english!!), placeholders(spanish!!))
            assertNotEquals("$key is not translated", english, spanish)
        }
        assertEquals(setOf("%1\$s"), placeholders(en.getValue(BANNER)))
        assertEquals(emptySet<String>(), placeholders(en.getValue(BADGE)))
    }

    @Test
    fun `the banner names 2_12_4, says not confirmed, and never delivered or failed`() {
        val english = en[BANNER]
        val spanish = es[BANNER]
        assertNotNull("values has no $BANNER", english)
        assertNotNull("values-es has no $BANNER", spanish)
        for (text in listOf(english!!, spanish!!)) assertTrue("the banner does not name 2.12.4: $text", "2.12.4" in text)
        assertTrue("the banner does not say not confirmed: $english", "not confirmed" in english.lowercase())
        assertTrue("the banner does not say sin confirmar: $spanish", "sin confirmar" in spanish.lowercase())
        for (word in listOf("delivered", "failed", "lost")) assertFalse("the banner says $word: $english", word in english.lowercase())
        for (word in listOf("entregad", "falló", "perdid")) assertFalse("the banner says $word: $spanish", word in spanish.lowercase())
        assertTrue("the badge does not say not confirmed", "not confirmed" in en[BADGE].orEmpty().lowercase())
    }

    @Test
    fun `groupSendBannerText maps RelayUnconfirmed to the banner with the names`() {
        val name = "co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupSendResult\$RelayUnconfirmed"
        val cls = runCatching { Class.forName(name) }.getOrNull()
        assertNotNull("GroupSendResult.RelayUnconfirmed does not exist", cls)
        val constructor = cls!!.constructors.single { it.parameterCount == 3 }
        val result = constructor.newInstance("zgrp_synthetic", listOf("Bea", "Dan"), false) as GroupSendResult
        val text =
            groupSendBannerText(
                result = result,
                string = ChatResourceText::english,
                plural = ChatResourceText::englishPlural,
            )
        assertEquals(String.format(en.getValue(BANNER), "Bea, Dan"), text)
    }

    private companion object {
        const val BANNER = "group_send_relay_unconfirmed"
        const val BADGE = "group_invite_not_confirmed_status"
    }
}
