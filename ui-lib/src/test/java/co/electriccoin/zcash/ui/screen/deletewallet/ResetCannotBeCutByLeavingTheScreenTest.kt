package co.electriccoin.zcash.ui.screen.deletewallet

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_RESTART
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_WALLET_CLEAR
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-090-R (register ZC-090, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.2 RS-4, 4.4, 5.1 and 6.3): leaving
 * the Reset screen changes nothing once the Reset has begun, a Reset that did not start says so on the sheet, Full
 * Wallet Reset shows no success it cannot have, the restart always ends the process, and the texts say what the Reset
 * now does: ZCHAT closes to finish it, nothing is deleted when it cannot start, the cache goes too, old wallet data
 * that was set aside to rescan goes too.
 *
 * The sheet is driven with the real view model and a Reset driven on FakePhone ([ResetDriver]); the rest reads the
 * sources and the string files. Every new name is reached by name or reflection: this class compiles before the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetCannotBeCutByLeavingTheScreenTest {
    private val drivers = ArrayList<ResetDriver>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // A Reset left hanging must end while Main is still set, or its late resume leaks into another test.
        drivers.forEach { it.close() }
        vms.forEach { vm -> waitFor { vm.state.value?.isResetting == false } }
        Dispatchers.resetMain()
    }

    private val vms = mutableListOf<ResetZashiConfirmationVM>()

    private fun driver(): ResetDriver = ResetDriver().also { drivers += it }.apply { seedPhraseStore() }

    // ------------------------------------------------------------------ the caller

    @Test
    fun `the caller is cancelled right after the record - the wallet clear and the restart still come`() =
        runBlocking {
            val reset = driver()
            val caller = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { reset.reset(keepFiles = false) }
            // The folder sync right after the record's rename is the moment the screen goes away.
            reset.phone.killAt = { operation, file ->
                if (operation == "sync" && file.name == "no_backup") caller.cancel()
                false
            }
            caller.start()
            caller.join()
            waitFor { reset.at(STEP_RESTART) >= 0 }
            assertTrue("fixture: the record was written", reset.record() != null)
            assertTrue("the wallet was not cleared: ${reset.phone.events}", reset.at(STEP_WALLET_CLEAR) >= 0)
            assertTrue("the restart was not requested: ${reset.phone.events}", reset.at(STEP_RESTART) >= 0)
        }

    // ------------------------------------------------------------------ the sheet

    @Test
    fun `Back on the sheet - it does nothing while the Reset runs, and navigates before and after`() {
        val running = driver().apply { unlock = "hang" }
        val backs = AtomicInteger()
        val vm = ResetZashiConfirmationVM(ResetZashiConfirmationArgs(keepFiles = true), running.useCase as ResetZashiUseCase, router(backs))
        vms += vm
        vm.state.value!!.onBack()
        assertEquals("before the Reset, Back navigates", 1, backs.get())
        vm.state.value!!.onConfirm()
        assertTrue("fixture: the Reset runs", vm.state.value!!.isResetting)
        vm.state.value!!.onBack()
        assertEquals("while the Reset runs, Back navigates", 1, backs.get())

        val cancelled = driver().apply { unlock = "cancel" }
        val after = AtomicInteger()
        val again =
            ResetZashiConfirmationVM(
                ResetZashiConfirmationArgs(keepFiles = true),
                cancelled.useCase as ResetZashiUseCase,
                router(after),
            )
        again.state.value!!.onConfirm()
        waitFor { again.state.value?.isResetting == false }
        again.state.value!!.onBack()
        assertEquals("after a Reset that did not run, Back navigates", 1, after.get())
    }

    @Test
    fun `not started - the sheet says delete_wallet_failed, both buttons work, no error screen`() {
        val reset = driver().apply { phone.installTime = 0L }
        val vm =
            ResetZashiConfirmationVM(
                ResetZashiConfirmationArgs(keepFiles = false),
                reset.useCase as ResetZashiUseCase,
                router(AtomicInteger()),
            )
        vm.state.value!!.onConfirm()
        waitFor { vm.state.value?.isResetting == false }
        val state = vm.state.value!!
        assertFalse("the buttons stay disabled", state.isResetting)
        val failed = runCatching { state.javaClass.getMethod("getFailedText").invoke(state) }.getOrNull()
        assertEquals("the sheet does not say delete_wallet_failed", R.string.delete_wallet_failed, failed)
        val source = SourceScan.stripComments(code(USE_CASE))
        assertFalse("the Reset asks for an error screen", "NavigateToErrorUseCase" in source || "navigateToError" in source)
    }

    // ------------------------------------------------------------------ Full Wallet Reset

    @Test
    fun `Full Wallet Reset - no success after a started Reset, no clear of the identities, delete_wallet_failed when it did not start`() {
        val vm = SourceScan.stripComments(code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityVM.kt"))
        val full = block(vm, "private suspend fun performFullReset()").orEmpty()
        assertTrue("fixture: performFullReset", full.isNotEmpty())
        assertFalse("Full Wallet Reset still clears the identities in the running process", "identityManager.clearAll()" in full)
        assertFalse("Full Wallet Reset still shows a success dialog", "showSuccessDialog = true" in full)
        assertTrue("a Full Wallet Reset that did not start does not say delete_wallet_failed", "R.string.delete_wallet_failed" in full)
        assertFalse("a Full Wallet Reset that did not start says identity_error_failed", "identity_error_failed" in full)
        for (file in stringFiles()) {
            assertFalse(
                "identity_reset_done still exists in ${file.parentFile?.name}/${file.name}",
                "name=\"identity_reset_done\"" in file.readText(),
            )
        }
        val view = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityView.kt")
        assertFalse("ChangeIdentityView names identity_reset_done", "identity_reset_done" in view)
    }

    @Test
    fun `a launch that throws - the call that ends the process stands in a finally`() {
        val files =
            SourceScan.stripComments(
                code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/walletstartup/WalletDataDatabaseFiles.kt"),
            )
        val restart = block(files, "override fun restart()").orEmpty()
        assertTrue("fixture: AppRestarterImpl.restart", "startActivity(" in restart)
        val fin = restart.indexOf("finally")
        assertTrue("restart() has no finally", fin >= 0)
        assertTrue("the launch is not covered by the finally", restart.indexOf("startActivity(") < fin)
        assertTrue("the process end is not in the finally", restart.indexOf("exit(", fin) > fin)
    }

    // ------------------------------------------------------------------ the texts

    @Test
    fun `the texts - closes, nothing was deleted, no cache, the restart note`() {
        val en = strings(DW, "values")
        val es = strings(DW, "values-es")
        val subtitle = "delete_wallet_confirmation_subtitle"
        assertTrue("$subtitle (EN) does not say 'closes'", "closes" in en.getValue(subtitle))
        assertFalse("$subtitle (EN) says 'cannot be undone'", "cannot be undone" in en.getValue(subtitle))
        assertTrue("$subtitle (ES) does not say «se cierra»", "se cierra" in es.getValue(subtitle))
        assertTrue("delete_wallet_failed (EN)", "Nothing was deleted" in en.getValue("delete_wallet_failed"))
        assertTrue("delete_wallet_failed (ES)", "No se eliminó nada" in es.getValue("delete_wallet_failed"))
        assertFalse("delete_wallet_text_2 (EN) says cache", "cache" in en.getValue("delete_wallet_text_2").lowercase())
        assertFalse("delete_wallet_text_2 (ES) says caché", "caché" in es.getValue("delete_wallet_text_2").lowercase())
        val enIdentity = strings(IDENTITY, "values")
        val esIdentity = strings(IDENTITY, "values-es")
        for (map in listOf(enIdentity, esIdentity)) assertTrue("identity_reset_restart_note", "identity_reset_restart_note" in map)
        assertNotEquals(enIdentity["identity_reset_restart_note"], esIdentity["identity_reset_restart_note"])
    }

    @Test
    fun `the cache sentence - fetched or prepared, also for chats set aside, shown again only if its host has it`() {
        val en = strings(DW, "values").getValue(WARNING)
        val es = strings(DW, "values-es").getValue(WARNING)
        for (words in listOf("fetched or prepared", "set aside for other recovery phrases", "only if its host still has it")) {
            assertTrue("$WARNING (EN) does not say '$words'", words in en)
        }
        for (words in listOf("obtuvo o preparó", "apartados para otras frases de recuperación", "solo podrá volver a mostrarse si")) {
            assertTrue("$WARNING (ES) does not say «$words»", words in es)
        }
        // What ZC-718 put in stays true; AI-KEY-T: "kept" is said by warnings 2 and 3 only.
        assertTrue("deleted" in en && "AI chats" in en && "AI pictures" in en)
        assertTrue("eliminar" in es)
        for (key in listOf("${WARNING}_ai_kept", "${WARNING}_ai_kept_parked")) {
            assertTrue(key, "kept" in strings(DW, "values").getValue(key))
            assertTrue(key, "conserva" in strings(DW, "values-es").getValue(key))
        }
        for (count in listOf("Two things", "Dos cosas")) assertFalse("$WARNING counts what stays: $count", count in en || count in es)
    }

    @Test
    fun `old wallet data - set aside to rescan, from a different recovery phrase, deleted too, only in the full-reset branch`() {
        val en = strings(DW, "values")
        val es = strings(DW, "values-es")
        val warningEn = en.getValue(WARNING)
        val warningEs = es.getValue(WARNING)
        for (words in listOf("set aside", "different recovery phrase", "deleted too")) {
            assertTrue("$WARNING (EN): '$words'", words in warningEn)
        }
        for (words in listOf("apartado", "frase de recuperación distinta", "también se eliminan")) {
            assertTrue("$WARNING (ES): «$words»", words in warningEs)
        }
        val old = warningEn.indexOf("old wallet data")
        assertTrue("the old-data sentence is not in the warning", old >= 0)
        assertEquals("delete_wallet_checkbox_warning_checked changed", CHECKED, en["delete_wallet_checkbox_warning_checked"])

        val note = "identity_reset_old_data_note"
        val enNote = strings(IDENTITY, "values")[note]
        val esNote = strings(IDENTITY, "values-es")[note]
        assertTrue("$note in EN and ES", enNote != null && esNote != null)
        assertNotEquals(enNote, esNote)
        val view = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityView.kt")
        val dialog = view.substringAfter("R.string.identity_reset_warning", "")
        val fullBranch = dialog.substringBefore("} else {")
        assertTrue("the full-reset branch of the confirm dialog does not show $note", "R.string.$note" in fullBranch)
        assertEquals("$note is shown elsewhere too", 1, Regex("""R\.string\.$note\b""").findAll(view).count())

        // The sentence cannot ship without ZC-090-O: the pass's delete set names the marker of old set-aside data.
        val wipe = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/ResetWipe.kt")
        assertTrue("ResetWipe's delete set does not name the set-aside marker", "SET_ASIDE_MARKER" in wipe)
    }

    // ------------------------------------------------------------------ helpers

    private fun router(backs: AtomicInteger): NavigationRouter =
        ResetDriver.standIn { name, _ ->
            if (name == "back") backs.incrementAndGet()
            Unit
        }

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

    private fun stringFiles(): List<File> =
        File(repoRoot(), "ui-lib/src/main/res")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name?.startsWith("values") == true }
            .toList()

    private fun strings(
        folder: String,
        qualifier: String,
    ): Map<String, String> {
        val file =
            File(repoRoot(), "ui-lib/src/main/res/ui/$folder/$qualifier")
                .listFiles()
                .orEmpty()
                .filter { it.extension == "xml" }
        val pairs =
            file.flatMap { xml ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(xml)
                        .getElementsByTagName("string")
                (0 until nodes.length).map {
                    val node = nodes.item(it)
                    node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
                }
            }
        return pairs.toMap()
    }

    /** The text of a declaration from [start] to its closing brace at the same depth, or null. */
    private fun block(
        source: String,
        start: String,
    ): String? {
        val at = source.indexOf(start).takeIf { it >= 0 } ?: return null
        val open = source.indexOf('{', at).takeIf { it >= 0 } ?: return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(at, i + 1)
            }
        }
        return null
    }

    private companion object {
        const val USE_CASE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt"
        const val DW = "delete_wallet"
        const val IDENTITY = "common"
        const val WARNING = "delete_wallet_checkbox_warning_unchecked"
        const val CHECKED = "Kept on this phone. Restore this same recovery phrase to get it back."
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
