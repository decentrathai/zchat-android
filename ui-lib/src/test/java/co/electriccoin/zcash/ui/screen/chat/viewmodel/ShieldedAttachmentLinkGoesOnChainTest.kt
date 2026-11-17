package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentBlockReason
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SR-A / ZC-105 (fix-all 2.13.0, train T5b; photos-privacy SPEC §1 with Review changes R-A1..R-A5 and SR-A × SR-C1,
 * corrected by t5b SPECS §3 C-SRA-1..C-SRA-10; owner decision W-16, 28 Sep: "Link on-chain always").
 *
 * At 97ba1a6a8, in a Shielded chat, a photo, file or voice note is uploaded first and its link (host url + wrapped key +
 * hash) then goes over the relays, free, whenever the contact's relay key is known (handleNostrRouteIfApplicable's VAULT
 * branch, "VAULT file send → routing ZFILE ref over free NOSTR"); without it the link goes on-chain with no price question
 * (a one-memo link) or with an "at least" floor. Nothing asks before the upload, so a declined or unaffordable send has
 * already put the ciphertext on a public host. A Retry of a stored Shielded link sends it again with no question.
 *
 * Wanted: in a Shielded chat the link always travels inside the paid Shielded transaction, never over the relays, with
 * no relay fallback; before anything is uploaded the chat asks ONE exact question (PaidAction SHIELDED_ATTACHMENT, an
 * exact total = outputs + the probed network fee); Pay uploads and sends with that quote, and the send is refused before
 * the spend if the proposal differs; Cancel, a replaced question, leaving the chat, a Keystone switch or a changed key
 * upload and pay nothing; a shortfall, a probe failure or a confirming wallet upload nothing; every Retry of a Shielded
 * link asks the exact question again (SHIELDED_ATTACHMENT_RETRY) and never uploads again; an owed key exchange is quoted
 * with the link as one total; Tunnel and Open are unchanged.
 *
 * New API is reached by name (PaidAction names, state class names, ChatSendCopy constants, PaidActionConfirmation's
 * exact/memos), so this file compiles before it exists. Upload servers are answered offline by an OkHttp interceptor and
 * the relays by a recording publisher. All addresses, keys and bytes are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedAttachmentLinkGoesOnChainTest {
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

    private val publishes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var savedOwnHistoryRead = false

    private companion object {
        const val ACCEPTING_HOST = "nostr.download"
        const val SECOND_HOST = "blossom.yakihonne.com"
        const val QUESTION = "NeedsPaidActionConfirmation"
        const val WARNING = "LegacyContactAttachmentWarning"
        const val FEE = 15_000L

        /** One transaction of one memo: 1,000 to the contact, the 1,000 ZChat fee and the probed network fee. */
        const val ONE_MEMO_TOTAL = 1_000L + 1_000L + FEE
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { text, _ ->
            publishes += text
            NostrChatBridge.PublishResult(1, "synthetic-sra-${publishes.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    // ------------------------------------------------------------------ A-1, A-2

    @Test
    fun `A-1 a photo in a Shielded chat asks one exact question before anything is uploaded, published or proposed`() =
        runBlocking<Unit> {
            val h = Harness(1)
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.assertNothingWentOut()
        }

    @Test
    fun `A-2 Pay uploads once and sends the link on-chain inside the ratchet, never over the relays`() =
        runBlocking<Unit> {
            val h = Harness(2)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)

            h.pay(vm)

            assertEquals(1, h.servers.stored().size, "the photo was not uploaded exactly once: ${h.servers.puts()}")
            assertEquals(1, h.proposals(), "the link was not sent on-chain exactly once")
            assertEquals(emptyList(), h.fileLinks(), "the Shielded link went over the relays")
            val memos = h.memosOf(0)
            assertTrue(memos.none { it.contains("ZFILE|") }, "a memo carries the link in the clear: $memos")
            assertTrue(memos.any { it.contains("E2E1:") }, "the link is not inside the ratchet: $memos")
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("no row for the paid photo: ${h.outgoingRows(vm)}")
            assertTrue(row.id.startsWith("pending_"), "the paid photo's row is not an on-chain row: ${row.id}")
            assertFalse(row.status == MessageStatus.FAILED, "the paid photo is FAILED: ${row.failureReason}")
        }

    // ------------------------------------------------------------------ A-3, A-18

    @Test
    fun `A-3 a proposal whose fee differs from the quote pays nothing, and Retry asks again with the new price`() =
        runBlocking<Unit> {
            val h = Harness(3)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.base.seam.proposalFee = 20_000L

            h.pay(vm)

            assertEquals(0, h.submits(), "a proposal that differs from the quote was submitted")
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("the refused photo left no row: ${h.outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, row.status, "the refused photo is not FAILED")
            assertEquals(copy("ATTACHMENT_PRICE_CHANGED"), row.failureReason, "the refused photo does not say the price changed")
            assertEquals(null, row.submitStartedAtMillis, "the refused photo's submit was marked as started")
            assertNotNull(row.fileZfileContent, "the refused photo lost the link its Retry sends")
            val uploads = h.servers.stored().size

            h.base.seam.feeProbe = { FeeProbe.Built(Zatoshi(20_000L), usesOrchardInputs = false, transactionCount = 1) }
            assertTrue(vm.retryMessage(h.peer, row.id), "Retry on the refused photo did nothing")
            h.awaitSettled(vm)

            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT_RETRY", total = 22_000L)
            assertEquals(1, h.proposals(), "the Retry proposed before Pay")
            h.pay(vm)
            assertEquals(uploads, h.servers.stored().size, "the Retry uploaded the photo again")
            assertEquals(2, h.proposals(), "the Retry was not sent once after Pay")
            assertEquals(1, h.submits(), "the Retry was not paid once at its quote")
            assertEquals(emptyList(), h.fileLinks(), "the Retry went over the relays")
        }

    @Test
    fun `A-18 a never-submitted Shielded link row from an older build asks the exact question before it is sent again`() =
        runBlocking<Unit> {
            val h = Harness(18)
            val vm = h.viewModel()
            val id = "pending_${System.nanoTime()}"
            h.seedFailedLinkRow(vm, id)

            assertTrue(vm.retryMessage(h.peer, id), "Retry on the stored link did nothing")
            h.awaitSettled(vm)

            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT_RETRY", total = ONE_MEMO_TOTAL)
            assertEquals(0, h.proposals(), "the stored link was sent before the question was answered")
            h.pay(vm)
            assertEquals(1, h.proposals(), "the stored link was not sent once after Pay")
            assertEquals(emptyList(), h.servers.puts(), "the stored link was uploaded again")
            assertEquals(emptyList(), h.fileLinks(), "the stored link went over the relays")
        }

    // ------------------------------------------------------------------ A-4, A-5, A-6, A-7, A-8

    @Test
    fun `A-4 Cancel uploads and pays nothing and leaves a FAILED row with its retry copy`() =
        runBlocking<Unit> {
            val h = Harness(4)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)

            vm.dismissPaidAction()
            h.awaitSettled(vm)

            h.assertNothingWentOut()
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("the cancelled photo left no row")
            assertEquals(MessageStatus.FAILED, row.status, "the cancelled photo is not FAILED")
            assertTrue(h.retryCopyKept(vm, row.id), "the cancelled photo lost what its Retry sends")
            assertTrue(h.tracker(vm).tryStart(), "the upload slot was not freed")
        }

    @Test
    fun `A-5 a wallet that cannot pay uploads nothing and shows the empty-wallet notice with a resource-backed reason`() =
        runBlocking<Unit> {
            val h = Harness(5)
            h.base.seam.feeProbe = { FeeProbe.Short }
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertNothingWentOut()
            val state = HandshakeHarness.sendMessageState(vm)
            assertEquals("ShieldedAttachmentNeedsFunds", state.javaClass.simpleName, "no empty-wallet notice, state $state")
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("the refused photo left no row")
            assertEquals(MessageStatus.FAILED, row.status)
            assertEquals(copy("ATTACHMENT_NO_FUNDS_ROW"), row.failureReason, "the row's reason is not the resource-backed text")
            assertNotNull(ChatSendCopy.localizedResource(row.failureReason!!), "the row's reason has no string resource")
        }

    @Test
    fun `A-6 with no relay key the photo is asked about the same way`() =
        runBlocking<Unit> {
            val h = Harness(6, relayKnown = false)
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.assertNothingWentOut()
        }

    @Test
    fun `A-6 with the relay publisher down the photo is asked about the same way`() =
        runBlocking<Unit> {
            val h = Harness(61)
            val vm = h.viewModel()
            NostrChatBridge.unregisterPublisher()

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.assertNothingWentOut()
        }

    @Test
    fun `A-7 a file asks first and Pay sends its link on-chain only`() =
        runBlocking<Unit> {
            val h = Harness(7)
            val vm = h.viewModel()
            h.pickFile(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.assertNothingWentOut()

            h.pay(vm)

            assertEquals(1, h.proposals(), "the file's link was not sent on-chain once")
            assertEquals(emptyList(), h.fileLinks(), "the file's link went over the relays")
        }

    @Test
    fun `A-7 a voice note asks first and Pay sends its link on-chain only`() =
        runBlocking<Unit> {
            val h = Harness(71)
            val vm = h.viewModel()
            h.recordVoice(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.assertNothingWentOut()

            h.pay(vm)

            assertEquals(1, h.proposals(), "the voice note's link was not sent on-chain once")
            assertEquals(emptyList(), h.fileLinks(), "the voice note's link went over the relays")
        }

    @Test
    fun `A-8 the first content message after the key exchange is priced as the two-memo INIT it is sent as`() =
        runBlocking<Unit> {
            val h = Harness(8, init = true)
            val vm = h.viewModel()
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>("u1" + "q".repeat(176)))
            h.pickPhoto(vm)
            h.awaitSettled(vm)

            // Two memo outputs (1,000 + 1,000 dust), the 1,000 ZChat fee and the probed network fee.
            val confirmation = h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = 3_000L + FEE)
            assertEquals(2, number(confirmation, "getMemos"), "the question does not count the INIT's two memos")
            h.pay(vm)

            assertEquals(1, h.proposals(), "the INIT was not sent once")
            assertEquals(2, h.memosOf(0).size, "the INIT was not sent as the two memos it was priced as")
            assertEquals(1, h.submits(), "the INIT was not paid at its quote")
        }

    // ------------------------------------------------------------------ A-10, A-11

    @Test
    fun `A-10 a photo queued behind a running send carries its quote and a different fee is refused`() =
        runBlocking<Unit> {
            val h = Harness(10)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            val hold = h.servers.holdNextUpload()

            vm.confirmPaidAction()
            h.awaitUploadStarted()
            AutoSpendVm.setField(vm, "_sendMessageState", stateFlow(vm).also { it.value = SendMessageState.Sending })
            hold.countDown()
            h.awaitTrue { h.outgoingRows(vm).any { it.id.startsWith("pending_") } }
            main.scheduler.advanceUntilIdle()
            assertEquals(0, h.proposals(), "the queued photo was sent while another send was running")

            h.base.seam.proposalFee = 20_000L
            stateFlow(vm).value = SendMessageState.Idle
            ChatViewModel::class.java
                .getDeclaredMethod("processNextQueuedMessage")
                .apply { isAccessible = true }
                .invoke(vm)
            h.awaitSettled(vm)

            assertEquals(0, h.submits(), "the queued photo paid a fee it never showed")
            val row = h.outgoingRows(vm).single { it.id.startsWith("pending_") }
            assertEquals(MessageStatus.FAILED, row.status, "the queued photo with another fee is not FAILED")
            assertEquals(copy("ATTACHMENT_PRICE_CHANGED"), row.failureReason)
        }

    @Test
    fun `A-11 a FAILED relay row of a link in a chat that is Shielded now is asked about and sent on-chain once`() =
        runBlocking<Unit> {
            val h = Harness(11)
            val vm = h.viewModel()
            val id = "nostr-out-${System.nanoTime()}"
            h.seedFailedLinkRow(vm, id)

            assertTrue(vm.retryMessage(h.peer, id), "Retry on the relay link row did nothing")
            h.awaitSettled(vm)

            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT_RETRY", total = ONE_MEMO_TOTAL)
            assertEquals(emptyList(), h.fileLinks(), "the Shielded link was published over the relays")
            assertFalse(
                (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message == ChatSendCopy.RETRY_MODE_CHANGED,
                "the Shielded link row was refused as a changed mode",
            )
            h.pay(vm)
            assertEquals(1, h.proposals(), "the link was not sent on-chain once after Pay")
            assertEquals(emptyList(), h.fileLinks(), "the Shielded link was published over the relays")
            assertEquals(emptyList(), h.servers.puts(), "the stored link was uploaded again")
            assertTrue(h.outgoingRows(vm).none { it.status == MessageStatus.FAILED }, "a FAILED row is left: ${h.outgoingRows(vm)}")
        }

    // ------------------------------------------------------------------ A-14 (R-A4)

    @Test
    fun `A-14 a question replaced by another one reads as Cancel and a late Pay uploads nothing`() =
        runBlocking<Unit> {
            val h = Harness(14)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            val photo = h.outgoingRows(vm).single()

            // A long text whose own on-chain question replaces the photo's.
            vm.sendMessage(h.peer, "synthetic long words ".repeat(60))
            h.awaitSettled(vm)
            vm.dismissPaidAction()
            h.awaitSettled(vm)
            vm.confirmPaidAction()
            h.awaitSettled(vm)

            h.assertNothingWentOut()
            val row = h.outgoingRows(vm).singleOrNull { it.id == photo.id } ?: fail("the replaced photo left no row")
            assertEquals(MessageStatus.FAILED, row.status, "the photo whose question was replaced is not FAILED")
            assertTrue(h.retryCopyKept(vm, photo.id), "the replaced photo lost its retry copy")
            assertTrue(h.tracker(vm).tryStart(), "the upload slot is still held by the replaced photo")
        }

    @Test
    fun `A-14 leaving the chat with the question open reads as Cancel`() =
        runBlocking<Unit> {
            val h = Harness(141)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            val photo = h.outgoingRows(vm).single()

            vm.viewModelScope.cancel()
            main.scheduler.advanceUntilIdle()
            vm.confirmPaidAction()
            main.scheduler.advanceUntilIdle()

            h.assertNothingWentOut()
            val row = h.outgoingRows(vm).singleOrNull { it.id == photo.id } ?: fail("the photo left no row")
            assertEquals(MessageStatus.FAILED, row.status, "the photo of a chat left with its question open is not FAILED")
            assertTrue(h.retryCopyKept(vm, photo.id), "the photo lost its retry copy")
            assertTrue(h.tracker(vm).tryStart(), "the upload slot is still held")
        }

    // ------------------------------------------------------------------ A-15 (R-A3, owner W-18 Q20 "quote both")

    @Test
    fun `A-15 a key exchange still owed is quoted with the link as one exact total and nothing else is paid`() =
        runBlocking<Unit> {
            val h = Harness(15)
            val vm = h.viewModel()
            h.owe(vm)

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            val confirmation = h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = 2 * ONE_MEMO_TOTAL)
            assertEquals(2, confirmation.onChainMessages, "the question does not count the owed key exchange")
            h.assertNothingWentOut()
            h.pay(vm)
            assertEquals(2, h.proposals(), "the owed key exchange and the link were not sent once each")
            assertEquals(2, h.submits(), "more or fewer than the two quoted transactions were paid")
            assertEquals(emptyList(), h.fileLinks(), "the link went over the relays")
        }

    // ------------------------------------------------------------------ A-16, A-19 (R-A5)

    @Test
    fun `A-16 Short while another send runs and the balance covers the price says still confirming, not no funds`() =
        runBlocking<Unit> {
            val h = Harness(16)
            h.base.seam.feeProbe = { FeeProbe.Short }
            val vm = h.viewModel()
            h.useAccount(vm, ironwood(available = 100_000L))
            stateFlow(vm).value = SendMessageState.Sending

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertNothingWentOut()
            assertEquals(SendMessageState.Error(copy("ATTACHMENT_STILL_CONFIRMING")), HandshakeHarness.sendMessageState(vm))
        }

    @Test
    fun `A-16 Short while our own change confirms says still confirming, not no funds`() =
        runBlocking<Unit> {
            val h = Harness(162)
            h.base.seam.feeProbe = { FeeProbe.Short }
            val vm = h.viewModel()
            h.useAccount(vm, ironwood(available = 0L, changePending = 60_000L))

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertNothingWentOut()
            assertEquals(SendMessageState.Error(copy("ATTACHMENT_STILL_CONFIRMING")), HandshakeHarness.sendMessageState(vm))
            assertEquals(copy("ATTACHMENT_STILL_CONFIRMING"), h.outgoingRows(vm).single().failureReason)
        }

    @Test
    fun `A-19 a price that cannot be worked out uploads nothing - a throw, Orchard inputs or two transactions`() =
        runBlocking<Unit> {
            val probes =
                listOf<() -> FeeProbe>(
                    { throw IllegalStateException("synthetic probe failure") },
                    { FeeProbe.Built(Zatoshi(FEE), usesOrchardInputs = true, transactionCount = 1) },
                    { FeeProbe.Built(Zatoshi(FEE), usesOrchardInputs = false, transactionCount = 2) },
                )
            probes.forEachIndexed { i, probe ->
                val h = Harness(190 + i)
                h.base.seam.feeProbe = probe
                val vm = h.viewModel()

                h.pickPhoto(vm)
                h.awaitSettled(vm)

                h.assertNothingWentOut()
                assertEquals(
                    SendMessageState.Error(copy("ATTACHMENT_PRICE_UNAVAILABLE")),
                    HandshakeHarness.sendMessageState(vm),
                    "probe case $i",
                )
                assertEquals(MessageStatus.FAILED, h.outgoingRows(vm).single().status, "probe case $i")
            }
        }

    /**
     * ZC-782 (review of 12 Oct, finding 2): in the update-required state the price function throws the update refusal,
     * and the attachment door showed ATTACHMENT_PRICE_UNAVAILABLE ("Tap Retry in a moment"). Wanted: the update
     * sentence on the screen and on the row; nothing uploaded, priced or paid.
     */
    @Test
    fun `ZC-782 a Shielded photo in the update-required state shows the update sentence and uploads nothing`() =
        runBlocking<Unit> {
            val updateSentence = co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException.MESSAGE
            val h = Harness(782)
            val vm = h.viewModel()
            co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard.onSubmitResult(
                co.electriccoin.zcash.ui.common.model.SubmitResult
                    .Failure(emptyList(), 0, "incorrect consensus branch id"),
            )
            try {
                h.pickPhoto(vm)
                h.awaitSettled(vm)

                h.assertNothingWentOut()
                assertEquals(0, h.probes(), "the SDK was asked for a price in the update-required state")
                assertEquals(SendMessageState.Error(updateSentence), HandshakeHarness.sendMessageState(vm))
                val row = h.outgoingRows(vm).single()
                assertEquals(MessageStatus.FAILED, row.status)
                assertEquals(updateSentence, ChatSendCopy.failureReasonFor(row), "the row's reason is the update sentence")
            } finally {
                co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
                    .resetIfPresent()
            }
        }

    // ------------------------------------------------------------------ A-17 (R-A1)

    @Test
    fun `A-17 a host whose url would change the quoted memo count is skipped and the next host is used`() =
        runBlocking<Unit> {
            val h = Harness(17)
            h.servers.urlFor[ACCEPTING_HOST] = { sha -> "https://x.$ACCEPTING_HOST/" + sha + "/" + "p".repeat(300 - 33 - sha.length) }
            h.servers.accepting += SECOND_HOST
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)

            h.pay(vm)

            assertEquals(1, h.proposals(), "the photo was not sent once")
            assertEquals(1, h.memosOf(0).size, "the link was sent in another memo count than the quote")
            assertEquals(1, h.submits())
            val link = h.outgoingRows(vm).firstNotNullOfOrNull { it.fileZfileContent?.let(ZFILEMessage::parse) }
            assertNotNull(link, "no link row after the send")
            assertTrue(link.url.contains(SECOND_HOST), "the long url was used: ${link.url.take(40)}")
        }

    // ------------------------------------------------------------------ A-20, A-21 (R-A4)

    @Test
    fun `A-20 a switch to a Keystone account while the question is open uploads and pays nothing`() =
        runBlocking<Unit> {
            val h = Harness(20)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)

            h.useAccount(vm, keystoneAccount())
            h.pay(vm)

            h.assertNothingWentOut()
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_CHATS_CLOSED), HandshakeHarness.sendMessageState(vm))
            assertEquals(MessageStatus.FAILED, h.outgoingRows(vm).single().status)
        }

    @Test
    fun `A-20 a photo picked on a Keystone account is refused before the upload`() =
        runBlocking<Unit> {
            val h = Harness(201)
            val vm = h.viewModel()
            h.useAccount(vm, keystoneAccount())

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            h.assertNothingWentOut()
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_CHATS_CLOSED), HandshakeHarness.sendMessageState(vm))
        }

    @Test
    fun `A-21 a key change while the question is open uploads and pays nothing`() =
        runBlocking<Unit> {
            val h = Harness(21)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)

            h.base.keyChanged = true
            h.pay(vm)

            h.assertNothingWentOut()
            assertEquals(MessageStatus.FAILED, h.outgoingRows(vm).single().status)
        }

    @Test
    fun `A-21 a new shared key that no longer opens the wrapped file key uploads and pays nothing`() =
        runBlocking<Unit> {
            val h = Harness(211)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)

            h.replacePeerKey()
            h.pay(vm)

            h.assertNothingWentOut()
            assertEquals(MessageStatus.FAILED, h.outgoingRows(vm).single().status)
        }

    // ------------------------------------------------------------------ SR-A × SR-C1, SR-C2 on T4c's landed code

    @Test
    fun `C1-6 a contact on an older ZChat is warned first, Cancel probes nothing and Send anyway reaches the question`() =
        runBlocking<Unit> {
            val h = Harness(161, legacy = true)
            val vm = h.viewModel()
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            assertEquals(WARNING, HandshakeHarness.sendMessageState(vm).javaClass.simpleName)

            vm.cancelLegacyContactAttachment()
            h.awaitSettled(vm)
            h.assertNothingWentOut()
            assertEquals(0, h.probes(), "the price was asked after Cancel on the older-ZChat warning")

            h.pickPhoto(vm)
            h.awaitSettled(vm)
            assertEquals(WARNING, HandshakeHarness.sendMessageState(vm).javaClass.simpleName)
            vm.confirmLegacyContactAttachment()
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = ONE_MEMO_TOTAL)
            h.assertNothingWentOut()
        }

    @Test
    fun `C2-6 a Shielded chat with no key exchange shows the Shielded notice, never the price question`() =
        runBlocking<Unit> {
            val h = Harness(26, haveKeys = false)
            val vm = h.viewModel()

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            assertEquals(
                SendMessageState.AttachmentNeedsKeyExchange(AttachmentBlockReason.NEEDS_KEY_EXCHANGE_SHIELDED),
                HandshakeHarness.sendMessageState(vm),
            )
            h.assertNothingWentOut()
            assertEquals(0, h.probes(), "a chat with no key exchange was priced")
            assertEquals(emptyList(), h.outgoingRows(vm), "a row was left")
        }

    // ------------------------------------------------------------------ X-04 (T5b cross-review)

    /**
     * T5b cross-review X-04 (found by GPT): sendAttachmentBlob read the chat's mode once, before the upload. A chat
     * switched to Shielded while the photo uploads sent its link through the unquoted path: on-chain, paid, with no
     * exact price question. Wanted: nothing is paid without the question; the row is FAILED with its retry copy and
     * a reason that says so, and Retry asks the exact question.
     */
    @Test
    fun `X-04 a chat switched to Shielded during the upload pays nothing without the exact question, and Retry asks it`() =
        runBlocking<Unit> {
            val h = Harness(604, mode = ConversationMode.TUNNEL)
            val vm = h.viewModel()
            val hold = h.servers.holdNextUpload()

            h.pickPhoto(vm)
            h.awaitUploadStarted()
            assertFalse(
                HandshakeHarness.sendMessageState(vm).javaClass.simpleName == QUESTION,
                "fixture sanity: a Tunnel photo was asked about",
            )
            // The user switches the chat to Shielded while the photo is still uploading.
            h.base.mode = ConversationMode.VAULT
            hold.countDown()
            h.awaitTrue { h.servers.stored().isNotEmpty() }
            h.awaitSettled(vm)

            assertEquals(0, h.proposals(), "the link was proposed on-chain with no exact price question")
            assertEquals(0, h.submits(), "the link was paid on-chain with no exact price question")
            assertEquals(emptyList(), h.fileLinks(), "the link of a chat that is Shielded now went over the relays")
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("the photo left no single row: ${h.outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, row.status, "the photo of a chat switched to Shielded is not FAILED")
            assertEquals(copy("ATTACHMENT_SWITCHED_TO_SHIELDED"), row.failureReason, "the row does not say why it was not sent")
            assertTrue(h.retryCopyKept(vm, row.id), "the photo lost its retry copy")
            assertTrue(h.tracker(vm).tryStart(), "the upload slot is still held")
            h.tracker(vm).reset()

            assertTrue(vm.retryMessage(h.peer, row.id), "Retry on the photo did nothing")
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT_RETRY", total = ONE_MEMO_TOTAL)
            assertEquals(0, h.proposals(), "the Retry proposed before Pay")
        }

    // ------------------------------------------------------------------ X-06 (T5b cross-review)

    /**
     * T5b cross-review X-06 (found by Grok and GPT): in a quoted "key exchange + link" send the key exchange is paid
     * first; when the link's transaction then differs from its quote the link is refused, and the row said "Nothing
     * was paid" and its Retry question "The earlier try was not paid". Wanted: neither text denies the paid key
     * exchange; the row and the Retry question say that the key exchange was paid and the attachment was not.
     */
    @Test
    fun `X-06 a link refused after its key exchange was paid never says nothing was paid, on the row or at Retry`() =
        runBlocking<Unit> {
            val h = Harness(606)
            val vm = h.viewModel()
            h.owe(vm)
            h.pickPhoto(vm)
            h.awaitSettled(vm)
            h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT", total = 2 * ONE_MEMO_TOTAL)
            // The key exchange is paid at its quote; the link's transaction then carries another fee than quoted.
            h.afterEverySubmit { h.base.seam.proposalFee = 20_000L }

            h.pay(vm)

            assertEquals(1, h.submits(), "fixture sanity: the key exchange alone was to be paid")
            val row = h.outgoingRows(vm).singleOrNull() ?: fail("the refused photo left no single row: ${h.outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, row.status, "the refused photo is not FAILED")
            val reason = row.failureReason ?: fail("the refused photo has no reason")
            assertFalse("Nothing was paid" in reason, "the key exchange was paid, and the row says: $reason")
            assertEquals(copy("ATTACHMENT_PRICE_CHANGED_KEX_PAID"), reason, "the row does not say what was paid and what was not")
            val notice = (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message.orEmpty()
            assertFalse("Nothing was paid" in notice, "the key exchange was paid, and the notice says: $notice")

            h.base.seam.feeProbe = { FeeProbe.Built(Zatoshi(20_000L), usesOrchardInputs = false, transactionCount = 1) }
            assertTrue(vm.retryMessage(h.peer, row.id), "Retry on the refused photo did nothing")
            h.awaitSettled(vm)

            val confirmation = h.assertExactQuestion(vm, "SHIELDED_ATTACHMENT_RETRY", total = 22_000L)
            assertEquals(1, confirmation.onChainMessages, "the Retry quotes the paid key exchange again")
            assertEquals(
                "paid_action_shielded_attachment_retry_kex_paid",
                ChatResourceText.stringName(PaidActionCopy.actionText(confirmation)),
                "the Retry question says the earlier try was not paid although its key exchange was",
            )
            h.pay(vm)
            assertEquals(2, h.submits(), "the Retry did not pay the link once")
        }

    // ------------------------------------------------------------------ controls

    @Test
    fun `A-12 control - a photo in a Tunnel chat goes over the relays, free, with no question`() =
        runBlocking { assertFreeOverRelays(ConversationMode.TUNNEL, 12) }

    @Test
    fun `A-12 control - a photo in an Open chat goes over the relays, free, with no question`() =
        runBlocking { assertFreeOverRelays(ConversationMode.OPEN, 121) }

    @Test
    fun `A-13 control - a Shielded link on a Keystone account is neither published nor paid`() =
        runBlocking<Unit> {
            val h = Harness(13)
            val vm = h.viewModel()
            h.useAccount(vm, keystoneAccount())

            h.pickPhoto(vm)
            h.awaitSettled(vm)

            assertEquals(emptyList(), h.fileLinks(), "a Shielded link went over the relays on a Keystone account")
            assertEquals(0, h.proposals(), "a Shielded link was paid on a Keystone account")
        }

    private suspend fun assertFreeOverRelays(
        mode: ConversationMode,
        n: Int,
    ) {
        val h = Harness(n, mode = mode)
        val vm = h.viewModel()

        h.pickPhoto(vm)
        h.awaitSettled(vm)

        assertFalse(HandshakeHarness.sendMessageState(vm).javaClass.simpleName == QUESTION, "a $mode photo was asked about")
        assertEquals(1, h.servers.stored().size, "the $mode photo was not uploaded once")
        assertEquals(1, h.fileLinks().size, "the $mode photo's link did not go over the relays once")
        assertEquals(0, h.proposals(), "a $mode photo paid something")
    }

    // ------------------------------------------------------------------ shared

    private fun copy(name: String): String =
        try {
            ChatSendCopy::class.java.getField(name).get(null) as String
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no $name", e)
        }

    private fun number(
        target: Any,
        getter: String,
    ): Int =
        try {
            target.javaClass.getMethod(getter).invoke(target) as Int
        } catch (e: NoSuchMethodException) {
            throw AssertionError("${target.javaClass.simpleName} has no ${getter.removePrefix("get").lowercase()}", e)
        }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(vm: ChatViewModel): MutableStateFlow<SendMessageState> =
        ChatViewModel::class.java
            .getDeclaredField("_sendMessageState")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<SendMessageState>

    private suspend fun ironwood(
        available: Long,
        changePending: Long = 0L,
    ): WalletAccount {
        val base = syntheticZashiAccount()
        return base.copy(
            unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(available), Zatoshi(changePending), Zatoshi(0))),
        )
    }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-sra-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-sra-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }

    /** The selected account the ViewModel reads, switchable while a question is open. */
    private class SwitchableAccounts(
        initial: WalletAccount,
    ) : AccountDataSource {
        override val allAccounts = MutableStateFlow<List<WalletAccount>?>(listOf(initial))
        private val current: WalletAccount get() = allAccounts.value!!.first()
        override val selectedAccount: Flow<WalletAccount?> get() = flowOf(current)
        override val zashiAccount: Flow<ZashiAccount?> get() = flowOf(current as? ZashiAccount)

        override suspend fun getAllAccounts(): List<WalletAccount> = allAccounts.value!!

        override suspend fun getSelectedAccount(): WalletAccount = current

        override suspend fun getZashiAccount(): ZashiAccount = current as? ZashiAccount ?: error("not a ZChat account")

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used")

        override suspend fun requestNextShieldedAddress() = Unit
    }

    private inner class Harness(
        val n: Int,
        val mode: ConversationMode = ConversationMode.VAULT,
        val relayKnown: Boolean = true,
        val legacy: Boolean = false,
        val init: Boolean = false,
        haveKeys: Boolean = true,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(6_100 + n)
        val base = PaidChatFixture(peer, mode, haveOurKey = haveKeys)
        private val peerRelayKey = "%064x".format(n.toLong() * 0x9e3779b1L + 0x7654321L)
        val servers = FakeServers()
        val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val cache: File = Files.createTempDirectory("sr-a-cache").toFile().apply { deleteOnExit() }
        val context: Context =
            object : ContextWrapper(null) {
                override fun getCacheDir(): File = cache
            }
        private var accounts: SwitchableAccounts? = null

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when {
                    method.name == "getPeerNostrPubkey" && args?.get(0) == peer -> if (relayKnown) peerRelayKey else null
                    method.name == "findPeerByNostrPubkey" -> if (args?.get(0) == peerRelayKey) peer else null
                    method.name == "getPeerIronwoodCap" && args?.get(0) == peer ->
                        if (legacy) ZchatPreferences.PeerIronwoodCap(false, Instant.now().epochSecond - 60) else null
                    method.name == "needsConversationInit" -> init
                    method.name == "getOrCreateConversationIdForMessage" -> PaidChatFixture.CONV_ID to init
                    // FC-ID (ZC-892): our KEX is built only under an own-made id; this fixture's id is that id.
                    method.name == "ownConversationId" || method.name == "ownConversationIdForStart" -> PaidChatFixture.CONV_ID
                    method.name == "isKexOwed" -> args!![0] in kexOwed
                    method.name == "getKexOwedPeers" -> HashSet(kexOwed)
                    method.name == "setKexOwed" -> {
                        if (args!![1] as Boolean) kexOwed += args[0] as String else kexOwed -= args[0] as String
                        true
                    }
                    method.name == "getKexOwedAccount" -> null
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
            val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
            processors["$peer:${PaidChatFixture.CONV_ID}"] =
                E2EMessageProcessor(ByteArray(32) { 7 }, PaidChatFixture.CONV_ID, true, InMemoryRatchetStateStore())
            AutoSpendVm.setField(vm, "messageProcessors", processors)
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
            // A declined text question hands its text back to the input (A-14's second question).
            AutoSpendVm.setField(vm, "_restoredTypedText", MutableStateFlow<Any?>(null))
            servers.online = true
            return vm
        }

        /** X-06: [action] runs at every submit (each one is paid), before it is counted. */
        fun afterEverySubmit(action: () -> Unit) {
            base.seam.javaClass
                .getDeclaredField("onSubmit")
                .apply { isAccessible = true }
                .set(base.seam, action)
        }

        /** The selected account becomes [account] (the ViewModel reads it on every check). */
        fun useAccount(
            vm: ChatViewModel,
            account: WalletAccount,
        ) {
            val switchable = accounts ?: SwitchableAccounts(account).also { accounts = it }
            switchable.allAccounts.value = listOf(account)
            AutoSpendVm.setField(vm, "accountDataSource", switchable)
        }

        /** Our key exchange to the contact is still owed by the selected account (the keys are held). */
        fun owe(vm: ChatViewModel) {
            kexOwed += peer
            val field = ChatViewModel::class.java.declaredFields.first { it.name == "kexOwedPeers" }
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(if (Modifier.isStatic(field.modifiers)) null else vm) as MutableSet<String>) += peer
        }

        /** The contact's key changes silently (no flag): the shared key the file key was wrapped with is gone. */
        fun replacePeerKey() {
            val field = PaidChatFixture::class.java.getDeclaredField("peerKeys").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(base) as MutableMap<String, String>)[peer] = E2EEncryption.generateKeyPair().publicKey
        }

        /** A FAILED Shielded link row [id] whose submit never started, its file key wrapped with today's shared key. */
        fun seedFailedLinkRow(
            vm: ChatViewModel,
            id: String,
        ) {
            val shared = vm.getE2ESharedKey(peer) ?: fail("fixture sanity: no shared key")
            val wrapped = Base64.getEncoder().encodeToString(E2EEncryption.wrapFileKey(E2EEncryption.generateFileKey(), shared))
            val sha = "%064x".format(n.toLong() * 0x51ed27L + 0xabcdefL)
            val line =
                ZFILEMessage(
                    hash = sha.take(32),
                    type = ZFILEType.JPEG,
                    size = 4_096L,
                    url = "https://$ACCEPTING_HOST/$sha",
                    wrappedKey = wrapped,
                    blurhash = "",
                ).serialize()
            val at = Instant.now().minusSeconds(120)
            base.proxy.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = id,
                    text = line,
                    timestampMillis = at.toEpochMilli(),
                    peerAddress = peer,
                    isPending = false,
                    status = MessageStatus.FAILED.name,
                    sendAmountZatoshi = 1_000L,
                    submitTracked = id.startsWith("pending_"),
                ),
            )
            pending(vm).value =
                listOf(
                    ChatMessage(
                        id = id,
                        txId = null,
                        text = "📷 Photo",
                        timestamp = at,
                        isOutgoing = true,
                        peerAddress = peer,
                        isPending = false,
                        status = MessageStatus.FAILED,
                        fileHash = sha.take(32),
                        fileZfileContent = line,
                        sendAmountZatoshi = 1_000L,
                    ),
                )
        }

        fun proposals(): Int = base.seam.proposalRequests.get()

        /** Submits the SDK was asked for (each one is paid). */
        fun submits(): Int = base.seam.submits.get()

        /** Price probes asked of the SDK (nothing is stored or paid by one). */
        fun probes(): Int = base.seam.probes.get()

        /** The memos of proposal [index], decoded from its ZIP 321 request (the platform-fee output carries none). */
        fun memosOf(index: Int): List<String> {
            val uri = synchronized(base.seam.proposalUris) { base.seam.proposalUris.getOrNull(index) } ?: fail("no proposal $index")
            return uri
                .substringAfter('?')
                .split('&')
                .filter { it.substringBefore('=').let { k -> k == "memo" || k.startsWith("memo.") } }
                .map { String(Base64.getUrlDecoder().decode(it.substringAfter('=')), Charsets.UTF_8) }
        }

        /** The ZFILE links that went over the relays. */
        fun fileLinks(): List<String> = synchronized(publishes) { publishes.filter { it.contains("ZFILE|") } }

        fun assertNothingWentOut() {
            assertEquals(emptyList(), servers.puts().map { it.host }, "something was uploaded")
            assertEquals(emptyList(), fileLinks(), "an attachment link went over the relays")
            assertEquals(0, proposals(), "something was proposed on-chain")
        }

        /** The exact price question for [action] with [total] zatoshi; returns its confirmation. */
        fun assertExactQuestion(
            vm: ChatViewModel,
            action: String,
            total: Long,
        ): PaidActionConfirmation {
            val state = HandshakeHarness.sendMessageState(vm)
            val asked = state as? SendMessageState.NeedsPaidActionConfirmation ?: fail("no price question before the upload, state $state")
            val confirmation = asked.confirmation
            assertEquals(action, confirmation.action.name, "the question is not the Shielded attachment's")
            assertEquals(total, confirmation.totalCost.value, "the question's total is not the exact price")
            val exact =
                try {
                    confirmation.javaClass.getMethod("getExact").invoke(confirmation) as Boolean
                } catch (e: NoSuchMethodException) {
                    throw AssertionError("PaidActionConfirmation has no exact flag", e)
                }
            assertTrue(exact, "the question quotes a floor ('at least'), not the exact price")
            return confirmation
        }

        fun pay(vm: ChatViewModel) {
            vm.confirmPaidAction()
            runBlocking { awaitSettled(vm) }
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
            val prepare: () -> ByteArray? = { plaintext("synthetic photo $n ${System.nanoTime()}") }
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

        suspend fun awaitUploadStarted() {
            awaitTrue { servers.puts().isNotEmpty() }
        }

        suspend fun awaitTrue(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 20_000
            while (!condition()) {
                pumpMain()
                if (System.currentTimeMillis() > deadline) fail("the condition was never met")
                delay(5)
            }
        }

        /**
         * Runs the view model's coroutines until nothing is left to run, or a question or warning is on screen (the
         * attachment then waits for the answer).
         */
        suspend fun awaitSettled(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                pumpMain()
                val shown = HandshakeHarness.sendMessageState(vm).javaClass.simpleName
                if (shown == QUESTION || shown == WARNING) return
                if (vm.viewModelScope.coroutineContext.job.children
                        .none { it.isActive }
                ) {
                    break
                }
                if (System.currentTimeMillis() > deadline) fail("the view model did not settle (state $shown)")
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

    /** Offline upload servers: [accepting] hosts store a blob once [online] (its url from [urlFor]); others fail. */
    private class FakeServers {
        data class Put(
            val host: String,
            val sha256: String,
        )

        @Volatile var online = false
        val accepting: MutableSet<String> = Collections.synchronizedSet(mutableSetOf(ACCEPTING_HOST))
        val urlFor: MutableMap<String, (String) -> String> = ConcurrentHashMap()
        private val puts: MutableList<Put> = Collections.synchronizedList(mutableListOf())
        private val stored: MutableList<Put> = Collections.synchronizedList(mutableListOf())

        @Volatile private var hold: CountDownLatch? = null

        fun puts(): List<Put> = synchronized(puts) { puts.toList() }

        /** The blobs a host really stored. */
        fun stored(): List<Put> = synchronized(stored) { stored.toList() }

        /** The next upload waits until the returned latch is counted down. */
        fun holdNextUpload(): CountDownLatch = CountDownLatch(1).also { hold = it }

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                if (request.method == "PUT") puts += Put(host, sha)
                hold?.let {
                    hold = null
                    it.await(20, TimeUnit.SECONDS)
                }
                if (!online || host !in accepting || request.method != "PUT") throw java.net.UnknownHostException(host)
                stored += Put(host, sha)
                val url = urlFor[host]?.invoke(sha) ?: "https://$host/$sha"
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"url":"$url","sha256":"$sha","size":${body.size},"type":"application/octet-stream"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ).build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }
}
