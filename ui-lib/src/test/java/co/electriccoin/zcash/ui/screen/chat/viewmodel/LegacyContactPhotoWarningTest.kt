package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentIds
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SR-C1 / KI-37 (fix-all 2.13.0, train T4c; photos-privacy spec §4 with the review's SR-C1 changes): a photo, file or
 * voice note from 2.13.0 to a friend still on 2.12.4.
 *
 * 2.13.0 uploads attachments to hosts 2.12.4's allow-list does not know (FileUploadManager, commit 55d73d212), so 2.12.4
 * cannot parse the link and shows the raw `ZFILE|…` line as a text message. 2.13.0 puts `["zchat_cap","ironwood"]` in
 * every relay rumor it sends and 2.12.4 never does, but at HEAD nothing records it per contact (only `gp1` is read), so
 * the user sends with no warning.
 *
 * Wanted:
 * - C1-1: the contact's latest relay rumor carried no Ironwood tag → before any upload the chat shows
 *   SendMessageState.LegacyContactAttachmentWarning; nothing is uploaded, published or paid while it is open.
 * - C1-2: "Send anyway" → the photo is uploaded once and its link goes over the relays once.
 * - C1-2b: "Cancel" → nothing uploaded, published or paid; the row stays FAILED (with its own reason, never "try
 *   again") and keeps its retry copy; its Retry asks again instead of sending.
 * - C1-3 (control): the latest rumor carried the tag → no warning, sent as today.
 * - C1-4 (control): no relay rumor from the contact yet → no warning.
 * - C1-5: an old untagged rumor, then a newer tagged one → no warning (the latest wins).
 * - C1-6a: a Shielded chat with an untagged record warns before any upload too. (C1-6 proper — the warning before
 *   SR-A's price question — is SR-A's RED; SR-A has not landed.)
 * - C1-7: a tagged rumor at T, then a replayed untagged rumor with createdAt T − 3 days → no warning (ordered by the
 *   rumor's createdAtSec, not by arrival).
 * - C1-8: a rumor KI-24's replay drop refuses (already seen) never updates the record.
 * - C1-9: a file and a voice note warn too.
 * - C1-10: a warning replaced or dismissed (the screen resets the state) reads as Cancel: the upload slot is freed and
 *   nothing is uploaded.
 * - C1-11: the record in the real store keeps the newest rumor only and is wiped with the contact's keys and on a
 *   full wipe.
 *
 * The new API is reached by name so this file compiles before it exists. The upload servers are answered offline by an
 * OkHttp interceptor and the relays by a recording publisher. All addresses, keys and bytes are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyContactPhotoWarningTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()
    private val publishes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var savedOwnHistoryRead = false
    private val watcher = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private companion object {
        const val ACCEPTING_HOST = "nostr.download"
        const val WARNING_STATE = "LegacyContactAttachmentWarning"
        val IRONWOOD_TAG = listOf("zchat_cap", "ironwood")
        val GP1_TAG = listOf("zchat_cap", "gp1")
        const val DAY_SEC = 24L * 60 * 60
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
            NostrChatBridge.PublishResult(1, "synthetic-src1-${publishes.size}")
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

    // ------------------------------------------------------------------ C1-1, C1-2

    @Test
    fun `C1-1 a photo to a contact whose latest relay rumor had no Ironwood tag warns before any upload`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 1)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertWarning(vm)
            h.assertNothingWentOut()
        }

    @Test
    fun `C1-2 Send anyway uploads the photo once and sends its link over the relays once`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 2)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)
            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)
            h.assertWarning(vm)

            h.answer(vm, "confirmLegacyContactAttachment")
            h.awaitIdle(vm)

            assertEquals(1, h.servers.stored().size, "the photo was not uploaded exactly once: ${h.servers.puts()}")
            assertEquals(1, h.fileLinks().size, "the photo's link did not go over the relays exactly once")
            assertEquals(0, h.proposals(), "a Tunnel photo paid something")
            assertTrue(h.outgoingRows(vm).none { it.status == MessageStatus.FAILED }, "a FAILED row is left: ${h.outgoingRows(vm)}")
        }

    @Test
    fun `C1-2b Cancel uploads nothing, keeps a FAILED row with its retry copy, and its Retry asks again`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 3)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)
            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)
            h.assertWarning(vm)

            h.answer(vm, "cancelLegacyContactAttachment")
            h.awaitIdle(vm)

            h.assertNothingWentOut()
            val row = h.outgoingRows(vm).singleOrNull { it.id.startsWith(AttachmentIds.PHOTO) }
            assertNotNull(row, "Cancel dropped the user's photo row: ${h.outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, row.status)
            assertEquals(copyConstant("ATTACHMENT_LEGACY_CONTACT_CANCELLED"), row.failureReason, "the row does not say why")
            assertNull(h.tracker(vm).progress.value, "the upload slot is still held after Cancel")
            assertTrue(h.retryCopyKept(vm, row.id), "Cancel lost the retry copy")

            assertTrue(vm.retryMessage(h.peer, row.id), "Retry did not start")
            h.awaitWarningOrIdle(vm)
            h.assertWarning(vm)
            h.assertNothingWentOut()
            h.answer(vm, "cancelLegacyContactAttachment")
            h.awaitIdle(vm)
            h.assertNothingWentOut()
        }

    // ------------------------------------------------------------------ C1-3, C1-4 (controls)

    @Test
    fun `C1-3 control - a contact whose latest rumor carried the Ironwood tag gets the photo with no warning`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 4)
            val vm = h.viewModel()
            h.receive(vm, tags = listOf(IRONWOOD_TAG, GP1_TAG), agoSec = 60)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertNoWarning(vm)
            h.assertSentOnce()
        }

    @Test
    fun `C1-4 control - no relay rumor from the contact yet means no warning`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 5)
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertNoWarning(vm)
            h.assertSentOnce()
        }

    // ------------------------------------------------------------------ C1-5, C1-7, C1-8

    @Test
    fun `C1-5 an old untagged rumor then a newer tagged one - the latest wins, no warning`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 6)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 3_600)
            h.receive(vm, tags = listOf(IRONWOOD_TAG, GP1_TAG), agoSec = 60)
            h.assertRecordedIronwood(agoSec = 60)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertNoWarning(vm)
            h.assertSentOnce()
        }

    @Test
    fun `C1-5b a newer untagged rumor after a tagged one warns - the friend went back to an older ZChat`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 7)
            val vm = h.viewModel()
            h.receive(vm, tags = listOf(IRONWOOD_TAG, GP1_TAG), agoSec = 3_600)
            h.receive(vm, tags = emptyList(), agoSec = 60)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertWarning(vm)
            h.assertNothingWentOut()
        }

    @Test
    fun `C1-7 a replayed untagged rumor three days older than a tagged one does not mark the friend as old`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 8)
            val vm = h.viewModel()
            h.receive(vm, tags = listOf(IRONWOOD_TAG, GP1_TAG), agoSec = 60)
            // Arrives later (a relay replaying its backlog), but was written three days before.
            h.receive(vm, tags = emptyList(), agoSec = 60 + 3 * DAY_SEC)
            h.assertRecordedIronwood(agoSec = 60)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertNoWarning(vm)
            h.assertSentOnce()
        }

    @Test
    fun `C1-8 a rumor the replay drop refuses never updates the record`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 9)
            val vm = h.viewModel()
            h.receive(vm, tags = listOf(IRONWOOD_TAG, GP1_TAG), agoSec = 120)
            // An untagged, newer rumor whose gift-wrap was already handled: NostrChatBridge.dispatch drops it (KI-24).
            h.seen += "src1-replayed-${h.n}"
            h.receive(vm, tags = emptyList(), agoSec = 30, eventId = "src1-replayed-${h.n}", expectRow = false)
            h.assertRecordedIronwood(agoSec = 120)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertNoWarning(vm)
            h.assertSentOnce()
        }

    // ------------------------------------------------------------------ C1-6a (Shielded, at HEAD's transport)

    @Test
    fun `C1-6a a Shielded chat whose latest relay rumor had no tag warns before any upload`() =
        runBlocking {
            val h = Harness(ConversationMode.VAULT, 10)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)

            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)

            h.assertWarning(vm)
            h.assertNothingWentOut()
            h.answer(vm, "cancelLegacyContactAttachment")
            h.awaitIdle(vm)
            h.assertNothingWentOut()
        }

    // ------------------------------------------------------------------ C1-9

    @Test
    fun `C1-9 a file to a contact on an older ZChat warns before any upload`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 11)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)

            h.pickFile(vm)
            h.awaitWarningOrIdle(vm)

            h.assertWarning(vm)
            h.assertNothingWentOut()
        }

    @Test
    fun `C1-9 a voice note to a contact on an older ZChat warns before any upload`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 12)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)

            h.recordVoice(vm)
            h.awaitWarningOrIdle(vm)

            h.assertWarning(vm)
            h.assertNothingWentOut()
        }

    // ------------------------------------------------------------------ C1-10

    @Test
    fun `C1-10 a warning the screen dismisses reads as Cancel - nothing uploaded and the slot is freed`() =
        runBlocking {
            val h = Harness(ConversationMode.TUNNEL, 13)
            val vm = h.viewModel()
            h.receive(vm, tags = emptyList(), agoSec = 60)
            h.pickPhoto(vm)
            h.awaitWarningOrIdle(vm)
            h.assertWarning(vm)

            vm.resetSendState()
            h.awaitIdle(vm)

            h.assertNothingWentOut()
            assertNull(h.tracker(vm).progress.value, "the upload slot is still held after the warning went")
            val row = h.outgoingRows(vm).singleOrNull { it.id.startsWith(AttachmentIds.PHOTO) }
            assertNotNull(row, "the photo row vanished")
            assertEquals(MessageStatus.FAILED, row.status)
        }

    // ------------------------------------------------------------------ C1-11 (the real store)

    @Test
    fun `C1-11 the real store keeps the newest rumor only and wipes the record with the contact's keys`() {
        val (prefs, _) = realPreferences()
        val peer = SyntheticUnifiedAddress.mainnet(5_381)
        val other = SyntheticUnifiedAddress.mainnet(5_382)

        fun record(
            who: String,
            cap: Boolean,
            at: Long,
        ) = call(prefs, "recordPeerIronwoodCap", arrayOf(String::class.java, java.lang.Boolean.TYPE, java.lang.Long.TYPE), who, cap, at)

        fun read(who: String): Pair<Boolean, Long>? = capOf(call(prefs, "getPeerIronwoodCap", arrayOf(String::class.java), who))

        assertNull(read(peer), "a record exists before any rumor")
        record(peer, true, 2_000L)
        assertEquals(true to 2_000L, read(peer))
        record(peer, false, 1_000L)
        assertEquals(true to 2_000L, read(peer), "an older rumor replaced a newer one in the store")
        record(peer, false, 3_000L)
        assertEquals(false to 3_000L, read(peer), "a newer rumor did not replace the record")
        record(other, true, 3_000L)

        prefs.clearE2EKeys(peer)
        assertNull(read(peer), "the record outlived the contact's keys")
        assertEquals(true to 3_000L, read(other), "another contact's record was wiped")

        prefs.clearAll()
        assertNull(read(other), "the record outlived a full wipe")
    }

    // ------------------------------------------------------------------ shared

    private fun copyConstant(name: String): String =
        try {
            ChatSendCopy::class.java.getField(name).get(null) as String
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no $name", e)
        }

    private fun call(
        target: Any,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any,
    ): Any? {
        val method =
            try {
                target.javaClass.getMethod(name, *types)
            } catch (e: NoSuchMethodException) {
                throw AssertionError("${target.javaClass.simpleName} has no $name", e)
            }
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** (capIronwood, atSec) of a stored record, read by name. */
    private fun capOf(record: Any?): Pair<Boolean, Long>? {
        record ?: return null
        val cap = record.javaClass.getMethod("getCapIronwood").invoke(record) as Boolean
        val at = record.javaClass.getMethod("getAtSec").invoke(record) as Long
        return cap to at
    }

    private inner class Harness(
        mode: ConversationMode,
        val n: Int,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(5_300 + n)
        private val base = PaidChatFixture(peer, mode, haveOurKey = true)
        private val peerRelayKey = relayKey(5_300 + n)
        val servers = FakeServers()
        val cache: File = Files.createTempDirectory("sr-c1-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }
        val seen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        /** The record the view model wrote, as the store would keep it (the ordering rule is the view model's). */
        @Volatile private var capRecord: Pair<Boolean, Long>? = null

        /** The newest rumor must be the one on record: Ironwood-capable, written [agoSec] seconds ago. */
        fun assertRecordedIronwood(agoSec: Long) {
            val record = capRecord ?: fail("nothing recorded about the contact's ZChat version")
            assertTrue(record.first, "the record says the contact is on an older ZChat: $record")
            val expected = System.currentTimeMillis() / 1000 - agoSec
            assertTrue(kotlin.math.abs(record.second - expected) <= 30, "the record is not the newest rumor's time: $record")
        }

        private var received = 0

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when {
                    method.name == "getPeerNostrPubkey" && args?.get(0) == peer -> peerRelayKey
                    method.name == "findPeerByNostrPubkey" -> if (args?.get(0) == peerRelayKey) peer else null
                    method.name == "hasSeenNostrEvent" -> args?.get(0) in seen
                    method.name == "markNostrEventSeen" -> {
                        seen += args!![0] as String
                        writeResult(method)
                    }
                    method.name == "recordPeerIronwoodCap" && args?.get(0) == peer -> {
                        capRecord = (args[1] as Boolean) to (args[2] as Long)
                        writeResult(method)
                    }
                    method.name == "getPeerIronwoodCap" && args?.get(0) == peer ->
                        capRecord?.let { (cap, at) ->
                            method.returnType
                                .getConstructor(java.lang.Boolean.TYPE, java.lang.Long.TYPE)
                                .newInstance(cap, at)
                        }
                    else ->
                        try {
                            method.invoke(base.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        private fun writeResult(method: Method): Any? = if (method.returnType == java.lang.Boolean.TYPE) true else null

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
            servers.online = true
            return vm
        }

        /**
         * The contact's relay rumor reaches the chat screen's reader through NostrChatBridge.dispatch (its KI-24 replay
         * drop included), [agoSec] seconds after it was written, with [tags] inside the rumor. The reader is started
         * for this one delivery and stopped once the rumor was handled, so the view model can settle afterwards.
         */
        suspend fun receive(
            vm: ChatViewModel,
            tags: List<List<String>>,
            agoSec: Long,
            eventId: String = "src1-event-$n-${received + 1}",
            expectRow: Boolean = true,
        ) {
            received++
            val rumorId = "src1-rumor-$n-$received"
            val before =
                vm.viewModelScope.coroutineContext.job.children
                    .toSet()
            RelayKeyChatScreen.start(vm, "observeNostrInbound")
            val reader: Job? =
                vm.viewModelScope.coroutineContext.job.children
                    .firstOrNull { it !in before }
            val at = System.currentTimeMillis() / 1000 - agoSec
            NostrChatBridge.dispatch(
                NostrInboxManager.InboundDm(
                    senderPubkeyHex = peerRelayKey,
                    content = "synthetic relay text $n-$received",
                    createdAtSec = at,
                    eventId = eventId,
                    rumorId = rumorId,
                    rumorTags = listOf(listOf("p", "synthetic-recipient")) + tags,
                    wrapCreatedAtSec = at,
                ),
                proxy,
            )
            if (expectRow) {
                val deadline = System.currentTimeMillis() + 10_000
                while (pending(vm).value.none { it.id == "nmsg-$rumorId" }) {
                    main.scheduler.advanceUntilIdle()
                    if (System.currentTimeMillis() > deadline) fail("the contact's relay text was not handled")
                    delay(10)
                }
            }
            main.scheduler.advanceUntilIdle()
            reader?.cancel()
            awaitIdle(vm)
        }

        fun proposals(): Int = base.seam.proposalRequests.get()

        /** The ZFILE links that went over the relays. */
        fun fileLinks(): List<String> = synchronized(publishes) { publishes.filter { it.contains("ZFILE|") } }

        fun assertNothingWentOut() {
            assertEquals(emptyList(), servers.puts().map { it.host }, "something was uploaded")
            assertEquals(emptyList(), fileLinks(), "an attachment link went over the relays")
            assertEquals(0, proposals(), "something was paid or priced on-chain")
        }

        fun assertSentOnce() {
            assertEquals(1, servers.stored().size, "the photo was not uploaded exactly once: ${servers.puts()}")
            assertEquals(1, fileLinks().size, "the photo's link did not go over the relays exactly once")
            assertEquals(0, proposals(), "a photo paid something")
        }

        fun assertWarning(vm: ChatViewModel) {
            val state = HandshakeHarness.sendMessageState(vm)
            assertEquals(WARNING_STATE, state.javaClass.simpleName, "no warning before the upload, state $state")
            val shownFor =
                state.javaClass
                    .getMethod("getPeerAddress")
                    .invoke(state)
            assertEquals(peer, shownFor, "the warning names another chat")
        }

        fun assertNoWarning(vm: ChatViewModel) {
            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse(state.javaClass.simpleName == WARNING_STATE, "a warning for a contact on 2.13: $state")
        }

        fun answer(
            vm: ChatViewModel,
            name: String,
        ) {
            val method =
                try {
                    ChatViewModel::class.java.getMethod(name)
                } catch (e: NoSuchMethodException) {
                    throw AssertionError("ChatViewModel has no $name", e)
                }
            try {
                method.invoke(vm)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
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

        fun retryCopyKept(
            vm: ChatViewModel,
            id: String,
        ): Boolean {
            val store =
                ChatViewModel::class.java
                    .getDeclaredField("attachmentRetryStore")
                    .apply { isAccessible = true }
                    .get(vm) ?: return false
            return store.javaClass.getMethod("contains", String::class.java).invoke(store, id) as Boolean
        }

        fun plaintext(marker: String): ByteArray = ("%PDF-1.4 synthetic $marker ".repeat(200)).toByteArray()

        fun pickPhoto(vm: ChatViewModel) {
            val prepare: () -> ByteArray? = { plaintext("synthetic photo $n") }
            invoke(
                vm,
                "handlePickedImage",
                arrayOf(String::class.java, Context::class.java, java.lang.Boolean.TYPE, Function0::class.java),
                arrayOf(peer, context, false, prepare),
            )
        }

        fun pickFile(vm: ChatViewModel) {
            val bytes = plaintext("synthetic file $n")
            val open: () -> InputStream? = { bytes.inputStream() }
            invoke(
                vm,
                "handlePickedFile",
                arrayOf(String::class.java, Context::class.java, String::class.java, java.lang.Long.TYPE, Function0::class.java),
                arrayOf(peer, context, "application/pdf", bytes.size.toLong(), open),
            )
        }

        fun recordVoice(vm: ChatViewModel) {
            val recording = File(cache, "synthetic-recording-$n.m4a").apply { writeBytes(plaintext("synthetic voice $n")) }
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

        /**
         * Runs the view model's coroutines until the warning is on screen (the attachment then waits for the answer) or
         * nothing is left to run.
         */
        suspend fun awaitWarningOrIdle(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                main.scheduler.advanceUntilIdle()
                if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == WARNING_STATE) return
                if (vm.viewModelScope.coroutineContext.job.children
                        .none { it.isActive }
                ) {
                    break
                }
                if (System.currentTimeMillis() > deadline) fail("the view model did not settle and showed no warning")
                delay(5)
            }
            HandshakeHarness.awaitIdle(vm)
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
                delay(5)
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

    /** Offline upload servers: nostr.download stores the blob once [online]; every other host is unreachable. */
    private class FakeServers {
        data class Put(
            val host: String,
            val sha256: String,
        )

        @Volatile var online = false
        private val puts: MutableList<Put> = Collections.synchronizedList(mutableListOf())

        fun puts(): List<Put> = synchronized(puts) { puts.toList() }

        /** The blobs a host really stored (an unreachable host's attempt is only in [puts]). */
        fun stored(): List<Put> = puts().filter { online && it.host == ACCEPTING_HOST }

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
