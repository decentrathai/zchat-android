package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * G3 8a and 8c (fix-all 2.13.0, train T4b; groups SPEC §3.5 J2, §3.7 with review G) — the Join outcome texts and the
 * own-address text of New Chat are string resources in English and Spanish (an agent draft for the native-speaker
 * pass), with the same placeholders.
 *
 * The texts claim only what the code does: `group_join_sent` says the phone joined and nothing about the "I joined"
 * reply (a paid reply can still be refused, and that refusal has its own notice); `group_join_failed` says nothing was
 * sent or paid (every `false` of joinGroupInvite returns before a send is started); `new_chat_own_address` says the
 * address is this wallet's, not that it is invalid.
 */
class GroupJoinStringsHaveSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val expectedEnglish =
        mapOf(
            "group_join_sent" to "You joined “%1\$s”.",
            "group_join_failed" to
                "Couldn’t join “%1\$s”. Nothing was sent or paid. Open the group again to see where it stands.",
            "new_chat_own_address" to
                "This address belongs to this wallet (one of its accounts). You can’t start a chat with it.",
        )

    @Test
    fun every_new_key_is_english_and_spanish_with_the_same_placeholders() {
        expectedEnglish.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(english),
                SpanishSendPaymentDialogTest.placeholders(spanish),
                "$key: the Spanish placeholders differ",
            )
        }
    }

    @Test
    fun the_join_text_makes_no_claim_about_the_reply() {
        for (lang in listOf(en, es)) {
            val sent = lang["group_join_sent"].orEmpty().lowercase()
            for (claim in listOf("reply", "on its way", "respuesta", "en camino")) {
                assertFalse(claim in sent, "group_join_sent claims something about the reply: $sent")
            }
        }
    }
}
