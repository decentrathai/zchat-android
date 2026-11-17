package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue

/**
 * T2-extra (b) (fix-all 2.13.0, train T2; T1b carry-over 5c-bis): the chat list's long-press menu is in English in the
 * Spanish app.
 *
 * `ChatListView`'s chat-row menu hard-codes "Delete Chat", "Edit Contact" and "Add to Contacts". The two dialogs those
 * items open in `AndroidChat` (Add to Contacts, Edit Contact) hard-code their titles, "Contact Name", their buttons and
 * their toasts.
 *
 * Pinned here: no `Text("…")` literal and no `Toast.makeText(…, "…", …)` literal in the menu block or either dialog
 * block; each reads its resource; every new key has an English and a Spanish text (the Spanish is agent-drafted, for the
 * F14 native-speaker pass), and the English is what the screen said before.
 */
class ChatRowMenuStringsAreTranslatedTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun the_chat_row_menu_has_no_english_literal() {
        val literals = uiLiterals(menuBlock())
        assertTrue(literals.isEmpty(), "English-only texts in the chat-row menu: $literals")
    }

    @Test
    fun each_chat_row_menu_item_reads_its_resource() {
        val block = menuBlock()
        val missing = MENU_KEYS.keys.filter { "R.string.$it" !in block }
        assertTrue(missing.isEmpty(), "chat-row menu items that do not read their resource: $missing")
    }

    @Test
    fun the_contact_dialogs_have_no_english_literal() {
        val literals = uiLiterals(addContactBlock()) + uiLiterals(editContactBlock())
        assertTrue(literals.isEmpty(), "English-only texts in the Add/Edit contact dialogs: $literals")
    }

    @Test
    fun the_contact_dialogs_read_their_resources() {
        val block = addContactBlock() + editContactBlock()
        val missing = DIALOG_KEYS.keys.filter { "R.string.$it" !in block }
        assertTrue(missing.isEmpty(), "contact dialog texts that do not read their resource: $missing")
    }

    @Test
    fun every_new_key_has_its_english_and_spanish_text() {
        val problems =
            (MENU_KEYS + DIALOG_KEYS).flatMap { (key, texts) ->
                val (english, spanish) = texts
                listOfNotNull(
                    "$key: English is ${en[key]}, want $english".takeIf { en[key] != english },
                    "$key: Spanish is ${es[key]}, want $spanish".takeIf { es[key] != spanish },
                )
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** The chat row's menu: the long-press menu of SwipeableConversationItem (the group row above has its own). */
    private fun menuBlock(): String {
        val source = ChatResourceText.source(LIST)
        val row = source.indexOf("private fun SwipeableConversationItem(")
        check(row >= 0) { "SwipeableConversationItem not found" }
        return between(source.substring(row), "// Long-press dropdown menu", "// Divider — indented past avatar")
    }

    private fun addContactBlock(): String = between(ChatResourceText.source(CHAT), "// Add Contact Dialog", "// Edit Contact Dialog")

    private fun editContactBlock(): String = between(ChatResourceText.source(CHAT), "// Edit Contact Dialog", "if (showModeIntro)")

    private fun between(
        source: String,
        start: String,
        end: String
    ): String {
        val from = source.indexOf(start)
        check(from >= 0) { "marker not found: $start" }
        val to = source.indexOf(end, from)
        check(to > from) { "marker not found after $start: $end" }
        return source.substring(from, to)
    }

    /** Every string literal handed straight to a `Text(…)` or as the text of a `Toast.makeText(…)`. */
    private fun uiLiterals(block: String): List<String> =
        Regex("""\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"|Toast\.makeText\(\s*[\w.]+\s*,\s*"((?:[^"\\]|\\.)*)"""")
            .findAll(block)
            .map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            .toList()

    private companion object {
        const val LIST = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"
        const val CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"

        val MENU_KEYS =
            mapOf(
                "chat_list_row_menu_delete_chat" to ("Delete Chat" to "Eliminar chat"),
                "chat_list_row_menu_edit_contact" to ("Edit Contact" to "Editar contacto"),
                "chat_list_row_menu_add_contact" to ("Add to Contacts" to "Añadir a contactos"),
            )

        val DIALOG_KEYS =
            mapOf(
                "chat_list_add_contact_title" to ("Add to Contacts" to "Añadir a contactos"),
                "chat_list_contact_name_label" to ("Contact Name" to "Nombre del contacto"),
                "chat_list_add_contact_confirm" to ("Add" to "Añadir"),
                "chat_list_contact_dialog_cancel" to ("Cancel" to "Cancelar"),
                "chat_list_contact_added" to ("Contact added" to "Contacto añadido"),
                "chat_list_edit_contact_title" to ("Edit Contact" to "Editar contacto"),
                "chat_list_edit_contact_save" to ("Save" to "Guardar"),
                "chat_list_edit_contact_remove" to ("Remove" to "Quitar"),
                "chat_list_contact_updated" to ("Contact updated" to "Contacto actualizado"),
                "chat_list_contact_removed" to ("Contact removed" to "Contacto quitado"),
            )
    }
}
