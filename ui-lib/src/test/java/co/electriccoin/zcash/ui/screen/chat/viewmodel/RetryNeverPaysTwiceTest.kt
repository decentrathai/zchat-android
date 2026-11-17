package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.firstOutputAmount
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.zec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * P2a — Retry never pays twice.
 *
 * A message whose on-chain submit started (the transaction may be stored and broadcast) reloads as FAILED
 * with a Retry button when the app is closed before the result arrives, and a send the node rejected after
 * the SDK stored it shows FAILED in the same session. At 6add5e024 Retry re-sends both at the chosen amount,
 * so one tap pays the full amount a second time while the first transaction can still be mined.
 *
 * Wanted: such a message is re-sent only once the wallet has proven it did not go out — the wallet has
 * complete original-attempt evidence establishes that no component can still pay — and
 * until then the chat says it may already have been sent. A send that never reached the submit step still
 * retries at the chosen amount (RetryKeepsChosenAmountTest), and Retry on a reply keeps the reply link.
 *
 * Drives the real sendMessage / sendReply / retryMessage through the real use case and repository; only the
 * preferences store, the settlement seam and the wallet's transaction list and scan height are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryNeverPaysTwiceTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticretrytwicepeer0001"
        const val OTHER_PEER = "usyntheticretrytwiceother001"
        const val CHOSEN = 50_000L // 0.0005 ZEC, one of the composer's amounts
        const val ATTEMPT_HEIGHT = 3_000_000L
        const val PAST_EXPIRY = ATTEMPT_HEIGHT + 100
        const val MAY_HAVE_BEEN_SENT = "may already have been sent"
        const val MESSAGE_MEMO = "ZMSG|v4|PAYA2345|synthetic-ciphertext"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a stale queued actor cannot adopt a retry owners marker by reloading preferences`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val original = paymentViewModel(prefs, seam)
            val text = "synthetic stale queued owner"
            // Produce a real never-started queued row. Its original actor has no submit owner.
            PaymentHarness.setSendMessageState(original, SendMessageState.Sending)
            assertTrue(original.sendMessage(PEER, text, CHOSEN))
            val queuedId =
                prefs.pending.values
                    .single()
                    .id
            assertEquals(0, seam.submits.get())

            // ZC-1071: a queued row is live while its screen is (LoaderNeverFailsALiveSendTest); this keeps the stale actor's
            // case, a second screen that saw the row FAILED, by emptying the live-sender registry first.
            LiveSenders.resetForTest()
            val retryOwner = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(retryOwner)
            assertEquals(MessageStatus.FAILED, PaymentHarness.pendingMessages(retryOwner).single().status)
            assertTrue(retryOwner.retryMessage(PEER, queuedId))
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            try {
                val marker = durableAttemptMarker(prefs.pending.getValue(queuedId))
                assertTrue("retry must hold a durable submit marker", marker != null)
                // The old actor is still the originally queued command. A UI/store reload must NOT
                // transfer ownership to it merely because the displayed row now carries the new marker.
                PaymentHarness.loadPendingMessagesFromPrefs(original)
                // Model that original command being dequeued; don't leave a second copy in the fixture.
                PaymentHarness.setField(original, "messageQueue", mutableListOf<Any>())
                invokeOriginalQueuedActor(original, queuedId, text)
                awaitIdle(original)

                assertEquals("the stale original actor reached SDK settlement after adopting a mutable marker", 1, seam.submits.get())
                assertEquals(
                    "the stale actor changed the other attempts marker",
                    marker,
                    durableAttemptMarker(prefs.pending.getValue(queuedId))
                )
            } finally {
                finish(seam)
                awaitIdle(retryOwner, original)
            }
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a stale queued actors pre-submit failure cannot clear the active retry owners marker`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val staleSeam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic stale pre-submit failure"))
            val retrySeam = PaymentSeam(PEER, holdFirstSubmit = true)
            val original = paymentViewModel(prefs, staleSeam)
            val text = "synthetic stale failure owner"
            PaymentHarness.setSendMessageState(original, SendMessageState.Sending)
            assertTrue(original.sendMessage(PEER, text, CHOSEN))
            val queuedId =
                prefs.pending.values
                    .single()
                    .id

            // ZC-1071: a queued row is live while its screen is (LoaderNeverFailsALiveSendTest); this keeps the stale actor's
            // case, a second screen that saw the row FAILED, by emptying the live-sender registry first.
            LiveSenders.resetForTest()
            val retryOwner = paymentViewModel(prefs, retrySeam)
            PaymentHarness.loadPendingMessagesFromPrefs(retryOwner)
            assertTrue(retryOwner.retryMessage(PEER, queuedId))
            withTimeout(PaymentHarness.TIMEOUT_MS) { retrySeam.firstSubmitEntered.await() }
            try {
                val marker = durableAttemptMarker(prefs.pending.getValue(queuedId))
                assertTrue("retry must hold a durable submit marker", marker != null)
                PaymentHarness.loadPendingMessagesFromPrefs(original)
                PaymentHarness.setField(original, "messageQueue", mutableListOf<Any>())
                invokeOriginalQueuedActor(original, queuedId, text)
                awaitIdle(original)

                assertEquals("stale actor must not reach settlement", 0, staleSeam.submits.get())
                assertEquals(
                    "a failed stale actor cleared another attempts durable ownership",
                    marker,
                    durableAttemptMarker(prefs.pending.getValue(queuedId))
                )
                val reopened = paymentViewModel(prefs, retrySeam)
                PaymentHarness.loadPendingMessagesFromPrefs(reopened)
                reopened.retryMessage(PEER, queuedId)
                awaitIdle(reopened)
                assertEquals("clearing the marker authorized another payment", 1, retrySeam.submits.get())
                assertMayHaveBeenSent(reopened, queuedId)
            } finally {
                finish(retrySeam)
                awaitIdle(retryOwner, original)
            }
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    /**
     * Invoke the ORIGINAL queued command with its immutable null owner, never the row's current marker.
     * The unfixed entry point has five arguments; the fixed entry point adds the immutable owner.
     * Passing null explicitly preserves the original queued command ownership.
     */
    private fun invokeOriginalQueuedActor(vm: ChatViewModel, id: String, text: String) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single {
                    it.name == "doSendMessage" && it.parameterCount in 5..6
                }.apply { isAccessible = true }
        val args = mutableListOf<Any?>(PEER, text, CHOSEN, id, 0)
        if (method.parameterCount == 6) args.add(null)
        method.invoke(vm, *args.toTypedArray())
    }

    /** Read the persisted marker without a compile-time dependency on the new product property. */
    private fun durableAttemptMarker(row: ZchatPreferences.PendingMessageData): Long? =
        row.javaClass.getMethod("getSubmitStartedAtMillis").invoke(row) as Long?

    @Test
    fun `an old mined same-text transaction cannot erase a newly queued message before submit`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            val text = "synthetic repeated queued text"
            PaymentHarness.setSendMessageState(vm, SendMessageState.Sending)
            assertTrue(vm.sendMessage(PEER, text, CHOSEN))
            val id =
                prefs.pending.values
                    .single()
                    .id
            assertEquals(0, seam.submits.get())

            val rendered = reconcilePaidPending(vm, prefs, text, mined = true)

            assertTrue("history erased a NEW never-submitted durable row", prefs.pending.containsKey(id))
            assertTrue("the new queued bubble disappeared", rendered.any { it.id == id })
            assertEquals(text, prefs.pending.getValue(id).text)
            // Execute the queued command once, as processNextQueuedMessage would. The previous
            // regression removed its durable row, making the SDK-boundary claim fail and lose the send.
            PaymentHarness.setField(vm, "messageQueue", mutableListOf<Any>())
            invokeOriginalQueuedActor(vm, id, text)
            awaitIdle(vm)
            assertEquals("the accepted new payload never reached settlement", 1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `old same-text mined transaction cannot clear another submitted transaction after reload`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val ownId = reconciliationTxId(2)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = SubmitResult.Success(listOf(ownId)))
            val sender = paymentViewModel(prefs, seam)
            val text = "synthetic repeated submitted text"
            assertTrue(sender.sendMessage(PEER, text, CHOSEN))
            awaitIdle(sender)
            val id =
                prefs.pending.values
                    .single()
                    .id
            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)

            // WalletFacts assigns this old transaction ID 1, different from this attempt's ID 2.
            val rendered = reconcilePaidPending(reopened, prefs, text, mined = true)

            assertTrue("same text is not proof that this attempt mined", prefs.pending.containsKey(id))
            assertTrue("the unconfirmed newer attempt must remain visible", rendered.any { it.id == id })
            assertEquals(1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `same-text mined transaction cannot clear a submission whose transaction ids are unknown`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = SubmitResult.SentUnconfirmed(emptyList(), "synthetic unknown settlement"))
            val sender = paymentViewModel(prefs, seam)
            val text = "synthetic unknown submission text"
            assertTrue(sender.sendMessage(PEER, text, CHOSEN))
            awaitIdle(sender)
            val id =
                prefs.pending.values
                    .single()
                    .id
            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)

            val rendered = reconcilePaidPending(reopened, prefs, text, mined = true)

            assertTrue("unknown attempt identity must stay held", prefs.pending.containsKey(id))
            assertTrue(rendered.any { it.id == id })
            reopened.retryMessage(PEER, id)
            awaitIdle(reopened)
            assertEquals("history must not enable another payment", 1, seam.submits.get())
            assertMayHaveBeenSent(reopened, id)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `exact submitted transaction stays durable while unmined and clears only once mined after reload`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val ownId = reconciliationTxId(1)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = SubmitResult.Success(listOf(ownId)))
            val sender = paymentViewModel(prefs, seam)
            val text = "synthetic exact transaction text"
            assertTrue(sender.sendMessage(PEER, text, CHOSEN))
            awaitIdle(sender)
            val id =
                prefs.pending.values
                    .single()
                    .id
            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)

            reconcilePaidPending(reopened, prefs, text, mined = false)
            assertTrue("an unmined observation must not delete durable ownership", prefs.pending.containsKey(id))
            assertTrue(PaymentHarness.pendingMessages(reopened).any { it.id == id })

            val minedRows = reconcilePaidPending(reopened, prefs, text, mined = true)
            assertTrue("exact mined attempt should leave the pending store", !prefs.pending.containsKey(id))
            assertTrue(PaymentHarness.pendingMessages(reopened).none { it.id == id })
            assertEquals("the mined chain row remains visible once", 1, minedRows.count { it.id == ownId })
            assertEquals(1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    private fun reconciliationTxId(n: Int): String = TransactionId.new("%064x".format(n)).txIdString()

    /**
     * Runs the REAL suspend conversion against WalletFacts, including its pending-store deletions.
     * Plaintext legacy memos deliberately avoid crypto/key-recovery side effects: this test is about
     * outgoing transaction identity, which must hold for old plaintext history too.
     */
    private suspend fun reconcilePaidPending(
        vm: ChatViewModel,
        prefs: PaymentPrefs,
        text: String,
        mined: Boolean,
    ): List<co.electriccoin.zcash.ui.screen.chat.model.ChatMessage> {
        val scanPrefs =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getConversationId", "getDisappearingTtl", "getNickname", "clearPendingTxFirstSeen" -> null
                    "isE2ERecoveryFloorPending" -> false
                    // KI-24 fixup G-KI24-2: the pass reports to the processed-KEX floor (no key offer in these tests).
                    "limitKexFloor" -> null
                    "getOrPutPendingTxFirstSeenMillis" -> args!![1]
                    else -> method.invoke(prefs.proxy, *(args ?: emptyArray()))
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", scanPrefs)
        val contactType = co.electriccoin.zcash.ui.screen.chat.model.ContactBook::class.java
        val contactBook =
            Proxy.newProxyInstance(contactType.classLoader, arrayOf(contactType)) { _, method, _ ->
                when (method.name) {
                    "getContact" -> null
                    else -> error("unexpected ContactBook.${method.name}")
                }
            }
        PaymentHarness.setField(vm, "contactBook", contactBook)
        installWallet(
            vm,
            WalletFacts(
                scanned = PAST_EXPIRY,
                tip = PAST_EXPIRY,
                txs =
                    listOf(
                        FactTx(
                            recipients = listOf(PEER),
                            memos = listOf(ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text)),
                            minedHeight = if (mined) ATTEMPT_HEIGHT - 100 else null,
                            expiryHeight = ATTEMPT_HEIGHT + 40,
                            state = if (mined) TransactionState.Confirmed else TransactionState.Pending,
                        )
                    ),
            ),
        )
        val repository =
            ChatViewModel::class.java
                .getDeclaredField("transactionRepository")
                .apply { isAccessible = true }
                .get(vm) as TransactionRepository
        val transactions = repository.getTransactions()
        val pending = PaymentHarness.pendingMessages(vm)
        val method =
            ChatViewModel::class.java.declaredMethods
                .single {
                    it.name == "convertToConversations" && it.parameterCount == 7
                }.apply { isAccessible = true }
        val conversations =
            kotlin.coroutines.intrinsics
                .suspendCoroutineUninterceptedOrReturn<List<co.electriccoin.zcash.ui.screen.chat.model.Conversation>> { continuation ->
                    try {
                        method.invoke(
                            vm,
                            transactions,
                            PaymentHarness.SELF,
                            emptySet<String>(),
                            pending,
                            emptySet<String>(),
                            false,
                            continuation
                        )
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw e.targetException
                    }
                }
        // This is also a fixture control: the production transaction parser actually reached the
        // outgoing message, so retaining pending state cannot pass merely by skipping the history.
        assertEquals(1, conversations.flatMap { it.messages }.count { it.id == reconciliationTxId(1) })
        return conversations.flatMap { it.messages }
    }

    @Test
    fun `a failed durable submit write never reaches settlement or loses the queued payload`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            val failingStore =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    if (method.name == "commitPendingMessage") error("synthetic durable write failure")
                    method.invoke(prefs.proxy, *(args ?: emptyArray()))
                } as ZchatPreferences
            PaymentHarness.setField(vm, "zchatPreferences", failingStore)
            val text = "synthetic message retained after storage failure"
            assertTrue(vm.sendMessage(PEER, text, CHOSEN))
            awaitIdle(vm)
            assertEquals("SDK entry requires the durable write", 0, seam.submits.get())
            val row = prefs.pending.values.single()
            assertEquals(text, row.text)
            assertEquals(CHOSEN, row.sendAmountZatoshi)

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            assertTrue(reopened.retryMessage(PEER, row.id))
            awaitIdle(reopened)
            assertEquals("a proven pre-SDK failure remains retryable once storage works", 1, seam.submits.get())
            assertEquals(zec(CHOSEN), firstOutputAmount(seam.proposalUris.last()))
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `known submitted transaction ids still prevent a retry if its timestamp marker is absent`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val ownId = reconciliationTxId(2)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = SubmitResult.Success(listOf(ownId)))
            val sender = paymentViewModel(prefs, seam)
            assertTrue(sender.sendMessage(PEER, "synthetic known transaction without timestamp", CHOSEN))
            awaitIdle(sender)
            val row = prefs.pending.values.single()
            assertEquals(listOf(ownId), row.submittedTxIds)
            prefs.pending[row.id] = row.copy(submitStartedAtMillis = null, isPending = false, status = MessageStatus.FAILED.name)
            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            assertTrue(!reopened.retryMessage(PEER, row.id))
            awaitIdle(reopened)
            assertEquals("a stored transaction is still a paid attempt", 1, seam.submits.get())
            assertEquals(listOf(ownId), prefs.pending.getValue(row.id).submittedTxIds)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    // ---- the defect -------------------------------------------------------------------------------------

    @Test
    fun `a send closed during its broadcast is not paid again by Retry after a reopen`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val (reopen, orphanId) = sendThenReopenDuringBroadcast(prefs, seam)
            // The wallet has not yet scanned past the attempt.
            installWallet(reopen, WalletFacts(scanned = ATTEMPT_HEIGHT + 2, tip = ATTEMPT_HEIGHT + 2))

            reopen.retryMessage(PEER, orphanId)
            awaitIdle(reopen)

            assertEquals("Retry paid a second time for a message whose broadcast had started", 1, seam.submits.get())
            assertMayHaveBeenSent(reopen, orphanId)
            finish(seam)
        }

    @Test
    fun `a send the node rejected after it was stored is not paid again by Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam =
                PaymentSeam(
                    PEER,
                    firstSubmitOutcome =
                        SubmitResult.Failure(
                            txIds = listOf("synthetic-stored-txid"),
                            code = -26,
                            description = "synthetic rejected"
                        ),
                )
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(vm, ATTEMPT_HEIGHT)
            vm.sendMessage(PEER, "synthetic rejected hello", CHOSEN)
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            installWallet(vm, WalletFacts(scanned = ATTEMPT_HEIGHT + 2, tip = ATTEMPT_HEIGHT + 2))

            vm.retryMessage(PEER, failed.id)
            awaitIdle(vm)

            assertEquals("Retry paid a second time for a stored transaction the SDK may re-broadcast", 1, seam.submits.get())
            assertMayHaveBeenSent(vm, failed.id)
        }

    @Test
    fun `Retry stays off while the wallet holds a transaction to that contact from the attempt`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val (reopen, orphanId) = sendThenReopenDuringBroadcast(prefs, seam)
            val mined =
                FactTx(
                    recipients = listOf(PEER),
                    memos = listOf(MESSAGE_MEMO),
                    minedHeight = ATTEMPT_HEIGHT + 3,
                    expiryHeight = ATTEMPT_HEIGHT + 41,
                    state = TransactionState.Confirmed,
                )
            installWallet(reopen, WalletFacts(scanned = PAST_EXPIRY, tip = PAST_EXPIRY, txs = listOf(mined)))

            reopen.retryMessage(PEER, orphanId)
            awaitIdle(reopen)

            assertEquals("Retry paid again although the wallet holds a matching transaction", 1, seam.submits.get())
            assertMayHaveBeenSent(reopen, orphanId)
            finish(seam)
        }

    @Test
    fun `an expired label is not trusted before the wallet has fully scanned to that expiry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val (reopen, orphanId) = sendThenReopenDuringBroadcast(prefs, seam)
            // Non-linear scan: the tip was scanned first, so the SDK can call an unmined transaction expired
            // while blocks below its expiry height (where it may be mined) are still unscanned.
            val unscannedExpiry = ATTEMPT_HEIGHT + 80
            val labelledExpired =
                FactTx(
                    recipients = listOf(PEER),
                    memos = listOf(MESSAGE_MEMO),
                    minedHeight = null,
                    expiryHeight = unscannedExpiry,
                    state = TransactionState.Expired,
                )
            installWallet(
                reopen,
                WalletFacts(scanned = unscannedExpiry - 10, tip = unscannedExpiry + 50, txs = listOf(labelledExpired)),
            )

            reopen.retryMessage(PEER, orphanId)
            awaitIdle(reopen)

            assertEquals("Retry trusted an expiry the wallet had not scanned to", 1, seam.submits.get())
            assertMayHaveBeenSent(reopen, orphanId)
            finish(seam)
        }

    @Test
    fun `a message pending from an older version is not blindly re-sent`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            // An older build persisted the row before broadcasting and recorded nothing about the submit.
            val legacyId = "pending_123456789"
            prefs.pending[legacyId] =
                ZchatPreferences.PendingMessageData(
                    id = legacyId,
                    text = "synthetic legacy hello",
                    timestampMillis = Instant.now().minusSeconds(60).toEpochMilli(),
                    peerAddress = PEER,
                )
            val reopen = paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(reopen, ATTEMPT_HEIGHT)
            installWallet(reopen, WalletFacts(scanned = ATTEMPT_HEIGHT, tip = ATTEMPT_HEIGHT))
            PaymentHarness.loadPendingMessagesFromPrefs(reopen)
            assertEquals(MessageStatus.FAILED, PaymentHarness.pendingMessages(reopen).single().status)

            reopen.retryMessage(PEER, legacyId)
            awaitIdle(reopen)

            assertEquals("Retry re-sent a legacy in-flight message without any proof", 0, seam.submits.get())
            assertMayHaveBeenSent(reopen, legacyId)
        }

    @Test
    fun `retrying a failed reply keeps the reply link`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic node unreachable"))
            val vm = paymentViewModel(prefs, seam)
            val target = "synthetic-target-id"

            vm.sendReply(PEER, "synthetic reply", target, "synthetic preview", CHOSEN)
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertEquals("fixture sanity", target, failed.replyToId)

            assertTrue(vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.proposalUris.size)
            assertEquals(zec(CHOSEN), firstOutputAmount(seam.proposalUris[1]))
            val resent = PaymentHarness.pendingMessages(vm).single()
            assertEquals("Retry dropped the reply link from the re-sent message", target, resent.replyToId)
            assertEquals("synthetic preview", resent.replyToPreview)
            val persisted = prefs.pending.values.single()
            assertEquals("the persisted re-send lost the reply link", target, persisted.replyToId)
        }

    // ---- retry safety and known pre-submit failures ----------------------------------------------------

    @Test
    fun `control - a send that stored nothing still retries at the chosen amount`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            // The key could not be derived: NothingBroadcastException, reported as a Failure with no txid.
            val seam =
                PaymentSeam(
                    PEER,
                    firstSubmitOutcome = SubmitResult.Failure(txIds = emptyList(), code = 0, description = "synthetic no key"),
                )
            val vm = paymentViewModel(prefs, seam)
            vm.sendMessage(PEER, "synthetic unsent hello", CHOSEN)
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }

            assertTrue(vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.submits.get())
            assertEquals(zec(CHOSEN), firstOutputAmount(seam.proposalUris[1]))
        }

    @Test
    fun `transactions elsewhere and an advanced scan do not prove the held attempt was never sent`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val (reopen, orphanId) = sendThenReopenDuringBroadcast(prefs, seam)
            val elsewhere =
                FactTx(
                    recipients = listOf(OTHER_PEER),
                    memos = listOf(MESSAGE_MEMO),
                    minedHeight = ATTEMPT_HEIGHT + 3,
                    expiryHeight = ATTEMPT_HEIGHT + 41,
                    state = TransactionState.Confirmed,
                )
            installWallet(reopen, WalletFacts(scanned = PAST_EXPIRY, tip = PAST_EXPIRY, txs = listOf(elsewhere)))

            reopen.retryMessage(PEER, orphanId)
            awaitIdle(reopen)

            // The first submit is still live. This cached UI projection neither identifies that attempt
            // nor establishes its actual expiry/completeness, even though the scan height advanced.
            assertEquals("Absence from the contact view authorized another payment", 1, seam.submits.get())
            assertEquals("Retry created another fee-bearing proposal", 1, seam.proposalUris.size)
            assertMayHaveBeenSent(reopen, orphanId)
            finish(seam)
        }

    @Test
    fun `an expired peer transaction does not prove all components of the held attempt are finished`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val (reopen, orphanId) = sendThenReopenDuringBroadcast(prefs, seam)
            val expired =
                FactTx(
                    recipients = listOf(PEER),
                    memos = listOf(MESSAGE_MEMO),
                    minedHeight = null,
                    expiryHeight = ATTEMPT_HEIGHT + 41,
                    state = TransactionState.Expired,
                )
            // An older chat-setup memo to the same contact is not this message either.
            val kexMemo = ZMSGConstants.Prefixes.V4 + "PAYA2345|" + ZMSGConstants.Markers.KEX + "synthetic"
            assertTrue("fixture sanity", ZMSGProtocol.isKEXMessage(kexMemo))
            val kex =
                FactTx(
                    recipients = listOf(PEER),
                    memos = listOf(kexMemo),
                    minedHeight = ATTEMPT_HEIGHT + 1,
                    expiryHeight = ATTEMPT_HEIGHT + 41,
                    state = TransactionState.Confirmed,
                )
            installWallet(reopen, WalletFacts(scanned = PAST_EXPIRY, tip = PAST_EXPIRY, txs = listOf(expired, kex)))

            reopen.retryMessage(PEER, orphanId)
            awaitIdle(reopen)

            // No durable original txid links this expired row to every component of the live attempt.
            // One expired peer row plus an unrelated KEX cannot authorize principal or fees again.
            assertEquals("An uncorrelated expiry authorized another payment", 1, seam.submits.get())
            assertEquals("Retry created another fee-bearing proposal", 1, seam.proposalUris.size)
            assertMayHaveBeenSent(reopen, orphanId)
            finish(seam)
        }

    @Test
    fun `a stale empty transaction view at a far later height never authorizes ambiguous resend`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val (reopen, orphanId) = sendThenReopenDuringBroadcast(prefs, seam)
            try {
                // This list is the same empty projection from before settlement, while the independent
                // scan-height flow has advanced. It is not an authoritative original-attempt inventory.
                val farLater = ATTEMPT_HEIGHT + 10_000
                installWallet(reopen, WalletFacts(scanned = farLater, tip = farLater))

                reopen.retryMessage(PEER, orphanId)
                awaitIdle(reopen)

                assertEquals("A stale empty view authorized a second payment", 1, seam.submits.get())
                assertEquals("Retry created another fee-bearing proposal", 1, seam.proposalUris.size)
                assertMayHaveBeenSent(reopen, orphanId)
            } finally {
                finish(seam)
            }
        }

    @Test
    fun `two chat screens cannot retry the same proven pre-submit failure twice`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic pre-submit failure"))
            val first = paymentViewModel(prefs, seam)
            first.sendMessage(PEER, "synthetic shared retry", CHOSEN)
            awaitIdle(first)
            val failed = PaymentHarness.pendingMessages(first).single { it.status == MessageStatus.FAILED }
            assertEquals("fixture sanity: proposal failure must precede settlement", 0, seam.submits.get())

            // Both screens hold the SAME logical failed message before either Retry. The second one
            // deliberately retains its stale copy after the first has claimed and sent the replacement.
            val staleScreen = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(staleScreen)
            assertEquals("the safe failure must remain recoverable", failed.id, PaymentHarness.pendingMessages(staleScreen).single().id)

            assertTrue(first.retryMessage(PEER, failed.id))
            awaitIdle(first)
            assertEquals("first retry should settle exactly once", 1, seam.submits.get())
            staleScreen.retryMessage(PEER, failed.id)
            awaitIdle(staleScreen)

            assertEquals("a stale screen paid the same logical retry again", 1, seam.submits.get())
            assertEquals("a stale screen rebuilt a second fee-bearing proposal", 2, seam.proposalUris.size)
            assertEquals(zec(CHOSEN), firstOutputAmount(seam.proposalUris[1]))
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a rejected retry retains its payload amount and reply link after reload`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic pre-submit failure"))
            val vm = paymentViewModel(prefs, seam)
            val text = "synthetic preserved reply"
            val target = "synthetic-preserved-target"
            vm.sendReply(PEER, text, target, "synthetic preserved preview", CHOSEN)
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }

            // Exercise the real immediate rejection path without adding mutable behavior to the shared
            // fixture: only the peer-key-change read is overridden; all storage calls still use prefs.
            val changedKeyPrefs =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    if (method.name == "isE2EKeyChanged") true else method.invoke(prefs.proxy, *(args ?: emptyArray<Any?>()))
                } as ZchatPreferences
            PaymentHarness.setField(vm, "zchatPreferences", changedKeyPrefs)
            vm.retryMessage(PEER, failed.id)
            awaitIdle(vm)

            assertEquals("rejected Retry must not reach settlement", 0, seam.submits.get())
            assertEquals("rejected Retry must not build another proposal", 1, seam.proposalUris.size)
            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val restored = PaymentHarness.pendingMessages(reopened).single()
            assertEquals(failed.id, restored.id)
            assertEquals(MessageStatus.FAILED, restored.status)
            assertEquals(text, restored.text)
            assertEquals(CHOSEN, restored.sendAmountZatoshi)
            assertEquals(target, restored.replyToId)
            assertEquals("synthetic preserved preview", restored.replyToPreview)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a proven pre-submit failure retries after reload at the chosen amount`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic pre-submit failure"))
            val first = paymentViewModel(prefs, seam)
            val text = "synthetic durable plain message"
            first.sendMessage(PEER, text, CHOSEN)
            awaitIdle(first)
            assertEquals("fixture sanity: no settlement before reload", 0, seam.submits.get())

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val failed = PaymentHarness.pendingMessages(reopened).single()
            assertEquals(MessageStatus.FAILED, failed.status)
            assertEquals(text, failed.text)
            assertEquals(CHOSEN, failed.sendAmountZatoshi)
            assertTrue(reopened.retryMessage(PEER, failed.id))
            awaitIdle(reopened)

            assertEquals(1, seam.submits.get())
            assertEquals(2, seam.proposalUris.size)
            assertEquals(zec(CHOSEN), firstOutputAmount(seam.proposalUris[1]))
            assertEquals(
                text,
                prefs.pending.values
                    .single()
                    .text
            )
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a proven pre-submit reply failure retries after reload without losing its quote or amount`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic pre-submit failure"))
            val first = paymentViewModel(prefs, seam)
            val text = "synthetic durable reply"
            val target = "synthetic-durable-target"
            first.sendReply(PEER, text, target, "synthetic durable preview", CHOSEN)
            awaitIdle(first)
            assertEquals("fixture sanity: no settlement before reload", 0, seam.submits.get())

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val failed = PaymentHarness.pendingMessages(reopened).single()
            assertEquals(MessageStatus.FAILED, failed.status)
            assertEquals(target, failed.replyToId)
            assertEquals("synthetic durable preview", failed.replyToPreview)
            assertEquals(CHOSEN, failed.sendAmountZatoshi)
            assertTrue(reopened.retryMessage(PEER, failed.id))
            awaitIdle(reopened)

            assertEquals(1, seam.submits.get())
            assertEquals(2, seam.proposalUris.size)
            assertEquals(zec(CHOSEN), firstOutputAmount(seam.proposalUris[1]))
            val resent = prefs.pending.values.single()
            assertEquals(text, resent.text)
            assertEquals(target, resent.replyToId)
            assertEquals("synthetic durable preview", resent.replyToPreview)
            assertEquals(CHOSEN, resent.sendAmountZatoshi)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a stored rejection retains ambiguity after its durable row disappears during settlement`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam =
                PaymentSeam(
                    PEER,
                    holdFirstSubmit = true,
                    firstSubmitOutcome =
                        SubmitResult.Failure(
                            txIds = listOf("synthetic-stored-after-row-removal"),
                            code = -26,
                            description = "synthetic stored rejection after reconciliation",
                        ),
                )
            val first = paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(first, ATTEMPT_HEIGHT)
            first.sendMessage(PEER, "synthetic removed pending row", CHOSEN)
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            val originalId =
                prefs.pending.values
                    .single()
                    .id
            try {
                // Model reconciliation/deletion removing the durable pending record while settlement
                // remains outstanding. Failure recovery must not recreate a marker-free safe row.
                prefs.pending.clear()
            } finally {
                finish(seam)
            }
            awaitIdle(first)

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val restored = PaymentHarness.pendingMessages(reopened).single()
            assertEquals(originalId, restored.id)
            assertEquals(MessageStatus.FAILED, restored.status)
            // A far-height empty projection must not hide this persistence loss if older proof logic
            // still exists. The original stored transaction, not UI absence, owns payment recovery.
            val farLater = ATTEMPT_HEIGHT + 10_000
            installWallet(reopened, WalletFacts(scanned = farLater, tip = farLater))
            reopened.retryMessage(PEER, restored.id)
            awaitIdle(reopened)

            assertEquals("fallback persistence lost the marker and paid again", 1, seam.submits.get())
            assertEquals("fallback persistence authorized another fee-bearing proposal", 1, seam.proposalUris.size)
            assertMayHaveBeenSent(reopened, restored.id)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `plain Open retries keep one free failed row and remain retryable after reload`() =
        runBlocking {
            val prefs =
                PaymentPrefs(
                    PEER,
                    mode = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN,
                )
            val seam = PaymentSeam(PEER)
            val first = paymentViewModel(prefs, seam)
            val text = "synthetic free retry with missing peer key"
            assertTrue(first.sendMessage(PEER, text, CHOSEN))
            awaitIdle(first)
            val initial = PaymentHarness.pendingMessages(first).single()
            assertEquals(MessageStatus.FAILED, initial.status)
            assertEquals("fixture sanity", 1, prefs.pending.size)

            assertTrue("free Retry should be accepted even when it fails again without a peer key", first.retryMessage(PEER, initial.id))
            awaitIdle(first)
            assertEquals("Retry left a claimed old row next to the replacement", 1, prefs.pending.size)
            val afterRetry = PaymentHarness.pendingMessages(first).single()
            assertEquals(MessageStatus.FAILED, afterRetry.status)
            assertEquals(text, afterRetry.text)

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val restored = PaymentHarness.pendingMessages(reopened).single()
            assertEquals(MessageStatus.FAILED, restored.status)
            assertEquals(text, restored.text)
            // Acceptance after reload verifies that no paid-submit ambiguity marker poisoned the free
            // row. Missing-key publication remains a real failure and must allow another free attempt.
            assertTrue("a free retry became permanently blocked as possibly paid", reopened.retryMessage(PEER, restored.id))
            awaitIdle(reopened)
            assertEquals("reloaded Retry duplicated the durable payload", 1, prefs.pending.size)
            assertEquals(MessageStatus.FAILED, PaymentHarness.pendingMessages(reopened).single().status)
            assertEquals("Open retry must not create an on-chain proposal", 0, seam.proposalUris.size)
            assertEquals("Open retry must not reach settlement", 0, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    // ---- fixture ------------------------------------------------------------------------------------------

    /** Sends a paid message, waits until its broadcast has started, then opens a fresh chat screen. */
    private suspend fun sendThenReopenDuringBroadcast(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
    ): Pair<ChatViewModel, String> {
        val first = paymentViewModel(prefs, seam)
        PaymentHarness.setBlockHeight(first, ATTEMPT_HEIGHT)
        first.sendMessage(PEER, "synthetic in-flight hello", CHOSEN)
        withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }

        // Model a reopened screen from persisted rows while settlement is held; this is not a process-kill test.
        // ZC-1071: a screen opened beside the live send now shows it as sending (LoaderNeverFailsALiveSendTest). This
        // fixture keeps the case these F4 tests guard, the row shown FAILED while its submit is still held here, by
        // emptying the live-sender registry: the F4 refusals are still checked behind the loader.
        LiveSenders.resetForTest()
        val reopen = paymentViewModel(prefs, seam)
        PaymentHarness.setBlockHeight(reopen, ATTEMPT_HEIGHT + 2)
        PaymentHarness.loadPendingMessagesFromPrefs(reopen)
        val orphan = PaymentHarness.pendingMessages(reopen).single()
        assertEquals("fixture sanity", MessageStatus.FAILED, orphan.status)
        return reopen to orphan.id
    }

    private suspend fun finish(seam: PaymentSeam) {
        seam.release.complete(Unit)
        withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }
    }

    private fun assertMayHaveBeenSent(
        vm: ChatViewModel,
        messageId: String,
    ) {
        assertTrue(
            "the message that may have gone out vanished from the chat",
            PaymentHarness.pendingMessages(vm).any { it.id == messageId },
        )
        val state = PaymentHarness.sendMessageState(vm)
        assertTrue(
            "the chat did not say the message may already have been sent: $state",
            state is SendMessageState.Error && state.message.contains(MAY_HAVE_BEEN_SENT, ignoreCase = true),
        )
    }

    private class FactTx(
        val recipients: List<String>,
        val memos: List<String>,
        val minedHeight: Long?,
        val expiryHeight: Long?,
        val state: TransactionState,
        val blockTimeEpochSeconds: Long? = minedHeight?.let { Instant.now().epochSecond },
    )

    private class WalletFacts(
        val scanned: Long,
        val tip: Long,
        val txs: List<FactTx> = emptyList(),
    )

    /** Gives [vm] a wallet: its fully scanned height, the chain tip and its sent transactions. */
    private fun installWallet(
        vm: ChatViewModel,
        facts: WalletFacts,
    ) {
        val byTxId = LinkedHashMap<String, FactTx>()
        val transactions: List<Transaction> =
            facts.txs.mapIndexed { i, fact ->
                val hex = "%064x".format(i + 1)
                val id = TransactionId.new(hex)
                byTxId[id.txIdString()] = fact
                SendTransaction.Success(
                    id = id,
                    amount = Zatoshi(CHOSEN),
                    timestamp = fact.blockTimeEpochSeconds?.let { Instant.ofEpochSecond(it) },
                    memoCount = fact.memos.size,
                    fee = Zatoshi(10_000),
                    transactionOutputs = emptyList(),
                    overview = overview(id, fact),
                    recipient = null,
                )
            }
        val repository =
            Proxy.newProxyInstance(
                TransactionRepository::class.java.classLoader,
                arrayOf(TransactionRepository::class.java),
            ) { p, method, args ->
                fun factOf(i: Int) = byTxId[(args!![i] as Transaction).id.txIdString()]
                when (method.name) {
                    "getTransactions" -> transactions
                    "getAllRecipientAddresses" -> factOf(0)?.recipients ?: emptyList<String>()
                    "getMemos" -> factOf(0)?.memos ?: emptyList<String>()
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "WalletFactsRepository"
                    else -> error("unexpected TransactionRepository.${method.name}")
                }
            } as TransactionRepository
        val synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getFullyScannedHeight" -> MutableStateFlow(BlockHeight.new(facts.scanned))
                    "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(facts.tip))
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "WalletFactsSynchronizer"
                    else -> error("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer
        val provider =
            Proxy.newProxyInstance(
                SynchronizerProvider::class.java.classLoader,
                arrayOf(SynchronizerProvider::class.java),
            ) { p, method, args ->
                when (method.name) {
                    // The property getter takes no argument; the suspend getSynchronizer() takes a Continuation.
                    "getSynchronizer" -> if (args.isNullOrEmpty()) MutableStateFlow<Synchronizer?>(synchronizer) else synchronizer
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "WalletFactsSynchronizerProvider"
                    else -> error("unexpected SynchronizerProvider.${method.name}")
                }
            } as SynchronizerProvider
        PaymentHarness.setField(vm, "transactionRepository", repository)
        PaymentHarness.setField(vm, "synchronizerProvider", provider)
    }

    private fun overview(
        id: TransactionId,
        fact: FactTx,
    ): TransactionOverview =
        TransactionOverview(
            id,
            fact.minedHeight?.let { BlockHeight.new(it) },
            fact.expiryHeight?.let { BlockHeight.new(it) },
            null,
            null,
            true,
            Zatoshi(CHOSEN + 10_000),
            Zatoshi(CHOSEN + 10_000),
            Zatoshi(0),
            Zatoshi(10_000),
            false,
            0,
            1,
            fact.memos.size,
            fact.blockTimeEpochSeconds,
            fact.state,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )
}
