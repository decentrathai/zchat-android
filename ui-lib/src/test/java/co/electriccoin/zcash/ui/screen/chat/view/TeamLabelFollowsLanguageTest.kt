package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.datasource.TeamContactMigration
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P10 (PHONE_TRIAGE ES-7, the Team label only) — the label TeamContactMigration writes into the old Team
 * contact ("ZChat Team (old address, not monitored)", or "<name> (old ZChat Team address, not monitored)") is new in
 * 2.13.0 and is shown in English on a Spanish phone, because the screens print the stored name as it is.
 *
 * Wanted: a display-time contactDisplayName(stored, strings) that shows the label in the phone's language. The stored
 * value is not changed (the migration keeps writing and matching the English constants, so its one-off pass and its
 * "already labelled" check are unchanged). It is used at the chat list row, the New Chat CONTACTS list, the Create
 * Group members and the Address book (contactDisplayNameRes, for the view models that build a StringResource).
 *
 * The functions are reached by reflection so this file compiles before they exist.
 */
class TeamLabelFollowsLanguageTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val modelPackage = "co.electriccoin.zcash.ui.screen.chat.model"

    private fun textFor(strings: Map<String, String>): Any {
        val cls = Class.forName("$modelPackage.TeamLabelText")
        val renamed = assertNotNull(strings["contact_team_old_renamed"], "no contact_team_old_renamed")
        val userNamed = assertNotNull(strings["contact_team_old_user_named"], "no contact_team_old_user_named")
        return cls.getConstructor(String::class.java, String::class.java).newInstance(renamed, userNamed)
    }

    private fun displayName(
        stored: String,
        strings: Map<String, String>
    ): String {
        val text = textFor(strings)
        val fn = Class.forName("$modelPackage.ContactDisplayNameKt").getMethod("contactDisplayName", String::class.java, text.javaClass)
        return fn.invoke(null, stored, text) as String
    }

    private fun displayNameRes(stored: String): StringResource {
        val fn = Class.forName("$modelPackage.ContactDisplayNameKt").getMethod("contactDisplayNameRes", String::class.java)
        return fn.invoke(null, stored) as StringResource
    }

    @Test
    fun the_label_has_an_english_and_a_spanish_resource() {
        // English byte for byte what the migration stores, so an English phone shows exactly what it showed before.
        assertEquals(TeamContactMigration.RENAMED_DEFAULT, en["contact_team_old_renamed"])
        assertEquals("%1\$s" + TeamContactMigration.USER_NAME_LABEL, en["contact_team_old_user_named"])
        for (key in listOf("contact_team_old_renamed", "contact_team_old_user_named")) {
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(en[key], spanish, "$key is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(en.getValue(key)),
                SpanishSendPaymentDialogTest.placeholders(spanish),
                "$key: the Spanish placeholders differ"
            )
        }
    }

    @Test
    fun a_spanish_phone_shows_the_label_in_spanish() {
        assertEquals(es["contact_team_old_renamed"], displayName(TeamContactMigration.RENAMED_DEFAULT, es))
        assertEquals(
            String.format(Locale.ROOT, es.getValue("contact_team_old_user_named"), "Ana"),
            displayName("Ana" + TeamContactMigration.USER_NAME_LABEL, es)
        )
    }

    @Test
    fun an_english_phone_shows_what_is_stored() {
        assertEquals(TeamContactMigration.RENAMED_DEFAULT, displayName(TeamContactMigration.RENAMED_DEFAULT, en))
        val labelled = "Ana" + TeamContactMigration.USER_NAME_LABEL
        assertEquals(labelled, displayName(labelled, en))
    }

    @Test
    fun every_other_name_is_shown_as_stored() {
        for (name in listOf("Ana", "ZChat Team", "", "Team (old)", "  Bob  ")) {
            assertEquals(name, displayName(name, es), "a name without the Team label changed: \"$name\"")
        }
    }

    @Test
    fun the_address_book_gets_the_label_as_a_resource() {
        val renamed = assertIs<StringResource.ByResource>(displayNameRes(TeamContactMigration.RENAMED_DEFAULT))
        assertEquals(ChatResourceText.stringId("contact_team_old_renamed"), renamed.resource)
        val labelled = assertIs<StringResource.ByResource>(displayNameRes("Ana" + TeamContactMigration.USER_NAME_LABEL))
        assertEquals(ChatResourceText.stringId("contact_team_old_user_named"), labelled.resource)
        assertEquals(listOf<Any>("Ana"), labelled.args)
        assertEquals(StringResource.ByString("Ana"), displayNameRes("Ana"))
    }

    @Test
    fun the_four_places_show_the_display_name() {
        val base = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/"
        for (file in listOf("chat/view/ChatListView.kt", "chat/view/ZchatComposeView.kt", "chat/view/CreateGroupView.kt")) {
            val src = ChatResourceText.source(base + file)
            assertTrue("contactDisplayName(" in src, "$file shows the stored contact name as it is")
        }
        val addressBook = ChatResourceText.source(base + "addressbook/AddressBookVM.kt")
        assertTrue("contactDisplayNameRes(contact.name)" in addressBook, "the Address book shows the stored contact name as it is")
        assertTrue("stringRes(contact.name)" !in addressBook, "the Address book still shows the stored contact name")
    }
}
