package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.BALANCE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.KEY_STATE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OWN_KEY_ID
import co.electriccoin.zcash.ui.screen.changeidentity.ChangeIdentityVM
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManagerImpl
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityMode
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI key round 3 (W-97; train-AIKEY/ROUND3.md item 4; GPT 4, Grok 5 of review-final1): "warning shown" means shown. Without
 * that fact a Reset whose AI store read fails refuses; with it, it deletes a key that may open paid credit. So ResetZashiVM
 * says true only when the line was UNREADABLE on the screen at the tap, and an older read never paints over the check's
 * answer on the Full Wallet Reset dialog.
 *
 * WHY IT FAILS TODAY: onConfirmClicked waits for the check and then reads the line of that moment, which the user never saw:
 * the screen is left at once. ChangeIdentityVM.readAiLine starts a second read that may end after the check, and shows what
 * it read before the check's answer was written. ChangeIdentityVM's own "shown" fact already takes the line at the tap.
 *
 * Not pinned: whether Confirm then hands on with false or stays on the screen for a second tap. Real time, as in
 * ResetScreenAiCheckIsBoundedTest. Everything here is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiWarningShownMeansOnTheScreenTest {
    private val phone = AiKeyScreensFixture()
    private val gate = StoreGate()
    private val main = Executors.newSingleThreadExecutor()
    private val forwarded = CopyOnWriteArrayList<Any>()
    private val router: NavigationRouter =
        ResetDriver.standIn { name, args ->
            if (name == "forward") forwarded.addAll((args[0] as Array<*>).filterNotNull())
            Unit
        }
    private val watchers = ArrayList<Job>()
    private val screens = ArrayList<androidx.lifecycle.ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        val open = { name: String -> gate.around(phone.files.Library(name).open()) }
        StoreOpenerApi.useCalls(
            StoreOpenerApi.aiHolder(phone.context),
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ -> open(name) },
                createByKeyName = { name, _ -> open(name) },
            ),
        )
    }

    @After
    fun tearDown() {
        gate.release()
        watchers.forEach { it.cancel() }
        screens.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            screens.forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(SCREEN_END_MS) {
                    screen.viewModelScope.coroutineContext[Job]?.join()
                }
            }
        }
        repeat(MAIN_TRIES) {
            try {
                Dispatchers.resetMain()
                main.shutdownNow()
                phone.close()
                return
            } catch (_: IllegalStateException) {
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun waitFor(
        limitMs: Long = WAIT_MS,
        condition: () -> Boolean,
    ): Boolean {
        val end = System.currentTimeMillis() + limitMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
        return condition()
    }

    // ---------------------------------------------------------------------------------------------- the Reset screen

    private fun resetScreen(): ResetZashiVM {
        val vm = ResetZashiVM(router, phone.access)
        screens += vm
        watchers += CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        Thread.sleep(SCREEN_UP_MS)
        vm.state.value.checkboxState
            .let { if (it.isChecked) it.onClick() }
        assertTrue("fixture: the box stays ticked", waitFor { !vm.state.value.checkboxState.isChecked })
        return vm
    }

    private fun forwards() = forwarded.filterIsInstance<ResetZashiConfirmationArgs>()

    private fun showsUnreadable(vm: ResetZashiVM) =
        vm.state.value.checkboxState.subtitle == stringRes(R.string.delete_wallet_checkbox_warning_unchecked_ai_unreadable)

    private fun showsLinked(vm: ResetZashiVM) =
        vm.state.value.checkboxState.subtitle == stringRes(R.string.delete_wallet_checkbox_warning_unchecked_ai_linked)

    @Test
    fun `4 - LINKED on the screen at the tap, the check ends UNREADABLE - the Reset is not told it was shown`() {
        val answerNow = CompletableDeferred<Unit>()
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = {
            answerNow.await()
            200 to """{"balanceUsd":1.0,"signInKeyId":"$OWN_KEY_ID","paid":true}"""
        }
        val vm = resetScreen()
        assertTrue("fixture: the re-check did not start", waitFor { phone.requests.toList() == listOf(BALANCE) })
        assertTrue("fixture: the line", waitFor { showsLinked(vm) })

        vm.state.value.buttonState
            .onClick()
        gate.failReads = true
        answerNow.complete(Unit)

        assertTrue("the warning never reached the screen", waitFor { showsUnreadable(vm) })
        Thread.sleep(SETTLE_MS)
        assertEquals("the Reset may now delete a key on a warning nobody saw", emptyList<Boolean>(), toldShown())
    }

    @Test
    fun `4 - the first read hangs, Confirm tapped with the plain line, the limit makes it UNREADABLE - not told it was shown`() {
        phone.store()
        gate.hangReads = true
        val vm = resetScreen()

        vm.state.value.buttonState
            .onClick()

        assertTrue("the warning never reached the screen", waitFor(AI_CHECK_LIMIT_MS + MARGIN_MS) { showsUnreadable(vm) })
        Thread.sleep(SETTLE_MS)
        assertEquals("the Reset may now delete a key on a warning nobody saw", emptyList<Boolean>(), toldShown())
    }

    private fun toldShown(): List<Boolean> = forwards().map { it.aiUnreadableWarningShown }.filter { it }

    @Test
    fun `4 guard - UNREADABLE on the screen at the tap and the check over - the Reset is told it was shown`() {
        phone.store()
        gate.failReads = true
        val vm = resetScreen()
        assertTrue("fixture: the line", waitFor { showsUnreadable(vm) })
        Thread.sleep(SETTLE_MS)

        vm.state.value.buttonState
            .onClick()

        assertTrue("Confirm did nothing", waitFor { forwards().size == 1 })
        assertTrue(forwards().single().aiUnreadableWarningShown)
    }

    // --------------------------------------------------------------------------------------------- Full Wallet Reset

    @Test
    fun `4 - Full Wallet Reset - a read started before the check's answer does not paint over it`() {
        val answerNow = CompletableDeferred<Unit>()
        phone.store()
        phone.answer = {
            answerNow.await()
            200 to """{"signInKeyId":"$OWN_KEY_ID","paid":true}"""
        }
        val vm = identityScreen()
        val note = { vm.state.value.aiResetNote }
        vm.state.value.onModeSelected(IdentityMode.FULL_RESET)
        waitFor { vm.state.value.selectedMode == IdentityMode.FULL_RESET }

        vm.state.value.onConfirmClick()
        assertTrue("fixture: the check did not start", waitFor { phone.requests.toList() == listOf(KEY_STATE) })
        assertTrue("fixture: the stored line", waitFor { note() == R.string.identity_reset_ai_note_ai_kept })
        vm.state.value.onConfirmationDialogDismiss()

        gate.holdNextLinkRead.set(true)
        vm.state.value.onConfirmClick()
        assertTrue("fixture: the second read did not start", gate.readHeld.await(WAIT_MS, TimeUnit.MILLISECONDS))

        answerNow.complete(Unit)
        assertTrue("fixture: the check's answer", waitFor { note() == R.string.identity_reset_ai_note_ai_linked })

        gate.release()
        Thread.sleep(SETTLE_MS)
        assertEquals("the older read painted over the check's answer", R.string.identity_reset_ai_note_ai_linked, note())
    }

    private fun identityScreen(): ChangeIdentityVM {
        val vm =
            ChangeIdentityVM(
                navigationRouter = router,
                getDefaultUnifiedAddress = bare(GetDefaultUnifiedAddressUseCase::class.java),
                zchatPreferences = noContacts(),
                resetZashiUseCase = bare(ResetZashiUseCase::class.java),
                identityManager = IdentityManagerImpl(MemoryContext()),
                accountDataSource = unused(),
                aiAccountAccess = phone.access,
            )
        screens += vm
        return vm
    }

    private fun noContacts(): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, m, a ->
            when (m.name) {
                "getAllContactAddresses", "getAllConversationPeerAddresses" -> emptySet<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                "toString" -> "NoContactsPrefs"
                else -> throw UnsupportedOperationException("unexpected preferences call ${m.name}")
            }
        } as ZchatPreferences

    private fun unused(): AccountDataSource =
        Proxy.newProxyInstance(AccountDataSource::class.java.classLoader, arrayOf(AccountDataSource::class.java)) { p, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                "toString" -> "UnusedAccounts"
                else -> throw UnsupportedOperationException("unexpected call ${m.name}")
            }
        } as AccountDataSource

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> bare(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, type) as T
    }

    private class MemoryContext : ContextWrapper(null) {
        private val files = HashMap<String, SharedPreferences>()

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences = files.getOrPut(name) { MemorySharedPreferences() }

        override fun getApplicationContext(): Context = this
    }

    /** The AI store's file: reads can fail or hang; [holdNextLinkRead] holds one read of the link AFTER it has read it. */
    private class StoreGate {
        @Volatile
        var hangReads = false

        @Volatile
        var failReads = false

        val holdNextLinkRead = AtomicBoolean(false)
        val readHeld = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun release() = released.countDown()

        private fun waitForRelease() {
            while (true) {
                try {
                    released.await()
                    return
                } catch (_: InterruptedException) {
                    // A blocking disk or Keystore read need not answer an interrupt.
                }
            }
        }

        fun around(real: SharedPreferences): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                if (hangReads) waitForRelease()
                if (failReads) throw IllegalStateException("synthetic: the store cannot be read")
                val value =
                    try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                if (method.name == "getString" && args?.get(0) == LINK_STATE && holdNextLinkRead.compareAndSet(true, false)) {
                    readHeld.countDown()
                    waitForRelease()
                }
                value
            } as SharedPreferences
    }

    private companion object {
        const val LINK_STATE = "ai_link_state"
        const val MARGIN_MS = 2_500L
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
        const val MAIN_TRIES = 50
        const val SCREEN_END_MS = 5_000L
        const val SCREEN_UP_MS = 200L
        const val SETTLE_MS = 500L
    }
}
