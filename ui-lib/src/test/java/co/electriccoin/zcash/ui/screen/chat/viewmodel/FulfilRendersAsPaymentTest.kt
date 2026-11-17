package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Round 8 R8-fixup F7 (REVIEW_R8 F7, device D-F9) — paying a Shielded payment request shows raw text on both sides.
 *
 * The payment goes out as the bare memo `ZREQ_FULFILL|<request id>` (rawMemo, no envelope, no sender):
 * - the payer's history pass marks the request paid but does not stop there, so the payer's chat shows a
 *   `ZREQ_FULFILL|…` text bubble;
 * - the requester has no handler for it: the payment lands as an unattributable raw memo, and the requester's own
 *   request bubble never shows Paid.
 *
 * Wanted:
 * - (a) the payer's pass shows no bubble whose text is the `ZREQ_FULFILL|` memo (the request bubble shows Paid);
 * - (b) the requester's pass matches a MINED incoming `ZREQ_FULFILL|<id>` that carries at least the requested amount
 *   to its own request `<id>`, marks that request paid, and shows no bubble for the raw memo;
 * - controls: an incoming fulfil below the requested amount, or not yet mined, does not mark the request paid.
 *
 * Runs the real convertToConversations. All addresses and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilRendersAsPaymentTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticfulfilrender000001"
        const val REQUESTED = 10_000L
        const val MINED_AT = 3_000_100L
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
    fun `the payer's chat shows no raw ZREQ_FULFILL bubble`() =
        runBlocking {
            val requestId = PendingReconcileHarness.txId(40)
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
            val vm = paymentViewModel(prefs, PaymentSeam(PEER))

            val rows = PendingReconcileHarness.reconcile(vm, prefs, listOf(SentTx(1, PEER, "ZREQ_FULFILL|$requestId", MINED_AT)))

            assertTrue("fixture sanity: the scan-back marked the request paid", requestId in prefs.paid)
            val raw = rows.filter { it.text.startsWith("ZREQ_FULFILL|") }
            assertEquals("the payer's chat shows the raw payment memo as text: ${raw.map { it.text }}", 0, raw.size)
        }

    @Test
    fun `the requester's request shows paid when a mined payment for it of at least the amount arrives`() =
        runBlocking {
            val (rows, prefs) = requesterPass(fulfilAmount = REQUESTED, fulfilMined = true)
            val requestId = txIdOf(1)

            val request = rows.single { it.id == requestId }
            assertEquals("fixture sanity: our own request bubble", true, request.paymentRequest != null && request.isOutgoing)
            assertEquals("the request was paid, but its bubble never shows Paid", true, request.paymentRequest?.isPaid)
            assertTrue("the paid mark is not kept for the next pass", requestId in prefs.paid)
            val raw = rows.filter { it.text.startsWith("ZREQ_FULFILL|") }
            assertEquals("the requester's chat shows the raw payment memo: ${raw.map { it.peerAddress }}", 0, raw.size)
        }

    @Test
    fun `control - a payment below the requested amount does not mark the request paid`() =
        runBlocking {
            val (rows, prefs) = requesterPass(fulfilAmount = REQUESTED - 1, fulfilMined = true)
            val requestId = txIdOf(1)

            assertEquals(false, rows.single { it.id == requestId }.paymentRequest?.isPaid)
            assertTrue(requestId !in prefs.paid)
        }

    @Test
    fun `control - an unmined payment does not mark the request paid`() =
        runBlocking {
            val (rows, prefs) = requesterPass(fulfilAmount = REQUESTED, fulfilMined = false)
            val requestId = txIdOf(1)

            assertEquals(false, rows.single { it.id == requestId }.paymentRequest?.isPaid)
            assertTrue(requestId !in prefs.paid)
        }

    // --------------------------------------------------------------- harness

    private fun txIdOf(n: Int): String = TransactionId.new("%064x".format(n)).txIdString()

    /** The requester's phone: our own mined request (tx 1) to [PEER], then [PEER]'s payment for it (tx 2). */
    private suspend fun requesterPass(
        fulfilAmount: Long,
        fulfilMined: Boolean,
    ): Pair<List<ChatMessage>, PaymentPrefs> {
        val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
        val vm = paymentViewModel(prefs, PaymentSeam(PEER))
        val requestMemo = ZMSGProtocol.createPaymentRequest(REQUESTED, PaymentHarness.SELF, "synthetic lunch")
        val requestId = TransactionId.new("%064x".format(1))
        val fulfilId = TransactionId.new("%064x".format(2))
        val sent =
            SendTransaction.Success(
                id = requestId,
                amount = Zatoshi(1_000),
                timestamp = Instant.now(),
                memoCount = 1,
                fee = Zatoshi(15_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(requestId, MINED_AT, Instant.now(), sent = true, amount = 1_000),
                recipient = null,
            )
        val received =
            ReceiveTransaction.Success(
                id = fulfilId,
                amount = Zatoshi(fulfilAmount),
                timestamp = Instant.now(),
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview =
                    ScanPassHarness.overview(
                        fulfilId,
                        if (fulfilMined) MINED_AT + 5 else null,
                        Instant.now(),
                        sent = false,
                        amount = fulfilAmount,
                    ),
                recipient = null,
            )
        val memos = mapOf(requestId.txIdString() to requestMemo, fulfilId.txIdString() to "ZREQ_FULFILL|${requestId.txIdString()}")
        val conversations = pass(vm, prefs, listOf(sent, received), memos)
        return conversations.flatMap { it.messages } to prefs
    }

    private suspend fun pass(
        vm: ChatViewModel,
        prefs: PaymentPrefs,
        transactions: List<Transaction>,
        memos: Map<String, String>,
    ): List<Conversation> {
        val scanPrefs =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getConversationId", "getDisappearingTtl", "getNickname", "clearPendingTxFirstSeen" -> null
                    "isE2ERecoveryFloorPending" -> false
                    "getOrPutPendingTxFirstSeenMillis" -> args!![1]
                    else -> {
                        // PaymentPrefs answers a call it does not model with null, which a primitive return cannot
                        // unbox: those calls get the type's empty value here.
                        val answer =
                            try {
                                method.invoke(prefs.proxy, *(args ?: emptyArray()))
                            } catch (e: InvocationTargetException) {
                                if (e.targetException !is NullPointerException) throw e.targetException
                                null
                            }
                        answer ?: when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            List::class.java -> emptyList<Any>()
                            Set::class.java -> emptySet<Any>()
                            Map::class.java -> emptyMap<Any, Any>()
                            else -> null
                        }
                    }
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", scanPrefs)
        PaymentHarness.setField(
            vm,
            "contactBook",
            Proxy.newProxyInstance(
                ContactBook::class.java.classLoader,
                arrayOf(ContactBook::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getContact" -> null
                    "hasContact" -> false
                    "getAllContacts" -> emptyList<Any>()
                    else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
                }
            },
        )
        val repository =
            Proxy.newProxyInstance(
                TransactionRepository::class.java.classLoader,
                arrayOf(TransactionRepository::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getTransactions" -> transactions
                    "getAllRecipientAddresses" ->
                        if (args!![0] is SendTransaction) listOf(PEER) else emptyList<String>()
                    "getMemos" -> listOfNotNull(memos[(args!![0] as Transaction).id.txIdString()])
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "FulfilRepository"
                    else -> error("unexpected TransactionRepository.${method.name}")
                }
            } as TransactionRepository
        val synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(PendingReconcileHarness.SCANNED))
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "FulfilSynchronizer"
                    else -> error("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer
        val provider =
            Proxy.newProxyInstance(
                SynchronizerProvider::class.java.classLoader,
                arrayOf(SynchronizerProvider::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getSynchronizer" -> if (args.isNullOrEmpty()) MutableStateFlow<Synchronizer?>(synchronizer) else synchronizer
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "FulfilSynchronizerProvider"
                    else -> error("unexpected SynchronizerProvider.${method.name}")
                }
            } as SynchronizerProvider
        PaymentHarness.setField(vm, "transactionRepository", repository)
        PaymentHarness.setField(vm, "synchronizerProvider", provider)
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        return kotlin.coroutines.intrinsics
            .suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(
                        vm,
                        transactions,
                        PaymentHarness.SELF,
                        emptySet<String>(),
                        PaymentHarness.pendingMessages(vm),
                        prefs.paid.toSet(),
                        false,
                        continuation,
                    )
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
    }
}
