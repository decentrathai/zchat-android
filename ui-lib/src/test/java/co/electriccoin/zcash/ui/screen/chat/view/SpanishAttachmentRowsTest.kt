package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 10a R10A-2 (F-R9P-4, Seeker run of cf72159ad on a Spanish phone) — the attachment rows speak the phone's
 * language.
 *
 * At cf72159ad the rows of a photo, a file and a voice note were English on a Spanish phone:
 * - the sending clock's and the read ticks' screen-reader labels, «Shared image», the voice button's
 *   «Downloading voice message» / «Pause» / «Play», «Uploading…» / «Downloading…», and the photo upload bar
 *   («Preparing image…», «Compressing…», «Encrypting & uploading… N%», «Finalizing…», «Sent») were literals;
 * - the photo's first row «📷 Preparing image…» was an English literal in the view model, and a file's row
 *   «📎 Document (7,0 MB)» took its words from ZFILEMessage.displayText, where only the number follows the locale;
 * - a failed upload row kept the label written when it failed («📎 File (failed)») after the phone switched to
 *   Spanish, because the view model survives the language change;
 * - the chat list's Search / New Group / My Address / Menu / Refresh / Destroy All labels and the Post-Quantum banner
 *   («Extra Security (Post-Quantum): Off») were English.
 *
 * Wanted: each has an English resource with the old English text and a Spanish one with the same placeholders, the
 * literals are gone, and a failed upload row's label is chosen when it is drawn.
 */
class SpanishAttachmentRowsTest {
    private val viewDir = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view"
    private val vmPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"

    @Test
    fun every_new_attachment_row_string_has_english_and_spanish_with_the_same_placeholders() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val wrong =
            NEW_STRINGS.flatMap { (name, texts) ->
                listOfNotNull(
                    if (en[name] != texts.first) "$name English\n  is:   ${en[name]}\n  want: ${texts.first}" else null,
                    if (es[name] != texts.second) "$name Spanish\n  is:   ${es[name]}\n  want: ${texts.second}" else null,
                    en[name]?.let { e ->
                        es[name]?.let { s ->
                            if (placeholders(e) != placeholders(s)) {
                                "$name placeholders: English ${placeholders(e)}, Spanish ${placeholders(s)}"
                            } else {
                                null
                            }
                        }
                    },
                )
            }
        assertTrue(wrong.isEmpty(), "attachment row strings missing or not as reviewed:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun the_english_literals_are_gone_from_the_attachment_rows_and_the_chat_list() {
        val literals =
            mapOf(
                "$viewDir/ChatDetailView.kt" to
                    listOf(
                        "contentDescription = \"Sending\"",
                        "contentDescription = \"Read\"",
                        "\"Shared image\"",
                        "-> \"Play\"",
                        "-> \"Pause\"",
                        "\"Downloading voice message\"",
                        "\"Uploading…\" else \"Downloading…\"",
                        "\"Preparing image…\"",
                        "\"Compressing…\"",
                        "\"Encrypting & uploading…",
                        "\"Finalizing…\"",
                        "\"Extra Security (Post-Quantum): \${",
                    ),
                "$viewDir/ChatListView.kt" to
                    listOf(
                        "contentDescription = \"Search\"",
                        "contentDescription = \"New Group\"",
                        "contentDescription = \"My Address\"",
                        "contentDescription = \"Menu\"",
                        "contentDescription = \"Refresh\"",
                        "contentDescription = \"Destroy All\"",
                    ),
                vmPath to
                    listOf(
                        "\"📷 Preparing image…\"",
                        "Voice message (\${durationSec}s) — uploading…",
                    ),
            )
        val found =
            literals.flatMap { (path, list) ->
                val code = code(ChatResourceText.source(path))
                list.filter { it in code }.map { "${path.substringAfterLast('/')} still holds $it" }
            }
        assertTrue(found.isEmpty(), found.joinToString("\n"))
    }

