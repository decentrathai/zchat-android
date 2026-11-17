package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.nostr.FileUploadManager
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
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
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 9 R9-4 (Round 8 devices section 3; R8-fixup F3 remainder, device D-F5) — a failed attachment.
 *
 * At 3bcbc11ab (re-checked at 734adc6dc):
 * - a FILE whose upload fails leaves no row at all (handlePickedFile's catch only sets SendMessageState.Error): the
 *   user's send vanishes;
 * - a PHOTO or VOICE note whose upload fails keeps a FAILED bubble, but its Retry does nothing: retryMessage returns
 *   false at `recoverRawSendPayload() ?: return` because a bubble whose upload failed carries no ZFILE payload;
 * - the failed bubbles read '📷 Photo (failed)' and '🎙️ Voice message (failed)' in English on every phone.
 *
 * Wanted:
 * (a) an upload failure in the file flow leaves exactly one outgoing FAILED row, labelled with the phone-language
 *     chat_file_failed_label; nothing is paid;
 * (b) Retry on a failed photo, file or voice row uploads the same ciphertext again, declared as an opaque blob
 *     (FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE), and on success sends exactly one ZFILE and leaves no FAILED
 *     row. What the retry keeps on disk is ciphertext, never the plaintext, and it is gone once the file is sent,
 *     once the row is deleted for me, or after 24 h;
 * (c) control: a row whose on-chain send may have gone out still refuses a paid retry (MAY_HAVE_BEEN_SENT) and
 *     uploads nothing.
 *
 * The upload servers are answered offline by an OkHttp interceptor (as in EncryptedUploadContentTypeTest): first
 * every host is unreachable (airplane mode), then nostr.download stores the blob. The picker's ContentResolver
 * cannot be faked on the JVM, so the photo and file flows are driven through the step after the picker (their
 * bytes), reached by name so this file compiles before those steps exist. All addresses and bytes are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedAttachmentKeepsRowAndRetriesTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()

    /**
     * ZC-1276: runs what is due on Main and moves virtual time 100 ms, never to the end: a jump would fire a send's 15-minute
     * build limit while its build runs on a real thread. With a real wait of at least 5 ms per step the limit would need 45 s,
     * beyond the 20 s these loops wait.
     */
    private fun pumpMain() {
        main.scheduler.advanceTimeBy(100L)
        main.scheduler.runCurrent()
    }

    private companion object {
        const val PEER = "usyntheticfailedattachment01"
        const val ACCEPTING_HOST = "nostr.download"
        const val DAY_MS = 24L * 60 * 60 * 1000
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

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a file whose upload fails keeps one FAILED row with the localized label`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()

            h.pickFile(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)

            val rows = h.outgoingRows(vm)
            assertEquals(1, rows.size, "a failed file must leave exactly one outgoing row, found ${rows.map { it.text }}")
            val row = rows.single()
            assertEquals(MessageStatus.FAILED, row.status, "the failed file's row is not shown as failed")
            assertEquals(h.label("chat_file_failed_label"), row.text, "the failed file's row is not the localized label")
            assertEquals(0, h.proposals(), "a failed upload paid something")
            assertTrue(h.servers.puts().isNotEmpty(), "fixture sanity: the upload was attempted")
        }

    @Test
    fun `a failed photo and a failed voice note are labelled from the phone's language`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)
            h.recordVoice(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)

            val labels =
                h
                    .outgoingRows(vm)
                    .filter { it.status == MessageStatus.FAILED }
                    .map { it.text }
                    .toSet()
            assertEquals(setOf(h.label("chat_photo_failed_label"), h.label("chat_voice_failed_label")), labels)
        }

    @Test
    fun `the failed labels are resources in English and Spanish, not literals in the view model`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val wanted =
            mapOf(
                "chat_photo_failed_label" to ("📷 Photo (failed)" to "📷 Foto (fallida)"),
                "chat_file_failed_label" to ("📎 File (failed)" to "📎 Archivo (fallido)"),
                "chat_voice_failed_label" to ("🎙️ Voice message (failed)" to "🎙️ Mensaje de voz (fallido)"),
            )
        for ((name, texts) in wanted) {
            assertEquals(texts.first, en[name], "English $name")
            assertEquals(texts.second, es[name], "Spanish $name")
        }
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt")
        for (literal in listOf("Photo (failed)", "File (failed)", "Voice message (failed)")) {
            assertFalse(source.contains(literal), "ChatViewModel still writes the English '$literal' on every phone")
        }
    }

    // ------------------------------------------------------------------ F-R9P-2 (round 10a R10A-1)

    /**
     * Seeker run of cf72159ad (F-R9P-2): a voice note recorded while a file was uploading was dropped with no row, no
     * message, and its recording left orphaned in the cache ('Ignoring audio: upload already in progress'). Wanted: the
     * voice bubble shows at once, waits for the running upload, and is then sent.
     */
    @Test
    fun `a voice note recorded while another upload runs is kept and sent after it`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            val tracker = h.tracker(vm)
            assertTrue(tracker.tryStart(), "fixture sanity: the upload slot was free")
            h.servers.online = true

            h.recordVoice(vm)
            // Not awaitIdle: the voice note's coroutine waits for the slot and stays active while it is held.
            main.scheduler.advanceUntilIdle()

            val waiting = h.outgoingRows(vm).filter { it.id.startsWith("audio-pending-") }
            assertEquals(1, waiting.size, "the voice note recorded during another upload left no row: ${h.outgoingRows(vm)}")
            assertEquals(MessageStatus.SENDING, waiting.single().status, "the waiting voice note is not shown as sending")
            assertTrue(File(h.cache, "synthetic-recording.m4a").exists(), "the waiting voice note's recording was deleted")

            tracker.reset()
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)

            assertTrue(h.servers.puts().isNotEmpty(), "the voice note was never uploaded once the slot was free")
            assertEquals(1, h.proposals(), "the voice note must be sent exactly once after the other upload")
            assertTrue(h.outgoingRows(vm).none { it.status == MessageStatus.FAILED }, "a FAILED row is left: ${h.outgoingRows(vm)}")
        }

    /**
     * Round 10a R10A-2 (F-R9P-4): a file's row read «📎 Document (7,0 MB)» on a Spanish phone, its words taken from the
     * English ZFILEMessage.displayText. Wanted: the row is built from the phone-language chat_attachment_document, and
     * once the upload fails the row is the failed label, never the English «Document (».
     */
    @Test
    fun `a file row is built from the phone-language document label`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            val asked = Collections.synchronizedList(mutableListOf<Int>())
            AutoSpendVm.setField(
                vm,
                "noteCopy",
                ChatNoteCopy { id, args ->
                    asked += id
                    EnglishNoteCopy.get(id, *args)
                },
            )

            h.pickFile(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)

            val document = ChatResourceText.stringId("chat_attachment_document") ?: fail("no string resource chat_attachment_document")
            assertTrue(document in asked, "the file row was not built from chat_attachment_document (the English displayText instead)")
            val row = h.outgoingRows(vm).single()
            assertEquals(h.label("chat_file_failed_label"), row.text, "the failed file's row is not the failed label")
            assertFalse(row.text.contains("Document ("), "the failed file's row keeps the English displayText: ${row.text}")
        }

    /** F-R9P-2 (feedback half): a photo or file picked during another upload was ignored with no word to the user. */
    @Test
    fun `a file picked while another upload runs says so`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            assertTrue(h.tracker(vm).tryStart(), "fixture sanity: the upload slot was free")

            h.pickFile(vm)
            main.scheduler.advanceUntilIdle()

            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue(state is SendMessageState.Error, "a file picked during another upload was dropped silently, state $state")
            assertEquals(h.label("chat_upload_busy"), state.message)
            assertEquals(0, h.proposals(), "a refused pick paid something")
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `Retry on a failed file uploads the same ciphertext again and sends one ZFILE`() =
        runBlocking { retrySendsOneZfile(Kind.FILE) }

    @Test
    fun `Retry on a failed photo uploads the same ciphertext again and sends one ZFILE`() =
        runBlocking { retrySendsOneZfile(Kind.PHOTO) }

    @Test
    fun `Retry on a failed voice note uploads the same ciphertext again and sends one ZFILE`() =
        runBlocking { retrySendsOneZfile(Kind.VOICE) }

    @Test
    fun `deleting a failed file for me removes what its retry kept`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            h.pickFile(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("fixture sanity: the failed file left no row")
            assertTrue(h.retryFiles().isNotEmpty(), "the failed file kept nothing to retry from")

            vm.hideMessage(row.id)

            assertEquals(emptyList(), h.retryFiles().map { it.name }, "the deleted file's retry copy is still on disk")
        }

    @Test
    fun `what a retry keeps is dropped after 24 hours`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            h.pickFile(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)
            val old = h.retryFiles()
            assertTrue(old.isNotEmpty(), "the failed file kept nothing to retry from")
            old.forEach { it.setLastModified(System.currentTimeMillis() - DAY_MS - 60_000) }

            h.pickFile(vm, marker = "second synthetic file")
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)

            val left = h.retryFiles().map { it.name }.toSet()
            for (f in old) assertFalse(f.name in left, "a retry copy older than 24 h is still on disk: ${f.name}")
        }

    /**
     * A retried file whose send waits behind the cost prompt (its ZFILE needs more than one memo): declining leaves the
     * bubble FAILED with its retry copy, so Retry works again; confirming sends it and only then drops the copy.
     */
    @Test
    fun `a retried file behind the cost prompt keeps its retry copy until the send takes it`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            h.pickFile(vm)
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)
            val failed = h.outgoingRows(vm).singleOrNull() ?: fail("fixture sanity: the failed file left no row")
            h.servers.online = true
            // SR-A: a padded url that changed the quoted memo count would now be skipped by the upload (A-17); every
            // Shielded attachment asks its exact price anyway, so the padding keeps the link within one memo.
            h.servers.urlPad = "?synthetic-padding=" + "p".repeat(60)

            assertTrue(vm.retryMessage(PEER, failed.id), "Retry on the failed file did nothing")
            h.awaitIdle(vm)
            assertTrue(
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation,
                "fixture sanity: the long ZFILE asks before paying, was ${HandshakeHarness.sendMessageState(vm)}",
            )
            vm.dismissPaidAction()
            h.awaitIdle(vm)

            assertEquals(0, h.proposals(), "declining paid something")
            val back = h.outgoingRows(vm).singleOrNull { it.id == failed.id }
            assertNotNull(back, "the declined file left no bubble")
            assertEquals(MessageStatus.FAILED, back.status)
            assertTrue(h.retryFiles().isNotEmpty(), "declining the prompt dropped what the next Retry needs")

            assertTrue(vm.retryMessage(PEER, failed.id), "Retry after declining did nothing")
            h.awaitIdle(vm)
            h.confirmIfAsked(vm)

            assertEquals(1, h.proposals(), "confirming must send the file once")
            assertTrue(h.outgoingRows(vm).none { it.status == MessageStatus.FAILED }, "a FAILED row is left after the confirmed send")
            assertEquals(emptyList(), h.retryFiles().map { it.name }, "the retry copy outlived the sent file")
        }

    // ------------------------------------------------------------------ (c)

    /** Control (passes before and after): a file row whose on-chain submit started is never paid again. */
    @Test
    fun `control - a file row whose send may have gone out refuses a paid retry`() =
        runBlocking {
            val h = Harness()
            val vm = h.viewModel()
            val zfile =
                ZFILEMessage(
                    hash = "0123456789abcdef0123456789abcdef",
                    type = ZFILEType.PDF,
                    size = 4096L,
                    url = "https://$ACCEPTING_HOST/0123456789abcdef0123456789abcdef",
                    wrappedKey = "c3ludGhldGlj",
                    blurhash = "",
                ).serialize()
            val id = "pending_424242"
            val started = System.currentTimeMillis() - 60_000
            h.fixture.proxy.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = id,
                    text = zfile,
                    timestampMillis = started,
                    peerAddress = PEER,
                    isPending = false,
                    status = MessageStatus.FAILED.name,
                    submitTracked = true,
                    submitStartedAtMillis = started,
                ),
            )
            h.pending(vm).value =
                listOf(
                    ChatMessage(
                        id = id,
                        txId = null,
                        text = "📎 synthetic.pdf",
                        timestamp = Instant.ofEpochMilli(started),
                        isOutgoing = true,
                        peerAddress = PEER,
                        isPending = false,
                        status = MessageStatus.FAILED,
                        fileHash = "0123456789abcdef0123456789abcdef",
                        fileZfileContent = zfile,
                        submitStartedAtMillis = started,
                    ),
                )

            val retried = vm.retryMessage(PEER, id)
            h.awaitIdle(vm)

            assertFalse(retried, "a file whose send may have gone out was retried")
            assertEquals(SendMessageState.Error(SubmitAttemptProof.MAY_HAVE_BEEN_SENT), HandshakeHarness.sendMessageState(vm))
            assertEquals(0, h.proposals(), "a possibly-sent file was paid again")
            assertEquals(emptyList(), h.servers.puts(), "a possibly-sent file was uploaded again")
        }

    // ------------------------------------------------------------------ shared body

    private suspend fun retrySendsOneZfile(kind: Kind) {
        val h = Harness()
        val vm = h.viewModel()

        when (kind) {
            Kind.FILE -> h.pickFile(vm)
            Kind.PHOTO -> h.pickPhoto(vm)
            Kind.VOICE -> h.recordVoice(vm)
        }
        h.awaitIdle(vm)
        h.confirmIfAsked(vm)
        val failed =
            h.outgoingRows(vm).singleOrNull { it.status == MessageStatus.FAILED }
                ?: fail("fixture sanity: the failed ${kind.name.lowercase()} left no FAILED row (${h.outgoingRows(vm)})")
        val firstBodies = h.servers.bodies()
        assertTrue(firstBodies.isNotEmpty(), "fixture sanity: the first upload was attempted")
        assertTrue(
            h.servers.puts().all { it.contentType == FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE },
            "the first ${kind.name.lowercase()} upload declared ${h.servers.puts().map { it.contentType }.toSet()}",
        )
        h.assertNoPlaintextKept(kind)

        h.servers.online = true
        val tried = h.servers.puts().size
        val retried = vm.retryMessage(PEER, failed.id)
        h.awaitIdle(vm)
        h.confirmIfAsked(vm)

        assertTrue(retried, "Retry on a failed ${kind.name.lowercase()} did nothing")
        val again = h.servers.puts().drop(tried)
        assertTrue(again.isNotEmpty(), "Retry did not upload the ${kind.name.lowercase()} again")
        assertTrue(
            again.all { it.contentType == FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE },
            "Retry uploaded the ciphertext declared as ${again.map { it.contentType }}",
        )
        assertEquals(
            setOf(firstBodies.first()),
            h.servers
                .bodies()
                .drop(firstBodies.size)
                .toSet(),
            "Retry must upload the same ciphertext it prepared the first time",
        )
        assertEquals(1, h.proposals(), "Retry must send exactly one ZFILE")
        val rows = h.outgoingRows(vm)
        assertTrue(rows.none { it.status == MessageStatus.FAILED }, "a FAILED row is left after a successful retry: $rows")
        val sent = rows.mapNotNull { it.fileZfileContent?.let(ZFILEMessage::parse) }
        assertEquals(1, sent.size, "exactly one ZFILE row after the retry, found $rows")
        assertEquals(kind.type, sent.single().type)
        assertTrue(sent.single().url.contains(ACCEPTING_HOST), "the ZFILE points at ${sent.single().url}")
        assertEquals(emptyList(), h.retryFiles().map { it.name }, "the retry copy outlived the sent file")
    }

    private enum class Kind(
        val type: ZFILEType,
    ) {
        FILE(ZFILEType.PDF),
        PHOTO(ZFILEType.JPEG),
        VOICE(ZFILEType.M4A),
    }

    // ------------------------------------------------------------------ harness

    private inner class Harness {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT)
        val servers = FakeServers()
        val cache: File = Files.createTempDirectory("r9-4-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }
        val plaintexts = mutableListOf<ByteArray>()

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            AutoSpendVm.setField(vm, "fileHttpClientProvider", servers.provider)
            AutoSpendVm.setField(vm, "uploadProgressTracker", UploadProgressTracker())
            AutoSpendVm.setField(vm, "noteCopy", EnglishNoteCopy)
            AutoSpendVm.setField(vm, "hiddenMessages", MutableStateFlow(emptySet<String>()))
            // The send's last step after a successful submit records the peer as a conversation partner.
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            return vm
        }

        fun proposals(): Int = fixture.seam.proposalRequests.get()

        /** The view model's one upload slot (set in [viewModel]); holding it stands for another upload running. */
        fun tracker(vm: ChatViewModel): UploadProgressTracker =
            ChatViewModel::class.java
                .getDeclaredField("uploadProgressTracker")
                .apply { isAccessible = true }
                .get(vm) as UploadProgressTracker

        fun label(name: String): String {
            val id = ChatResourceText.stringId(name) ?: fail("no string resource $name: the failed bubble is not localized")
            return EnglishNoteCopy.get(id)
        }

        @Suppress("UNCHECKED_CAST")
        fun pending(vm: ChatViewModel): MutableStateFlow<List<ChatMessage>> =
            ChatViewModel::class.java
                .getDeclaredField("pendingMessages")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<List<ChatMessage>>

        fun outgoingRows(vm: ChatViewModel): List<ChatMessage> = pending(vm).value.filter { it.isOutgoing && it.peerAddress == PEER }

        private fun plaintext(marker: String): ByteArray =
            ("%PDF-1.4 synthetic $marker ".repeat(200)).toByteArray().also { plaintexts += it }

        /** The file flow after the picker: its type, its declared size and a stream of its bytes. */
        fun pickFile(
            vm: ChatViewModel,
            marker: String = "first synthetic file",
        ) {
            val bytes = plaintext(marker)
            val open: () -> InputStream? = { bytes.inputStream() }
            invoke(
                vm,
                "handlePickedFile",
                arrayOf(String::class.java, Context::class.java, String::class.java, java.lang.Long.TYPE, Function0::class.java),
                arrayOf(PEER, context, "application/pdf", bytes.size.toLong(), open),
                "the file flow has no step after the picker that a failed upload can be kept and retried from",
            )
        }

        /** The photo flow after the picker: the prepared (compressed) JPEG bytes. */
        fun pickPhoto(vm: ChatViewModel) {
            val bytes = plaintext("synthetic photo")
            val prepare: () -> ByteArray? = { bytes }
            invoke(
                vm,
                "handlePickedImage",
                arrayOf(String::class.java, Context::class.java, java.lang.Boolean.TYPE, Function0::class.java),
                arrayOf(PEER, context, false, prepare),
                "the photo flow has no step after the picker that a failed upload can be kept and retried from",
            )
        }

        fun recordVoice(vm: ChatViewModel) {
            val recording = File(cache, "synthetic-recording.m4a").apply { writeBytes(plaintext("synthetic voice")) }
            vm.handleRecordedAudio(PEER, recording, 3_000L, context)
        }

        private fun invoke(
            vm: ChatViewModel,
            name: String,
            types: Array<Class<*>>,
            args: Array<Any>,
            missing: String,
        ) {
            val method =
                try {
                    ChatViewModel::class.java.getDeclaredMethod(name, *types)
                } catch (e: NoSuchMethodException) {
                    throw AssertionError(missing, e)
                }
            method.isAccessible = true
            try {
                method.invoke(vm, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        /** Files a retry keeps: everything under the cache except the renderer's own zchat_files and the recording. */
        fun retryFiles(): List<File> =
            cache
                .walkTopDown()
                .filter { it.isFile && it.parentFile?.name != "zchat_files" && !it.name.startsWith("synthetic-recording") }
                .toList()

        /** Whatever a failed attachment keeps for its retry holds ciphertext only (Retry itself proves something is kept). */
        fun assertNoPlaintextKept(kind: Kind) {
            val kept = retryFiles()
            for (f in kept) {
                val bytes = f.readBytes()
                for (p in plaintexts) {
                    assertFalse(contains(bytes, p.copyOf(64)), "${f.name} holds the ${kind.name.lowercase()}'s plaintext")
                }
            }
        }

        private fun contains(
            haystack: ByteArray,
            needle: ByteArray,
        ): Boolean {
            if (needle.isEmpty() || haystack.size < needle.size) return false
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }

        fun confirmIfAsked(vm: ChatViewModel) {
            if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) {
                vm.confirmPaidAction()
                runBlocking { awaitIdle(vm) }
            }
        }

        /** Runs the view model's coroutines to the end, skipping their delays (the test's Main has virtual time). */
        suspend fun awaitIdle(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                pumpMain()
                // SR-A: an attachment in a Shielded chat waits for its exact price question; the test answers it (confirmIfAsked).
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

    /** Offline upload servers: every host unreachable until [online], then nostr.download stores the blob. */
    private class FakeServers {
        data class Put(
            val host: String,
            val contentType: String?,
            val sha256: String,
        )

        @Volatile var online = false

        /** Appended to the stored blob's url: a long url makes the ZFILE memo need more than one memo (a cost prompt). */
        @Volatile var urlPad = ""
        private val puts: MutableList<Put> = Collections.synchronizedList(mutableListOf())

        fun puts(): List<Put> = synchronized(puts) { puts.toList() }

        fun bodies(): List<String> = puts().map { it.sha256 }

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                // Ktor's OkHttp engine sends the declared type as the Content-Type header and leaves the body's own type null.
                val declared =
                    (request.header("Content-Type") ?: request.body?.contentType()?.toString())
                        ?.substringBefore(';')
                        ?.trim()
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                if (request.method == "PUT") puts += Put(host, declared, sha)
                if (!online || host != ACCEPTING_HOST || request.method != "PUT") throw java.net.UnknownHostException(host)
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"url":"https://$host/$sha$urlPad","sha256":"$sha","size":${body.size},"type":"$declared"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ).build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }
}
