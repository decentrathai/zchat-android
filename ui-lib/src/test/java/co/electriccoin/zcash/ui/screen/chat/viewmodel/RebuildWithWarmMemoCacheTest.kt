package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionOverview
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * ZC-946 batch 3 (PERF-SPEC revision 1 section 2; review of 14 Oct, finding 1): the history pass over the memo cache.
 *
 * - The pass reads its memos through TransactionRepositoryImpl.getMemos. Wanted: the second pass over an unchanged
 *   wallet asks the SDK for no memo (fails today: every pass reads them all again).
 * - Golden guard (green before and after): two view models over the same recorded wallet, one reading the SDK on every
 *   pass and one over a warm cache, run the same passes and render equal rows with the same ordered calls to the store,
 *   each with its arguments (review 4, nit 4). The cache never changes what the pass decides.
 * - Latch guard (green before and after; a cache that answers a cancelled caller fails it): a complete pass cut short
 *   after its first memo read, during a rescan (the process-wide `processHistoryRead` latch is open), never closes the
 *   latch, and its later reads end as cancelled reads (KR-3 counts them). A warm answer handed to a cancelled pass
 *   would let it finish with no read failure and close the latch while the rescan runs, which unguards the automatic
 *   key payments.
 *
 * The fixtures of FulfilStoredIdsHarness (a fulfil mined, a fulfil whose memo is not read yet, an own send that expired
 * unmined, a plain own message) with the view model's TransactionRepository replaced by the real
 * TransactionRepositoryImpl over a stand-in synchronizer that answers from the recorded wallet and counts its memo
 * reads; every other repository call goes to the harness's own repository. Every id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuildWithWarmMemoCacheTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc946cachepeer0001"
        const val E = H + 40
        const val AMOUNT = 1_000L
        const val REQUEST = "synthetic-zc946-request"
        const val DAY_MS = 86_400_000L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        latch().set(false)
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private val txs: List<ChainTx> by lazy {
        val h = FulfilStoredIdsHarness(PEER)
        listOf(
            h.fulfilTx(1, REQUEST, minedHeight = E - 30, expiryHeight = E, amount = AMOUNT),
            h.fulfilTx(2, "$REQUEST-unread", minedHeight = null, expiryHeight = E + 20, amount = AMOUNT, memoRead = false),
            ChainTx(number = 3, recipient = PEER, memo = "synthetic expired message", minedHeight = null, expiryHeight = E - 25),
            ChainTx(number = 4, recipient = PEER, memo = "synthetic plain message", minedHeight = E - 28, expiryHeight = E),
        )
    }

    @Test
    fun `a second pass over an unchanged wallet asks the SDK for no memo`() =
        runBlocking {
            val chat = chat(cached = true)
            chat.pass(E - 20)
            val afterFirst = chat.sdk.reads.get()
            chat.pass(E - 19)

            assertTrue("fixture: the first pass read the memos", afterFirst > 0)
            assertEquals("the second pass over an unchanged wallet read the memos again", afterFirst, chat.sdk.reads.get())
        }

    @Test
    fun `golden - a warm cache renders the same rows with the same store calls as reading the SDK every pass`() =
        runBlocking {
            forgetProcessState()
            val plain = chat(cached = false)
            plain.warmUp()
            val plainRows = listOf(plain.pass(E - 20), plain.pass(E - 19), plain.pass(E - 18))

            forgetProcessState()
            val warm = chat(cached = true)
            warm.warmUp()
            val warmRows = listOf(warm.pass(E - 20), warm.pass(E - 19), warm.pass(E - 18))

            assertEquals("fixture: the plain chat read no memo through the repository", 0, plain.sdk.reads.get())
            assertTrue("fixture: the warm chat read its memos through the repository", warm.sdk.reads.get() > 0)
            assertEquals("the warm cache changed the rendered rows", plainRows, warmRows)
            assertEquals("the warm cache changed the store calls of the passes", plain.storeCalls.toList(), warm.storeCalls.toList())
        }

    @Test
    fun `latch - a warm complete pass cut short during a rescan never closes the history latch`() =
        runBlocking {
            val chat = chat(cached = true)
            chat.warmUp()
            latch().set(false) // a rescan started: no automatic key payment until a whole pass reads the history again
            val cut = Job()
            chat.cancelAfterFirstRead = cut

            runCatching { withContext(cut) { chat.pass(E - 20) } }

            assertTrue("fixture: the pass was cut short after its first memo read", cut.isCancelled)
            assertFalse("a pass cut short during a rescan closed the history latch", latch().get())
            assertTrue(
                "a read after the cut was answered instead of failing as a cancelled read (${chat.cancelledReads.get()})",
                chat.cancelledReads.get() > 0,
            )
        }

    @Test
    fun `control - a warm complete pass that is not cut short closes the latch`() =
        runBlocking {
            val chat = chat(cached = true)
            chat.warmUp()
            latch().set(false)

            chat.pass(E - 20)

            assertTrue("the fixture's complete pass never closes the latch, so the latch case proves nothing", latch().get())
        }

    /** One chat screen's view model over the recorded wallet [txs]; [cached]: its memos come through the real repository. */
    private suspend fun chat(cached: Boolean): Chat {
        val h = FulfilStoredIdsHarness(PEER)
        val sdk = WalletMemoSdk(txs)
        val impl = TransactionRepositoryImpl(Zc101FakeAccountDataSource(syntheticZashiAccount()), sdk.provider)
        val seam = FulfilStoredIdsHarness.IdsSeam(PEER, emptyList(), SubmitResult.Success(emptyList()))
        return Chat(cached, h, sdk, impl, h.viewModel(seam, REQUEST, AMOUNT))
    }

    /** The process-wide request sets this class's request ids may be in (a test of another view model left them). */
    @Suppress("UNCHECKED_CAST")
    private fun forgetProcessState() {
        latch().set(false)
        listOf("fulfilPendingIds", "fulfilInFlight").forEach { name ->
            val field = ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
            val set = field.get(null) as MutableSet<String>
            set.removeAll(setOf(REQUEST, "$REQUEST-unread"))
        }
    }

    private inner class Chat(
        cached: Boolean,
        private val h: FulfilStoredIdsHarness,
        val sdk: WalletMemoSdk,
        private val impl: TransactionRepository,
        val vm: ChatViewModel,
    ) {
        val storeCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cancelledReads = AtomicInteger()
        var cancelAfterFirstRead: Job? = null
        private val firstReadDone = AtomicBoolean(false)
        private val wallet = Wallet(scanned = E - 21, txs = txs)

        init {
            PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            h.installWallet(vm, wallet)
            val field = ChatViewModel::class.java.getDeclaredField("transactionRepository").apply { isAccessible = true }
            val base = field.get(vm) as TransactionRepository
            if (cached) field.set(vm, memosThroughTheRepository(base))
            logStoreCalls()
        }

        suspend fun pass(tip: Long): List<ChatMessage> = h.passAt(vm, wallet, tip)

        /** Every recorded transaction read once through the repository, as a first pass would. */
        suspend fun warmUp() {
            h.passAt(vm, wallet, E - 22)
        }

        private fun memosThroughTheRepository(base: TransactionRepository): TransactionRepository =
            Proxy.newProxyInstance(TransactionRepository::class.java.classLoader, arrayOf(TransactionRepository::class.java)) {
                p,
                method,
                args,
                ->
                when (method.name) {
                    "getMemos" -> {
                        val tx = args!![0] as Transaction
                        suspend { readMemos(tx) }.startCoroutineUninterceptedOrReturn(continuation(args.last()))
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "MemosThroughTheRepository"
                    else -> forward(base, method, args)
                }
            } as TransactionRepository

        private suspend fun readMemos(tx: Transaction): List<String> {
            try {
                return impl.getMemos(tx)
            } catch (e: CancellationException) {
                cancelledReads.incrementAndGet()
                throw e
            } finally {
                // Armed only once the test names the job to cut, so the warm-up pass never uses up the first read.
                val cut = cancelAfterFirstRead
                if (cut != null && firstReadDone.compareAndSet(false, true)) cut.cancel()
            }
        }

        private fun logStoreCalls() {
            val field = ChatViewModel::class.java.getDeclaredField("zchatPreferences").apply { isAccessible = true }
            val base = field.get(vm) as ZchatPreferences
            val logging =
                Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) {
                    p,
                    method,
                    args,
                    ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> p === args?.get(0)
                        "toString" -> "StoreCallLog"
                        else -> {
                            storeCalls += storeCall(method.name, args)
                            forward(base, method, args)
                        }
                    }
                } as ZchatPreferences
            field.set(vm, logging)
        }
    }

    /**
     * ZC-946-3 (review 4, nit 4): a store call as the golden compares it, its name WITH its arguments, so a cache that
     * changed what is written fails the golden too. The continuation of a suspend call is left out; an identity hash
     * in a rendering (an object without its own toString) is masked, since the two chats hold different instances; and
     * a wall-clock reading (a Long within a day of now, such as a pending send's first-seen time) is shown as <now>,
     * since the two chats run milliseconds apart.
     */
    private fun storeCall(
        name: String,
        args: Array<out Any?>?,
    ): String {
        val now = System.currentTimeMillis()
        val shown =
            args.orEmpty().filterNot { it is Continuation<*> }.joinToString { arg ->
                when (arg) {
                    is Long -> if (kotlin.math.abs(arg - now) < DAY_MS) "<now>" else arg.toString()
                    is Array<*> -> arg.contentDeepToString()
                    is ByteArray -> arg.contentToString()
                    is IntArray -> arg.contentToString()
                    is LongArray -> arg.contentToString()
                    else -> arg.toString()
                }
            }
        return "$name(${shown.replace(Regex("@[0-9a-f]{4,}"), "@#")})"
    }

    /** The continuation a reflective call of a suspend function carries as its last argument. */
    @Suppress("UNCHECKED_CAST")
    private fun continuation(last: Any?): Continuation<List<String>> = last as Continuation<List<String>>

    /** A synchronizer whose memo read answers from the recorded wallet (one output per memo) and counts its reads. */
    private class WalletMemoSdk(
        private val txs: List<ChainTx>,
    ) {
        val reads = AtomicInteger()

        val synchronizer: Synchronizer =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
                when (method.name) {
                    "getMemos" -> memos(args!![0] as TransactionOverview)
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "WalletMemoSdk"
                    else -> throw UnsupportedOperationException("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer

        val provider: SynchronizerProvider =
            object : SynchronizerProvider {
                override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
                override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(this@WalletMemoSdk.synchronizer)

                override suspend fun getSynchronizer(): Synchronizer = this@WalletMemoSdk.synchronizer

                override fun resetSynchronizer() = Unit
            }

        private fun memos(overview: TransactionOverview): Flow<String> {
            reads.incrementAndGet()
            val tx = txs.firstOrNull { SendAttemptHarness.txId(it.number) == overview.txId.txIdString() }
            return flow { tx?.memo?.let { emit(it) } }
        }
    }

    private fun forward(
        base: Any,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(base, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun latch(): AtomicBoolean =
        ChatViewModel::class.java
            .getDeclaredField("processHistoryRead")
            .apply { isAccessible = true }
            .get(null) as AtomicBoolean
}
