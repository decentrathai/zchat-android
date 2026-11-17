package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-090-P (register ZC-090, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.5 item 7, 5.1 and 6.2): what the
 * start says after the Reset pass. Finished, or a record that alone could not be removed: nothing (the start-up check
 * goes on to its ordinary states). Not finished: a state of its own, read before any store is opened, with two
 * buttons, Try again (a restart) and Uninstall, and no other way on; drawn by its own function, not by Destroy's
 * branch and not by the start-up screen's frame.
 *
 * The check reads `ResetWipe`'s outcome through a property of its own ([OUTCOME_PROPERTY]), whose default is
 * `ResetWipe.startOutcome`: the constructor keeps the seven parameters StartPassChecksWhatIsLeftTest pins. Every new
 * name is reached by name, so this class compiles before the fix.
 */
class ResetStartPassTellsWhatIsNotFinishedTest {
    @After
    fun tearDown() {
        runCatching { Class.forName(RESET_CLASS) }.getOrNull()?.let { setOutcome(it, "NONE") }
    }

    @Test
    fun `finished - the start-up check goes to its ordinary first state and shows nothing`() {
        for (outcome in listOf("FINISHED", "NONE", "IGNORED")) {
            val opened = ArrayList<String>()
            val state = stateFor(outcome, opened)
            assertEquals("$outcome: the check goes on", StartupStorageCheck.State.Ready, state)
            assertEquals("$outcome: the stores are opened", listOf("seed", "chats"), opened)
            assertEquals("$outcome: nothing is shown", null, startupFailureCause(null, state))
        }
    }

    @Test
    fun `not finished - its own state, read before any store is opened, no way on`() {
        val opened = ArrayList<String>()
        val state = stateFor("NOT_FINISHED", opened)
        assertEquals("ResetUnfinished", state.javaClass.simpleName)
        assertEquals("a store was opened before the Reset state", emptyList<String>(), opened)
        assertNotNull("the start-up screen is shown for it", startupFailureCause(null, state))
        assertFalse("the splash does not stay over it", isStartupStillLoading(SecretState.NONE, state))
        for (secret in SecretState.entries) {
            assertEquals("no wallet opens in this process ($secret)", SecretState.LOADING, gatedSecretState(secret, state))
        }
    }

    @Test
    fun `the record cannot be removed - no state of its own`() {
        // The pass answers FINISHED when only its record is left (ResetStartPassFinishesOnlyItsOwnResetTest).
        val state = stateFor("FINISHED", ArrayList())
        assertEquals(StartupStorageCheck.State.Ready, state)
    }

    @Test
    fun `not Destroy's branch - its own function, two buttons, none of the frame's parts`() {
        val screen = code(SCREEN)
        val view = block(screen, "fun ResetStartView(")
        assertNotNull("the start-up screen file has a view of its own for the Reset state: ResetStartView", view)
        val frameParts =
            listOf(
                "wallet_startup_failed_do_not_uninstall",
                "wallet_startup_failed_backup_button",
                "wallet_startup_failed_try_again",
            )
        for (name in frameParts) {
            assertFalse("the Reset view names $name", name in view!!)
        }
        assertEquals("the Reset view has TWO buttons", 2, Regex("""ZashiButton\(""").findAll(view!!).count())
        assertTrue("its first button is Try again", "R.string.reset_unfinished_try_again" in view)
        assertTrue("its second button asks for the uninstall", "R.string.reset_unfinished_uninstall" in view)
        assertFalse("the Reset view is not Destroy's", "DestroyStartView(" in view || "destroy_unfinished" in view)

        val entry = block(screen, "fun WalletStartupFailedScreen()").orEmpty()
        val branch = entry.indexOf("ResetStartView(")
        val frame = entry.indexOf("WalletStartupFailedView(")
        assertTrue("the screen draws ResetStartView before its frame", branch in 0 until frame)
        val drawn = entry.substring(branch, entry.indexOf("return", branch).takeIf { it > 0 } ?: frame)
        assertTrue("Try again restarts ZCHAT", "restart()" in drawn)
        assertTrue("Uninstall asks the phone to uninstall ZCHAT", "requestUninstall(" in drawn)

        val check = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/walletstartup/StartupStorageCheck.kt")
        val open = block(check, "private suspend fun openStores()").orEmpty()
        val asked = open.indexOf("resetStartOutcome()")
        assertTrue("the Reset outcome is read before the first store is opened", asked in 0 until open.indexOf("openSeedStore()"))
    }

