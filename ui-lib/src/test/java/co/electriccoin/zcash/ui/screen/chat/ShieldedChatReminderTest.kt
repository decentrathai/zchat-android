package co.electriccoin.zcash.ui.screen.chat

import co.electriccoin.zcash.ui.screen.chat.model.ChatFreeInstead
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ShieldedReminderUi
import co.electriccoin.zcash.ui.screen.chat.model.UpgradeReminders
import co.electriccoin.zcash.ui.screen.chat.model.ZchatShareExclusions
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bridge 3 (transition plan section 2.3 and 2.5, copy deck "2.3 In chats" and "2.5"): contacts still on
 * ZChat 2.12.4 or older can't see Shielded messages or ZEC sent after the Zcash network upgrade until they
 * update. A 2.13.0 chat says so where it matters:
 *  - every Shielded (Vault) chat shows a reminder, and only Shielded chats; Hide leaves a small
 *    "May need update" chip in that chat, and the chip opens the reminder again;
 *  - "Ask them to update" shares the update text outside ZChat: ZChat's own share target is excluded, so
 *    the text can't be posted into a chat;
 *  - "Chat free instead" opens the mode picker, or, with no chat key stored for the contact, the no-key text;
 *  - group chats and New Chat with Shielded selected carry a static line;
 *  - both payment confirms (Send Payment, the pay-request confirm) carry the payment line;
 *  - an Open chat shows the chat-request hint until a free message from the contact arrives.
 * Every sentence matches the copy deck word for word, in English and Spanish ({name} is %1$s).
 */
class ShieldedChatReminderTest {
    // ZC-1941: the reminder ends on 2026-12-17; these rules are pinned to a day before it so they hold after that day.
    private val beforeTheEnd: LocalDate = LocalDate.of(2026, 11, 20)

    // ---- the reminder and its chip ----

