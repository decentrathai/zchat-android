package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1104 (owner design 26 Oct 2026, W-79): at bc8efae90 `ConversationsAndGroupsList` draws a "Groups" header and
 * every group (`items(groups, …)`) above the private chats. It must draw the rows `ChatListLayout.rows(…)` returns
 * (one Groups row, collapsed by default, its groups inline only when expanded), take the expanded flag from
 * `ChatListGroupsFolderStore` and write the user's tap back to it, and label the row with string resources in English
 * and Spanish (Spanish is an agent draft for the native-speaker pass).
 *
 * JVM unit tests here have no Compose layout, so this pins the structure; the look is a phone check.
 */
class GroupsFolderRowIsWiredAndTextedTest {
    private val viewPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"
    private val lexed by lazy { KotlinUiLiterals.lex(ChatResourceText.source(viewPath)) }

    private fun code(anchor: String): String {
        val r = KotlinUiLiterals.block(lexed, anchor)
        return lexed.blanked.substring(r.first, r.last + 1)
    }

    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val enPlurals by lazy { ChatResourceText.plurals("values") }
    private val esPlurals by lazy { ChatResourceText.plurals("values-es") }

    @Test
    fun the_list_draws_the_layout_rows_not_every_group_first() {
        val list = code("private fun ConversationsAndGroupsList(")
        assertTrue(
            Regex("""\bChatListLayout\.rows\(""").containsMatchIn(list),
            "ConversationsAndGroupsList does not draw ChatListLayout.rows(…): every group is listed above the private chats"
        )
        assertFalse(
            Regex("""\bitems\(\s*groups\b""").containsMatchIn(list),
            "ConversationsAndGroupsList still draws items(groups, …) unconditionally above the chats"
        )
    }

    @Test
    fun the_expanded_flag_is_read_from_and_written_to_the_store() {
        val chatMain = File(ChatResourceText.repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat")
        val users =
            chatMain
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "ChatListGroupsFolderStore.kt" }
                .map { it.readText() }
                .filter { "ChatListGroupsFolderStore" in it }
                .toList()
        assertTrue(users.isNotEmpty(), "nothing outside the store uses ChatListGroupsFolderStore: the choice is not remembered")
        assertTrue(users.any { Regex("""\bisExpanded\(\)""").containsMatchIn(it) }, "the stored choice is never read")
        assertTrue(users.any { Regex("""\bsetExpanded\(""").containsMatchIn(it) }, "a tap on the Groups row is never stored")
    }

    @Test
    fun the_groups_row_texts_are_resources_in_english_and_spanish() {
        val view = lexed.blanked
        val refs =
            listOf(
                "R.plurals.chat_list_groups_folder_description",
                "R.string.chat_list_groups_folder_expand",
                "R.string.chat_list_groups_folder_collapse",
                "R.string.chat_list_section_groups"
            )
        for (ref in refs) {
            assertTrue(Regex(Regex.escape(ref) + "\\b").containsMatchIn(view), "ChatListView.kt does not use $ref")
        }

        val strings =
            mapOf(
                "chat_list_groups_folder_expand" to "Show groups",
                "chat_list_groups_folder_collapse" to "Hide groups"
            )
        strings.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
        }

        val key = "chat_list_groups_folder_description"
        val description = assertNotNull(enPlurals[key], "no English plurals $key")
        assertEquals("%1\$d group, %2\$d unread", description["one"], "English one")
        assertEquals("%1\$d groups, %2\$d unread", description["other"], "English other")
        val spanish = assertNotNull(esPlurals[key], "no Spanish plurals $key")
        for (quantity in listOf("one", "other")) {
            val text = assertNotNull(spanish[quantity], "no Spanish $quantity")
            assertNotEquals(description[quantity], text, "Spanish $quantity is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(description[quantity]!!).sorted(),
                SpanishSendPaymentDialogTest.placeholders(text).sorted(),
                "Spanish $quantity: the placeholders differ"
            )
        }
    }
}
