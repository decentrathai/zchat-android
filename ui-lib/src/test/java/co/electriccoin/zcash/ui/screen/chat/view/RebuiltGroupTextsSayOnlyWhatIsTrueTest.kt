package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-757 (FIRST-CONTACT-SPEC 7.3, 6.7 (p)) — the four texts of a group this phone rebuilt from its history: the notice
 * before the new key, the refusal of a send before it, the key change's own confirmation, and the notice after a new
 * key nobody was sent. Each exists in English and Spanish with no placeholder, promises nothing the code does not do,
 * and is shown where its state is (source-driven, like this package's other text tests; no rendered UI claim).
 * The Spanish lines are agent drafts for review.
 */
class RebuiltGroupTextsSayOnlyWhatIsTrueTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun src(path: String): String = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/$path")

    @Test
    fun the_four_texts_exist_in_english_and_spanish_with_no_placeholder() {
        for (key in KEYS) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            for (text in listOf(english, spanish)) {
                assertFalse(Regex("""%(\d+\$)?[sd]""").containsMatchIn(text), "$key has a placeholder")
            }
        }
    }

    @Test
    fun the_english_texts_promise_nothing_the_code_does_not_do() {
        for (key in KEYS) {
            val text = assertNotNull(en[key], "no English $key").lowercase()
            for (forbidden in listOf("restored", "all members", "every current member", "stay readable", "automatically")) {
                assertFalse(forbidden in text, "$key says '$forbidden'")
            }
        }
    }

    @Test
    fun the_spanish_texts_name_the_settings_screen_by_its_title() {
        for (key in listOf("group_rebuilt_needs_key", "group_send_needs_new_key", "group_rebuilt_no_reader")) {
            val text = assertNotNull(es[key], "no Spanish $key")
            assertTrue("Configuración del grupo" in text, "$key does not say «Configuración del grupo»")
        }
        for (key in KEYS) assertFalse("ajustes del grupo" in es[key].orEmpty(), "$key says «ajustes del grupo»")
    }

    @Test
    fun the_refusal_maps_to_its_two_sentences() {
        val banner = src("view/GroupSendBanner.kt")
        assertTrue("is GroupSendResult.NeedsNewKey" in banner, "the banner has no sentence for a refused send in a rebuilt group")
        assertTrue("R.string.group_send_needs_new_key" in banner, "the sentence before the new key is missing")
        assertTrue("R.string.group_rebuilt_no_reader" in banner, "the sentence after a new key nobody has is missing")
    }

    @Test
    fun the_notice_and_the_confirmation_are_shown_where_their_state_is() {
        val detail = src("view/GroupDetailView.kt")
        assertTrue("R.string.group_rebuilt_needs_key" in detail, "the group screen does not show the rebuilt group's notice")
        assertTrue("R.string.group_rebuilt_no_reader" in detail, "the group screen does not show the 'no reader' notice")
        val settings = src("view/GroupSettingsView.kt")
        assertTrue(
            "R.string.group_rotate_confirm_body_rebuilt" in settings,
            "(p) the key change of a rebuilt group shows the old confirmation, which promises every current member",
        )
        assertTrue(Regex("""R\.string\.group_rotate_confirm_body\b""").containsMatchIn(settings), "the old confirmation is gone")
    }

    private companion object {
        val KEYS =
            listOf(
                "group_rebuilt_needs_key",
                "group_send_needs_new_key",
                "group_rotate_confirm_body_rebuilt",
                "group_rebuilt_no_reader",
            )
    }
}