    @Test
    fun the_reminder_shows_in_shielded_chats_only() {
        assertEquals(
            ShieldedReminderUi.BANNER,
            UpgradeReminders.shieldedReminder(ConversationMode.VAULT, canReply = true, hiddenByUser = false, today = beforeTheEnd)
        )
        for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.OPEN)) {
            for (hidden in listOf(false, true)) {
                assertEquals(
                    ShieldedReminderUi.NONE,
                    UpgradeReminders.shieldedReminder(mode, canReply = true, hiddenByUser = hidden, today = beforeTheEnd),
                    "$mode"
                )
            }
        }
        // A message from an unknown sender can't be answered: nothing to remind about.
        assertEquals(
            ShieldedReminderUi.NONE,
            UpgradeReminders.shieldedReminder(ConversationMode.VAULT, canReply = false, hiddenByUser = false, today = beforeTheEnd)
        )
    }

    @Test
    fun hide_leaves_the_chip_and_the_chip_opens_the_reminder_again() {
        assertEquals(
            ShieldedReminderUi.CHIP,
            UpgradeReminders.shieldedReminder(ConversationMode.VAULT, canReply = true, hiddenByUser = true, today = beforeTheEnd)
        )
        // The chip clears the hidden flag: the reminder is back.
        assertEquals(
            ShieldedReminderUi.BANNER,
            UpgradeReminders.shieldedReminder(ConversationMode.VAULT, canReply = true, hiddenByUser = false, today = beforeTheEnd)
        )

        val view = source("view/ChatDetailView.kt")
        assertTrue(
            "ShieldedReminderUi.CHIP" in view && "onShowShieldedReminder" in view,
            "ChatDetailView shows no chip that reopens the reminder"
        )
        val chat = source("AndroidChat.kt")
        assertTrue("setShieldedReminderHidden(peerAddress, false)" in chat, "the chip does not open the reminder again")
        assertTrue("setShieldedReminderHidden(peerAddress, true)" in chat, "Hide does not leave the chip")
    }

    @Test
    fun the_reminder_matches_the_copy_deck_in_english_and_spanish() {
        assertDeck(REMINDER)
        val view = source("view/ChatDetailView.kt")
        for (key in REMINDER.keys) {
            assertTrue("R.string.$key" in view, "ChatDetailView does not show $key")
        }
    }

    // ---- "Chat free instead" and the no-key text ----

    @Test
    fun chat_free_instead_opens_the_mode_picker_or_the_no_key_text() {
        assertEquals(ChatFreeInstead.MODE_PICKER, UpgradeReminders.chatFreeInstead(hasPeerChatKey = true))
        assertEquals(ChatFreeInstead.NO_KEY_TEXT, UpgradeReminders.chatFreeInstead(hasPeerChatKey = false))
        assertDeck(NO_KEY)
        val chat = source("AndroidChat.kt")
        for (key in NO_KEY.keys) {
            assertTrue("R.string.$key" in chat, "the no-key text does not show $key")
        }
    }

    // ---- "Ask them to update" ----

    @Test
    fun the_share_intent_excludes_zchats_own_component() {
        val own = "xyz.zsend.zchat.foss.debug"
        // The alias is the component that receives ACTION_SEND text in every ZChat build.
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            Regex("""android:name="${Regex.escape(ZchatShareExclusions.SHARE_TARGET_ALIAS)}"""").containsMatchIn(manifest),
            "the share-target alias is not in the app manifest",
        )
        assertTrue(own to ZchatShareExclusions.SHARE_TARGET_ALIAS in ZchatShareExclusions.excluded(own, emptyList()))

        val resolved =
            listOf(
                "com.example.messenger" to "com.example.messenger.Share",
                own to ZchatShareExclusions.SHARE_TARGET_ALIAS,
                // Another ZChat build installed side by side (the release next to a test build).
                "xyz.zsend.zchat" to ZchatShareExclusions.SHARE_TARGET_ALIAS,
                "xyz.zsend.zchatter" to "xyz.zsend.zchatter.Share",
            )
        val excluded = ZchatShareExclusions.excluded(own, resolved)
        assertTrue(own to ZchatShareExclusions.SHARE_TARGET_ALIAS in excluded, "$excluded")
        assertTrue("xyz.zsend.zchat" to ZchatShareExclusions.SHARE_TARGET_ALIAS in excluded, "$excluded")
        assertFalse(excluded.any { it.first == "com.example.messenger" }, "$excluded")
        assertFalse(excluded.any { it.first == "xyz.zsend.zchatter" }, "$excluded")
        assertEquals(excluded.distinct(), excluded)

        val share = source("view/AskThemToUpdate.kt")
        assertTrue("EXTRA_EXCLUDE_COMPONENTS" in share && "ZchatShareExclusions.excluded" in share, "the chooser excludes nothing")
    }

    @Test
    fun the_share_text_matches_the_copy_deck_english_then_spanish() {
        val en = strings("values")
        assertEquals(SHARE_EN, en["upgrade_share_text_en"], "English share text")
        assertEquals(SHARE_ES, en["upgrade_share_text_es"], "Spanish share text")
        val text = en.getValue("upgrade_share_text_en") + en.getValue("upgrade_share_text_es")
        assertTrue("zsend.xyz/update" in text)
        assertFalse(Regex("""https?://""").containsMatchIn(text), "the only link is zsend.xyz/update")
    }

    @Test
    fun zc1911_the_share_text_names_both_network_upgrades_and_says_what_is_kept() {
        // ZC-1911 (owner W-148): the first paragraph named only the July upgrade, and "Nothing is lost" was unclear.
        val en = strings("values")
        val first =
            mapOf(
                "en" to en.getValue("upgrade_share_text_en").substringBefore("\n\n"),
                "es" to en.getValue("upgrade_share_text_es").substringBefore("\n\n"),
            )
        for ((lang, paragraph) in first) {
            assertTrue("Ironwood" in paragraph && "NU7" in paragraph, "$lang: names both upgrades: $paragraph")
            assertTrue("ZChat 2.13" in paragraph, "$lang: names the version to update to: $paragraph")
            assertFalse("28" in paragraph, "$lang: no single July date: $paragraph")
        }
        assertFalse("Nothing is lost" in first.getValue("en"), first.getValue("en"))
        assertFalse("No se pierde nada" in first.getValue("es"), first.getValue("es"))
    }

    // ---- static lines ----

    @Test
    fun group_chats_and_new_chat_with_shielded_selected_carry_the_version_line() {
        assertDeck(STATIC_LINES)
        assertTrue(UpgradeReminders.showsComposeLine(ConversationMode.VAULT))
        assertFalse(UpgradeReminders.showsComposeLine(ConversationMode.TUNNEL))
        assertFalse(UpgradeReminders.showsComposeLine(ConversationMode.OPEN))
        assertTrue("R.string.upgrade_group_line" in source("view/GroupDetailView.kt"), "group chats show no version line")
        val compose = source("view/ZchatComposeView.kt")
        assertTrue(
            "R.string.upgrade_compose_line" in compose && "UpgradeReminders.showsComposeLine" in compose,
            "New Chat shows no version line",
        )
    }

    // ---- payment line ----

    @Test
    fun both_payment_confirms_carry_the_payment_line() {
        assertDeck(PAYMENT)
        val dialogs = source("view/ChatDialogs.kt")
        val detail = source("view/ChatDetailView.kt")
        for (key in PAYMENT.keys) {
            assertTrue("R.string.$key" in dialogs, "Send Payment does not show $key")
            assertTrue("R.string.$key" in detail, "the pay-request confirm does not show $key")
        }
    }

    // ---- chat-request hint ----

    @Test
    fun an_open_chat_shows_the_request_hint_until_a_free_message_from_the_contact_arrives() {
        assertDeck(REQUEST_HINT)
        val ours = message("nmsg-ours", outgoing = true)
        val theirsOnChain = message("txid-theirs", outgoing = false)
        val theirsFree = message("nmsg-theirs", outgoing = false)
        val theirsFreeLegacy = message("nostr-theirs", outgoing = false)

        assertTrue(UpgradeReminders.showsChatRequestHint(ConversationMode.OPEN, emptyList()))
        assertTrue(UpgradeReminders.showsChatRequestHint(ConversationMode.OPEN, listOf(ours, theirsOnChain)))
        assertFalse(UpgradeReminders.showsChatRequestHint(ConversationMode.OPEN, listOf(ours, theirsFree)))
        assertFalse(UpgradeReminders.showsChatRequestHint(ConversationMode.OPEN, listOf(theirsFreeLegacy)))
        assertFalse(UpgradeReminders.showsChatRequestHint(ConversationMode.VAULT, listOf(ours)))
        assertFalse(UpgradeReminders.showsChatRequestHint(ConversationMode.TUNNEL, listOf(ours)))

        val detail = source("view/ChatDetailView.kt")
        assertTrue("UpgradeReminders.showsChatRequestHint" in detail, "ChatDetailView shows no chat-request hint")
        for (key in REQUEST_HINT.keys) {
            assertTrue("R.string.$key" in detail, "the chat-request hint does not show $key")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun message(
        id: String,
        outgoing: Boolean
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "synthetic",
        timestamp = Instant.EPOCH,
        isOutgoing = outgoing,
        peerAddress = "usynthetic-peer",
    )

    /** Each key's English and Spanish text equals the deck row; `{name}` is `%1$s`. */
    private fun assertDeck(rows: Map<String, Pair<String, String>>) {
        val en = strings("values")
        val es = strings("values-es")
        for ((key, deck) in rows) {
            assertEquals(deck.first.replace("{name}", "%1\$s"), assertNotNull(en[key], "values: $key missing"), "values: $key")
            assertEquals(deck.second.replace("{name}", "%1\$s"), assertNotNull(es[key], "values-es: $key missing"), "values-es: $key")
        }
    }

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun source(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/$path").readText()

    private fun strings(dir: String): Map<String, String> =
        File(repoRoot, "ui-lib/src/main/res/ui/common/$dir")
            .listFiles { f -> f.extension == "xml" }
            .orEmpty()
            .flatMap { f ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(f)
                        .getElementsByTagName("string")
                (0 until nodes.length)
                    .map { nodes.item(it) as Element }
                    .map {
                        it.getAttribute("name") to
                            it.textContent
                                .replace("\\'", "'")
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")
                    }
            }.toMap()

    // The copy deck rows are kept whole, one literal each, so they can be compared with the deck by eye.
    @Suppress("ktlint:standard:max-line-length")
    private companion object {
        // Copy deck "Shielded reminder": SR1, SR2.1, SR2.2, SR-B1, SR-B2, SR-B3, SR-C.
        val REMINDER =
            mapOf(
                "upgrade_shielded_short" to (
                    "Friends on ZChat 2.12.4 or older can't see Shielded messages yet" to
                        "Los amigos con ZChat 2.12.4 o anterior aún no ven los mensajes Shielded"
                ),
                "upgrade_shielded_full" to (
                    "If {name} is still on ZChat 2.12.4 or older, Shielded messages and ZEC you send here won't show for them until they update." to
                        "Si {name} todavía usa ZChat 2.12.4 o anterior, los mensajes Shielded y el ZEC que envíes aquí no le aparecerán hasta que actualice."
                ),
                "upgrade_nothing_lost" to ("Nothing is lost." to "No se pierde nada."),
                "upgrade_ask_them_to_update" to ("Ask them to update" to "Pídele que actualice"),
                "upgrade_chat_free_instead" to ("Chat free instead" to "Chatear gratis"),
                "upgrade_shielded_hide" to ("Hide" to "Ocultar"),
                "upgrade_shielded_chip" to ("May need update" to "Quizá deba actualizar"),
            )

        // Copy deck NK1, NK2, NK3.
        val NO_KEY =
            mapOf(
                "upgrade_no_key_1" to (
                    "Ask {name} for their ZChat invite (see \"Ask them to update\")." to
                        "Pídele a {name} su invitación de ZChat (mira \"Pídele que actualice\")."
                ),
                "upgrade_no_key_2" to (
                    "Paste it in New Chat, open this chat, then tap the mode under the contact's name → Open." to
                        "Pégala en Nuevo chat, abre este chat y toca el modo bajo el nombre del contacto → Open."
                ),
                "upgrade_no_key_3" to (
                    "If the invite shows a different address than this chat, ZChat starts a separate chat; that can happen if you first added {name} from an onboarding invite." to
                        "Si la invitación muestra una dirección distinta a la de este chat, ZChat abre un chat aparte; puede pasar si agregaste a {name} con una invitación de la bienvenida."
                ),
            )

        // Copy deck GL1, NC1.
        val STATIC_LINES =
            mapOf(
                "upgrade_group_line" to (
                    "Members still on ZChat 2.12.4 or older won't see group messages until they update." to
                        "Los miembros que sigan con ZChat 2.12.4 o anterior no verán los mensajes del grupo hasta que actualicen."
                ),
                "upgrade_compose_line" to (
                    "Friends on ZChat 2.12.4 or older won't see Shielded messages until they update." to
                        "Los amigos con ZChat 2.12.4 o anterior no verán los mensajes Shielded hasta que actualicen."
                ),
            )

        // Copy deck PL1, PL2.
        val PAYMENT =
            mapOf(
                "upgrade_payment_line" to (
                    "If {name} uses ZChat 2.12.4 or older, this ZEC won't show in their app until they update." to
                        "Si {name} usa ZChat 2.12.4 o anterior, este ZEC no le aparecerá en la app hasta que actualice."
                ),
                "upgrade_payment_not_lost" to ("It isn't lost." to "No se pierde."),
            )

        // Copy deck RH1, RH2.
        val REQUEST_HINT =
            mapOf(
                "upgrade_request_hint_1" to (
                    "Until {name} accepts your chat request, only one of your free messages reaches them and earlier ones are discarded; they see only its first lines." to
                        "Hasta que {name} acepte tu solicitud de chat, solo le llega uno de tus mensajes gratuitos y los anteriores se descartan; además, solo ve sus primeras líneas."
                ),
                "upgrade_request_hint_2" to (
                    "Send one short message saying who you are, then wait for them to accept." to
                        "Envía un solo mensaje corto diciendo quién eres y espera a que acepte."
                ),
            )

        // Copy deck 2.5, SH2-SH15 (SH1 names the sender's nickname, which ZChat does not have; the share
        // arrives from the sender's own messaging account). Three paragraphs, English block then Spanish.
        val SHARE_EN =
            listOf(
                listOf(
                    "Zcash upgraded its network twice: Ironwood in July 2026 and NU7 in November 2026.",
                    "ZChat 2.12.4 or older can't see my Vault messages or ZEC until you update to ZChat 2.13.",
                    "They are safe and show up after you update, except in chats with disappearing messages on.",
                ),
                listOf(
                    "1) Update: first check your recovery phrase is written down.",
                    "In ZChat, on the chat list tap the gear icon → Check for Updates → Update Now, or go to zsend.xyz/update.",
                    "Please don't uninstall: that deletes your chats, and your ZEC comes back only with your recovery phrase.",
                ),
                listOf(
                    "2) Until then we can chat free in Open mode.",
                    "My ZChat may switch our chat to Open.",
                    "Open messages go through relays, which can't read them but can see who talks to whom and when (older ZChat wrongly says gift-wrapping hides this).",
                    "Open ZChat at least every 3 weeks.",
                    "If my message arrives as a chat request, tap Accept, not Reject (Reject blocks my chat requests).",
                    "If \"Open\" is greyed out in your chat menu, don't pick Tunnel: it can't finish on your version.",
                    "Instead tap the QR icon at the top of the chat list (My Address) → \"Copy ZCHAT chat invite\" and give it to me.",
                    "Showing me the QR in person is safest: an invite contains your Zcash address and chat key, so anyone who sees it, including the app you send it through, can link it to you.",
                ),
            ).joinToString("\n\n") { it.joinToString(" ") }

        val SHARE_ES =
            listOf(
                listOf(
                    "Zcash actualizó su red dos veces: Ironwood en julio de 2026 y NU7 en noviembre de 2026.",
                    "Con ZChat 2.12.4 o anterior no puedes ver mis mensajes Vault ni el ZEC que te envío hasta que actualices a ZChat 2.13.",
                    "Están a salvo y aparecerán cuando actualices, salvo en los chats con mensajes temporales activados.",
                ),
                listOf(
                    "1) Actualiza: primero comprueba que tienes anotada tu frase de recuperación.",
                    "En ZChat, en la lista de chats toca el engranaje → Check for Updates → Update Now, o entra en zsend.xyz/update.",
                    "Por favor, no desinstales: eso borra tus chats, y tu ZEC solo se recupera con tu frase de recuperación.",
                ),
                listOf(
                    "2) Mientras tanto podemos chatear gratis en modo Open.",
                    "Mi ZChat puede cambiar nuestro chat a Open.",
                    "Los mensajes Open pasan por relays, que no pueden leerlos pero sí ver quién habla con quién y cuándo (las versiones antiguas de ZChat dicen, por error, que el gift-wrapping lo oculta).",
                    "Abre ZChat al menos cada 3 semanas.",
                    "Si mi mensaje te llega como solicitud de chat, toca Accept, no Reject (Reject bloquea mis solicitudes de chat).",
                    "Si \"Open\" aparece en gris en el menú del chat, no elijas Tunnel: en tu versión no puede completarse.",
                    "En su lugar, toca el icono QR arriba en la lista de chats (My Address) → \"Copy ZCHAT chat invite\" y pásamela.",
                    "Lo más seguro es mostrarme el QR en persona: una invitación contiene tu dirección de Zcash y tu clave de chat, así que cualquiera que la vea, incluida la app por la que la envíes, puede vincularla contigo.",
                ),
            ).joinToString("\n\n") { it.joinToString(" ") }
    }
}
