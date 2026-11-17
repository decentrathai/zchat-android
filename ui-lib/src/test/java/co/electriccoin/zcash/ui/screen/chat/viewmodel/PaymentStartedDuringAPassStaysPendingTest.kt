package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paymentPending
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-452 (KI-194; review-ultra 1 Oct, chunk 1 finding 3): a payment started while a history pass runs stays
 * "Payment pending".
 *
 * At 5eb9d5c18 `ChatViewModel.fulfilPendingThisPass` reads the process-wide in-flight claims once at its top, then reads
 * the started records, and a complete pass ends with `fulfilPendingIds.retainAll(pending)`. A Pay tap whose claim and
 * whose `fulfilPendingIds += id` both fall between that first read and the `retainAll` is not in `pending`, so the pass
 * drops the id from the process-wide set and returns a set without it: the request bubble says "Paid" for that pass
 * instead of "Payment pending". Pay stays hidden (the paid marker is written before the submit): a wrong status label,
 * not a second payment.
 *
 * Wanted: a complete pass removes from the process-wide set only the ids that were in it when the pass started and are
 * no longer pending, and an id added during the pass counts as pending for the rows of that pass too.
 *
 * The race is injected where it can happen: a preferences proxy whose `getFulfilStarted`, when `fulfilPendingThisPass`
 * calls it (after the in-flight read), first does once what a Pay tap does at its claim and in onSubmitStarting
 * (`fulfilInFlight.add(id)`, `fulfilPendingIds.add(id)`), then forwards. The real history pass over
 * [FulfilStoredIdsHarness]; every id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentStartedDuringAPassStaysPendingTest {
    private val sendScopes = SendHarnessScopes()
    private val used = mutableSetOf<String>()

    private companion object {
        const val PEER = "usyntheticzc452racepeer000001"
        const val AMOUNT = 1_000L
        const val E = H + 40
        const val PASS_FUNCTION = "fulfilPendingThisPass"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Process-wide statics, shared by every test of a Gradle worker.
        pendingIds().removeAll(used)
        inFlight().removeAll(used)
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a payment started during a complete pass says Payment pending and stays in the pending set`() =
        runBlocking {
            val requestId = id("synthetic-zc452-request-a")
            val stale = id("synthetic-zc452-stale-a")
            val h = FulfilStoredIdsHarness(PEER)
            val wallet = Wallet(scanned = E - 39, txs = emptyList())
            val vm = paidRequest(h, requestId, wallet)
            h.passAt(vm, wallet, E - 38)
            h.passAt(vm, wallet, E - 37)

            pendingIds().add(stale)
            val tap = PayTapDuringThePass(vm, requestId)
            val row = h.passAt(vm, wallet, E - 36).single { it.id == requestId }

            assertEquals("fixture: the Pay tap fell inside the pass, once", 1, tap.injected.get())
            assertFalse("fixture: the third pass is complete (it drops an id that is no longer pending)", stale in pendingIds())
            assertEquals("fixture: Pay stays hidden", true, row.paymentRequest?.isPaid)
            assertTrue(
                "a complete pass dropped the id of a payment that started while it ran from the pending set",
                requestId in pendingIds(),
            )
            assertEquals(
                "a payment that started while the pass ran says Paid, not Payment pending",
                true,
                paymentPending(row),
            )
            assertEquals("fixture sanity", emptyList<String>(), h.prefs.unexpectedCalls)
        }

    @Test
    fun `control - a complete pass drops an id that was pending at its start and is not any more`() =
        runBlocking {
            val requestId = id("synthetic-zc452-request-b")
            val h = FulfilStoredIdsHarness(PEER)
            val wallet = Wallet(scanned = E - 39, txs = emptyList())
            val vm = paidRequest(h, requestId, wallet)
            h.passAt(vm, wallet, E - 38)
            h.passAt(vm, wallet, E - 37)

            // In the set at the start; no started record, no claim, no sighting: nothing says it is still pending.
            pendingIds().add(requestId)
            val row = h.passAt(vm, wallet, E - 36).single { it.id == requestId }

            assertEquals("fixture: Pay stays hidden", true, row.paymentRequest?.isPaid)
            assertFalse("a complete pass kept an id that is no longer pending", requestId in pendingIds())
            assertEquals("a request whose payment is no longer pending still says Payment pending", false, paymentPending(row))
            assertEquals("fixture sanity", emptyList<String>(), h.prefs.unexpectedCalls)
        }

    @Test
    fun `control - a payment started during an incomplete pass says Payment pending and the set is kept`() =
        runBlocking {
            val requestId = id("synthetic-zc452-request-c")
            val stale = id("synthetic-zc452-stale-c")
            val h = FulfilStoredIdsHarness(PEER)
            val wallet = Wallet(scanned = E - 39, txs = emptyList())
            val vm = paidRequest(h, requestId, wallet)

            pendingIds().add(stale)
            val tap = PayTapDuringThePass(vm, requestId)
            // The first pass of a view model is never complete (it is not stable yet).
            val row = h.passAt(vm, wallet, E - 38).single { it.id == requestId }

            assertEquals("fixture: the Pay tap fell inside the pass, once", 1, tap.injected.get())
            assertTrue("fixture: the first pass is incomplete (it keeps what the set held)", stale in pendingIds())
            assertEquals("fixture: Pay stays hidden", true, row.paymentRequest?.isPaid)
            assertTrue("an incomplete pass dropped the id of a payment that started while it ran", requestId in pendingIds())
            assertEquals("a payment that started during an incomplete pass says Paid", true, paymentPending(row))
            assertEquals("fixture sanity", emptyList<String>(), h.prefs.unexpectedCalls)
        }

    /**
     * ZC-762 (T7b triage 13 Oct): a Pay that the node refused with nothing sent withdraws its paid marker and its
     * started record (settleFulfilFailure), but at 828ed7c0a it leaves the request's id in the process-wide pending set
     * that its onSubmitStarting added. A second Pay tapped while the next complete pass runs is then not "added during
     * the pass" (ZC-452's rule compares with the set at the start of the pass), so that pass drops the id and the
     * request says "Paid" instead of "Payment pending" for one pass. Pay stays hidden and no money moves.
     */
    @Test
    fun `a Pay refused with nothing sent leaves no pending id, and a Pay during the next pass says Payment pending`() =
        runBlocking {
            val requestId = id("synthetic-zc762-request-d")
            val h = FulfilStoredIdsHarness(PEER)
            val wallet = Wallet(scanned = E - 39, txs = emptyList())
            val refused =
                FulfilStoredIdsHarness.IdsSeam(
                    PEER,
                    emptyList(),
                    SubmitResult.Failure(emptyList(), code = 0, description = "synthetic refusal"),
                )
            val vm = h.viewModel(refused, requestId, AMOUNT)
            h.installWallet(vm, wallet)
            h.passAt(vm, wallet, E - 38)
            h.passAt(vm, wallet, E - 37)

            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture: the first Pay reached the submit once", 1, refused.submits.get())
            assertFalse("fixture: the paid marker of a Pay that sent nothing is withdrawn", requestId in h.prefs.paid)
            assertFalse("fixture: its started record is withdrawn", h.prefs.started.containsKey(requestId))
            assertFalse(
                "a Pay refused with nothing sent left its id in the process-wide pending set",
                requestId in pendingIds(),
            )

            // The second Pay: its paid marker as onSubmitStarting writes it, and its claim during the next pass.
            h.prefs.paid += requestId
            paidInMemory(vm).value += requestId
            val loaded = PaymentHarness.pendingMessages(vm).single { it.id == requestId }
            val paidRow = loaded.copy(paymentRequest = loaded.paymentRequest?.copy(isPaid = true, paymentPending = false))
            PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(paidRow)))
            val tap = PayTapDuringThePass(vm, requestId)
            val row = h.passAt(vm, wallet, E - 36).single { it.id == requestId }

            assertEquals("fixture: the Pay tap fell inside the pass, once", 1, tap.injected.get())
            assertEquals("fixture: Pay stays hidden", true, row.paymentRequest?.isPaid)
            assertEquals(
                "a second Pay during the pass after a refused one says Paid, not Payment pending",
                true,
                paymentPending(row),
            )
        }

    private fun id(value: String): String = value.also { used += it }

    /**
     * A chat whose request [requestId] is already paid as a Pay leaves it (the paid marker on disk and in memory, Pay
     * hidden, no pending flag), over [wallet]. No started record: the test's Pay tap has not written one yet.
     */
    private suspend fun paidRequest(
        h: FulfilStoredIdsHarness,
        requestId: String,
        wallet: Wallet,
    ): ChatViewModel {
        val seam = FulfilStoredIdsHarness.IdsSeam(PEER, emptyList(), SubmitResult.Success(emptyList()))
        val vm = h.viewModel(seam, requestId, AMOUNT)
        h.prefs.paid += requestId
        paidInMemory(vm).value += requestId
        val loaded = PaymentHarness.pendingMessages(vm).single { it.id == requestId }
        val row: ChatMessage = loaded.copy(paymentRequest = loaded.paymentRequest?.copy(isPaid = true, paymentPending = false))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(row)))
        h.installWallet(vm, wallet)
        return vm
    }

    /**
     * Wraps [vm]'s preferences once more: the first `getFulfilStarted` that `fulfilPendingThisPass` makes (its in-flight
     * read is behind it) first does what a Pay tap of [requestId] does at its claim and in onSubmitStarting, then
     * forwards. The reconcile's own `getFulfilStarted`, earlier in the pass, is forwarded untouched.
     */
    private inner class PayTapDuringThePass(
        vm: ChatViewModel,
        private val requestId: String,
    ) {
        val injected = AtomicInteger()

        init {
            val field = ChatViewModel::class.java.getDeclaredField("zchatPreferences").apply { isAccessible = true }
            val base = field.get(vm) as ZchatPreferences
            val proxy =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { p, method, args ->
                    when (method.name) {
                        "getFulfilStarted" -> {
                            val insideThePass = Throwable().stackTrace.any { it.methodName == PASS_FUNCTION }
                            if (insideThePass && injected.compareAndSet(0, 1)) {
                                inFlight().add(requestId)
                                pendingIds().add(requestId)
                            }
                            forward(base, method, args)
                        }
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> p === args?.get(0)
                        "toString" -> "PayTapDuringThePassPrefs"
                        else -> forward(base, method, args)
                    }
                } as ZchatPreferences
            field.set(vm, proxy)
        }

        private fun forward(
            base: ZchatPreferences,
            method: java.lang.reflect.Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun paidInMemory(vm: ChatViewModel): MutableStateFlow<Set<String>> =
        ChatViewModel::class.java
            .getDeclaredField("paidRequestIds")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<Set<String>>

    @Suppress("UNCHECKED_CAST")
    private fun pendingIds(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("fulfilPendingIds")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private fun inFlight(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("fulfilInFlight")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>
}
