package co.electriccoin.zcash.ui.screen.transactiondetail

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.TexApi
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.txid
import co.electriccoin.zcash.ui.common.usecase.DetailedTransactionData
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ReloadHandle
import co.electriccoin.zcash.ui.common.usecase.SendTransactionAgainUseCase
import co.electriccoin.zcash.ui.keystone.RouteRecordingNavigationRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-400 (fix-all 2.13.0, train T5b; t5b-add SPECS §6): "Send again" on a plain Wallet send proven never mined
 * pre-fills ONE new Send form, however fast the user taps.
 *
 * At 97acc9d45 every tap on the button calls `onSendAgainClick`, which launches `markSendAgainUsed(txId)` then
 * `sendTransactionAgain(transaction)`. `markSendAgainUsed` serializes on the checker's lock and returns Unit, so a second
 * tap before the button disappears also navigates: two pre-filled Send forms for one send proven never mined (the
 * owner's "once", W-10).
 *
 * Wanted: `TexSendMonitor.markSendAgainUsed(txId)` answers true only for the first caller in this process for that
 * send's hash (still recording the mark as today), and the detail pre-fills a proven-not-sent send only on true. A
 * mined send's repeat "Send again" (Zashi's repeat payment) is unchanged. Money: none (the pre-fill sends nothing).
 * Reached by name or over the F3 fakes, as PlainSendAgainOnlyOnceTest; the view model is allocated without its
 * constructor and its fields set by reflection, as the ChatViewModel harnesses build theirs. All txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendAgainTwoTapsOpenOneFormTest {
    private companion object {
        const val E = 6_000L
        const val PAYEE = "u1fixtureplainpayeenotreal0400"
        const val AMOUNT = 1_000L
        const val FEE = 10_000L
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 1_000L
        val P: String = txid(0xD3)
        val M: String = txid(0xD7)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun plain(
        txId: String,
        mined: Long?,
    ): Any =
        TexApi.facts(
            txId = txId,
            mined = mined,
            expiry = E,
            outputs = listOf(TexApi.output(PAYEE, isOwn = false, kind = "OTHER")),
        )

    private fun wallet() = FakeTexWallet().apply { transactions = listOf(plain(P, null), plain(M, E - 30)) }

    private fun monitor(
        wallet: FakeTexWallet,
        store: Any,
    ): Any = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store)

    /** Two passes past the expiry and the margin: P is proven never mined, M is mined. */
    private suspend fun proveDead(
        monitor: Any,
        wallet: FakeTexWallet,
    ): Any? {
        wallet.tip = E + 10
        TexApi.pass(monitor)
        wallet.tip = E + 11
        TexApi.pass(monitor)
        return TexSameBlockFixture.check(monitor)
    }

    @Test
    fun two_concurrent_marks_on_a_proven_dead_send_answer_true_once() =
        runBlocking {
            val store = TexApi.memoryStore()
            val wallet = wallet()
            val first = monitor(wallet, store)
            val check = proveDead(first, wallet)
            assertTrue(TexSameBlockFixture.sendAgainAllowed(P, check), "fixture: the dead send is offered Send again")

            val gate = CountDownLatch(1)
            val answers =
                (1..2)
                    .map {
                        async(Dispatchers.Default) {
                            gate.await()
                            TexApi.callSuspend(first, "markSendAgainUsed", P)
                        }
                    }.also { gate.countDown() }
                    .awaitAll()
            assertEquals(1, answers.count { it == true }, "two taps: exactly one caller may pre-fill, answers $answers")
            assertEquals(1, answers.count { it == false }, "two taps: the other caller must be refused, answers $answers")
            assertEquals(false, TexApi.callSuspend(first, "markSendAgainUsed", P), "a third tap may pre-fill again")

            val restartedBeforeAPass = monitor(wallet, store)
            assertEquals(
                false,
                TexApi.callSuspend(restartedBeforeAPass, "markSendAgainUsed", P),
                "after a restart (before any pass) a used Send again may pre-fill again",
            )
            val restarted = monitor(wallet, store)
            proveDead(restarted, wallet)
            assertEquals(
                false,
                TexApi.callSuspend(restarted, "markSendAgainUsed", P),
                "after a restart a used Send again may pre-fill again",
            )

            @Suppress("UNCHECKED_CAST")
            val hashes = TexApi.call(store, "sendAgainUsedHashes") as Set<String>
            assertTrue(TexApi.recordKey(P) in hashes, "the mark is no longer written to the store")
        }

    @Test
    fun two_quick_taps_on_send_again_of_a_proven_dead_send_open_one_send_form() =
        runBlocking {
            val wallet = wallet()
            val monitor = monitor(wallet, TexApi.memoryStore())
            val check = proveDead(monitor, wallet)
            val router = RouteRecordingNavigationRouter()
            val vm = detailVm(P, monitor as TexSendMonitor, router)

            tapTwiceAtOnce(vm, data(P), check)

            assertEquals(listOf("forward(Send)"), settledForwards(router), "two taps opened ${router.commands}")
            assertTrue(
                !TexSameBlockFixture.sendAgainAllowed(P, TexSameBlockFixture.check(monitor)),
                "Send again is still offered after it was used",
            )
        }

    @Test
    fun a_mined_send_keeps_its_repeat_send_again() =
        runBlocking {
            val wallet = wallet()
            val monitor = monitor(wallet, TexApi.memoryStore())
            val check = proveDead(monitor, wallet)
            val router = RouteRecordingNavigationRouter()
            val vm = detailVm(M, monitor as TexSendMonitor, router)

            tapTwiceAtOnce(vm, data(M), check)

            assertEquals(
                listOf("forward(Send)", "forward(Send)"),
                settledForwards(router, expected = 2),
                "a mined send's repeat payment changed: ${router.commands}",
            )
        }

    /** The two taps, each on its own thread, released together. */
    private fun tapTwiceAtOnce(
        vm: TransactionDetailVM,
        data: DetailedTransactionData,
        check: Any?,
    ) {
        val click =
            TransactionDetailVM::class.java
                .getDeclaredMethod("onSendAgainClick", DetailedTransactionData::class.java, TexCheck::class.java)
                .apply { isAccessible = true }
        val gate = CountDownLatch(1)
        val taps =
            (1..2).map {
                thread {
                    gate.await()
                    click.invoke(vm, data, check)
                }
            }
        gate.countDown()
        taps.forEach { it.join(WAIT_MS) }
    }

    /** The forwards once the taps' coroutines have run: waits for [expected], then a little more for any extra one. */
    private fun settledForwards(
        router: RouteRecordingNavigationRouter,
        expected: Int = 1,
    ): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (router.commands.size < expected && System.nanoTime() < deadline) Thread.sleep(10)
        Thread.sleep(SETTLE_MS)
        return synchronized(router.commands) { router.commands.toList() }
    }

    private fun detailVm(
        txId: String,
        monitor: TexSendMonitor,
        router: RouteRecordingNavigationRouter,
    ): TransactionDetailVM {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val vm =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, TransactionDetailVM::class.java) as TransactionDetailVM
        setField(vm, "transactionDetailArgs", TransactionDetailArgs(txId))
        setField(vm, "texSendMonitor", monitor)
        setField(vm, "navigationRouter", router)
        setField(vm, "sendTransactionAgain", SendTransactionAgainUseCase(PrefillSendUseCase(), router))
        return vm
    }

    private fun setField(
        vm: TransactionDetailVM,
        name: String,
        value: Any?,
    ) {
        TransactionDetailVM::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    private fun data(txId: String): DetailedTransactionData {
        val id = TransactionId.new(txId)
        return DetailedTransactionData(
            transaction =
                SendTransaction.Failed(
                    id = id,
                    amount = Zatoshi(AMOUNT),
                    timestamp = null,
                    memoCount = 0,
                    fee = Zatoshi(FEE),
                    transactionOutputs = emptyList(),
                    overview = overview(id),
                    recipient = null,
                ),
            memos = emptyList(),
            contact = null,
            metadata = TransactionMetadata(isBookmarked = false, isRead = true, note = null, swapMetadata = null),
            swap = null,
            reloadHandle =
                object : ReloadHandle {
                    override fun requestReload() = Unit
                },
        )
    }

    private fun overview(id: TransactionId): TransactionOverview =
        TransactionOverview(
            id,
            null,
            BlockHeight.new(E),
            null,
            null,
            true,
            Zatoshi(AMOUNT + FEE),
            Zatoshi(AMOUNT + FEE),
            Zatoshi(0),
            Zatoshi(FEE),
            false,
            0,
            1,
            0,
            null,
            TransactionState.Expired,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )
}
