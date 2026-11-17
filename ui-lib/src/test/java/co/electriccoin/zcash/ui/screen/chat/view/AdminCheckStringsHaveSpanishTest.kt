package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b section 8, skill app-texts; section 4.1 class 10) — the texts of the admin
 * check are string resources in English and Spanish (agent drafts for the native-speaker pass), with the same
 * placeholders (the pattern of [GroupJoinStringsHaveSpanishTest]).
 *
 * These are the 22 keys that ship with pair 1 (`group_admin_check_strings.xml`); pairs 2 and 3 add theirs. The English
 * is pinned word for word, because each sentence was written against what the code does:
 * - the line says the admin's changes "may not be applied" (some still are) and that someone the admin removed "may
 *   still read what you write" (this phone keeps sending to them under a key they hold);
 * - no text says "free" or "automatic": nothing here names a cost, and nothing is repaired without the user's tap;
 * - the result text says what is and is not caught up, and never "all".
 *
 * At `00f1a349b` none of the keys exists.
 */
class AdminCheckStringsHaveSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val expectedEnglish =
        mapOf(
            "group_admin_check_line" to
                "This phone can’t confirm this group’s admin. The admin’s changes may not be applied here, and someone " +
                "the admin removed may still read what you write.",
            "group_admin_check_action" to "Check",
            "group_admin_check_title" to "This phone can’t confirm this group’s admin",
            "group_admin_check_body" to
                "This phone can’t tell which of your chats belongs to the admin of this group. Until that is settled, " +
                "the admin’s changes are ignored here: removing a member, a new group key and the member list. Someone " +
                "the admin already removed can still read what you write in this group.",
            "group_admin_candidate_invite" to
                "ZChat’s records on this phone show that the invitation to this group came from your chat with " +
                "%1\$s (%2\$s).",
            "group_admin_candidate_verify" to
                "Compare your safety number with %1\$s first: open the chat, open the menu, tap “Verify safety number”, " +
                "compare the number with them in person or on a call you trust, and mark it as verified. If the numbers " +
                "don’t match, don’t mark it and don’t confirm. Then come back here.",
            "group_admin_candidate_key_changed" to
                "%1\$s’s security key changed and that is not settled yet. Settle the security key warning in your chat " +
                "with %1\$s first.",
            "group_admin_candidate_not_finished" to
                "Your chat with %1\$s is not a finished secure chat yet. Open it and finish setting up the secure chat " +
                "first; then compare your safety number with them.",
            "group_admin_no_candidate" to
                "This phone has no record of which of your chats belongs to this group’s admin, so it can’t offer a " +
                "check. Ask the admin to create a new group and invite everyone again.",
            "group_admin_open_chat" to "Open chat",
            "group_admin_check_button" to "Confirm the admin",
            "group_admin_confirm_title" to "Confirm this group’s admin?",
            "group_admin_confirm_body" to
                "%1\$s (%2\$s) will be able to remove members and change the key of “%3\$s” on this phone. Confirm only " +
                "if %1\$s is the person who created this group and you compared this safety number with them: %4\$s",
            "group_admin_confirm_applies" to
                "Changes this person already made to this group are applied right after you confirm, as far as this " +
                "phone has them. If they removed you from the group, you leave it then.",
            "group_admin_confirm_final" to
                "You can’t change this later in ZChat 2.13. If it turns out to be wrong, the admin has to create a new " +
                "group.",
            "group_admin_confirm_button" to "Confirm",
            "group_admin_confirmed_title" to "Admin confirmed",
            "group_admin_confirmed_body" to
                "From now on this phone applies the changes %1\$s makes to this group. ZChat now applies the earlier " +
                "changes this phone has: those sent on the chain, and those that reached it over the relays since this " +
                "update. Changes that reached this phone over the relays before that are not applied: if the admin " +
                "removed someone back then, that person may still be in this phone’s member list and may read what you " +
                "write, until the admin’s member list arrives or the admin changes the group key.",
            "group_admin_not_confirmed" to "Nothing was confirmed: something changed while this was open. Check again.",
            "group_admin_not_saved" to
                "ZChat couldn’t save this, so nothing was confirmed. Free up some space or restart your phone, then try " +
                "again.",
            "group_admin_catchup_list" to
                "The admin’s member list has not reached this phone yet. Someone the admin removed earlier may still " +
                "read what you write here. Ask the admin to open this group in ZChat 2.13 or newer.",
            "group_admin_catchup_key" to
                "This phone can’t read the newest messages here, and the admin’s ZChat is older than 2.13, so it can’t " +
                "send the key again on request. Ask the admin to update ZChat or to change the group key again.",
        )

    @Test
    fun every_new_key_is_english_and_spanish_with_the_same_placeholders() {
        expectedEnglish.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(english).sorted(),
                SpanishSendPaymentDialogTest.placeholders(spanish).sorted(),
                "$key: the Spanish placeholders differ",
            )
        }
    }

    @Test
    fun no_text_says_free_or_automatic() {
        for ((language, texts) in listOf("English" to en, "Spanish" to es)) {
            for (key in expectedEnglish.keys) {
                val text = assertNotNull(texts[key], "no $language $key")
                for (claim in listOf("automatic", "free", "gratis", "automátic")) {
                    assertFalse(claim in text, "$language $key says \"$claim\": $text")
                }
            }
        }
    }

    @Test
    fun the_line_says_that_someone_removed_may_still_read() {
        val english = assertNotNull(en["group_admin_check_line"], "no English group_admin_check_line")
        val spanish = assertNotNull(es["group_admin_check_line"], "no Spanish group_admin_check_line")
        assertTrue("removed may still read" in english, "the English line does not say that someone removed may still read: $english")
        assertTrue("puede seguir leyendo" in spanish, "the Spanish line does not say that someone removed may still read: $spanish")
    }
}
