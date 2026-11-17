package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.B1cPlanned
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1474 as a feature (OT213n B1c; owner W-123; FIX_PLAN REVISION 2; skills money-path and compat-2124): the sender.
 *
 * At 669cf19e1 an Open or Tunnel chat with no on-chain key exchange refuses every photo, file and voice note at
 * refuseAttachmentIfBlocked (SendMessageState.AttachmentNeedsKeyExchange), even when the contact's relay key is bound
 * and its relay rumors carry the 2.13 capability.
 *
 * Wanted:
 * - Open and Tunnel, bound relay key, 2.13 capability recorded (getPeerIronwoodCap = true), no shared key: the encrypted
 *   blob is uploaded as today, and exactly one `ZATT|v1|…` text goes out through the NIP-17 publish
 *   (NostrChatBridge.publish) to the contact's bound relay key. It names the uploaded blob (hash = the ciphertext's
 *   SHA-256 prefix) and its file key decrypts that blob; no publish carries a ZFILE, the plaintext or the address; no
 *   row shows the raw ZATT text; nothing FAILED.
 * - The free path never makes a proposal and never spends (the send seam's proposal count stays 0).
 * - Guards (pass before and after): a 2.12.4 contact (capability false) and an unknown one (no record) keep the notice
 *   and nothing goes out; a Shielded chat with a bound 2.13 contact and no key keeps the notice and sends no ZATT.
 * Real ChatViewModel photo, file and voice handlers over [PaidChatFixture]; upload servers answered offline by an OkHttp
 * interceptor; relays by a recording publisher. Every address, key and byte is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeAttachmentGoesToTheBoundRelayKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()
    private val publishes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var savedOwnHistoryRead = false

    private companion object {
        const val ACCEPTING_HOST = "nostr.download"
        const val NOTICE_STATE = "AttachmentNeedsKeyExchange"
        const val MARKER = "synthetic-b1c-plaintext-marker"
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
            NostrChatBridge.PublishResult(1, "synthetic-b1c-${publishes.size}")
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

    private enum class Kind { PHOTO, FILE, VOICE }

    // ------------------------------------------------------------------ the free path (RED at 669cf19e1)

    @Test
    fun `a photo to a bound 2_13 contact in an Open chat goes as one ZATT to the bound key, free`() =
        runBlocking { freePath(ConversationMode.OPEN, Kind.PHOTO) }

    @Test
    fun `a file to a bound 2_13 contact in an Open chat goes as one ZATT to the bound key, free`() =
        runBlocking { freePath(ConversationMode.OPEN, Kind.FILE) }

    @Test
    fun `a voice note to a bound 2_13 contact in an Open chat goes as one ZATT to the bound key, free`() =
        runBlocking { freePath(ConversationMode.OPEN, Kind.VOICE) }

    @Test
    fun `a photo to a bound 2_13 contact in a Tunnel chat goes as one ZATT to the bound key, free`() =
        runBlocking { freePath(ConversationMode.TUNNEL, Kind.PHOTO) }

    private suspend fun freePath(
        mode: ConversationMode,
        kind: Kind,
    ) {
        val h = Harness(mode, cap = true)
        h.servers.online = true
        val vm = h.viewModel()

        h.send(vm, kind)
        h.awaitIdle(vm)

        val state = HandshakeHarness.sendMessageState(vm)
        assertNotEquals(NOTICE_STATE, state.javaClass.simpleName, "$mode $kind to a bound 2.13 contact was refused: $state")
        assertEquals(0, h.proposals(), "the free $kind path made a proposal (money-path: nothing may be paid)")
        val zatts = h.sent().filter { it.first.contains("ZATT|") }
        assertEquals(1, zatts.size, "one ZATT must go out, went ${zatts.size} (publishes ${h.sent().map { it.first.take(12) }})")
        val (text, to) = zatts.single()
        assertTrue(text.startsWith("ZATT|v1|"), "the ZATT does not start with its prefix")
        assertEquals(h.boundKey, to, "the ZATT went to a key other than the contact's bound relay key")
        for ((other, _) in h.sent()) {
            assertFalse(other.startsWith("ZFILE|"), "a ZFILE went out without a shared key to wrap its file key")
            assertFalse(other.contains(h.peer), "a publish carries the contact's address")
            assertFalse(other.contains(MARKER), "a publish carries the plaintext")
        }
        val put = h.servers.puts().lastOrNull { it.host == ACCEPTING_HOST } ?: fail("the $kind was not uploaded")
        val parsed = assertNotNull(B1cPlanned.parse(text), "the sent ZATT does not parse")
        assertEquals(put.sha256.take(32), B1cPlanned.prop(parsed, "hash"), "the ZATT names another blob")
        val key = B1cPlanned.prop(parsed, "fileKey") as ByteArray
        val opened = E2EEncryption.decryptFile(put.body, key)
        assertContentEquals(h.lastPlaintext, opened, "the ZATT's file key does not open the uploaded blob")
        val rows = h.outgoingRows(vm)
        assertTrue(rows.none { it.status == MessageStatus.FAILED }, "a FAILED row is left: $rows")
        assertTrue(rows.none { it.text.contains("ZATT|") }, "the sender's bubble shows the raw ZATT text")
    }

    // ------------------------------------------------------------------ guards (pass before and after)

    @Test
    fun `guard - a 2_12_4 contact keeps the notice and nothing goes out`() =
        runBlocking {
            for (kind in Kind.entries) refused(ConversationMode.OPEN, cap = false, kind)
        }

    @Test
    fun `guard - a contact whose capability is unknown keeps the notice and nothing goes out`() =
        runBlocking {
            refused(ConversationMode.OPEN, cap = null, Kind.PHOTO)
            refused(ConversationMode.TUNNEL, cap = null, Kind.PHOTO)
        }

    @Test
    fun `guard - a Shielded chat with a bound 2_13 contact and no key sends no ZATT`() =
        runBlocking { refused(ConversationMode.VAULT, cap = true, Kind.PHOTO) }

    private suspend fun refused(
        mode: ConversationMode,
        cap: Boolean?,
        kind: Kind,
    ) {
        publishes.clear()
        val h = Harness(mode, cap)
        h.servers.online = true
        val vm = h.viewModel()

        h.send(vm, kind)
        h.awaitIdle(vm)

        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(NOTICE_STATE, state.javaClass.simpleName, "$mode cap=$cap $kind: the notice is not shown, state $state")
        assertEquals(emptyList(), h.servers.puts().map { it.host }, "$mode cap=$cap $kind: something was uploaded")
        assertEquals(emptyList(), h.sent().map { it.first.take(12) }, "$mode cap=$cap $kind: something went over the relays")
        assertEquals(0, h.proposals(), "$mode cap=$cap $kind: something was paid or priced on-chain")
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
        mode: ConversationMode,
        cap: Boolean?,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(14_740 + mode.ordinal * 10 + (cap?.let { if (it) 1 else 2 } ?: 3))
        val boundKey: String = relayKey(14_740 + mode.ordinal)
        private val base = PaidChatFixture(peer, mode, haveOurKey = false)
        val servers = FakeServers()
        val cache: File = Files.createTempDirectory("b1c-zatt-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }
        var lastPlaintext: ByteArray = ByteArray(0)

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                val forPeer = args?.getOrNull(0) == peer
                when {
                    method.name == "getPeerNostrPubkey" && forPeer -> boundKey
                    method.name == "findPeerByNostrPubkey" && args?.getOrNull(0) == boundKey -> peer
                    method.name in setOf("getPeerIronwoodCap", "getPeerZattCap") && forPeer ->
                        cap?.let { ZchatPreferences.PeerIronwoodCap(capIronwood = it, atSec = 1_790_000_000L) }
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

        @Suppress("UNCHECKED_CAST")
        fun outgoingRows(vm: ChatViewModel): List<ChatMessage> =
            (
                ChatViewModel::class.java
                    .getDeclaredField("pendingMessages")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<List<ChatMessage>>
            ).value.filter { it.isOutgoing && it.peerAddress == peer }

        private fun plaintext(what: String): ByteArray = ("%PDF-1.4 $MARKER $what ".repeat(200)).toByteArray()

        fun send(
            vm: ChatViewModel,
            kind: Kind,
        ) {
            when (kind) {
                Kind.PHOTO -> {
                    val bytes = plaintext("photo")
                    lastPlaintext = bytes
                    val prepare: () -> ByteArray? = { bytes }
                    invoke(
                        vm,
                        "handlePickedImage",
                        arrayOf(String::class.java, Context::class.java, java.lang.Boolean.TYPE, Function0::class.java),
                        arrayOf(peer, context, false, prepare),
                    )
                }
                Kind.FILE -> {
                    val bytes = plaintext("file")
                    lastPlaintext = bytes
                    val open: () -> InputStream? = { bytes.inputStream() }
                    invoke(
                        vm,
                        "handlePickedFile",
                        arrayOf(String::class.java, Context::class.java, String::class.java, java.lang.Long.TYPE, Function0::class.java),
                        arrayOf(peer, context, "application/pdf", bytes.size.toLong(), open),
                    )
                }
                Kind.VOICE -> {
                    val bytes = plaintext("voice")
                    lastPlaintext = bytes
                    val recording = File(cache, "synthetic-recording.m4a").apply { writeBytes(bytes) }
                    vm.handleRecordedAudio(peer, recording, 3_000L, context)
                }
            }
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

    /** Offline upload servers: every host unreachable until [online], then nostr.download stores the blob. */
    private class FakeServers {
        class Put(
            val host: String,
            val sha256: String,
            val body: ByteArray,
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
                if (request.method == "PUT") puts += Put(host, sha, body)
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
