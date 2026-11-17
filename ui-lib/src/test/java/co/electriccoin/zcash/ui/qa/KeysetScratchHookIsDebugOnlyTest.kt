package co.electriccoin.zcash.ui.qa

import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-705 (store-opener design, revision 2a, section 5.2, class G; the code check's should 4): the debug hook of phone
 * checks K2 to K4 (`xyz.zsend.zchat.debug.KEYSET_SCRATCH`) and the one helper in the repo that writes a key set
 * without encryption stay in the debug source set, work on one fixed scratch store, and are reachable from the hook
 * only. Beside [ReleaseHookAbsenceTest], which pins another action string of the same receiver.
 */
class KeysetScratchHookIsDebugOnlyTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        dir ?: error("repo root not found")
    }

    private val debugDir get() = File(repoRoot, "ui-lib/src/debug")
    private val receiver get() = File(debugDir, "java/co/electriccoin/zcash/ui/debug/$RECEIVER.kt")
    private val helper get() = File(debugDir, "java/co/electriccoin/zcash/ui/debug/KeysetScratch.kt")

    /** Every source and resource file of every module's source sets that ship, except `debug`. */
    private fun nonDebugFiles(): List<File> =
        repoRoot
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "src").isDirectory }
            .flatMap { module ->
                File(module, "src")
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name !in setOf("debug", "test", "androidTest") }
                    .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList() }
            }

    private fun code(file: File): String = StoreOpenerApi.stripComments(file.readText())

    private fun hookBody(): String {
        val text = code(receiver)
        val start = text.indexOf("private fun keysetScratch(")
        assertTrue(start >= 0, "$RECEIVER has no keysetScratch(")
        val end = text.indexOf("\n    private fun ", start + 1).takeIf { it > 0 } ?: text.length
        return text.substring(start, end)
    }

    @Test
    fun `no source outside the debug set holds the action or the scratch store's name`() {
        val leaks =
            nonDebugFiles()
                .filter { file -> file.readText().let { ACTION in it || SCRATCH in it } }
                .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
        assertEquals(emptyList(), leaks, "RELEASE LEAK: the scratch hook is named outside src/debug")
    }

    @Test
    fun `the debug manifest registers the action on the existing receiver, under the same permission`() {
        val manifest = File(debugDir, "AndroidManifest.xml").readText()
        val receiverBlock = manifest.substringAfter("co.electriccoin.zcash.ui.debug.$RECEIVER", "").substringBefore("</receiver>")
        assertTrue("android:permission=\"android.permission.DUMP\"" in receiverBlock, "the receiver lost its permission")
        assertTrue("<action android:name=\"$ACTION\" />" in receiverBlock, "the action is not on the existing receiver")
        assertEquals(1, Regex("<receiver").findAll(manifest).count(), "a second receiver was registered")
    }

    @Test
    fun `the hook takes no store name and opens the scratch store only`() {
        val body = hookBody()
        val extras = Regex("""get(\w+)Extra\(\s*(\w+)""").findAll(body).map { it.groupValues[2] }.toSet()
        assertEquals(setOf("EXTRA_MODE", "EXTRA_VALUE", "EXTRA_FLAG"), extras, "the hook reads an extra that could name a store")
        val opens = Regex("""openEncryptedStore\(\s*name\s*=\s*(\w+)""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(opens.isNotEmpty(), "the hook does not open through the opener")
        assertEquals(setOf("SCRATCH_STORE"), opens.toSet(), "the hook opens a store other than the scratch store")
        val files = Regex("""openEncryptedFile\(\s*context\s*,\s*(\w+)""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("SCRATCH_STORE"), files)
        assertTrue("const val SCRATCH_STORE = \"$SCRATCH\"" in code(receiver), "the scratch store's name changed")
    }

    @Test
    fun `the helper that writes a key entry in the clear is in src-debug only and is called from the hook only`() {
        assertTrue(helper.isFile, "there is no ${helper.path}")
        val calls =
            repoRoot
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".git", ".gradle", "test", "androidTest") }
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> Regex("""\bwriteKeyEntriesInTheClear\(""").findAll(code(file)).map { file } }
                .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
                .toList()
        val inReceiver = calls.count { it.endsWith("$RECEIVER.kt") }
        assertTrue(inReceiver >= 1, "the hook does not call the helper")
        assertEquals(
            setOf(
                "ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/KeysetScratch.kt",
                "ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/$RECEIVER.kt",
            ),
            calls.toSet(),
            "the helper is declared or called elsewhere",
        )
        val inHook = Regex("""\bwriteKeyEntriesInTheClear\(""").findAll(hookBody()).count()
        assertEquals(inReceiver, inHook, "the receiver calls it outside the hook")
    }

    @Test
    fun `the limit mode sets keepPlainAfterLimit for the scratch store only`() {
        val debugUses =
            debugDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> Regex("""keepPlainAfterLimit""").findAll(code(file)).map { file.name } }
                .toList()
        val body = hookBody()
        val inHook = Regex("""keepPlainAfterLimit""").findAll(body).count()
        assertTrue(inHook >= 1, "the hook's limit mode does not set keepPlainAfterLimit")
        assertEquals(debugUses.size, inHook, "keepPlainAfterLimit is set in the debug set outside the hook: $debugUses")
        val limit = body.substringAfter("\"limit\" ->", "").substringBefore("\"remove\" ->")
        assertTrue("keepPlainAfterLimit" in limit, "keepPlainAfterLimit is not in the limit mode")
        assertTrue(Regex("""name\s*=\s*SCRATCH_STORE""").containsMatchIn(limit), "the limit mode opens another store")
    }

    private companion object {
        const val ACTION = "xyz.zsend.zchat.debug.KEYSET_SCRATCH"
        const val SCRATCH = "zchat_qa_scratch_encrypted"

        // The class name in two parts: ReleaseHookAbsenceTest fails on any non-debug source that names the class.
        const val RECEIVER = "TestHooks" + "Receiver"
    }
}
