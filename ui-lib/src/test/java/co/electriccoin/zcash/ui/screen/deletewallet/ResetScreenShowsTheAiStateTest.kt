package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.BALANCE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.CHALLENGE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.KEY_STATE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OTHER_KEY_ID
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OWN_KEY_ID
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.SIGN_IN
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.ai.AiTabAccessFixture
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_AI_READ
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI-KEY-T (W-52, W-53; AI-SIGNIN-KEY-SPEC.md revision 2b, sections 4.3, 5.3 and 6.6, change 5, F8): the Reset screen
 * shows ONE warning, the first that fits, and hands the Reset the fact that it showed the one for an unreadable store.
 *
 * THE CONTRACT (new names, reached by reflection so that this class compiles before the fix):
 *   ResetZashiVM's constructor takes the NavigationRouter and the AiAccountAccess (and AiPreferences if it wants it),
 *     and every call of this screen goes through that object's `send`: there the requests are counted.
 *   ResetZashiConfirmationArgs(keepFiles, aiUnreadableWarningShown: Boolean = false) (the one-argument call of the
 *     earlier classes stays).
 *   The warning is the subtitle of the box's state; the four new keys are
 *     delete_wallet_checkbox_warning_unchecked_ai_{unreadable,kept,kept_parked,linked}.
 *
 * WHY IT FAILS TODAY: ResetZashiVM is built from the router alone and has one unticked warning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetScreenShowsTheAiStateTest {
    private var phone = AiKeyScreensFixture()
    private val scheduler = TestCoroutineScheduler()
    private val forwarded = CopyOnWriteArrayList<Any>()
    private val router: NavigationRouter =
        ResetDriver.standIn { name, args ->
            if (name == "forward") forwarded.addAll((args[0] as Array<*>).filterNotNull())
            Unit
        }
    private val watchers = ArrayList<Job>()
    private val drivers = ArrayList<ResetDriver>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    private val screens = mutableListOf<ResetZashiVM>()

    @After
    fun tearDown() {
        watchers.forEach { it.cancel() }
        drivers.forEach { it.close() }
        // A screen still resumes on Main while it ends; resetting Main first leaks into another test (ZC-269).
        screens.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        kotlinx.coroutines.runBlocking {
            screens.forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        Dispatchers.resetMain()
        phone.close()
    }

    /** The screen, open: something collects its state, as the view does. */
    private fun openScreen(): ResetZashiVM {
        val constructor = ResetZashiVM::class.java.constructors.maxBy { it.parameterCount }
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    NavigationRouter::class.java -> router
                    AiAccountAccess::class.java -> phone.access
                    AiPreferences::class.java -> phone.ai
                    else -> throw AssertionError("AI-KEY-T: ResetZashiVM asks for ${type.simpleName}")
                }
            }
        assertTrue(
            "AI-KEY-T: ResetZashiVM is not built from AiAccountAccess",
            AiAccountAccess::class.java in constructor.parameterTypes,
        )
        val vm = constructor.newInstance(*args.toTypedArray()) as ResetZashiVM
        screens += vm
        watchers += CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun warningId(key: String): Int =
        runCatching { R.string::class.java.getField(key).getInt(null) }
            .getOrElse { throw AssertionError("AI-KEY-T: there is no string $key") }

    private fun assertShows(
        vm: ResetZashiVM,
        key: String,
        what: String,
    ) {
        val wanted = stringRes(warningId(key))
        val shown =
            runBlocking {
                withTimeoutOrNull(WAIT_MS) { vm.state.first { it.checkboxState.subtitle == wanted } }
            }
        assertTrue("$what: wanted $key, shows ${checkbox(vm).subtitle}", shown != null)
    }

    private fun checkbox(vm: ResetZashiVM) = vm.state.value.checkboxState

    private fun confirm(vm: ResetZashiVM) = vm.state.value.buttonState

    private fun toggle(vm: ResetZashiVM) = checkbox(vm).onClick()

    private fun untick(vm: ResetZashiVM) {
        if (checkbox(vm).isChecked) toggle(vm)
    }

    private fun assertWarning(
        what: String,
        key: String,
        arrange: AiKeyScreensFixture.() -> Unit,
    ) {
        watchers.forEach { it.cancel() }
        phone.close()
        phone = AiKeyScreensFixture()
        phone.arrange()
        val vm = openScreen()
        untick(vm)
        assertShows(vm, key, what)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
    }

    // ---------------------------------------------------------------- the first that fits (offline: the stored state decides)

    @Test
    fun `the five warnings in the order of spec 5_3`() {
        assertWarning("unreadable wins over all", UNREADABLE) { unreadableStore() }
        assertWarning("no verdict, paid unknown", KEPT) { store() }
        assertWarning("no verdict, paid", KEPT) { store(paid = "yes") }
        assertWarning("own and paid", KEPT) { store(verdict = "own", paid = "yes") }
        assertWarning("kept over parked-only", KEPT) { store(verdict = "own", paid = "yes", parked = true) }
        assertWarning("parked only, no saved key", KEPT_PARKED) { store(saved = false, parked = true) }
        assertWarning("parked-only over linked", KEPT_PARKED) { store(verdict = "linked", paid = "yes", parked = true) }
        assertWarning("not paid and parked", KEPT_PARKED) { store(paid = "no", parked = true) }
        assertWarning("linked, offline", LINKED) { store(verdict = "linked", paid = "yes") }
        assertWarning("linked and not paid", LINKED) { store(verdict = "linked", paid = "no") }
        assertWarning("no saved key", PLAIN) { }
        assertWarning("not paid, not linked", PLAIN) { store(paid = "no") }
    }

    @Test
    fun `the box ticked - the ticked warning whatever the AI state, and back`() {
        phone.store()
        val vm = openScreen()
        assertShows(vm, "delete_wallet_checkbox_warning_checked", "ticked")
        untick(vm)
        assertShows(vm, KEPT, "unticked")
        toggle(vm)
        assertShows(vm, "delete_wallet_checkbox_warning_checked", "ticked again")
    }

    // ------------------------------------------------------------------------------------- what the screen asks the server

    @Test
    fun `no verdict - the linking runs once, however often the box is touched`() {
        phone.store()
        phone.answer = { 200 to """{"signInKeyId":"$OWN_KEY_ID","paid":true}""" }
        val vm = openScreen()
        untick(vm)
        assertShows(vm, LINKED, "linked by the try")
        toggle(vm)
        toggle(vm)
        assertEquals(listOf(KEY_STATE), phone.requests.toList())
    }

    @Test
    fun `no saved key - no call at all, the screen opens no account`() {
        val vm = openScreen()
        untick(vm)
        assertShows(vm, PLAIN, "nothing saved")
        Thread.sleep(SETTLE_MS)
        assertEquals(emptyList<String>(), phone.requests.toList())
    }

    @Test
    fun `only parked keys - no call at all, a parked key is never used`() {
        phone.store(saved = false, parked = true)
        val vm = openScreen()
        untick(vm)
        assertShows(vm, KEPT_PARKED, "parked")
        Thread.sleep(SETTLE_MS)
        assertEquals(emptyList<String>(), phone.requests.toList())
    }

    @Test
    fun `linked - the re-check is ONE balance call and no key-state call`() {
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = { 200 to """{"balanceUsd":1.0,"signInKeyId":"$OWN_KEY_ID","paid":true}""" }
        val vm = openScreen()
        untick(vm)
        assertShows(vm, LINKED, "the mark holds")
        waitFor { phone.requests.isNotEmpty() }
        toggle(vm)
        toggle(vm)
        Thread.sleep(SETTLE_MS)
        assertEquals(listOf(BALANCE), phone.requests.toList())
    }

    @Test
    fun `F8 - a linked mark that balance contradicts shows the kept warning`() {
        for ((onAccount, line) in listOf("null" to KEPT, "\"$OTHER_KEY_ID\"" to KEPT_PARKED)) {
            watchers.forEach { it.cancel() }
            phone.close()
            phone = AiKeyScreensFixture()
            phone.store(verdict = "linked", paid = "yes")
            phone.answer = { path ->
                when (path) {
                    BALANCE -> 200 to """{"balanceUsd":1.0,"signInKeyId":$onAccount,"paid":true}"""
                    CHALLENGE -> 200 to AiTabAccessFixture.CHALLENGE_ANSWER
                    SIGN_IN -> 409 to """{"error":"no account","code":"unknown_key"}"""
                    else -> throw AssertionError("the screen asked $path")
                }
            }
            val vm = openScreen()
            untick(vm)
            assertShows(vm, line, "the key on the account is $onAccount")
            assertEquals("the re-check asks balance once", 1, phone.requests.count { it == BALANCE })
            assertEquals("the re-check asks no key/state", 0, phone.requests.count { it == KEY_STATE })
        }
    }

    @Test
    fun `a linking try that does not answer - at most 8 seconds, Confirm enabled before, during and after, the stored state decides`() {
        val cancelled = AtomicBoolean(false)
        phone.store()
        phone.answer = {
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        val vm = openScreen()
        assertTrue("Confirm before", confirm(vm).isEnabled)
        untick(vm)
        waitFor { phone.requests.isNotEmpty() }
        assertEquals("the try did not start", listOf(KEY_STATE), phone.requests.toList())
        assertTrue("Confirm during the try", confirm(vm).isEnabled)
        assertShows(vm, KEPT, "during the try")

        scheduler.advanceTimeBy(EIGHT_SECONDS_MS + 1)
        scheduler.runCurrent()
        val end = System.currentTimeMillis() + EIGHT_SECONDS_MS + WAIT_MS
        while (!cancelled.get() && System.currentTimeMillis() < end) {
            scheduler.advanceTimeBy(POLL_MS)
            Thread.sleep(POLL_MS)
        }

        assertTrue("the linking try was not ended within its limit", cancelled.get())
        assertTrue("Confirm after", confirm(vm).isEnabled)
        assertShows(vm, KEPT, "after the try")
    }

    // ------------------------------------------------------------------------------------------- what the Reset is handed

    private fun confirmedArgs(vm: ResetZashiVM): Boolean {
        forwarded.clear()
        confirm(vm).onClick()
        waitFor { forwarded.filterIsInstance<ResetZashiConfirmationArgs>().isNotEmpty() }
        val args = forwarded.filterIsInstance<ResetZashiConfirmationArgs>().single()
        val getter =
            runCatching { args.javaClass.getMethod("getAiUnreadableWarningShown") }
                .getOrElse { throw AssertionError("AI-KEY-T: ResetZashiConfirmationArgs has no aiUnreadableWarningShown") }
        return getter.invoke(args) as Boolean
    }

    @Test
    fun `the view model hands on that the unreadable warning was shown, and only then`() {
        phone.unreadableStore()
        val unreadable = openScreen()
        assertTrue("fixture: the box starts ticked", checkbox(unreadable).isChecked)
        assertEquals("the ticked warning says nothing of it", false, confirmedArgs(unreadable))
        untick(unreadable)
        assertShows(unreadable, UNREADABLE, "unreadable")
        assertEquals("unreadable shown", true, confirmedArgs(unreadable))

        watchers.forEach { it.cancel() }
        phone.close()
        phone = AiKeyScreensFixture()
        phone.store()
        val kept = openScreen()
        untick(kept)
        assertShows(kept, KEPT, "kept")
        assertEquals("kept shown", false, confirmedArgs(kept))
    }

    @Test
    fun `the sheet hands it to the Reset - a failed read after that warning goes on with keep=none`() {
        val constructor =
            ResetZashiConfirmationArgs::class.java.constructors.firstOrNull {
                it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            } ?: throw AssertionError("AI-KEY-T: ResetZashiConfirmationArgs has no (keepFiles, aiUnreadableWarningShown)")
        val reset = ResetDriver().also { drivers += it }.apply { seedPhraseStore() }
        reset.phone.failAt += STEP_AI_READ
        val vm =
            ResetZashiConfirmationVM(
                constructor.newInstance(false, true) as ResetZashiConfirmationArgs,
                reset.useCase as ResetZashiUseCase,
                router,
            )

        vm.state.value!!.onConfirm()
        waitFor { reset.record() != null }
        // The Reset ends while Main is still set, or its late resume leaks into another test.
        waitFor { vm.state.value?.isResetting == false }

        assertEquals("none", reset.recordLines()["keep"])
    }

    @Test
    fun `source - the Full Wallet Reset dialog picks identity_reset_ai_note the same way and hands the same fact on`() {
        val folder = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity"
        val view = SourceScan.stripComments(File(repoRoot(), "$folder/ChangeIdentityView.kt").readText())
        val model = SourceScan.stripComments(File(repoRoot(), "$folder/ChangeIdentityVM.kt").readText())
        for (key in listOf("ai_unreadable", "ai_kept", "ai_kept_parked", "ai_linked")) {
            assertTrue("no screen of Full Wallet Reset names identity_reset_ai_note_$key", "identity_reset_ai_note_$key" in view + model)
        }
        assertTrue(
            "the Full Wallet Reset does not hand on which warning it showed",
            Regex("""resetZashiUseCase\(\s*keepFiles\s*=\s*false\s*,""").containsMatchIn(model),
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val PLAIN = "delete_wallet_checkbox_warning_unchecked"
        const val UNREADABLE = "${PLAIN}_ai_unreadable"
        const val KEPT = "${PLAIN}_ai_kept"
        const val KEPT_PARKED = "${PLAIN}_ai_kept_parked"
        const val LINKED = "${PLAIN}_ai_linked"
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
        const val EIGHT_SECONDS_MS = 8_000L
    }
}
