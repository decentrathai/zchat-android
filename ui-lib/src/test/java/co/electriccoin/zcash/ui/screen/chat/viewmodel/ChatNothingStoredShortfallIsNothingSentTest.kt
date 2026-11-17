package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * D1 (REVIEW_7C F1 + F8, E1, F9 c) — the ChatViewModel side. The SDK reports a shortfall while it builds the
 * transaction, after onSubmitStarting ran: `Failure(txIds = [], code = 0, "Insufficient balance (have 0, need 17000
 * including fee)")`. That proves nothing was stored, but the use case rethrows it as a cause-less
 * InsufficientFundsException, so:
 *
 * - an automatic KEX keeps its paid-setup stamp and is never re-armed, so the key exchange stalls;
 * - a fulfil keeps the paid marker it wrote before the submit: the request shows a false "Paid" for good, and the
 *   user is told the payment may have been sent;
 * - (E1) a 1:1 message, a payment request and a reply check only the TOP-LEVEL type for the nothing-stored proof,
 *   so the row is kept as "possibly sent" with Retry blocked.
 *
 * And (F9 c): the withdrawn paid marker is written with apply(), so a kill right after it brings the false "Paid"
 * back on the next start.
 *
 * Drives the real ChatViewModel send paths through the real CreateChunkedMessageProposalUseCase and
 * ZashiProposalRepositoryImpl ([PaymentHarness], [HandshakeHarness]). All ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNothingStoredShortfallIsNothingSentTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticd1chatshortfall001"
        const val AMOUNT = 50_000_000L
        const val SDK_SHORTFALL = "Insufficient balance (have 0, need 17000 including fee)"

        // ZC-1417 (QA2 k1): the notice carries the SDK's figures (have 0, need 17,000), no longer the static floor text.
        val SDK_SHORTFALL_COPY =
            ChatSendCopy.messageShortfall(Zatoshi(0L), Zatoshi(17_000L))
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun shortfallAfterSubmit(): SubmitResult = SubmitResult.Failure(txIds = emptyList(), code = 0, description = SDK_SHORTFALL)

    // ── (b) the automatic KEX ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a KEX the SDK fell short on after the submit started is re-armed and the next trigger pays it`() =
        runBlocking {
            val peer = "usyntheticd1kexshortfall0001"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            prefs.ownBootSent[peer] = true
            val seam = HandshakeSeam(peer, firstSubmitOutcome = shortfallAfterSubmit())
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals("fixture: one submit reached the SDK", 1, seam.submits.get())
            assertEquals("fixture sanity: the KEX's own boot stamp re-armed", false, prefs.ownBootSent[peer])

            prefs.ownBootSent[peer] = true // the next bootstrap trigger
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals(
                "the SDK stored nothing, but the KEX kept its paid-setup record (afterNothingSent never ran), so the " +
                    "next trigger read it as already paid and the key exchange stalls",
                1,
                seam.broadcasts.size,
            )
        }

    // ── (c) the fulfil ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a fulfil the SDK fell short on after the submit started withdraws its paid marker and stays payable`() =
        runBlocking {
            val requestId = "synthetic-d1-request-shortfall-1"
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = shortfallAfterSubmit())

            val vm = paymentViewModel(prefs, seam)
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK", 1, seam.submits.get())
            assertFalse("the SDK stored nothing, but the paid marker stayed on disk", requestId in prefs.paid)
            assertFalse(
                "the SDK stored nothing, but the request is still shown as Paid (no Pay button)",
                requestId in paidRequestIds(vm),
            )
            val state = PaymentHarness.sendMessageState(vm)
            assertNotEquals(
                "the SDK stored nothing, but the user is told the payment may have been sent",
                SendMessageState.Error(ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT),
                state,
            )
            assertEquals(
                "the user must be shown the shortfall",
                SendMessageState.Error(CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE),
                state,
            )

            // Pay again, now that the wallet can.
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("the request stayed unpayable after a failure that sent nothing", 2, seam.submits.get())
            assertTrue(requestId in prefs.paid)
        }

    // ── (e) E1: the 1:1 message, the payment request and the reply ──────────────────────────────────

    @Test
    fun `a 1to1 message the SDK fell short on after the submit started is failed, retryable and shows the shortfall`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = shortfallAfterSubmit())
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic d1 hello")
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK", 1, seam.submits.get())
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertNothingSentShown(vm, failed.id, SDK_SHORTFALL_COPY)

            assertTrue("Retry was refused for a message the SDK never stored", vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("Retry must send the message once", 2, seam.submits.get())
        }

    @Test
    fun `a payment request the SDK fell short on after the submit started is failed, retryable and shows the shortfall`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = shortfallAfterSubmit())
            val vm = paymentViewModel(prefs, seam)
            // ZC-1822: the request's question is asked only of a wallet that can pay it; the SDK still falls short here.
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts())

            assertTrue(vm.sendPaymentRequest(PEER, AMOUNT, "synthetic d1 request"))
            vm.confirmPaidAction() // Round 9 R9-1: a Shielded request asks for its cost first
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK", 1, seam.submits.get())
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertNothingSentShown(vm, failed.id, SDK_SHORTFALL_COPY)

            assertTrue(
                "Retry was refused for a request the SDK never stored",
                vm.retryMessage(PEER, failed.id, costConfirmed = true),
            )
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("Retry must send the request once", 2, seam.submits.get())
        }

    @Test
    fun `a reply the SDK fell short on after the submit started is failed, retryable and shows the shortfall`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = shortfallAfterSubmit())
            val vm = paymentViewModel(prefs, seam)
            val target = "synthetic-d1-target-id"

            vm.sendReply(PEER, "synthetic d1 reply", target, "synthetic preview")
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK", 1, seam.submits.get())
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertEquals("fixture sanity", target, failed.replyToId)
            assertNothingSentShown(vm, failed.id, SDK_SHORTFALL_COPY)

            assertTrue("Retry was refused for a reply the SDK never stored", vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("Retry must send the reply once", 2, seam.submits.get())
        }

    // ── (h) F9 c: the withdrawn marker survives a kill ───────────────────────────────────────────────

    @Test
    fun `a paid marker withdrawn after a nothing-sent fulfil stays withdrawn after a kill and a restart`() =
        runBlocking {
            val requestId = "synthetic-d1-request-kill-1"
            val disk = ConcurrentHashMap<String, Any>()
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = shortfallAfterSubmit())

            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "zchatPreferences", paidSetOn(storeOver(SplitPrefs(disk)), prefs))
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)
            assertEquals("fixture: one submit reached the SDK", 1, seam.submits.get())

            // The process is killed here: whatever apply() had not written is gone. A new process reads the disk.
            clearProcessClaims()
            val restartedStore = storeOver(SplitPrefs(disk))
            assertFalse(
                "the paid marker was withdrawn with apply(); after a kill it is back on disk and the request shows a " +
                    "false Paid for good",
                restartedStore.getPaidRequestIds().contains(requestId),
            )

            val restarted = paymentViewModel(prefs, seam)
            PaymentHarness.setField(restarted, "zchatPreferences", paidSetOn(restartedStore, prefs))
            PaymentHarness.setField(restarted, "paidRequestIds", MutableStateFlow(restartedStore.getPaidRequestIds()))
            restarted.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(restarted)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("after the restart the request must be payable once", 2, seam.submits.get())
            assertTrue(storeOver(SplitPrefs(disk)).getPaidRequestIds().contains(requestId))
        }

    // --------------------------------------------------------------- harness

    /**
     * The row [id] is FAILED with [copy] as its reason and is not held as possibly sent, and the screen shows [copy],
     * not the "may have been sent" notice.
     */
    private fun assertNothingSentShown(
        vm: ChatViewModel,
        id: String,
        copy: String,
    ) {
        val row = PaymentHarness.pendingMessages(vm).single { it.id == id }
        assertFalse("the SDK stored nothing, but the row is kept as possibly sent (Retry blocked)", row.sendMayHaveGoneOut)
        val state = PaymentHarness.sendMessageState(vm)
        assertNotEquals(
            "the SDK stored nothing, but the user is told it may have been sent",
            SendMessageState.Error(SubmitAttemptProof.MAY_HAVE_BEEN_SENT),
            state,
        )
        assertEquals("the user must be shown the shortfall", SendMessageState.Error(copy), state)
        assertEquals("the failed row must say why", copy, row.failureReason)
    }

    @Suppress("UNCHECKED_CAST")
    private fun paidRequestIds(vm: ChatViewModel): Set<String> =
        (
            ChatViewModel::class.java
                .getDeclaredField("paidRequestIds")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Set<String>>
        ).value

    /** A process restart forgets the process-wide in-flight claims (ChatViewModel companion). */
    private fun clearProcessClaims() {
        val field =
            ChatViewModel::class.java
                .getDeclaredField("fulfilInFlight")
                .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableSet<String>).clear()
    }

    /** [rest], except that the paid-request set is read and written through the real [store]. */
    private fun paidSetOn(
        store: ZchatPreferencesImpl,
        rest: PaymentPrefs,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getPaidRequestIds" -> store.getPaidRequestIds()
                "markRequestPaid" -> store.markRequestPaid(args!![0] as String)
                "unmarkRequestPaid" -> store.unmarkRequestPaid(args!![0] as String)
                // Round 8 P2: the fulfil's combined marker + started record write, on the same real store.
                "markFulfilStarted" ->
                    store.markFulfilStarted(
                        args!![0] as String,
                        args[1] as Long,
                        args[2] as String,
                        args[3] as Long,
                        args[4] as String,
                        args[5] as Long,
                    )
                "clearFulfilStarted" -> store.clearFulfilStarted(args!![0] as String)
                "getFulfilStarted" -> store.getFulfilStarted()
                "toString" -> "PaidSetOn($store)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(rest.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    /**
     * A preferences file with a separate memory and disk (the PaymentRequestPaidMarkerIsDurableTest idiom). Opening
     * it (a process start) loads memory from the disk. `apply()` updates memory only, as a write still queued when
     * the process dies; `commit()` writes memory and the disk and reports success.
     */
    private class SplitPrefs(
        private val disk: MutableMap<String, Any>,
    ) {
        private val memory = ConcurrentHashMap<String, Any>(disk)

        val prefs: SharedPreferences by lazy {
            val staged = mutableMapOf<String, Any?>()

            fun toMemory() {
                staged.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
            }
            lateinit var editor: SharedPreferences.Editor
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "putStringSet" -> {
                            @Suppress("UNCHECKED_CAST")
                            staged[args!![0] as String] = (args[1] as Set<String>?)?.toSet()
                            editor
                        }
                        "remove" -> {
                            staged[args!![0] as String] = null
                            editor
                        }
                        "apply" -> {
                            toMemory()
                            staged.clear()
                            null
                        }
                        "commit" -> {
                            toMemory()
                            staged.forEach { (k, v) -> if (v == null) disk.remove(k) else disk[k] = v }
                            staged.clear()
                            true
                        }
                        else -> editor
                    }
                } as SharedPreferences.Editor
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "edit" -> editor
                    "getStringSet" -> memory[args!![0] as String] ?: args[1]
                    "contains" -> memory.containsKey(args!![0] as String)
                    else -> null
                }
            } as SharedPreferences
        }
    }

    /** A [ZchatPreferencesImpl] with no Context whose main preferences file is [store] (R5-2 test idiom). */
    private fun storeOver(store: SplitPrefs): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        ZchatPreferencesImpl::class.java
            .getDeclaredField("prefs")
            .apply { isAccessible = true }
            .set(impl, store.prefs)
        return impl
    }
}
