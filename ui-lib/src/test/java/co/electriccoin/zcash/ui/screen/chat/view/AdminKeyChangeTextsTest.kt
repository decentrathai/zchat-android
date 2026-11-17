package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KI-25 pair 2, ZC-572 (KI-25-SPEC 3.6b section 8, the two keys marked (C); section 3.3; skill app-texts) — the card's
 * body for a group whose trusted admin key no chat holds any more, and the source line of a candidate from the
 * admin's address, in English (the spec's words) and Spanish (the spec's drafts) with the same placeholders, and the
 * card shows them: the body for ORPHANED, the line for the source ADDRESS.
 *
 * Before the fix neither key exists.
 */
class AdminKeyChangeTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    /** Each key with the spec's English and the spec's Spanish draft. */
    private val expected =
        listOf(
            Triple(
                "group_admin_check_body_changed",
                "The security key this group trusts for its admin is no longer the key of any of your chats: the " +
                    "admin’s key changed, or your secure chat with them was reset. Until you confirm the admin again, " +
                    "their changes may be ignored here, and someone they removed may still read what you write in this group.",
                // T7c ES review (es-review/gpt_verdict.json): the reviewed wording replaces the spec's draft.
                "La clave de seguridad en la que este grupo confía como la del administrador ya no coincide con la " +
                    "clave de ninguno de tus chats: la clave del administrador cambió o se restableció tu chat seguro con " +
                    "esa persona. Hasta que vuelvas a confirmar al administrador, es posible que sus cambios se ignoren " +
                    "aquí, y alguien a quien quitó puede seguir leyendo lo que escribes en este grupo.",
            ),
            Triple(
                "group_admin_candidate_address",
                "The address stored as this group’s admin is your chat with %1\$s (%2\$s).",
                "La dirección guardada como la del administrador de este grupo corresponde a tu chat con %1\$s (%2\$s).",
            ),
        )

    @Test
    fun the_two_keys_are_the_spec_s_english_and_spanish_with_the_same_placeholders() {
        expected.forEach { (key, english, spanishDraft) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertEquals(spanishDraft, spanish, "Spanish $key is not the spec's draft")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(english).sorted(),
                SpanishSendPaymentDialogTest.placeholders(spanish).sorted(),
                "$key: the Spanish placeholders differ",
            )
            for (claim in listOf("automatic", "free", "gratis", "automátic")) {
                assertFalse(claim in english.lowercase() || claim in spanish.lowercase(), "$key says \"$claim\"")
            }
        }
    }

    @Test
    fun the_card_shows_the_body_for_a_changed_key_and_the_line_for_the_address() {
        val card = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSettingsView.kt")
        assertTrue("R.string.group_admin_check_body_changed" in card, "the card never shows the body for a changed key")
        assertTrue("R.string.group_admin_candidate_address" in card, "the card never shows the line for the admin's address")
        assertTrue(
            Regex("""AdminCandidateSource\.ADDRESS\s*->\s*R\.string\.group_admin_candidate_address""").containsMatchIn(card),
            "the line for the admin's address is not the one shown for the source ADDRESS",
        )
    }
}