    @Test
    fun a_file_row_is_built_from_the_localized_type_label_not_from_displayText() {
        val vm = code(ChatResourceText.source(vmPath))
        val stale =
            Regex(""""📎 " \+\s*[\s\S]{0,600}?\.displayText""").findAll(vm).count() +
                Regex("""\{ "📎 \$\{it\.displayText\}" \}""").findAll(vm).count()
        assertEquals(0, stale, "ChatViewModel still builds an outgoing file row from the English ZFILEMessage.displayText")
        assertTrue("typeLabelRes" in vm, "ChatViewModel does not build the file row from ZFILEMessage.typeLabelRes")
        val model = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessage.kt")
        for (name in listOf("chat_attachment_image", "chat_attachment_voice", "chat_attachment_document")) {
            assertTrue("R.string.$name" in model, "ZFILEMessage.typeLabelRes does not use $name")
        }
    }

    @Test
    fun a_failed_upload_row_takes_its_label_when_it_is_drawn() {
        val detail = code(ChatResourceText.source("$viewDir/ChatDetailView.kt"))
        assertTrue("AttachmentIds.isUpload(" in detail, "the bubble does not tell an upload row by AttachmentIds")
        assertTrue(
            "AttachmentIds.failedLabelRes(" in detail,
            "the bubble shows the failed upload label written at failure time (message.text), not the current language's",
        )
        val ids = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/AttachmentIds.kt")
        for (name in listOf("chat_photo_failed_label", "chat_file_failed_label", "chat_voice_failed_label")) {
            assertTrue("R.string.$name" in ids, "AttachmentIds.failedLabelRes does not use $name")
        }
    }

    private fun code(source: String): String =
        source
            .lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** Placeholders as (argument index, conversion) pairs; unnumbered ones count up from 1. */
    private fun placeholders(text: String): List<String> {
        var next = 1
        return PLACEHOLDER
            .findAll(text)
            .filter { it.value != "%%" }
            .map { m ->
                val index = m.groupValues[1].ifEmpty { (next++).toString() }
                "%$index\$${m.groupValues[2]}"
            }.sorted()
            .toList()
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([sdfxXc%])""")

        val NEW_STRINGS: Map<String, Pair<String, String>> =
            mapOf(
                "chat_cd_sending" to ("Sending" to "Enviando"),
                "chat_a11y_status_read" to ("Read" to "Leído"),
                "chat_cd_shared_image" to ("Shared image" to "Imagen compartida"),
                "chat_cd_play" to ("Play" to "Reproducir"),
                "chat_cd_pause" to ("Pause" to "Pausar"),
                "chat_cd_downloading_voice" to ("Downloading voice message" to "Descargando el mensaje de voz"),
                "chat_transfer_uploading" to ("Uploading…" to "Subiendo…"),
                "chat_transfer_downloading" to ("Downloading…" to "Descargando…"),
                "chat_upload_preparing_image" to ("Preparing image…" to "Preparando la imagen…"),
                "chat_upload_compressing" to ("Compressing…" to "Comprimiendo…"),
                "chat_upload_encrypting" to ("Encrypting & uploading… %1\$d%%" to "Cifrando y subiendo… %1\$d %%"),
                "chat_upload_finalizing" to ("Finalizing…" to "Finalizando…"),
                "chat_upload_sent" to ("Sent" to "Enviado"),
                "chat_photo_preparing" to ("📷 Preparing image…" to "📷 Preparando la imagen…"),
                "chat_attachment_image" to ("Image (%1\$s)" to "Imagen (%1\$s)"),
                "chat_attachment_voice" to ("Voice message (%1\$s)" to "Mensaje de voz (%1\$s)"),
                "chat_attachment_document" to ("Document (%1\$s)" to "Documento (%1\$s)"),
                "chat_list_cd_search" to ("Search" to "Buscar"),
                "chat_list_cd_new_group" to ("New Group" to "Nuevo grupo"),
                "chat_list_cd_my_address" to ("My Address" to "Mi dirección"),
                "chat_list_cd_menu" to ("Menu" to "Menú"),
                "chat_list_cd_refresh" to ("Refresh" to "Actualizar"),
                "chat_list_cd_destroy_all" to ("Destroy All" to "Destruir todo"),
                "chat_extra_security_banner" to
                    ("Extra Security (Post-Quantum): %1\$s" to "Seguridad extra (poscuántica): %1\$s"),
                "chat_extra_security_off" to ("Off" to "Desactivada"),
                "chat_extra_security_on" to ("On" to "Activada"),
                "chat_extra_security_pending" to
                    ("Setting up — scan your peer's QR" to "Configurándose: escanea el QR de tu contacto"),
            )
    }
}
