package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1 finding 5c (fix-all 2.13.0, train T1b, T1b-3): the long-press message menu is in English in the Spanish app.
 *
 * `ChatDetailView`'s message menu hard-codes "Reply", "React", "Copy", "Save", "Share" and "Forward", and the Copy
 * item's toast says "Copied". Only "Delete Message" reads a resource (Round 8 D8). The chat list's Delete Chat
 * dialog in `AndroidChat` hard-codes its title, text, "Delete", "Cancel" and the "Chat hidden" toast.
 *
 * Pinned here:
 * - no `Text("…")` literal and no `Toast.makeText(…, "…", …)` literal in the message menu block or in the Delete
 *   Chat dialog block;
 * - each item reads its resource;
 * - every new key has an English and a Spanish text (the Spanish is agent-drafted, for the F14 native-speaker pass),
 *   and the English is what the menu said before.
 */
class MessageMenuStringsAreTranslatedTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun the_message_menu_has_no_english_literal() {
        val literals = uiLiterals(menuBlock())
        assertTrue(literals.isEmpty(), "English-only texts in the message menu: $literals")
    }

    @Test
    fun each_message_menu_item_reads_its_resource() {
        val block = menuBlock()
        val missing = MENU_KEYS.keys.filter { "R.string.$it" !in block }
        assertTrue(missing.isEmpty(), "message menu items that do not read their resource: $missing")
    }

    @Test
    fun the_delete_chat_dialog_has_no_english_literal() {
        val literals = uiLiterals(deleteChatBlock())
        assertTrue(literals.isEmpty(), "English-only texts in the Delete Chat dialog: $literals")
    }

    @Test
    fun the_delete_chat_dialog_reads_its_resources() {
        val block = deleteChatBlock()
        val missing = DELETE_CHAT_KEYS.keys.filter { "R.string.$it" !in block }
        assertTrue(missing.isEmpty(), "Delete Chat dialog texts that do not read their resource: $missing")
    }

    @Test
    fun every_new_key_has_its_english_and_spanish_text() {
        val problems =
            (MENU_KEYS + DELETE_CHAT_KEYS).flatMap { (key, texts) ->
                val (english, spanish) = texts
                listOfNotNull(
                    "$key: English is ${en[key]}, want $english".takeIf { en[key] != english },
                    "$key: Spanish is ${es[key]}, want $spanish".takeIf { es[key] != spanish },
                )
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** Control: the scan finds what it is meant to find, and passes what reads a resource. */
    @Test
    fun the_scan_finds_the_literals_it_is_meant_to_find() {
        val sample =
            """
            DropdownMenuItem(text = { Text("Reply") }, onClick = {})
            Text(text = "Forward")
            Toast.makeText(copyContext, "Copied", Toast.LENGTH_SHORT).show()
            Text(stringResource(R.string.chat_message_menu_delete))
            Toast.makeText(context, context.getString(R.string.chat_list_chat_hidden), Toast.LENGTH_SHORT).show()
            Text("Delete", color = NightwireColors.ColorDanger)
            Text(
                "Are you sure?"
            )
            """.trimIndent()
        assertEquals(listOf("Reply", "Forward", "Copied", "Delete", "Are you sure?"), uiLiterals(sample))
    }

    private fun menuBlock(): String = between(ChatResourceText.source(DETAIL), "// Long-press dropdown menu", "if (showDeleteConfirm)")

    private fun deleteChatBlock(): String =
        between(ChatResourceText.source(CHAT), "// Delete Chat Confirmation Dialog", "// Leave Group Confirmation Dialog")

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
        const val DETAIL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"

        val MENU_KEYS =
            mapOf(
                "chat_message_menu_reply" to ("Reply" to "Responder"),
                "chat_message_menu_react" to ("React" to "Reaccionar"),
                "chat_message_menu_copy" to ("Copy" to "Copiar"),
                "chat_message_copied" to ("Copied" to "Copiado"),
                "chat_message_menu_save" to ("Save" to "Guardar"),
                "chat_message_menu_share" to ("Share" to "Compartir"),
                "chat_message_menu_forward" to ("Forward" to "Reenviar"),
                "chat_message_menu_delete" to ("Delete Message" to "Eliminar mensaje"),
            )

        val DELETE_CHAT_KEYS =
            mapOf(
                "chat_list_delete_chat_title" to ("Delete Chat" to "Eliminar chat"),
                "chat_list_delete_chat_text" to (
                    "Are you sure you want to delete this chat? The messages will still be stored on the blockchain, " +
                        "but they will be hidden from this app." to
                        "¿Seguro que quieres eliminar este chat? Los mensajes seguirán guardados en la blockchain, " +
                        "pero se ocultarán en esta app."
                ),
                "chat_list_delete_chat_confirm" to ("Delete" to "Eliminar"),
                "chat_list_delete_chat_cancel" to ("Cancel" to "Cancelar"),
                "chat_list_chat_hidden" to ("Chat hidden" to "Chat oculto"),
            )
    }
}
