package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.BALANCE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.CHALLENGE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OWN_KEY_ID
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.SIGN_IN
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.ai.AiResetLine
import co.electriccoin.zcash.ui.screen.ai.AiTabAccessFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * AI-KEY-R/T round 2 (W-97; ZC-1223; RT_ROUND2.md items 1, 2, 3 and 6): the Reset screen's AI check is really capped at 8
 * seconds, a store that does not answer shows warning 1, and Confirm hands on the line of the finished check.
 *
 * THE CONTRACT: [AiAccountAccess.resetLine] itself never takes longer than AI_CHECK_LIMIT_MS and a late read is
 * [AiResetLine.UNREADABLE]. On Confirm with the box unticked, ResetZashiVM awaits the check already running for what is
 * left of its 8 seconds, makes no new call, forwards once, and Confirm stays enabled. With the box ticked the AI state says
 * nothing, so it forwards at once. What arrives after the limit changes nothing, on the screen or in the store.
 *
 * REAL TIME, NOT THE SCHEDULER: the point is what a blocked thread does to the wait, and a virtual clock cannot show it.
 * The main dispatcher is a real thread, the stand-ins block their thread and ignore interrupts (a disk or Keystore read
 * need not answer one), and each case waits at most the limit plus [MARGIN_MS].
 *
 * WHY IT FAILS TODAY: withTimeoutOrNull cannot end a blocking read, the reads of resetLine stand outside it, so a hung
 * read leaves the default PLAIN; onConfirmClicked forwards at once on the line of that moment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetScreenAiCheckIsBoundedTest {
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
    private val screens = ArrayList<ResetZashiVM>()

    @Before
    fun setUp() {
        whileNothingUsesMain { Dispatchers.setMain(main.asCoroutineDispatcher()) }
        val open = { name: String -> gate.around(phone.files.Library(name).open()) }
        StoreOpenerApi.useCalls(
            StoreOpenerApi.aiHolder(phone.context),
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ ->
                    gate.onOpen()
                    open(name)
                },
                createByKeyName = { name, _ ->
                    gate.onOpen()
                    open(name)
                },
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
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        whileNothingUsesMain { Dispatchers.resetMain() }
        main.shutdownNow()
        phone.close()
    }

    /**
     * A blocked stand-in that was just let go still comes back through Dispatchers.Main, and setting it meanwhile throws
     * "used concurrently": a matter of this class's own threads, never of what a case is about.
     */
    private fun whileNothingUsesMain(set: () -> Unit) {
        repeat(MAIN_TRIES) {
            try {
                set()
                return
            } catch (_: IllegalStateException) {
                Thread.sleep(POLL_MS)
            }
        }
        set()
    }

    private fun openScreen(access: AiAccountAccess = phone.access): ResetZashiVM {
        val constructor = ResetZashiVM::class.java.constructors.maxBy { it.parameterCount }
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    NavigationRouter::class.java -> router
                    AiAccountAccess::class.java -> access
                    AiPreferences::class.java -> phone.ai
                    else -> throw AssertionError("ResetZashiVM asks for ${type.simpleName}")
                }
            }
        val vm = constructor.newInstance(*args.toTypedArray()) as ResetZashiVM
        screens += vm
        watchers += CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    /** As a thumb does it: on a screen that is up, and the box is seen unticked before anything else happens. */
    private fun untick(vm: ResetZashiVM) {
        Thread.sleep(SCREEN_UP_MS)
        val box = { vm.state.value.checkboxState }
        if (box().isChecked) box().onClick()
        assertTrue("fixture: the box stays ticked", waitFor(WAIT_MS) { !box().isChecked })
    }

    private fun tapConfirm(vm: ResetZashiVM) {
        val confirm = vm.state.value.buttonState
        confirm.onClick()
    }

    private fun confirmEnabled(vm: ResetZashiVM): Boolean {
        val confirm = vm.state.value.buttonState
        return confirm.isEnabled
    }

    private fun forwards() = forwarded.filterIsInstance<ResetZashiConfirmationArgs>()

    private fun waitFor(
        limitMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val end = System.currentTimeMillis() + limitMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
        return condition()
    }

    private fun shownName(vm: ResetZashiVM): String {
        val box = vm.state.value.checkboxState
        val shown = box.subtitle
        val found = WARNINGS.entries.firstOrNull { stringRes(it.value) == shown }
        return found?.key ?: "another warning: $shown"
    }

    private fun assertShows(
        vm: ResetZashiVM,
        wanted: String,
        what: String,
        limitMs: Long = WAIT_MS,
    ) {
        assertTrue("$what: wanted $wanted, shows ${shownName(vm)}", waitFor(limitMs) { shownName(vm) == wanted })
    }

    private fun assertNothingForwardedYet(what: String) {
        Thread.sleep(NOT_YET_MS)
        assertEquals("$what: Confirm did not wait for the check", 0, forwards().size)
    }

    private val capped = AI_CHECK_LIMIT_MS + MARGIN_MS

    /** The balance answer waits for [answerNow] and then contradicts the linked mark (F8): the line becomes KEPT. */
    private fun linkedMarkThatBalanceWillContradict(answerNow: CompletableDeferred<Unit>) {
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = { path ->
            when (path) {
                BALANCE -> {
                    answerNow.await()
                    200 to """{"balanceUsd":1.0,"signInKeyId":null,"paid":true}"""
                }
                CHALLENGE -> 200 to AiTabAccessFixture.CHALLENGE_ANSWER
                SIGN_IN -> 409 to """{"error":"no account","code":"unknown_key"}"""
                else -> throw AssertionError("the screen asked $path")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ item 1

    @Test
    fun `item 1 - a re-check that throws - the store is read again and Confirm still hands on`() {
        phone.store()
        val access =
            AiAccountAccess(
                prefs = phone.ai,
                seed = { AiKeyScreensFixture.SEED.copyOf() },
                candidates = { emptyList() },
                ownerTag = { AiKeyScreensFixture.TAG },
                scopeCounter = { 0L },
                walletSaved = {
                    phone.ai.writeIfStillThisWallet({ true }) {
                        removePair()
                        removeLink()
                    }
                    throw IllegalStateException("synthetic: the wallet store failed")
                },
                clock = System::currentTimeMillis,
                send = { _, _, _, _ -> throw AssertionError("no call was to be made") },
            )
        val vm = openScreen(access)
        untick(vm)

        assertShows(vm, PLAIN, "the line after the failed re-check")
        tapConfirm(vm)
        assertTrue("Confirm did nothing", waitFor(WAIT_MS) { forwards().size == 1 })
    }

    // ------------------------------------------------------------------------------------------ item 2

    @Test
    fun `item 2 - a hung store read - warning 1 at the limit, Confirm hands that on, the late answer changes nothing`() {
        phone.store()
        gate.hangReads = true
        val vm = openScreen()
        untick(vm)
        tapConfirm(vm)
        assertTrue("Confirm is enabled", confirmEnabled(vm))

        assertShows(vm, UNREADABLE, "a store read that does not answer", capped)
        Thread.sleep(SETTLE_MS)
        assertEquals("the warning came after the tap: the screen stays", 0, forwards().size)
        tapConfirm(vm)
        assertTrue("the second tap did not go on", waitFor(capped) { forwards().isNotEmpty() })
        assertEquals("forwarded once", 1, forwards().size)
        assertTrue("the Reset is not told warning 1 was shown", forwards().single().aiUnreadableWarningShown)

        gate.release()
        Thread.sleep(SETTLE_MS)
        assertShows(vm, UNREADABLE, "after the late answer", limitMs = 0)
    }

    @Test
    fun `item 2 - a store that hangs while opening - warning 1 at the limit`() {
        phone.files.put(AiPreferences.PREFS_NAME, Keysets.wrappedEntries() + mapOf("ai_token" to "synthetic-token"))
        gate.hangOpen = true
        val vm = openScreen()
        untick(vm)

        assertShows(vm, UNREADABLE, "a store that does not open", capped)
    }

    @Test
    fun `item 6 - resetLine itself returns UNREADABLE at the limit, so the Full Wallet Reset dialog is capped too`() {
        phone.store()
        gate.hangReads = true
        val line = AtomicReference<AiResetLine?>(null)
        val reader = Thread { runBlocking { line.set(phone.access.resetLine()) } }.apply { isDaemon = true }
        reader.start()
        reader.join(capped)

        assertEquals("resetLine has no limit of its own", AiResetLine.UNREADABLE, line.get())
    }

    // ------------------------------------------------------------------------------------------ item 3

    @Test
    fun `item 3 - Confirm before the balance answer waits for it, makes no new call, and goes on with the fresh line`() {
        val answerNow = CompletableDeferred<Unit>()
        linkedMarkThatBalanceWillContradict(answerNow)
        val vm = openScreen()
        untick(vm)
        assertTrue("the re-check did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })
        assertShows(vm, LINKED, "before the answer")

        tapConfirm(vm)
        assertTrue("Confirm is enabled during the wait", confirmEnabled(vm))
        assertNothingForwardedYet("the user was told linked, the answer still out")

        answerNow.complete(Unit)
        assertTrue("Confirm did not go on after the answer", waitFor(WAIT_MS) { forwards().size == 1 })
        assertShows(vm, KEPT, "what the sheet follows", limitMs = 0)
        assertEquals("Confirm made a call of its own", 1, phone.requests.count { it == BALANCE })
    }

    @Test
    fun `item 3 - Confirm waits only for the rest of the 8 seconds, not 8 more`() {
        phone.store()
        phone.answer = { awaitCancellation() }
        val vm = openScreen()
        untick(vm)
        assertTrue("the try did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })
        Thread.sleep(TAP_AFTER_MS)

        tapConfirm(vm)
        assertNothingForwardedYet("the try still out")

        val rest = AI_CHECK_LIMIT_MS - TAP_AFTER_MS - NOT_YET_MS
        assertTrue("Confirm waited past the 8 seconds", waitFor(rest + MARGIN_MS) { forwards().size == 1 })
        assertEquals("Confirm made a call of its own", 1, phone.requests.size)
    }

    @Test
    fun `item 3 - two taps during the wait forward once`() {
        val answerNow = CompletableDeferred<Unit>()
        linkedMarkThatBalanceWillContradict(answerNow)
        val vm = openScreen()
        untick(vm)
        assertTrue("the re-check did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })

        tapConfirm(vm)
        tapConfirm(vm)
        answerNow.complete(Unit)
        assertTrue("Confirm did not go on", waitFor(WAIT_MS) { forwards().isNotEmpty() })
        Thread.sleep(SETTLE_MS)

        assertEquals("the sheet opened twice", 1, forwards().size)
    }

    @Test
    fun `item 3 - the store becomes unreadable during the wait - the fact handed on is that of the fresh line`() {
        val answerNow = CompletableDeferred<Unit>()
        linkedMarkThatBalanceWillContradict(answerNow)
        val vm = openScreen()
        untick(vm)
        assertTrue("the re-check did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })
        assertShows(vm, LINKED, "before the answer")

        tapConfirm(vm)
        gate.failReads = true
        answerNow.complete(Unit)

        assertShows(vm, UNREADABLE, "the fresh line")
        Thread.sleep(SETTLE_MS)
        assertEquals("the warning came after the tap: the screen stays", 0, forwards().size)
        tapConfirm(vm)
        assertTrue("the second tap did not go on", waitFor(WAIT_MS) { forwards().isNotEmpty() })
        assertTrue("the second tap saw the warning", forwards().single().aiUnreadableWarningShown)
    }

    // GUARD, passes before and after the fix.
    @Test
    fun `item 3 - the check is over - Confirm goes on at once and makes no call`() {
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = { 200 to """{"balanceUsd":1.0,"signInKeyId":"$OWN_KEY_ID","paid":true}""" }
        val vm = openScreen()
        untick(vm)
        assertTrue("the re-check did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })
        Thread.sleep(SETTLE_MS)

        tapConfirm(vm)

        assertTrue("Confirm waited for nothing", waitFor(AT_ONCE_MS) { forwards().size == 1 })
        assertEquals(listOf(BALANCE), phone.requests.toList())
    }

    // GUARD, passes before and after the fix: with the box ticked no AI warning is shown, so nothing is there to wait for.
    @Test
    fun `item 3 - the box ticked - Confirm goes on at once while the check runs`() {
        phone.store()
        phone.answer = { awaitCancellation() }
        val vm = openScreen()
        assertTrue("the try did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })

        tapConfirm(vm)

        assertTrue("Confirm waited though the box is ticked", waitFor(AT_ONCE_MS) { forwards().size == 1 })
        assertFalse(forwards().single().aiUnreadableWarningShown)
    }

    // ------------------------------------------------------------------------------------------ item 6

    @Test
    fun `item 6 - a call that blocks its thread - at the limit the stored state decides`() {
        phone.store()
        phone.answer = {
            gate.waitForRelease()
            200 to """{"signInKeyId":"$OWN_KEY_ID","paid":true}"""
        }
        val vm = openScreen()
        untick(vm)
        assertTrue("the try did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })
        phone.ai.writeIfStillThisWallet({ true }) {
            putLinkStateJson(AiKeyScreensFixture.linkJson(AiKeyScreensFixture.USER, "linked", "yes"))
        }

        assertShows(vm, LINKED, "the store was not read again at the limit", capped)
    }

    @Test
    fun `item 6 - an answer after the limit writes nothing - the key the screen said is kept stays kept`() {
        phone.store()
        phone.answer = {
            gate.waitForRelease()
            200 to """{"signInKeyId":"$OWN_KEY_ID","paid":true}"""
        }
        val vm = openScreen()
        untick(vm)
        assertTrue("the try did not start", waitFor(WAIT_MS) { phone.requests.isNotEmpty() })
        Thread.sleep(capped)
        assertShows(vm, KEPT, "at the limit", limitMs = 0)

        gate.release()
        Thread.sleep(SETTLE_MS)

        val linkState = phone.ai.getLinkStateJson().orEmpty()
        assertFalse("the late answer wrote a verdict: the Reset would drop the key the screen said it keeps", "linked" in linkState)
        assertTrue(AiPreferences.ACCESS_KEY_ENTRIES.first() in phone.ai.entriesKeptAtReset())
        assertShows(vm, KEPT, "after the late answer", limitMs = 0)
    }

    @Test
    fun `item 6 - source - the Full Wallet Reset dialog does not rest on a coroutine timeout around the check`() {
        val model =
            SourceScan.stripComments(
                File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityVM.kt").readText(),
            )
        assertFalse(
            "a coroutine timeout does not end a blocking read",
            Regex("""withTimeoutOrNull\(\s*AI_CHECK_LIMIT_MS\s*\)\s*\{\s*access\.recheckForResetScreen""").containsMatchIn(model),
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    /** The AI store's file: its reads or its opening can hang (ignoring interrupts) or fail, on the test's word. */
    private class StoreGate {
        @Volatile
        var hangReads = false

        @Volatile
        var hangOpen = false

        @Volatile
        var failReads = false

        private val released = CountDownLatch(1)

        fun release() = released.countDown()

        fun waitForRelease() {
            while (true) {
                try {
                    released.await()
                    return
                } catch (_: InterruptedException) {
                    // A blocking disk or Keystore read need not answer an interrupt.
                }
            }
        }

        fun onOpen() {
            if (hangOpen) waitForRelease()
        }

        fun around(real: SharedPreferences): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                if (hangReads) waitForRelease()
                if (failReads) throw IllegalStateException("synthetic: the store cannot be read")
                try {
                    method.invoke(real, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as SharedPreferences
    }

    private companion object {
        const val MARGIN_MS = 2_500L
        const val WAIT_MS = 5_000L
        const val SCREEN_END_MS = 5_000L
        const val POLL_MS = 20L
        const val MAIN_TRIES = 50
        const val SCREEN_UP_MS = 200L
        const val SETTLE_MS = 500L
        const val NOT_YET_MS = 500L
        const val AT_ONCE_MS = 1_000L
        const val TAP_AFTER_MS = 3_000L
        const val PLAIN = "plain"
        const val UNREADABLE = "unreadable"
        const val KEPT = "kept"
        const val LINKED = "linked"
        val WARNINGS =
            mapOf(
                "ticked" to R.string.delete_wallet_checkbox_warning_checked,
                PLAIN to R.string.delete_wallet_checkbox_warning_unchecked,
                UNREADABLE to R.string.delete_wallet_checkbox_warning_unchecked_ai_unreadable,
                KEPT to R.string.delete_wallet_checkbox_warning_unchecked_ai_kept,
                LINKED to R.string.delete_wallet_checkbox_warning_unchecked_ai_linked,
            )
    }
}
