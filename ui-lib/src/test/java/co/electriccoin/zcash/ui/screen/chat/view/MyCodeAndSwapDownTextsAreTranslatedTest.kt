package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1458 / ZC-1461 / ZC-1463 / ZC-1482 (OT213b batch B4): the new New Chat buttons, the My code screen and the swap
 * service-down texts are resources in English and Spanish with the same placeholders, and the My code screen no longer
 * shows its old English literals. The swap texts do not blame the user's connection.
 */
class MyCodeAndSwapDownTextsAreTranslatedTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val keys =
        listOf(
            "new_chat_scan_a_code",
            "new_chat_my_code",
            "my_code_title",
            "my_code_tab_chat",
            "my_code_tab_address",
            "my_code_chat_helper",
            "my_code_copy_invite",
            "my_code_invite_disclaimer",
            "my_code_heading_shielded",
            "my_code_heading_transparent",
            "my_code_subtitle_shielded",
            "my_code_subtitle_transparent",
            "my_code_qr_hint",
            "my_code_copy_address",
            "my_code_share",
            "my_code_show_transparent",
            "my_code_show_shielded",
            "my_code_badge_transparent",
            "my_code_important",
            "my_code_transparent_warning_use",
            "my_code_transparent_warning_shield",
            "my_code_recommended",
            "my_code_shielded_info_share",
            "my_code_shielded_info_use",
            "my_code_info_icon",
            "my_code_load_failed",
            "my_code_retry",
            "swap_down_title",
            "swap_down_body",
            "swap_down_checked",
            "swap_down_checked_again",
            "swap_down_other_way",
        )

    /** The same word in both languages: the mode name and a word Spanish shares. */
    private val sameInBoth = setOf("my_code_badge_shielded", "my_code_error_icon")

    private val placeholder = Regex("""%\d+\$[sd]""")

    /** The old English literals of the My code screen, now resources. */
    private val oldLiterals =
        listOf(
            "My Address",
            "Shielded Address",
            "Transparent Address",
            "Copy Address",
            "Share",
            "Retry",
            "Scan this to start a free chat",
            "Copy ZCHAT chat invite",
            "Show Transparent Address",
            "Show Shielded Address",
            "Important",
            "Recommended",
            "Information",
            "Error",
            "Shielded",
            "Transparent",
            "For receiving from exchanges",
        )

    @Test
    fun `every new text has English and a Spanish translation with the same placeholders`() {
        (keys + sameInBoth).forEach { key ->
            val english = assertNotNull(en[key], "no English text for $key")
            val spanish = assertNotNull(es[key], "no Spanish text for $key")
            if (key !in sameInBoth) assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                placeholder.findAll(english).map { it.value }.toSet(),
                placeholder.findAll(spanish).map { it.value }.toSet(),
                "$key: the Spanish placeholders differ from the English ones",
            )
        }
    }

    @Test
    fun `the retry line says when it was checked again`() {
        val again = en.getValue("swap_down_checked_again")
        assertTrue("%1\$s" in again, "the checked-again line has no time")
        assertTrue("again" in again, "the checked-again line does not say it was checked again")
    }

    @Test
    fun `the swap service-down texts do not blame the connection`() {
        listOf("swap_down_title", "swap_down_body").forEach { key ->
            assertFalse("connection" in en.getValue(key).lowercase(), "$key blames the user's connection")
            assertFalse("conexión" in es.getValue(key).lowercase(), "$key (ES) blames the user's connection")
        }
    }

    @Test
    fun `My code shows no English literals`() {
        val texts = KotlinUiLiterals.lex(ChatResourceText.source(VIEW)).literals.map { it.text }
        oldLiterals.forEach { old ->
            assertFalse(old in texts, "ZchatReceiveView still shows the English literal \"$old\"")
        }
    }

    private companion object {
        const val VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatReceiveView.kt"
    }
}