    @Test
    fun `the texts - four keys in English and Spanish, the body names Try again and what an uninstall removes`() {
        val english = strings("values")
        val spanish = strings("values-es")
        val expected =
            mapOf(
                "reset_unfinished_title" to "ZCHAT could not finish the reset",
                "reset_unfinished_try_again" to "Try again",
                "reset_unfinished_uninstall" to "Uninstall ZCHAT",
            )
        for ((key, text) in expected) assertEquals("English $key", text, english[key])
        for (key in KEYS) {
            val en = english[key] ?: throw AssertionError("no English $key")
            val es = spanish[key] ?: throw AssertionError("no Spanish $key")
            assertNotEquals("$key: the Spanish is the English", en, es)
            assertFalse("$key holds a placeholder", "%" in en || "%" in es)
        }
        val body = english.getValue("reset_unfinished_body")
        val cuerpo = spanish.getValue("reset_unfinished_body")
        assertTrue(body, "Try again" in body)
        assertTrue(cuerpo, "Reintentar" in cuerpo)
        assertTrue(body, "chose to keep" in body)
        assertTrue(cuerpo, "elegiste conservar" in cuerpo)
        assertTrue("no string named reset_record_stuck_*", (english.keys + spanish.keys).none { it.startsWith("reset_record_stuck_") })
    }

    // ------------------------------------------------------------------ helpers

    private fun stateFor(
        outcome: String,
        opened: MutableList<String>,
    ): StartupStorageCheck.State {
        val check =
            StartupStorageCheck(
                openSeedStore = { opened += "seed" },
                openChatStores = { opened += "chats" },
                ioDispatcher = Dispatchers.Unconfined,
            )
        val reset = Class.forName(RESET_CLASS)
        setOutcome(reset, outcome)
        val property =
            StartupStorageCheck::class.java.methods.firstOrNull { it.name == SETTER }
                ?: throw AssertionError("StartupStorageCheck has no property $OUTCOME_PROPERTY")
        val value = reset.getMethod("getStartOutcome").invoke(reset.getField("INSTANCE").get(null))
        property.invoke(check, { value })
        runBlocking { check.run() }
        return check.state.value
    }

    private fun setOutcome(
        reset: Class<*>,
        name: String,
    ) {
        val type = reset.getMethod("getStartOutcome").returnType
        val value = type.enumConstants!!.first { (it as Enum<*>).name == name }
        reset.getMethod("setStartOutcome", type).invoke(reset.getField("INSTANCE").get(null), value)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun code(relative: String): String = File(repoRoot(), relative).takeIf { it.isFile }?.readText().orEmpty()

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

    private fun strings(qualifier: String): Map<String, String> {
        val file = File(repoRoot(), "ui-lib/src/main/res/ui/delete_wallet/$qualifier/strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private companion object {
        const val RESET_CLASS = "co.electriccoin.zcash.ui.screen.chat.util.ResetWipe"
        const val OUTCOME_PROPERTY = "resetStartOutcome"
        const val SETTER = "setResetStartOutcome"
        const val SCREEN = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/walletstartup/WalletStartupFailedScreen.kt"
        val KEYS = listOf("reset_unfinished_title", "reset_unfinished_body", "reset_unfinished_try_again", "reset_unfinished_uninstall")
    }
}
