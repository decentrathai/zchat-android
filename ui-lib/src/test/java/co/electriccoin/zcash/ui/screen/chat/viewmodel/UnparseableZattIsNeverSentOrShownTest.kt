package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.B1cPlanned
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZATTMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import co.electriccoin.zcash.ui.service.relayMessagePreview
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1502 (OT213n B1d; FIX_PLAN REVISION 6; Grok's review of B1c, review-B1c/grok.json): a ZATT line that
 * [ZATTMessage.parse] rejects is never published and never shown raw (it holds the file key).
 *
 * At e626e879d the sender checks only the key length ([ZATTMessage.build]); parse also caps the size at
 * [ZATTMessage.MAX_SIZE_BYTES]. A file picked at the 25 MiB pick cap encrypts to 25 MiB + 28 bytes (nonce and tag), so
 * sendZattAttachment publishes a ZATT its own parser rejects: the peer drops it, and the sender's bubble and stored row
 * get the raw line (publishNostrAndRenderLocal finds no file card), file key included. A stored relay send whose line
 * does not parse restores as that raw text too (storedRelaySendRow).
 *
 * Wanted:
 * - a 25 MiB file to a bound zatt1 contact in an Open chat: no ZATT goes out, no bubble or stored row holds a ZATT line,
 *   the user is told (a FAILED bubble or an error); nothing paid;
 * - a stored settled relay send holding a ZATT line that does not parse restores with a label, never the line.
 * Guards (pass before and after, review claims not reproduced in the code):
 * - an upload host answering a url off the media allow-list is skipped by FileUploadManager.upload itself (it checks
 *   ZFILEMessage.isAllowedMediaUrl before the caller's acceptUrl): no ZATT carries such a url;
 * - the relay notification of a ZATT that does not parse never shows the line (relayMessagePreview is null, and the
 *   notifier then posts nothing).
 * Real ChatViewModel file handler over [PaidChatFixture]; upload servers answered offline by an OkHttp interceptor; relays
 * by a recording publisher. Every address, key and byte is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnparseableZattIsNeverSentOrShownTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()
    private val publishes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var savedOwnHistoryRead = false

    private companion object {
        const val ACCEPTING_HOST = "nostr.download"
        const val OFF_LIST_HOST = "files.synthetic.invalid"
        const val MARKER = "synthetic-b1d-plaintext-marker"
        const val PICK_CAP_BYTES = 25 * 1024 * 1024
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
        NostrChatBridge.registerPublisher { text, to ->
            publishes += text to to
            NostrChatBridge.PublishResult(1, "synthetic-b1d-${publishes.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ------------------------------------------------------------------ RED at e626e879d

    @Test
    fun `a file at the pick cap whose ZATT would not parse is refused before anything is published`() =
        runBlocking {
            val h = Harness(answerHost = ACCEPTING_HOST)
            val vm = h.viewModel()
            val bytes = ByteArray(PICK_CAP_BYTES).also { "%PDF-1.4 $MARKER".toByteArray().copyInto(it) }

            h.sendFile(vm, bytes)
            h.awaitIdle(vm)

            val zatts = h.sent().filter { it.first.contains("ZATT|") }
            val sizes = zatts.map { it.first.split('|').getOrNull(4) }
            assertEquals(0, zatts.size, "a ZATT its own parser rejects went out (size $sizes, cap ${ZATTMessage.MAX_SIZE_BYTES})")
            val bubbles = h.outgoingRows(vm)
            assertTrue(bubbles.none { it.text.contains("ZATT|") }, "the sender's bubble shows the raw ZATT line (file key inside)")
            assertTrue(h.storedTexts().none { it.contains("ZATT|") }, "a stored row's text is the raw ZATT line (file key inside)")
            val told =
                h.outgoingRows(vm).any { it.status == MessageStatus.FAILED } ||
                    HandshakeHarness.sendMessageState(vm) is SendMessageState.Error
            assertTrue(told, "the refused file is neither a FAILED bubble nor an error: ${HandshakeHarness.sendMessageState(vm)}")
            assertEquals(0, h.proposals(), "the free path made a proposal (money-path: nothing may be paid)")
        }

    @Test
    fun `a stored relay send holding a ZATT line that does not parse restores with a label, never the line`() =
        runBlocking {
            val h = Harness(answerHost = ACCEPTING_HOST)
            val vm = h.viewModel()
            val line = B1cPlanned.wire(size = ZATTMessage.MAX_SIZE_BYTES + 28)
            assertNull(ZATTMessage.parse(line), "setup: the stored line must be one parse rejects")
            // As publishNostrAndRenderLocal stores it at e626e879d (no file card: the line is the row's text), and as a row
            // that kept the line as its file content.
            val asText = ZchatPreferences.PendingMessageData(id = "nmsg-b1d-1", text = line, timestampMillis = 1L, peerAddress = h.peer)
            val asFile = asText.copy(id = "nmsg-b1d-2", fileZfileContent = line)

            for (data in listOf(asText, asFile)) {
                val row = h.storedRelaySendRow(vm, data)
                assertFalse(row.text.contains("ZATT|"), "a stored ZATT line that does not parse restores as raw text (${data.id})")
                assertFalse(row.text.contains(B1cPlanned.FILE_KEY_B64), "a restored row shows the file key (${data.id})")
                assertTrue(row.text.isNotBlank(), "a restored ZATT row has no label (${data.id})")
            }
        }

    // ------------------------------------------------------------------ guards (pass before and after)

    @Test
    fun `guard - an upload url off the media allow-list never goes into a ZATT`() =
        runBlocking {
            val h = Harness(answerHost = OFF_LIST_HOST)
            val vm = h.viewModel()

            h.sendFile(vm, ("%PDF-1.4 $MARKER ".repeat(200)).toByteArray())
            h.awaitIdle(vm)

            assertTrue(h.sent().none { it.first.contains(OFF_LIST_HOST) }, "a publish carries the off-allow-list url")
            assertEquals(0, h.sent().count { it.first.contains("ZATT|") }, "a ZATT went out with no allowed upload")
            assertEquals(0, h.proposals(), "something was paid")
        }

    @Test
    fun `guard - the relay notification of a ZATT that does not parse never shows the line`() {
        val lines =
            listOf(
                B1cPlanned.wire(size = ZATTMessage.MAX_SIZE_BYTES + 28),
                B1cPlanned.wire(url = "https://$OFF_LIST_HOST/blob"),
                B1cPlanned.wire(keyB64 = "AAAA"),
                "ZATT|v2|future",
            )
        for (line in lines) {
            assertNull(ZATTMessage.parse(line), "setup: parse must reject the line")
            val preview = relayMessagePreview(line)
            assertFalse(preview?.contains("ZATT|") == true, "the notification shows a raw ZATT line")
            assertFalse(preview?.contains(B1cPlanned.FILE_KEY_B64) == true, "the notification shows the file key")
        }
    }

    // ------------------------------------------------------------------ harness

    private object EnglishNoteCopy : ChatNoteCopy {
        override fun get(
            id: Int,
            vararg args: Any,
        ): String {
            val text = ChatResourceText.english(id)
            return if (args.isEmpty()) text else String.format(text, *args)
        }
    }

    private inner class Harness(
        answerHost: String,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(15_020)
        val boundKey: String = relayKey(15_021)
        private val base = PaidChatFixture(peer, ConversationMode.OPEN, haveOurKey = false)
        private val servers = FakeServers(answerHost)
        private val stored: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cache: File = Files.createTempDirectory("b1d-zatt-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                val forPeer = args?.getOrNull(0) == peer
                args?.filterIsInstance<ZchatPreferences.PendingMessageData>()?.forEach { stored += it.text }
                when {
                    method.name == "getPeerNostrPubkey" && forPeer -> boundKey
                    method.name == "findPeerByNostrPubkey" && args?.getOrNull(0) == boundKey -> peer
                    method.name in setOf("getPeerIronwoodCap", "getPeerZattCap") && forPeer ->
                        ZchatPreferences.PeerIronwoodCap(capIronwood = true, atSec = 1_790_000_000L)
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
            AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            AutoSpendVm.setField(vm, "fileHttpClientProvider", servers.provider)
            AutoSpendVm.setField(vm, "uploadProgressTracker", UploadProgressTracker())
            AutoSpendVm.setField(vm, "noteCopy", EnglishNoteCopy)
            AutoSpendVm.setField(vm, "hiddenMessages", MutableStateFlow(emptySet<String>()))
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
            AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
            AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
            return vm
        }

        fun proposals(): Int = base.seam.proposalRequests.get()

        /** What went over the relays as (text, recipient key), apart from a profile photo. */
        fun sent(): List<Pair<String, String>> = synchronized(publishes) { publishes.filterNot { it.first.startsWith("ZPROF") } }

        /** The text of every row handed to the store. */
        fun storedTexts(): List<String> = synchronized(stored) { stored.toList() }

        @Suppress("UNCHECKED_CAST")
        fun outgoingRows(vm: ChatViewModel): List<ChatMessage> =
            (
                ChatViewModel::class.java
                    .getDeclaredField("pendingMessages")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<List<ChatMessage>>
            ).value.filter { it.isOutgoing && it.peerAddress == peer }

        fun sendFile(
            vm: ChatViewModel,
            bytes: ByteArray,
        ) {
            val open: () -> InputStream? = { bytes.inputStream() }
            invoke(
                vm,
                "handlePickedFile",
                arrayOf(String::class.java, Context::class.java, String::class.java, java.lang.Long.TYPE, Function0::class.java),
                arrayOf(peer, context, "application/pdf", bytes.size.toLong(), open),
            )
        }

        fun storedRelaySendRow(
            vm: ChatViewModel,
            data: ZchatPreferences.PendingMessageData,
        ): ChatMessage =
            invoke(vm, "storedRelaySendRow", arrayOf(ZchatPreferences.PendingMessageData::class.java), arrayOf(data)) as ChatMessage

        private fun invoke(
            vm: ChatViewModel,
            name: String,
            types: Array<Class<*>>,
            args: Array<Any>,
        ): Any? {
            val method = ChatViewModel::class.java.getDeclaredMethod(name, *types)
            method.isAccessible = true
            return try {
                method.invoke(vm, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        suspend fun awaitIdle(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 60_000
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

    /**
     * Offline upload servers: only nostr.download takes a PUT; its answer names the blob on [answerHost] (nostr.download
     * itself, or a host off the media allow-list). Only that host's body is read (a 25 MiB body is copied once).
     */
    private class FakeServers(
        private val answerHost: String,
    ) {
        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                if (host != ACCEPTING_HOST || request.method != "PUT") throw java.net.UnknownHostException(host)
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"url":"https://$answerHost/$sha","sha256":"$sha","size":${body.size},"type":"application/octet-stream"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ).build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }
}
