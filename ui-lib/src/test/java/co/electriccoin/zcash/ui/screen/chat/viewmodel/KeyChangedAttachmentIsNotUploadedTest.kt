package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.nio.file.Files
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-250 (fix-all 2.13.0, train T5b; t5b SPECS §6 item 5) — an attachment in a key-changed Open or Tunnel chat is
 * compressed, encrypted and UPLOADED before anything refuses it.
 *
 * At 3ff26bc9a the attachment handlers (photo, file, voice) and the screen's entries (picker, camera, mic, share sheet)
 * ask only SR-C2's gate (refuseAttachmentIfBlocked: is there an end-to-end key?). A chat whose contact's security key
 * changed still has its old key, so the photo is read, encrypted with a key wrapped for a possibly substituted contact
 * and put on a public file host; only then does sendMessage's TOFU/MITM gate refuse the link.
 *
 * Wanted: with the flag set, the entry check (refuseAttachmentIfBlocked) answers true and every handler refuses at its
 * entry, next to SR-C2's gate and before the upload slot: 0 uploads, no bubble, 0 proposals, the key-changed notice
 * (Error(KEY_CHANGED_VERIFY) and keyChangeRefusal = the contact). Control (passes today): without the flag the same
 * photo is uploaded.
 *
 * The upload servers answer offline (as in FailedAttachmentKeepsRowAndRetriesTest); the photo and file flows are driven
 * through the step after the picker, by name. All addresses and bytes are synthetic; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChangedAttachmentIsNotUploadedTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()

    private companion object {
        const val PEER = "usyntheticzc250attachment01"
        const val ACCEPTING_HOST = "nostr.download"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `the screen's attachment entries are refused in a key-changed chat`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keyChanged = true)
            val vm = h.viewModel()

            assertTrue(vm.refuseAttachmentIfBlocked(PEER), "the picker, camera, mic and share sheet would open in a key-changed chat")
            h.assertRefusedWithNothingDone(vm, "the entry check")
        }

    @Test
    fun `a photo in a key-changed Open chat is not uploaded`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keyChanged = true)
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitIdle(vm)
            h.assertRefusedWithNothingDone(vm, "a photo")
        }

    @Test
    fun `a file in a key-changed Tunnel chat is not uploaded`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, keyChanged = true)
            val vm = h.viewModel()

            h.pickFile(vm)
            h.awaitIdle(vm)
            h.assertRefusedWithNothingDone(vm, "a file")
        }

    @Test
    fun `a voice note in a key-changed Open chat is not uploaded`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keyChanged = true)
            val vm = h.viewModel()

            h.recordVoice(vm)
            h.awaitIdle(vm)
            h.assertRefusedWithNothingDone(vm, "a voice note")
        }

    @Test
    fun `control - without a key change the same photo is uploaded`() =
        runBlocking {
            val h = Harness(ConversationMode.OPEN, keyChanged = false)
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitIdle(vm)
            assertTrue(h.servers.puts().isNotEmpty(), "fixture sanity: an attachment in this chat is uploaded")
        }

    private inner class Harness(
        mode: ConversationMode,
        keyChanged: Boolean,
    ) {
        val fixture = PaidChatFixture(PEER, mode).apply { this.keyChanged = keyChanged }
        val servers = FakeServers().apply { online = true }
        val cache: File = Files.createTempDirectory("zc250-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            AutoSpendVm.setField(vm, "fileHttpClientProvider", servers.provider)
            AutoSpendVm.setField(vm, "uploadProgressTracker", UploadProgressTracker())
            AutoSpendVm.setField(vm, "noteCopy", EnglishNoteCopy)
            AutoSpendVm.setField(vm, "hiddenMessages", MutableStateFlow(emptySet<String>()))
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            return vm
        }

        fun assertRefusedWithNothingDone(
            vm: ChatViewModel,
            what: String,
        ) {
            assertEquals(emptyList(), servers.puts(), "$what was uploaded in a key-changed chat")
            assertEquals(0, fixture.seam.proposalRequests.get(), "$what built a proposal in a key-changed chat")
            assertEquals(emptyList(), outgoingRows(vm).map { it.id }, "$what left a bubble in a key-changed chat")
            assertEquals(
                SendMessageState.Error(ChatSendCopy.KEY_CHANGED_VERIFY),
                HandshakeHarness.sendMessageState(vm),
                "$what did not show the key-changed notice",
            )
            assertEquals(PEER, refusal(vm), "$what's refusal is not on screen")
            assertTrue(fixture.keyChanged, "the gate must not be weakened: the flag stays")
        }

        /** ChatViewModel.keyChangeRefusal (ZC-250), by name. */
        private fun refusal(vm: ChatViewModel): String? {
            val getter =
                try {
                    ChatViewModel::class.java.getMethod("getKeyChangeRefusal")
                } catch (e: NoSuchMethodException) {
                    throw AssertionError("ChatViewModel has no keyChangeRefusal", e)
                }
            return (getter.invoke(vm) as StateFlow<*>).value as String?
        }

        @Suppress("UNCHECKED_CAST")
        fun outgoingRows(vm: ChatViewModel): List<ChatMessage> =
            (
                ChatViewModel::class.java
                    .getDeclaredField("pendingMessages")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<List<ChatMessage>>
            ).value.filter { it.isOutgoing && it.peerAddress == PEER }

        private fun plaintext(marker: String): ByteArray = ("%PDF-1.4 synthetic $marker ".repeat(200)).toByteArray()

        fun pickFile(vm: ChatViewModel) {
            val bytes = plaintext("zc250 file")
            val open: () -> InputStream? = { bytes.inputStream() }
            invoke(
                vm,
                "handlePickedFile",
                arrayOf(String::class.java, Context::class.java, String::class.java, java.lang.Long.TYPE, Function0::class.java),
                arrayOf(PEER, context, "application/pdf", bytes.size.toLong(), open),
            )
        }

        fun pickPhoto(vm: ChatViewModel) {
            val bytes = plaintext("zc250 photo")
            val prepare: () -> ByteArray? = { bytes }
            invoke(
                vm,
                "handlePickedImage",
                arrayOf(String::class.java, Context::class.java, java.lang.Boolean.TYPE, Function0::class.java),
                arrayOf(PEER, context, false, prepare),
            )
        }

        fun recordVoice(vm: ChatViewModel) {
            val recording = File(cache, "synthetic-zc250-recording.m4a").apply { writeBytes(plaintext("zc250 voice")) }
            vm.handleRecordedAudio(PEER, recording, 3_000L, context)
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

        /** Runs the view model's coroutines to the end, skipping their delays; stops at an open price question. */
        suspend fun awaitIdle(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                main.scheduler.advanceUntilIdle()
                if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) return
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

    /** Upload servers answered offline: nostr.download stores every PUT while [online]; every PUT is recorded. */
    private class FakeServers {
        @Volatile var online = false
        private val puts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun puts(): List<String> = synchronized(puts) { puts.toList() }

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                if (request.method == "PUT") puts += host
                if (!online || host != ACCEPTING_HOST || request.method != "PUT") throw java.net.UnknownHostException(host)
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"url":"https://$host/synthetic-zc250-${body.size}","size":${body.size}}"""
                            .toResponseBody("application/json".toMediaType()),
                    ).build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }
}
