package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 D9 (DECISIONS_NIGHT §3, p2-ad7656310802) — the first Shielded message's end-to-end claim.
 *
 * In a Shielded (Vault) chat whose contact has not answered the key exchange there is no ratchet and E2E is off, so
 * doSendMessage and sendReply send the text as it is (`else -> message`): it is inside the Zcash shielded memo, but
 * without ZChat's own end-to-end layer. Meanwhile the chat's ⋮ menu says "End-to-end encrypted / Every message in
 * this chat is encrypted — always on." and its toast "Every message in this chat is end-to-end encrypted."
 *
 * Wanted (the message still sends; the claims become true):
 * - one predicate, ChatViewModel.appLayerE2EPending(peer) = Shielded, no ratchet, E2E off, used by the send branch and
 *   by the menu (through Conversation.appLayerE2EPending and the view's e2eMenuRow);
 * - while pending, the menu shows chat_menu_e2e_pending_title / _subtitle and its toast is the subtitle;
 * - the first message that goes out on the plaintext branch writes one system note
 *   (chat_note_first_message_zcash_only, naming the contact); later ones write none;
 * - with a ratchet the menu keeps its end-to-end text and no note is written;
 * - Tunnel and Open are unchanged;
 * - the three strings exist in English and Spanish with matching placeholders.
 *
 * The new API is reached by name, so this file compiles before it exists. All addresses and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstShieldedMessageClaimTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- 1. a pending Shielded chat says so ----

    @Test
    fun `1 - a Shielded chat whose contact has not answered the key exchange is pending`() =
        runBlocking {
            val (vm, _) = pendingChat(PEER_PENDING)

            assertEquals("appLayerE2EPending for a Shielded chat with no ratchet and E2E off", true, pending(vm, PEER_PENDING))
        }

    @Test
    fun `1 - the menu of a pending chat shows the pending text, never the always-on claim`() {
        val row = menuRow(pending = true)

        assertEquals("chat_menu_e2e_pending_title", row.title)
        assertEquals("chat_menu_e2e_pending_subtitle", row.subtitle)
        assertEquals("the toast uses the subtitle", "chat_menu_e2e_pending_subtitle", row.toast)
        for (name in listOf(row.title, row.subtitle, row.toast)) {
            val text = english(name)
            assertFalse("$name claims every message is end-to-end encrypted: $text", "Every message in this chat is" in text)
            assertFalse("$name claims always on: $text", "always on" in text)
        }
        assertEquals(PENDING_TITLE_EN, english(row.title))
        assertEquals(PENDING_SUBTITLE_EN, english(row.subtitle))
    }

    @Test
    fun `1 - the send branch and the menu use the one predicate`() {
        val vmSrc = ChatResourceText.source(VM_PATH)
        val definitions = Regex("""fun appLayerE2EPending\(""").findAll(vmSrc).count()
        assertEquals("exactly one appLayerE2EPending definition in ChatViewModel", 1, definitions)
        val body = vmSrc.substringAfter("fun appLayerE2EPending(").take(PREDICATE_BODY_CHARS)
        assertTrue("the predicate reads the mode (Shielded only): $body", "ConversationMode.VAULT" in body)
        assertTrue("the predicate reads isE2EEnabled: $body", "isE2EEnabled(" in body)

        // doSendMessage and sendReply: the branch that sends without the ratchet is the predicate's.
        for (fn in listOf("private fun doSendMessage(", "fun sendReply(")) {
            val start = vmSrc.indexOf(fn)
            assertTrue("$fn not found", start >= 0)
            val end = NEXT_MEMBER.find(vmSrc, start + fn.length)?.range?.first ?: vmSrc.length
            val sendBody = vmSrc.substring(start, end)
            assertTrue("$fn does not decide its plaintext branch with appLayerE2EPending", "appLayerE2EPending(" in sendBody)
        }

        // The menu: the conversation carries the predicate, and the view picks its row from it.
        assertTrue(
            "the chat list's conversations are not hydrated with appLayerE2EPending",
            Regex("""appLayerE2EPending\s*=\s*appLayerE2EPending\(""").containsMatchIn(vmSrc),
        )
        val model = ChatResourceText.source(MODEL_PATH)
        assertTrue("Conversation has no appLayerE2EPending field", "val appLayerE2EPending: Boolean" in model)
        val view = ChatResourceText.source(VIEW_PATH)
        assertTrue(
            "the ⋮ menu does not pick its row with e2eMenuRow(conversation.appLayerE2EPending)",
            "e2eMenuRow(conversation.appLayerE2EPending)" in view,
        )
        assertFalse(
            "the ⋮ menu still reads R.string.chat_menu_e2e_title directly",
            "stringResource(R.string.chat_menu_e2e_title)" in view,
        )
    }

    // ---- 2. one note, the first time ----

    @Test
    fun `2 - the first plaintext-branch send writes exactly one note and a second writes none`() =
        runBlocking {
            val (vm, fixture) = pendingChat(PEER_PENDING)

            vm.sendMessage(PEER_PENDING, "hello")
            awaitIdle(vm)
            assertEquals("the message still goes out", 1, fixture.seam.submits.get())
            val notes = notes(fixture)
            assertEquals("one note after the first plaintext send, was $notes", 1, notes.size)
            val noteId = ChatResourceText.stringId("chat_note_first_message_zcash_only")
            assertNotNull("no chat_note_first_message_zcash_only resource", noteId)
            assertEquals("note#$noteId($CONTACT_NAME)", notes.single().text)
            assertFalse("a note is not an outgoing message", notes.single().isOutgoing)

            vm.sendMessage(PEER_PENDING, "hello again")
            awaitIdle(vm)
            assertEquals("the second message goes out too", 2, fixture.seam.submits.get())
            assertEquals("a second plaintext send writes no note", notes, notes(fixture))
        }

    // ---- 3. with a ratchet: the end-to-end text, no note ----

    @Test
    fun `3 - once the ratchet exists the menu shows the end-to-end text and no note appears`() =
        runBlocking {
            val fixture = PaidChatFixture(PEER_RATCHET, ConversationMode.VAULT)
            val vm = fixture.viewModel()
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)

            assertEquals(false, pending(vm, PEER_RATCHET))
            val row = menuRow(pending = false)
            assertEquals("chat_menu_e2e_title", row.title)
            assertEquals("chat_menu_e2e_subtitle", row.subtitle)
            assertEquals("chat_menu_e2e_toast", row.toast)

            vm.sendMessage(PEER_RATCHET, "hello")
            awaitIdle(vm)
            assertEquals(1, fixture.seam.submits.get())
            assertEquals("an encrypted send writes no note", emptyList<ZchatPreferences.PendingMessageData>(), notes(fixture))
        }

    // ---- 4. Tunnel and Open unchanged ----

    @Test
    fun `4 - Tunnel and Open chats are never pending, whatever their keys`() =
        runBlocking {
            for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.OPEN)) {
                val (vm, _) = pendingChat(PEER_PENDING, mode)
                assertEquals("$mode must keep its menu text", false, pending(vm, PEER_PENDING))
            }
        }

    // ---- 5. English and Spanish ----

    @Test
    fun `5 - the three strings exist in English and Spanish with matching placeholders`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val wanted =
            mapOf(
                "chat_menu_e2e_pending_title" to (PENDING_TITLE_EN to PENDING_TITLE_ES),
                "chat_menu_e2e_pending_subtitle" to (PENDING_SUBTITLE_EN to PENDING_SUBTITLE_ES),
                "chat_note_first_message_zcash_only" to (NOTE_EN to NOTE_ES),
            )
        for ((name, texts) in wanted) {
            assertEquals("English $name", texts.first, en[name])
            assertEquals("Spanish $name", texts.second, es[name])
            assertEquals(
                "placeholders of $name",
                PLACEHOLDER.findAll(texts.first).map { it.value }.toList(),
                PLACEHOLDER.findAll(es[name].orEmpty()).map { it.value }.toList(),
            )
        }
    }

    // ---- fixtures and helpers ----

    /**
     * A chat in [mode] with a contact who has not answered the key exchange: our key exists (our KEX went out), the
     * contact's does not, E2E is off, no ratchet is cached.
     */
    private suspend fun pendingChat(
        peer: String,
        mode: ConversationMode = ConversationMode.VAULT,
    ): Pair<ChatViewModel, PaidChatFixture> {
        val fixture = PaidChatFixture(peer, mode)
        val vm = fixture.viewModel()
        AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
        AutoSpendVm.setField(vm, "zchatPreferences", noAnswerYet(fixture.proxy))
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        return vm to fixture
    }

    private fun noAnswerYet(base: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getE2EPeerPublicKey" -> null
                "isE2EEnabled" -> false
                "getDisplayName" -> CONTACT_NAME
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private fun notes(fixture: PaidChatFixture): List<ZchatPreferences.PendingMessageData> =
        fixture.proxy
            .getPendingMessages()
            .filter { SysNotes.isSystemNoteId(it.id) }
            .sortedBy { it.id }

    private fun pending(
        vm: ChatViewModel,
        peer: String
    ): Boolean {
        val method =
            ChatViewModel::class.java.methods.firstOrNull {
                it.name == "appLayerE2EPending" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        assertNotNull("ChatViewModel has no appLayerE2EPending(peerAddress: String)", method)
        return method!!.invoke(vm, peer) as Boolean
    }

    private data class MenuRow(
        val title: String,
        val subtitle: String,
        val toast: String
    )

    /** The view's e2eMenuRow(pending), as resource names. */
    private fun menuRow(pending: Boolean): MenuRow {
        val method =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.view.PrivacyCopyKt")
                .methods
                .firstOrNull { it.name.startsWith("e2eMenuRow") && it.parameterCount == 1 }
        assertNotNull("PrivacyCopy.kt has no e2eMenuRow(appLayerE2EPending: Boolean)", method)
        val row = method!!.invoke(null, pending)

        fun res(getter: String): String {
            val id = row.javaClass.getMethod(getter).invoke(row) as Int
            return ChatResourceText.stringName(id) ?: throw AssertionError("no string resource with id $id")
        }
        return MenuRow(res("getTitle"), res("getSubtitle"), res("getToast"))
    }

    private fun english(name: String): String =
        ChatResourceText.strings("values")[name] ?: throw AssertionError("no English $name")

    private companion object {
        const val PEER_PENDING = "usyntheticd9pendingpeer000001"
        const val PEER_RATCHET = "usyntheticd9ratchetpeer000001"
        const val CONTACT_NAME = "Synthetic Contact"

        const val VM_PATH = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val MODEL_PATH = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ChatMessage.kt"
        const val VIEW_PATH = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val PREDICATE_BODY_CHARS = 1_200

        /** The next member of ChatViewModel (a function at class indentation), where a send function's body ends. */
        val NEXT_MEMBER = Regex("""\n    (?:private |internal |public |override )*(?:suspend )?fun """)

        /** A positional string placeholder such as %1${'$'}s. */
        val PLACEHOLDER = Regex("%\\d\\${'$'}s")

        const val PENDING_TITLE_EN = "Shielded by Zcash. Extra encryption pending"
        const val PENDING_TITLE_ES = "Protegido por Zcash. Cifrado extra pendiente"
        const val PENDING_SUBTITLE_EN =
            "Until your contact answers the key exchange, messages here are protected only by Zcash's shielded " +
                "encryption. ZChat's own end-to-end layer starts after that."
        const val PENDING_SUBTITLE_ES =
            "Hasta que tu contacto responda al intercambio de claves, los mensajes aquí están protegidos solo por el " +
                "cifrado blindado de Zcash. La capa de cifrado de extremo a extremo de ZChat empieza después."
        const val NOTE_EN =
            "🔐 This message is protected by Zcash's shielded encryption. ZChat adds its own end-to-end encryption " +
                "once %1\$s answers the key exchange."
        const val NOTE_ES =
            "🔐 Este mensaje está protegido por el cifrado blindado de Zcash. ZChat añade su propio cifrado de " +
                "extremo a extremo cuando %1\$s responda al intercambio de claves."
    }
}
