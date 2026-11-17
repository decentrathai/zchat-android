package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentIds
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SR-C2 (fix-all 2.13.0, train T4c; photos-privacy spec §5 with the review's SR-C2 changes, option (a)): a photo, file
 * or voice note in a chat that has no end-to-end key.
 *
 * At HEAD every attachment is locked with a file key wrapped by the chat's E2E shared key. An Open chat never makes a
 * key exchange (the relay key is enough for text), and a new Shielded contact has none before the first message. There
 * the photo flow puts up a "preparing" bubble, compresses and encrypts the picture, and only then finds no shared key
 * and throws "No E2E shared secret … (need KEX first)". The bubble turns FAILED with "Couldn't send the photo. Please
 * try again." — trying again can never work — and its Retry does nothing at all, because no retry copy was kept.
 *
 * Wanted:
 * - C2-1, C2-2: with no shared key the photo, file and voice-note handlers refuse at their entry, before the upload
 *   slot and before any compression: no row is left, nothing is uploaded, published or paid, and the chat shows the
 *   key-exchange notice (SendMessageState.AttachmentNeedsKeyExchange) — for an Open chat the variant that offers
 *   "Switch to Shielded".
 * - C2-3: Retry on a FAILED attachment row that has no retry copy is never silent: with no shared key it shows the same
 *   notice; with a key it says the attachment must be picked again (ChatSendCopy.ATTACHMENT_RETRY_COPY_GONE).
 * - C2-4: the texts are resources in English and Spanish with no "try again"; the thrown "need KEX first" maps to
 *   ChatSendCopy.ATTACHMENT_NEEDS_KEY_EXCHANGE; the chat screen checks the gate before it opens a picker or the
 *   recorder.
 * - C2-5 (control, passes before and after): an Open chat that has a key exchange sends a photo over the relays, free.
 * - C2-6: a Shielded chat with a new contact shows the Shielded variant (send a text message first), not the Open one.
 * - C2-7: a restored wallet still reading its history says so (ChatSendCopy.ATTACHMENT_STILL_READING_HISTORY), not
 *   "no key exchange".
 * - C2-8: our key went out but the contact's answer has not arrived: the "not finished yet" variant, with no switch.
 *
 * The new API is reached by name so this file compiles before it exists. The upload servers are answered offline by an
 * OkHttp interceptor and the relays by a recording publisher. All addresses, keys and bytes are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsNeedAKeyExchangeTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()
    private val publishes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var savedOwnHistoryRead = false

    /** Watches the rows on screen, outside the view model's scope (the harness waits for that scope to go idle). */
    private val watcher = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private companion object {
        const val ACCEPTING_HOST = "nostr.download"
        const val NOTICE_STATE = "AttachmentNeedsKeyExchange"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { text, _ ->
            publishes += text
            NostrChatBridge.PublishResult(1, "synthetic-src2-${publishes.size}")
        }
    }

    @After
    fun tearDown() {
        watcher.cancel()
        NostrChatBridge.unregisterPublisher()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ------------------------------------------------------------------ C2-1, C2-2

    @Test
    fun `C2-1 a photo in an Open chat with no key exchange leaves no row and shows the key-exchange notice`() =
        runBlocking { refusedAtEntry(Kind.PHOTO) }

    @Test
    fun `C2-2 a file in an Open chat with no key exchange leaves no row and shows the key-exchange notice`() =
        runBlocking { refusedAtEntry(Kind.FILE) }

    @Test
    fun `C2-2 a voice note in an Open chat with no key exchange leaves no row and shows the key-exchange notice`() =
        runBlocking { refusedAtEntry(Kind.VOICE) }

    @Test
    fun `C2-1 the refusal comes before the upload slot and before the picture is prepared`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keys = Keys.NONE)
            val vm = h.viewModel()
            var prepared = 0

            h.pickPhoto(vm) {
                prepared++
                h.plaintext("synthetic photo")
            }
            // The tracker is read right after the call returns: a claimed slot shows as a progress value.
            val claimed = h.tracker(vm).progress.value
            h.awaitIdle(vm)

            assertEquals(null, claimed, "the upload slot was claimed for a photo that cannot be sent")
            assertEquals(0, prepared, "the picture was compressed for a chat that has no key to lock it with")
            assertTrue(
                h.everShown.none { it.startsWith(AttachmentIds.PHOTO) },
                "a 'preparing' bubble flashed: ${h.everShown}",
            )
        }

    // ------------------------------------------------------------------ C2-3

    @Test
    fun `C2-3 Retry on a failed photo with no retry copy in a chat with no key shows the notice, never silence`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keys = Keys.NONE)
            val vm = h.viewModel()
            val id = h.putRehydratedFailedPhoto(vm)

            val retried = vm.retryMessage(h.peer, id)
            h.awaitIdle(vm)

            assertFalse(retried, "Retry claimed to start a photo that cannot be sent")
            h.assertNotice(vm, "NEEDS_KEY_EXCHANGE")
            h.assertNothingWentOut()
            val row = h.outgoingRows(vm).singleOrNull { it.id == id }
            assertNotNull(row, "Retry dropped the user's failed row")
            assertEquals(MessageStatus.FAILED, row.status)
        }

    @Test
    fun `C2-3 Retry on a failed photo with no retry copy in a chat with a key says to pick it again`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keys = Keys.BOTH)
            val vm = h.viewModel()
            val id = h.putRehydratedFailedPhoto(vm)

            val retried = vm.retryMessage(h.peer, id)
            h.awaitIdle(vm)

            assertFalse(retried, "Retry claimed to start a photo it has no copy of")
            val state = HandshakeHarness.sendMessageState(vm)
            val gone = copyConstant("ATTACHMENT_RETRY_COPY_GONE")
            assertEquals(SendMessageState.Error(gone), state, "Retry with no retry copy was silent or said something else")
            h.assertNothingWentOut()
        }

    // ------------------------------------------------------------------ C2-4

    @Test
    fun `C2-4 the notices are resources in English and Spanish and never say try again`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val keys =
            listOf(
                "chat_attachment_needs_kex_title",
                "chat_attachment_needs_kex_body",
                "chat_attachment_needs_kex_shielded_body",
                "chat_attachment_needs_kex_unanswered_body",
                "chat_attachment_needs_kex_switch_shielded",
                "chat_attachment_needs_kex_not_now",
                "chat_attachment_needs_kex_row",
                "chat_attachment_still_reading_history",
                "chat_attachment_retry_copy_gone",
            )
        val tryAgain = listOf("try again", "retry", "otra vez", "inténtalo", "reintenta", "de nuevo")
        for (key in keys) {
            val english = en[key] ?: fail("no English string $key")
            val spanish = es[key] ?: fail("no Spanish string $key")
            assertTrue(english != spanish, "$key is not translated")
            for (text in listOf(english, spanish)) {
                for (phrase in tryAgain) {
                    assertFalse(text.contains(phrase, ignoreCase = true), "$key promises '$phrase': $text")
                }
            }
        }
        // The Shielded and "not answered yet" variants must not send the user to a switch that cannot help.
        for (key in listOf("chat_attachment_needs_kex_shielded_body", "chat_attachment_needs_kex_unanswered_body")) {
            for (text in listOf(en.getValue(key), es.getValue(key))) {
                assertFalse(text.contains("Open"), "$key talks about Open chats: $text")
                assertFalse(text.contains("Switch", ignoreCase = true) || text.contains("Cambia", ignoreCase = true), "$key: $text")
            }
        }
        // ChatSendCopy constants are shown through their resource, and the English resource is the same text.
        for ((constant, key) in listOf(
            "ATTACHMENT_NEEDS_KEY_EXCHANGE" to "chat_attachment_needs_kex_row",
            "ATTACHMENT_STILL_READING_HISTORY" to "chat_attachment_still_reading_history",
            "ATTACHMENT_RETRY_COPY_GONE" to "chat_attachment_retry_copy_gone",
        )) {
            val text = copyConstant(constant)
            assertEquals(en[key], text, "ChatSendCopy.$constant differs from its English resource")
            assertEquals(ChatResourceText.stringId(key), ChatSendCopy.localizedResource(text), "ChatSendCopy.$constant has no resource")
        }
    }

    @Test
    fun `C2-4 the view model's need-KEX refusal is a typed notice, not a generic failure`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt")
        assertFalse(
            source.contains("refusing to send file unencrypted (need KEX first)\"\n"),
            "the photo, file, voice and Retry paths still throw a plain error() for a missing key, which is shown as " +
                "'Couldn't send … Please try again.'",
        )
        assertTrue(source.contains("fun attachmentBlockReason("), "ChatViewModel has no attachmentBlockReason")
        assertTrue(source.contains("ChatSendCopy.ATTACHMENT_NEEDS_KEY_EXCHANGE"), "the need-KEX refusal is not mapped to its text")
    }

    @Test
    fun `C2-4 the chat screen checks the gate before it opens a picker, the camera or the recorder`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        for (callback in listOf("onSendImage", "onTakePhoto", "onSendFile", "onSendViewOnceImage", "onMicTap", "onMicLongPress")) {
            val start = source.indexOf("        $callback = {")
            assertTrue(start >= 0, "AndroidChat has no $callback callback")
            val head = source.substring(start, minOf(source.length, start + 260))
            assertTrue(
                head.contains("refuseAttachmentIfBlocked"),
                "$callback opens its picker or recorder without checking for a key exchange first:\n$head",
            )
        }
        assertTrue(source.contains("AttachmentNeedsKeyExchange"), "AndroidChat never shows the key-exchange notice")
    }

    // ------------------------------------------------------------------ C2-5 (control)

    @Test
    fun `C2-5 control - an Open chat with a key exchange sends a photo over the relays for free`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keys = Keys.BOTH)
            h.servers.online = true
            val vm = h.viewModel()

            h.pickPhoto(vm) { h.plaintext("synthetic photo") }
            h.awaitIdle(vm)

            assertTrue(h.servers.puts().isNotEmpty(), "the photo was not uploaded")
            assertTrue(
                h.sent().isNotEmpty(),
                "the photo's link did not go over the relays: rows ${h.outgoingRows(vm)}, state ${HandshakeHarness.sendMessageState(vm)}",
            )
            assertEquals(0, h.proposals(), "a photo in an Open chat paid something")
            assertTrue(h.outgoingRows(vm).none { it.status == MessageStatus.FAILED }, "a FAILED row is left: ${h.outgoingRows(vm)}")
        }

    // ------------------------------------------------------------------ C2-6, C2-7, C2-8

    @Test
    fun `C2-6 a Shielded chat with a new contact shows the Shielded variant with no switch`() =
        runBlocking {
            val h = Harness(ConversationMode.VAULT, keys = Keys.NONE)
            val vm = h.viewModel()

            h.pickPhoto(vm) { h.plaintext("synthetic photo") }
            h.awaitIdle(vm)

            h.assertNotice(vm, "NEEDS_KEY_EXCHANGE_SHIELDED")
            assertEquals(emptyList(), h.outgoingRows(vm), "a row was left for a photo that cannot be sent")
            h.assertNothingWentOut()
        }

    @Test
    fun `C2-7 a restored wallet still reading its history says so, not no key exchange`() =
        runBlocking {
            SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(false)
            val h = Harness(ConversationMode.OPEN, keys = Keys.NONE)
            val vm = h.viewModel()

            h.pickPhoto(vm) { h.plaintext("synthetic photo") }
            h.awaitIdle(vm)

            val reading = copyConstant("ATTACHMENT_STILL_READING_HISTORY")
            assertEquals(SendMessageState.Error(reading), HandshakeHarness.sendMessageState(vm))
            assertEquals(emptyList(), h.outgoingRows(vm), "a row was left for a photo that cannot be sent")
            h.assertNothingWentOut()
        }

    @Test
    fun `C2-8 our key went out but the contact has not answered - the not-finished variant`() =
        runBlocking {
            val h = Harness(ConversationMode.VAULT, keys = Keys.OURS_ONLY)
            val vm = h.viewModel()

            h.pickPhoto(vm) { h.plaintext("synthetic photo") }
            h.awaitIdle(vm)

            h.assertNotice(vm, "KEY_EXCHANGE_UNANSWERED")
            assertEquals(emptyList(), h.outgoingRows(vm))
            h.assertNothingWentOut()
        }

    // ------------------------------------------------------------------ shared

    private suspend fun refusedAtEntry(kind: Kind) {
        val h = Harness(ConversationMode.OPEN, keys = Keys.NONE)
        h.servers.online = true
        val vm = h.viewModel()

        when (kind) {
            Kind.PHOTO -> h.pickPhoto(vm) { h.plaintext("synthetic photo") }
            Kind.FILE -> h.pickFile(vm)
            Kind.VOICE -> h.recordVoice(vm)
        }
        h.awaitIdle(vm)

        h.assertNotice(vm, "NEEDS_KEY_EXCHANGE")
        assertEquals(emptyList(), h.outgoingRows(vm), "a row was left for a ${kind.name.lowercase()} that cannot be sent")
        h.assertNothingWentOut()
    }

    private enum class Kind { PHOTO, FILE, VOICE }

    private enum class Keys { NONE, OURS_ONLY, BOTH }

    private fun copyConstant(name: String): String =
        try {
            ChatSendCopy::class.java.getField(name).get(null) as String
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no $name", e)
        }

    private inner class Harness(
        mode: ConversationMode,
        keys: Keys,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(5_231 + mode.ordinal * 10 + keys.ordinal)
        private val base = PaidChatFixture(peer, mode, haveOurKey = keys != Keys.NONE)
        private val peerRelayKey = relayKey(5_231 + mode.ordinal)
        private val oursOnly = keys == Keys.OURS_ONLY
        val servers = FakeServers()
        val cache: File = Files.createTempDirectory("sr-c2-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }

        /** Every outgoing attachment id that was ever on screen (a bubble that flashes and goes is still caught). */
        val everShown: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        /** The fixture's store, with the contact's relay key known (and, for [Keys.OURS_ONLY], no answer to our KEX). */
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when {
                    method.name == "getPeerNostrPubkey" && args?.get(0) == peer -> peerRelayKey
                    oursOnly && method.name == "getE2EPeerPublicKey" -> null
                    oursOnly && method.name == "isE2EEnabled" -> false
                    oursOnly && method.name == "isE2EKeyExchangeComplete" -> false
                    else ->
                        try {
                            method.invoke(base.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        suspend fun viewModel(): ChatViewModel {
            val vm = HandshakeHarness.chatViewModelWith(proxy, base.seam).also(AutoSpendVm::wireBootstrapFields)
            val pending = MutableStateFlow(emptyList<ChatMessage>())
            AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", pending)
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            AutoSpendVm.setField(vm, "fileHttpClientProvider", servers.provider)
            AutoSpendVm.setField(vm, "uploadProgressTracker", UploadProgressTracker())
            AutoSpendVm.setField(vm, "noteCopy", EnglishNoteCopy)
            AutoSpendVm.setField(vm, "hiddenMessages", MutableStateFlow(emptySet<String>()))
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            // The relay route strips a reply reference from what it renders (untagReply).
            AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
            AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
            AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
            watcher.launch {
                pending.collect { rows -> rows.filter { it.isOutgoing }.forEach { everShown += it.id } }
            }
            return vm
        }

        fun proposals(): Int = base.seam.proposalRequests.get()

        /** What went over the relays, apart from a profile photo (SR-B1's delivery is not this batch's concern). */
        fun sent(): List<String> = synchronized(publishes) { publishes.filterNot { it.startsWith("ZPROF") } }

        fun assertNothingWentOut() {
            assertEquals(emptyList(), servers.puts().map { it.host }, "something was uploaded")
            assertEquals(emptyList(), sent(), "something went over the relays")
            assertEquals(0, proposals(), "something was paid or priced on-chain")
        }

        fun assertNotice(
            vm: ChatViewModel,
            reason: String,
        ) {
            val state = HandshakeHarness.sendMessageState(vm)
            assertEquals(NOTICE_STATE, state.javaClass.simpleName, "the chat did not show the key-exchange notice, state $state")
            val shown =
                state.javaClass
                    .getMethod("getReason")
                    .invoke(state)
                    ?.toString()
            assertEquals(reason, shown, "the notice shows the wrong variant")
        }

        fun tracker(vm: ChatViewModel): UploadProgressTracker =
            ChatViewModel::class.java
                .getDeclaredField("uploadProgressTracker")
                .apply { isAccessible = true }
                .get(vm) as UploadProgressTracker

        @Suppress("UNCHECKED_CAST")
        fun pending(vm: ChatViewModel): MutableStateFlow<List<ChatMessage>> =
            ChatViewModel::class.java
                .getDeclaredField("pendingMessages")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<List<ChatMessage>>

        fun outgoingRows(vm: ChatViewModel): List<ChatMessage> = pending(vm).value.filter { it.isOutgoing && it.peerAddress == peer }

        fun plaintext(marker: String): ByteArray = ("%PDF-1.4 synthetic $marker ".repeat(200)).toByteArray()

        /** A FAILED photo row as a restart brings it back: no ZFILE, no retry copy kept. */
        fun putRehydratedFailedPhoto(vm: ChatViewModel): String {
            val id = "${AttachmentIds.PHOTO}424242"
            pending(vm).value =
                listOf(
                    ChatMessage(
                        id = id,
                        txId = null,
                        text = "📷 Photo (failed)",
                        timestamp = Instant.now().minusSeconds(600),
                        isOutgoing = true,
                        peerAddress = peer,
                        isPending = false,
                        status = MessageStatus.FAILED,
                    ),
                )
            return id
        }

        fun pickPhoto(
            vm: ChatViewModel,
            prepare: () -> ByteArray?,
        ) = invoke(
            vm,
            "handlePickedImage",
            arrayOf(String::class.java, Context::class.java, java.lang.Boolean.TYPE, Function0::class.java),
            arrayOf(peer, context, false, prepare),
        )

        fun pickFile(vm: ChatViewModel) {
            val bytes = plaintext("synthetic file")
            val open: () -> InputStream? = { bytes.inputStream() }
            invoke(
                vm,
                "handlePickedFile",
                arrayOf(String::class.java, Context::class.java, String::class.java, java.lang.Long.TYPE, Function0::class.java),
                arrayOf(peer, context, "application/pdf", bytes.size.toLong(), open),
            )
        }

        fun recordVoice(vm: ChatViewModel) {
            val recording = File(cache, "synthetic-recording.m4a").apply { writeBytes(plaintext("synthetic voice")) }
            vm.handleRecordedAudio(peer, recording, 3_000L, context)
        }

        private fun invoke(
            vm: ChatViewModel,
            name: String,
            types: Array<Class<*>>,
            args: Array<Any>,
        ) {
            val method = ChatViewModel::class.java.getDeclaredMethod(name, *types)
            method.isAccessible = true
            try {
                method.invoke(vm, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        /** Runs the view model's coroutines to the end, skipping their delays (the test's Main has virtual time). */
        suspend fun awaitIdle(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                main.scheduler.advanceUntilIdle()
                if (vm.viewModelScope.coroutineContext.job.children
                        .none { it.isActive }
                ) {
                    break
                }
                if (System.currentTimeMillis() > deadline) fail("the view model did not settle")
                Thread.sleep(5)
            }
            HandshakeHarness.awaitIdle(vm)
        }
    }

    /** English resources, as the view model's getString would return them on an English phone. */
    private object EnglishNoteCopy : ChatNoteCopy {
        override fun get(
            id: Int,
            vararg args: Any,
        ): String {
            val text = ChatResourceText.english(id)
            return if (args.isEmpty()) text else String.format(text, *args)
        }
    }

    /** Offline upload servers: every host unreachable until [online], then nostr.download stores the blob. */
    private class FakeServers {
        data class Put(
            val host: String,
            val sha256: String,
        )

        @Volatile var online = false
        private val puts: MutableList<Put> = Collections.synchronizedList(mutableListOf())

        fun puts(): List<Put> = synchronized(puts) { puts.toList() }

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                if (request.method == "PUT") puts += Put(host, sha)
                if (!online || host != ACCEPTING_HOST || request.method != "PUT") throw java.net.UnknownHostException(host)
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"url":"https://$host/$sha","sha256":"$sha","size":${body.size},"type":"application/octet-stream"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ).build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }
}
