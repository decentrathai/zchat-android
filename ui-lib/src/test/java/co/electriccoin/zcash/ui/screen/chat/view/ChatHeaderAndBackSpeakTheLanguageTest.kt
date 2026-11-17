package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ES-A with W-11 item (i) (fix-all 2.13.0, train T4c; T4 phone step RESULT.md observation 2, Seeker es-ES): the chat
 * header's mode word ("shielded" / "tunnel" / "open", next to the green dot) and the Back content description of the
 * chat screens' top bars are English literals on a Spanish phone.
 *
 * What it must be: the mode word is a resource. English is unchanged ("shielded" / "tunnel" / "open"); Spanish shows
 * the mode's name as the Spanish mode picker shows it (the reviewed rule: mode names stay Shielded / Tunnel / Open,
 * SpanishReviewedTermsTest). Every Back arrow reads the existing back_navigation_content_description ("Atrás").
 */
class ChatHeaderAndBackSpeakTheLanguageTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"

    private val modeKeys =
        mapOf(
            "chat_header_mode_shielded" to ("Shielded" to "chat_mode_label_vault"),
            "chat_header_mode_tunnel" to ("Tunnel" to "chat_mode_label_tunnel"),
            "chat_header_mode_open" to ("Open" to "chat_mode_label_open"),
        )

    @Test
    fun the_header_mode_word_is_a_resource() {
        val lexed = KotlinUiLiterals.lex(ChatResourceText.source("$chat/view/ChatDetailView.kt"))
        val block = KotlinUiLiterals.block(lexed, "val modeLabel = when (conversationMode) {")
        val shown = lexed.literals.filter { it.offset in block }.map { it.text }
        for ((key, pair) in modeKeys) {
            assertFalse(pair.first in shown, "the chat header still shows the English literal \"${pair.first}\"")
            assertTrue("R.string.$key" in lexed.blanked.substring(block.first, block.last + 1), "the chat header does not read $key")
        }
    }

    @Test
    fun the_chip_has_one_case_in_english_and_spanish_uses_the_pickers_mode_names() {
        for ((key, pair) in modeKeys) {
            val (english, pickerKey) = pair
            assertEquals(english, en[key], "$key: the chip reads \"$english\" in one case in both languages (QA2 Q2-27)")
            val picker = assertNotNull(es[pickerKey], "no Spanish $pickerKey").substringBefore(" (")
            assertEquals(picker, es[key], "$key: Spanish must name the mode as the picker does")
        }
    }

    @Test
    fun every_back_arrow_reads_the_translated_description() {
        val files =
            listOf(
                "$chat/view/ChatDetailView.kt",
                "$chat/view/components/NightwireComponents.kt",
                "$chat/view/ZchatReceiveView.kt",
                "$chat/share/AndroidSharePicker.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/invite/view/InviteFriendView.kt",
            )
        for (path in files) {
            val lexed = KotlinUiLiterals.lex(ChatResourceText.source(path))
            val name = path.substringAfterLast('/')
            assertFalse("Back" in lexed.literals.map { it.text }, "$name still describes its Back arrow as the English \"Back\"")
            assertTrue(
                "R.string.back_navigation_content_description" in lexed.blanked,
                "$name does not read back_navigation_content_description"
            )
        }
        val header = KotlinUiLiterals.lex(ChatResourceText.source("$chat/view/ChatDetailView.kt"))
        assertFalse("Close search" in header.literals.map { it.text }, "the chat header's close-search arrow is English")
        assertEquals("Atrás", es["back_navigation_content_description"])
    }
}
