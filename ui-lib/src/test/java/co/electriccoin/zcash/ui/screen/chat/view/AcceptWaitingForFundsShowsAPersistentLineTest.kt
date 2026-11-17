package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1363 (phone matrix 1 Nov, Seeker release, 08:48:47): Accept with funds confirming shows only a toast, cut by the phone
 * to two lines, so "goes by itself once the ZEC can be spent, while ZChat is open" is never read and nothing on screen says
 * the Accept is waiting. The waiting state must stand as a line in the chat with the confirmation progress; the toast stays
 * one short sentence.
 *
 * Seams (English and Spanish): `chat_accept_waits_for_funds_banner`, taking the confirmations (%1$d), the confirmations
 * needed (%2$d) and the minutes (%3$d), filled by ChatDetailView from a ConfirmationProgress (`confirmations`, `required`,
 * `etaMinutes`); `chat_send_kex_answer_waits_for_funds` stays the toast, one sentence under [TOAST_MAX] characters. Read from
 * the sources: a JVM test cannot render the chat. A phone check of the line is still owed.
 */
class AcceptWaitingForFundsShowsAPersistentLineTest {
    private val locales = listOf("values", "values-es")

    @Test
    fun `the waiting line takes the progress and says the Accept goes by itself while ZChat is open, in both languages`() {
        val says =
            mapOf(
                "values" to listOf("goes by itself", "while ZChat is open"),
                "values-es" to listOf("solo", "mientras ZChat esté abierto"),
            )
        for (locale in locales) {
            val line =
                assertNotNull(
                    ChatResourceText.strings(locale)["chat_accept_waits_for_funds_banner"],
                    "$locale has no chat_accept_waits_for_funds_banner: the waiting Accept is only a toast",
                )
            for (slot in listOf("%1\$d", "%2\$d", "%3\$d")) assertTrue(slot in line, "$locale waiting line lacks $slot: $line")
            for (words in says.getValue(locale)) assertTrue(words in line, "$locale waiting line does not say \"$words\": $line")
        }
    }

    @Test
    fun `the chat shows the waiting line with the confirmation progress`() {
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        val at = view.indexOf("R.string.chat_accept_waits_for_funds_banner")
        assertTrue(at >= 0, "ChatDetailView shows no line for an Accept waiting for its funds")
        val around = view.substring((at - WINDOW).coerceAtLeast(0), (at + WINDOW).coerceAtMost(view.length))
        for (field in listOf("confirmations", "required", "etaMinutes")) {
            assertTrue(field in around, "the waiting line does not show $field")
        }
    }

    @Test
    fun `the Accept waits toast is one sentence a phone shows whole, in both languages`() {
        for (locale in locales) {
            val toast =
                assertNotNull(
                    ChatResourceText.strings(locale)["chat_send_kex_answer_waits_for_funds"],
                    "$locale has no chat_send_kex_answer_waits_for_funds",
                ).trim()
            assertTrue(toast.length < TOAST_MAX, "$locale Accept waits toast is ${toast.length} characters (cut on the phone): $toast")
            val sentences = toast.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
            assertTrue(sentences.size == 1, "$locale Accept waits toast is ${sentences.size} sentences: $toast")
        }
    }

    private companion object {
        const val WINDOW = 600
        const val TOAST_MAX = 80
    }
}
