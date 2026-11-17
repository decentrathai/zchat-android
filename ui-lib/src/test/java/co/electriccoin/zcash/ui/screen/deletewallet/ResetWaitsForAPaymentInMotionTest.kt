package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.NothingBroadcastException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.TexState
import co.electriccoin.zcash.ui.common.tex.TexStatus
import co.electriccoin.zcash.ui.common.tex.WalletActivity
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.RECORD_RENAME
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEPT_WRITE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_OWNER_WRITE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_RESTART
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_WALLET_CLEAR
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

/**
 * RESET-PAY (register ZC-886, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.2 RS-10, 4.9 and 6.7; owner question
 * 11.2 item 1, built as answer (a)): Reset ZCHAT does not start while a payment is in motion in this process (a send
 * between its create and its settle, or a step of the private move), nor while the last finished check of the TEX
 * recovery shows a payment to an exchange address that is not finished; and once a Reset has begun no new payment can
 * begin. Finished sends, NEEDS_SUPPORT and "no check finished yet" do not refuse: a wallet that does not load is the
 * wallet that most needs a Reset.
 *
 * The gate is one more field of WalletActivity: the Reset sets it and then reads the two counters; the one submit path
 * enters its counter and then reads the gate. The Reset is driven on FakePhone ([ResetDriver]); the TEX check is the
 * process-wide value TexSendMonitor publishes, set here by reflection. Every new name is reached by name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetWaitsForAPaymentInMotionTest {
    private val drivers = ArrayList<ResetDriver>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        drivers.forEach { it.close() }
        publish(null)
        runCatching { gate("reopen") }
        for (kind in WalletActivity.Kind.entries) while (WalletActivity.inProgress(kind) > 0) WalletActivity.leave(kind)
    }

    private fun driver(): ResetDriver = ResetDriver().also { drivers += it }.apply { seedPhraseStore() }

    @Test
    fun `a send is running - not started with the cause payment running, nothing done, the gate open again`() =
        runBlocking {
            for (kind in WalletActivity.Kind.entries) {
                val reset = driver()
                WalletActivity.enter(kind)
                val answer =
                    try {
                        reset.reset(keepFiles = false)
                    } finally {
                        WalletActivity.leave(kind)
                    }
                assertEquals("$kind: the answer", RUNNING, answer)
                assertNothingDone(reset, "$kind")
                assertEquals("$kind: the gate stayed closed after a Reset that did not start", false, closing())
            }
        }

    @Test
    fun `an unfinished TEX send - not started with the cause payment open, in each of its states and when unmatched`() =
        runBlocking {
            val cases =
                OPEN_STATES.map { state -> state.name to status(state) } +
                    listOf("unmatched" to status(TexState.NEEDS_SUPPORT, unmatched = true))
            for ((what, status) in cases) {
                val reset = driver()
                publish(TexCheck(statuses = listOf(status), seenTxIds = emptySet()))
                assertEquals("$what: the answer", OPEN, reset.reset(keepFiles = false))
                assertNothingDone(reset, what)
                assertEquals("$what: the gate stayed closed", false, closing())
            }
        }

    @Test
    fun `finished sends do not refuse - DONE, NOT_SENT, SHIELDED and NEEDS_SUPPORT`() =
        runBlocking {
            for (state in listOf(TexState.DONE, TexState.NOT_SENT, TexState.SHIELDED, TexState.NEEDS_SUPPORT)) {
                val reset = driver()
                publish(TexCheck(statuses = listOf(status(state)), seenTxIds = emptySet()))
                assertEquals("$state: the Reset did not start", STARTED, reset.reset(keepFiles = true))
                runCatching { gate("reopen") }
            }
        }

    @Test
    fun `nothing known - no check has finished in this process, and the Reset starts`() =
        runBlocking {
            publish(null)
            val reset = driver()
            assertEquals(STARTED, reset.reset(keepFiles = true))
        }

    @Test
    fun `no new payment once it began - the real submit path refuses before it creates anything`() =
        runBlocking {
            val reset = driver()
            assertEquals(STARTED, reset.reset(keepFiles = true))
            val asked = AtomicBoolean(false)
            val provider =
                ResetDriver.standIn<SynchronizerProvider> { name, args ->
                    when {
                        name == "getError" || (name == "getSynchronizer" && args.isEmpty()) -> MutableStateFlow(null)
                        name == "getSynchronizer" -> {
                            asked.set(true)
                            throw IllegalStateException("synthetic: the create of a send was reached")
                        }
                        else -> Unit
                    }
                }
            val pczt = Pczt(byteArrayOf(0x11, 0x11, 0x11))
            val thrown = runCatching { ProposalDataSourceImpl(provider).submitTransaction(pczt, pczt) }.exceptionOrNull()
            assertTrue("the submit did not answer 'nothing was broadcast': $thrown", thrown is NothingBroadcastException)
            assertFalse("a send was created after the Reset began", asked.get())
            assertEquals("the send's counter was not left", 0, WalletActivity.inProgress(WalletActivity.Kind.SEND))
        }

    @Test
    fun `either one sees the other - a send and a Reset never both go on`() {
        val bothWent = AtomicInteger()
        repeat(ROUNDS) {
            val barrier = CyclicBarrier(2)
            // A send holds its counter for as long as it runs: here until both sides have decided.
            val decided = CyclicBarrier(2)
            val sendWent = AtomicBoolean()
            val resetWent = AtomicBoolean()
            val send =
                Thread {
                    barrier.await()
                    WalletActivity.track(WalletActivity.Kind.SEND) {
                        try {
                            sendWent.set(!closing())
                        } finally {
                            runCatching { decided.await(DECIDE_S, TimeUnit.SECONDS) }
                        }
                    }
                }
            val reset =
                Thread {
                    barrier.await()
                    try {
                        resetWent.set(gate("closeForReset") as Boolean)
                    } finally {
                        runCatching { decided.await(DECIDE_S, TimeUnit.SECONDS) }
                    }
                }
            send.start()
            reset.start()
            send.join()
            reset.join()
            if (sendWent.get() && resetWent.get()) bothWent.incrementAndGet()
            gate("reopen")
        }
        assertEquals("a send and a Reset both went on", 0, bothWent.get())
    }

    @Test
    fun `it asks the SDK for nothing - two counters and one value in memory`() {
        val source = SourceScan.stripComments(code(USE_CASE))
        assertTrue("the Reset does not ask WalletActivity", "WalletActivity.closeForReset()" in source)
        assertTrue("the Reset does not read the TEX check", "TexSendMonitor.latestCheck" in source)
        for (name in listOf("getSynchronizer", "TexWallet", "SdkSynchronizer", "transactions")) {
            assertFalse("the Reset's check names $name", name in source)
        }
        val data = SourceScan.stripComments(code(PROPOSALS))
        val internal =
            data
                .substringAfter("private suspend fun submitTransactionInternal(", "")
                .substringBefore("private suspend fun submitTransactionCounted(")
        val track = internal.indexOf("WalletActivity.track(")
        val read = internal.indexOf("WalletActivity.closing")
        assertTrue("the submit path does not read the gate after it entered its counter", track >= 0 && read > track)
    }

    @Test
    fun `the texts - the three keys in English and Spanish, nothing was deleted, nothing said to be lost`() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in KEYS) {
            assertTrue("$key in EN and ES", key in en && key in es)
            assertNotEquals("$key: the Spanish is the English", en[key], es[key])
            for (text in listOf(en.getValue(key), es.getValue(key))) {
                assertFalse("$key has a placeholder", Regex("""%\d*\$?[sd]|\{\d}""").containsMatchIn(text))
            }
        }
        for (key in REFUSALS) {
            assertTrue("$key (EN)", "Nothing was deleted" in en.getValue(key))
            assertTrue("$key (ES)", "No se eliminó nada" in es.getValue(key))
            assertFalse("$key (EN) says lost", "lost" in en.getValue(key).lowercase())
            assertFalse("$key (ES) says se pierde", "se pierde" in es.getValue(key).lowercase())
        }
    }

    @Test
    fun `the sheet and Full Wallet Reset - the same two refusals, and the fixed line on both`() {
        val running = driver()
        WalletActivity.enter(WalletActivity.Kind.SEND)
        val vm = ResetZashiConfirmationVM(ResetZashiConfirmationArgs(keepFiles = true), running.useCase as ResetZashiUseCase, router())
        try {
            vm.state.value!!.onConfirm()
            waitFor { vm.state.value?.isResetting == false }
        } finally {
            WalletActivity.leave(WalletActivity.Kind.SEND)
        }
        val state = vm.state.value!!
        val failed = runCatching { state.javaClass.getMethod("getFailedText").invoke(state) }.getOrNull()
        assertEquals("the sheet does not say why", id("delete_wallet_failed_payment_running"), failed)

        val change = SourceScan.stripComments(code(CHANGE_IDENTITY_VM))
        val full =
            change
                .substringAfter("private suspend fun performFullReset()", "")
                .substringBefore("private suspend fun performDiversifiedChange()")
        for (key in REFUSALS) assertTrue("Full Wallet Reset does not show $key", "R.string.$key" in full)
        assertFalse("Full Wallet Reset shows identity_error_failed", "identity_error_failed" in full)
        val sheet = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiConfirmationView.kt")
        assertTrue("the sheet has no fixed line", "R.string.$NOTE" in sheet)
        val dialog = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityView.kt")
        val branch = dialog.substringAfter("R.string.identity_reset_warning", "").substringBefore("} else {")
        assertTrue("the Full Wallet Reset dialog has no fixed line", "R.string.$NOTE" in branch)
    }

    // ------------------------------------------------------------------ helpers

    private fun assertNothingDone(
        reset: ResetDriver,
        what: String,
    ) {
        assertNull("$what: a record was written", reset.record())
        for (step in listOf(STEP_KEPT_WRITE, STEP_OWNER_WRITE, RECORD_RENAME, STEP_WALLET_CLEAR, STEP_RESTART)) {
            assertTrue("$what: $step happened: ${reset.phone.events}", reset.at(step) < 0)
        }
    }

    private fun status(
        state: TexState,
        unmatched: Boolean = false,
    ): TexStatus =
        TexStatus(
            key = "synthetic-key",
            leg1TxId = "synthetic-leg1",
            leg2TxId = "synthetic-leg2",
            state = state,
            unmatched = unmatched,
        )

    /** Sets the process-wide check TexSendMonitor publishes (its companion's flow). */
    @Suppress("UNCHECKED_CAST")
    private fun publish(check: TexCheck?) {
        val field = TexSendMonitor::class.java.getDeclaredField("mutableLatestCheck").apply { isAccessible = true }
        (field.get(null) as MutableStateFlow<TexCheck?>).value = check
    }

    /** A function of WalletActivity's gate, by name (RESET-PAY). */
    private fun gate(name: String): Any? {
        val method =
            WalletActivity::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?: throw AssertionError("WalletActivity has no gate function $name")
        return method.invoke(WalletActivity)
    }

    private fun closing(): Boolean = gate("getClosing") as Boolean

    private fun id(key: String): Int? = runCatching { R.string::class.java.getField(key).getInt(null) }.getOrNull()

    private fun router(): NavigationRouter = ResetDriver.standIn { _, _ -> Unit }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun code(relative: String): String = File(repoRoot(), relative).takeIf { it.isFile }?.readText().orEmpty()

    private fun strings(qualifier: String): Map<String, String> {
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File(repoRoot(), "ui-lib/src/main/res/ui/delete_wallet/$qualifier/strings.xml"))
                .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private companion object {
        const val USE_CASE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt"
        const val PROPOSALS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ProposalDataSource.kt"
        const val CHANGE_IDENTITY_VM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityVM.kt"
        const val STARTED = "STARTED"
        const val RUNNING = "NOT_STARTED_PAYMENT_RUNNING"
        const val OPEN = "NOT_STARTED_PAYMENT_OPEN"
        const val NOTE = "delete_wallet_confirmation_payment_note"
        const val ROUNDS = 1_000
        const val DECIDE_S = 5L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
        val OPEN_STATES =
            listOf(
                TexState.SENDING_STEP1,
                TexState.SENDING_STEP2,
                TexState.CHECKING,
                TexState.NEEDS_TOR,
                TexState.RETURNED,
                TexState.SHIELDING,
            )
        val REFUSALS = listOf("delete_wallet_failed_payment_running", "delete_wallet_failed_payment_open")
        val KEYS = REFUSALS + NOTE
    }
}
