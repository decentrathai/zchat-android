package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-283 (fix-all 2.13.0, train T5b; r8-findings SPECS §2.5, S4) — the status broadcast's notices say what happened to each
 * contact, in English and Spanish, and promise only what the code does.
 *
 * At 998956906 the only notice is chat_send_status_partial ("Set it again later to reach the rest."), shown also when a
 * contact's memo may already be on its way, and setting it again paid every contact again.
 *
 * Wanted:
 * - plurals chat_send_status_partial_maybe (some contacts may already be getting it; nobody is proven not reached) and
 *   chat_send_status_partial_maybe_and_rest (both), quantity = the "maybe" count (%3$d), with %1$d reached and %2$d all
 *   contacts in every form; English one/other, Spanish one/many/other; the maybe-only text never asks to set it again;
 *   both say ZChat can't tell yet and will not send it to those contacts again;
 * - chat_send_status_busy = ChatSendCopy.STATUS_BROADCAST_BUSY (a status set while a broadcast runs), English and
 *   Spanish, mapped by ChatSendCopy.localizedResource;
 * - paid_action_status_broadcast_rest: the question for the contacts the same status has not reached yet;
 * - the chat list's notice picks the plurals when a contact may have it (source of AndroidChat).
 *
 * The Spanish lines are agent drafts, listed for the native-speaker round in the train's REVIEW.md.
 */
class StatusPartialTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val enPlurals by lazy { ChatResourceText.plurals("values") }
    private val esPlurals by lazy { ChatResourceText.plurals("values-es") }

    private fun placeholders(text: String): Set<String> = Regex("%[0-9]+\\$[a-z]").findAll(text).map { it.value }.toSet()

    @Test
    fun the_maybe_sent_notices_exist_in_english_and_spanish_with_the_three_counts() {
        for (name in listOf(MAYBE, MAYBE_AND_REST)) {
            val english = assertNotNull(enPlurals[name], "English plurals $name missing")
            val spanish = assertNotNull(esPlurals[name], "Spanish plurals $name missing")
            assertEquals(setOf("one", "other"), english.keys, "$name: English one/other")
            assertEquals(setOf("one", "many", "other"), spanish.keys, "$name: Spanish one/many/other")
            for ((form, text) in english.mapKeys { "en/${it.key}" } + spanish.mapKeys { "es/${it.key}" }) {
                assertEquals(setOf("%1\$d", "%2\$d", "%3\$d"), placeholders(text), "$name $form: placeholders in $text")
            }
        }
    }

    @Test
    fun the_maybe_sent_notices_say_zchat_cannot_tell_yet_and_will_not_send_again() {
        assertEquals(EN_MAYBE, enPlurals[MAYBE]?.get("other"))
        assertEquals(EN_MAYBE, enPlurals[MAYBE]?.get("one"))
        assertEquals("$EN_MAYBE $EN_REST", enPlurals[MAYBE_AND_REST]?.get("other"))
        assertEquals(ES_MAYBE_ONE, esPlurals[MAYBE]?.get("one"))
        assertEquals(ES_MAYBE_OTHER, esPlurals[MAYBE]?.get("many"))
        assertEquals(ES_MAYBE_OTHER, esPlurals[MAYBE]?.get("other"))
        assertEquals("$ES_MAYBE_ONE $ES_REST", esPlurals[MAYBE_AND_REST]?.get("one"))
        assertEquals("$ES_MAYBE_OTHER $ES_REST", esPlurals[MAYBE_AND_REST]?.get("other"))
        for (text in enPlurals[MAYBE].orEmpty().values) {
            assertTrue("can't tell yet" in text, "English: $text")
            assertFalse("Set the same status again" in text, "the maybe-only notice must not ask to set it again: $text")
        }
        for (text in esPlurals[MAYBE].orEmpty().values) {
            assertTrue("aún no lo sabe" in text, "Spanish: $text")
            assertFalse("Vuelve a configurar" in text, "the maybe-only notice must not ask to set it again: $text")
        }
        for (text in enPlurals[MAYBE_AND_REST].orEmpty().values) assertTrue("Set the same status again" in text, text)
        for (text in esPlurals[MAYBE_AND_REST].orEmpty().values) assertTrue("Vuelve a configurar el mismo estado" in text, text)
    }

    @Test
    fun the_busy_notice_is_its_constant_in_english_and_has_spanish() {
        val field =
            ChatSendCopy::class.java.declaredFields.firstOrNull { it.name == "STATUS_BROADCAST_BUSY" }
                ?: throw AssertionError("ChatSendCopy has no STATUS_BROADCAST_BUSY notice")
        val english = field.apply { isAccessible = true }.get(null) as String
        assertEquals(EN_BUSY, english)
        assertEquals(english, en["chat_send_status_busy"], "English resource chat_send_status_busy must be the notice's text")
        assertEquals(ES_BUSY, es["chat_send_status_busy"])
        assertEquals(ChatResourceText.stringId("chat_send_status_busy"), ChatSendCopy.localizedResource(english))
    }

    @Test
    fun the_question_for_the_rest_is_described_in_english_and_spanish() {
        val english = assertNotNull(en["paid_action_status_broadcast_rest"], "English paid_action_status_broadcast_rest")
        val spanish = assertNotNull(es["paid_action_status_broadcast_rest"], "Spanish paid_action_status_broadcast_rest")
        assertEquals("Sending your status to the Shielded contacts it hasn't reached yet.", english)
        assertEquals("Enviar tu estado a los contactos Shielded a los que aún no ha llegado.", spanish)
        assertNotEquals(english, spanish)
    }

    @Test
    fun the_chat_list_notice_picks_the_maybe_sent_plurals() {
        val source = ChatResourceText.source(ANDROID_CHAT)
        assertTrue("R.plurals.$MAYBE," in source, "AndroidChat does not show $MAYBE")
        assertTrue("R.plurals.$MAYBE_AND_REST," in source, "AndroidChat does not show $MAYBE_AND_REST")
        assertTrue("partial.maybe" in source, "AndroidChat does not read the maybe-sent count")
        assertTrue("R.string.chat_send_status_partial," in source, "the notice with no maybe-sent contact stays")
    }

    private companion object {
        const val ANDROID_CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        const val MAYBE = "chat_send_status_partial_maybe"
        const val MAYBE_AND_REST = "chat_send_status_partial_maybe_and_rest"
        const val EN_MAYBE =
            "Your status reached %1\$d of %2\$d contacts. For %3\$d more it may already be on its way; ZChat can't tell " +
                "yet, so it won't send it to them again."
        const val EN_REST = "Set the same status again later to reach the rest."
        const val ES_MAYBE_ONE =
            "Tu estado llegó a %1\$d de %2\$d contactos. A %3\$d contacto más puede que ya le esté llegando; ZChat aún no " +
                "lo sabe, así que no se lo volverá a enviar."
        const val ES_MAYBE_OTHER =
            "Tu estado llegó a %1\$d de %2\$d contactos. A %3\$d contactos más puede que ya les esté llegando; ZChat aún " +
                "no lo sabe, así que no se lo volverá a enviar."
        const val ES_REST = "Vuelve a configurar el mismo estado más tarde para llegar al resto."
        const val EN_BUSY =
            "Your status is still being sent to your contacts. Nothing more was sent or paid; set it again once that " +
                "finishes."
        const val ES_BUSY =
            "Tu estado aún se está enviando a tus contactos. No se envió ni se pagó nada más; vuelve a configurarlo " +
                "cuando termine."
    }
}
