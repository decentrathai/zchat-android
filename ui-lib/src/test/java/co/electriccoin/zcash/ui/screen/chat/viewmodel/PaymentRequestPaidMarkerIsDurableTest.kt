package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.util.concurrent.ConcurrentHashMap

/**
 * B2 / Grok Q4 + Grok checklist R5-2 "outside the five" (PAY-REQUEST-MARK-APPLY): the record that a payment
 * request was paid is written with `apply()`, so a kill right after paying can bring Pay back.
 *
 * `fulfilPaymentRequestToOutcome` calls `ZchatPreferences.markRequestPaid` after the submit (and on a cancelled
 * settlement). `markRequestPaid` ends in `SharedPreferences.apply()`: the in-memory copy changes at once, the
 * disk later, and a failed write is never reported. The in-flight claim and `paidRequestIds` live only in
 * memory. If the process dies before the write lands, the restarted app's `getPaidRequestIds()` lacks the id,
 * a NOSTR-delivered request bubble shows Pay before the history scan-back has read our ZREQ_FULFILL memo, and a
 * second tap pays the full requested amount again.
 *
 * Wanted:
 * - the marker is written with a checked, synchronous write (it survives a kill right after it returns);
 * - when that write reports failure, this process keeps the request claimed (no second payment from any chat
 *   screen) and tells the user the payment may have been sent, never "Payment sent".
 *
 * The first test drives a real [ZchatPreferencesImpl] over a preferences file whose memory and disk are kept
 * apart (`apply()` = memory only, `commit()` = disk), the OwedKeyStampIsDurableBeforePayTest idiom. The other
 * two drive the real fulfil path ([PaymentHarness]). All ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestPaidMarkerIsDurableTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val AMOUNT = 50_000_000L
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
    fun `a request marked paid is still paid after the process is killed`() {
        val disk = ConcurrentHashMap<String, Any>()

        storeOver(SplitPrefs(disk)).markRequestPaid("synthetic-request-R1")
        // The process dies here: whatever apply() had not written is gone. A new process reads the disk.
        val restarted = storeOver(SplitPrefs(disk))

        assertTrue(
            "the paid marker never reached the disk, so after a restart the request shows Pay and can be paid again",
            restarted.getPaidRequestIds().contains("synthetic-request-R1"),
        )
    }

    @Test
    fun `when the paid marker cannot be saved no chat screen in this process pays the request again`() =
        runBlocking {
            val peer = "usyntheticpaidmarker00000001"
            val requestId = "synthetic-request-unsaved-1"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer)

            val first = openChat(prefs, seam)
            first.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(first)
            assertEquals("fixture sanity: the request was paid once", 1, seam.submits.get())

            // The user reopens the chat (a fresh ChatViewModel loads the paid set from the store, which has none).
            val reopened = openChat(prefs, seam)
            reopened.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(reopened)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "the marker that stops a second payment was not saved, the claim was released, and the request was paid again",
                1,
                seam.submits.get(),
            )
        }

    @Test
    fun `when the paid marker cannot be saved the user is told the payment may have been sent`() =
        runBlocking {
            val peer = "usyntheticpaidmarker00000002"
            val requestId = "synthetic-request-unsaved-2"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer)

            val vm = openChat(prefs, seam)
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            val state = PaymentHarness.sendMessageState(vm)
            assertTrue(
                "the paid marker could not be saved, but the screen said $state instead of asking the user to check",
                state is SendMessageState.Error && state.message.contains("may have been sent"),
            )
        }

    // --------------------------------------------------------------- harness

    /**
     * A chat screen over [prefs] whose `markRequestPaid` reports that the write did not reach the disk once the
     * payment was broadcast. C3 (B2 review item 5): the marker is also written before the submit; a store that
     * fails that first write sends nothing (PaymentRequestMarkedBeforeSubmitTest), so these two tests fail only
     * the write that follows the broadcast, the case they are about.
     */
    private suspend fun openChat(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
    ): ChatViewModel {
        val vm = paymentViewModel(prefs, seam)
        PaymentHarness.setField(vm, "zchatPreferences", markerNotSaved(prefs.proxy, seam))
        return vm
    }

    /** [base], except that marking a request paid after [seam] broadcast stores nothing and reports failure. */
    private fun markerNotSaved(
        base: ZchatPreferences,
        seam: PaymentSeam,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "markRequestPaid" -> if (seam.submits.get() == 0) base.markRequestPaid(args!![0] as String) else false
                "toString" -> "MarkerNotSaved($base)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    /**
     * A preferences file with a separate memory and disk. Opening it (a process start) loads memory from the
     * disk. `apply()` updates memory only, as a write still queued when the process dies; `commit()` writes
     * memory and the disk and reports success.
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
                        "putString" -> {
                            staged[args!![0] as String] = args[1]
                            editor
                        }
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
                    "getString" -> memory[args!![0] as String] ?: args[1]
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
