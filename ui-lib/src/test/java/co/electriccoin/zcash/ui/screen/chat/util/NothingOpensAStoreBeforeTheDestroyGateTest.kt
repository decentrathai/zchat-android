package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-692-S (register ZC-692, high; owner rule W-41): nothing of the app opens a store before the start pass.
 *
 * The start pass (`DestroyWipe.atProcessStart`) deletes raw files as the first statement of the app's start. That is
 * only right while nothing has read one of those files into memory before it: a settings object that was read before
 * its file is deleted is what the app would open afterwards, and it would write the file back.
 *
 * Android creates an app's content providers before `Application.onCreate`. So this class pins, by source scans:
 * which providers the manifests of this repo declare (a further one fails here until it is listed and looked at),
 * that the provider classes of this repo and their base class touch no settings, no encrypted store, no phone-security
 * key, no dependency graph, no synchronizer and no store class of ZChat, that this repo adds no `androidx.startup`
 * initializer of its own, and that the pass itself reads raw files only and asks the one main-process rule (ZC-706).
 * What libraries add to the merged manifest is checked on the built APK, not here.
 */
class NothingOpensAStoreBeforeTheDestroyGateTest {
    private data class Provider(
        val manifest: String,
        val name: String,
        val removed: Boolean
    )

    /** Every module of this repo whose code can be in the app: the test-only modules are left out. */
    private val modules: List<File> =
        ChatResourceText.repoRoot
            .listFiles()
            .orEmpty()
            .filter { File(it, "src").isDirectory && !it.name.endsWith("-test") && it.name != "test-lib" && it.name != "buildSrc" }
            .sortedBy { it.name }

    private fun shippedSets(): List<File> =
        modules.flatMap { module ->
            File(module, "src").listFiles().orEmpty().filter { it.isDirectory && it.name != "test" && it.name != "androidTest" }
        }

    private fun manifests(): List<File> = shippedSets().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }

    private fun providers(): List<Provider> =
        manifests().flatMap { manifest ->
            Regex("""<provider\b[^>]*>""")
                .findAll(manifest.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), ""))
                .map { tag ->
                    Provider(
                        manifest = DestroySources.relative(manifest),
                        name = nameOf(tag.value),
                        removed = "tools:node=\"remove\"" in tag.value
                    )
                }.toList()
        }

    private fun nameOf(tag: String): String {
        val match = Regex("""android:name="([^"]+)"""").find(tag)
        return match?.groupValues?.get(1).orEmpty()
    }

    @Test
    fun `the manifests of this repo declare exactly the known providers`() {
        val found = providers()
        assertTrue("fixture: the manifests were found: ${manifests().size}", manifests().size >= 10)
        assertEquals(
            "a provider runs before the app's start, so before the start pass: a new one must be looked at and listed here",
            setOf(SHARE, MAIN_PROCESS_NAME, CRASH_PROCESS_NAME, FAULT_SEAMS, FIREBASE),
            found.map { it.name }.toSet()
        )
        assertEquals(
            "the debug seam loader is declared in ui-lib's debug source set only",
            listOf("ui-lib/src/debug/AndroidManifest.xml"),
            found.filter { it.name == FAULT_SEAMS }.map { it.manifest }
        )
        assertTrue(
            "the share provider is declared by the app module only",
            found.filter { it.name == SHARE }.all { it.manifest.startsWith("app/src/") }
        )
        assertEquals(
            "the main process's name provider is spackle's",
            listOf("spackle-android-lib/src/main/AndroidManifest.xml"),
            found.filter { it.name == MAIN_PROCESS_NAME }.map { it.manifest }
        )
        assertTrue(
            "the crash process's name provider is the crash library's",
            found.filter { it.name == CRASH_PROCESS_NAME }.all { it.manifest.startsWith("crash-android-lib/src/") }
        )
        val firebase = found.filter { it.name == FIREBASE }
        assertTrue("fixture: the store flavors name Firebase's init provider", firebase.isNotEmpty())
        assertTrue(
            "Firebase's init provider is named only to REMOVE it, and only by the crash library's store flavors: $firebase",
            firebase.all { it.removed && Regex("""crash-android-lib/src/zcash\w+Store\w+/AndroidManifest\.xml""").matches(it.manifest) }
        )
        assertTrue("no other provider is removed or re-declared", found.filter { it.removed }.all { it.name == FIREBASE })
    }

    @Test
    fun `the provider classes of this repo and their base class touch no store, no key and no dependency graph`() {
        val sources =
            listOf(
                "ui-lib/src/main/java/co/electriccoin/zcash/global/ShareFileProvider.kt",
                "spackle-android-lib/src/main/kotlin/co/electriccoin/zcash/spackle/process/internal/DefaultProcessContentProvider.kt",
                "crash-android-lib/src/main/kotlin/co/electriccoin/zcash/crash/android/internal/local/CrashProcessNameContentProvider.kt",
                "ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/FaultSeamsLoader.kt",
                "spackle-android-lib/src/main/kotlin/co/electriccoin/zcash/spackle/process/AbstractProcessNameContentProvider.kt",
            )
        val forbidden =
            listOf(
                "SharedPreferences",
                "PreferenceProvider",
                "PreferenceHolder",
                "MasterKey",
                "KeyStore",
                "KeyGenParameterSpec",
                "DataStore",
                "SQLite",
                "koin",
                "Koin",
                "inject",
                "Synchronizer",
                "WalletCoordinator",
                "ZchatPreferences",
                "EncryptedStoreOpener",
                "ContactBook",
                "AddressCache",
                "AiPrefs",
                "noBackupFilesDir",
                "getDatabasePath",
            )
        for (path in sources) {
            val code = DestroySources.code(path)
            assertTrue("fixture: $path exists", code.isNotEmpty())
            for (name in forbidden) {
                assertFalse("$path runs before the start pass and must not name $name", name in code)
            }
        }
        val share = DestroySources.code(sources[0])
        val plainFileProvider = Regex("""class ShareFileProvider\s*:\s*FileProvider\(""")
        assertTrue("the share provider is a plain file provider", plainFileProvider.containsMatchIn(share))
        for (path in sources.subList(1, 3)) {
            assertTrue(
                "$path is an empty subclass of the process-name provider",
                Regex("""class \w+\s*:\s*AbstractProcessNameContentProvider\(\)\s*$""").containsMatchIn(DestroySources.code(path).trim())
            )
        }
    }

    @Test
    fun `this repo adds no start-up initializer of its own`() {
        for (manifest in manifests()) {
            assertFalse(
                "${DestroySources.relative(manifest)} must not declare an androidx.startup entry",
                "androidx.startup" in manifest.readText()
            )
        }
        val initializers =
            shippedSets()
                .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
                .filter { file ->
                    val text = file.readText()
                    "androidx.startup" in text || Regex(""":\s*Initializer<""").containsMatchIn(text)
                }.map { DestroySources.relative(it) }
        assertEquals("no product class implements androidx.startup's Initializer", emptyList<String>(), initializers)
    }

    @Test
    fun `the start pass reads raw files only and asks the one main-process rule`() {
        val wipe = DestroySources.code(DestroySources.WIPE)
        assertTrue("DestroyWipe.kt exists", wipe.isNotEmpty())
        val forbidden =
            listOf(
                "getSharedPreferences",
                "EncryptedSharedPreferences",
                "MasterKey",
                "PreferenceProvider",
                "koin",
                "Koin",
                "inject(",
                "getSynchronizer",
            )
        for (name in forbidden) {
            assertFalse("DestroyWipe.kt must not name $name", name in wipe)
        }
        assertTrue("DestroyWipe.kt has the start pass", Regex("""fun atProcessStart\(\s*context: Context\s*\)""").containsMatchIn(wipe))
        assertTrue("the start pass uses MainProcess.isMain (ZC-706's rule)", "MainProcess.isMain(" in wipe)
        assertTrue("and the process name the app's own provider stored", "ProcessNameCompat.getProcessName(" in wipe)
        assertEquals("the rule is asked in one place", 1, Regex("""MainProcess\.isMain\(""").findAll(wipe).count())
        assertFalse(
            "DestroyWipe.kt holds no second comparison of a process name with the package name",
            Regex("""[pP]rocess\w*\s*[!=]=\s*(\w+\.)?packageName\b|\bpackageName\s*[!=]=\s*\w*[pP]rocess""").containsMatchIn(wipe)
        )
    }

    private companion object {
        const val SHARE = "co.electriccoin.zcash.global.ShareFileProvider"
        const val MAIN_PROCESS_NAME = ".process.internal.DefaultProcessContentProvider"
        const val CRASH_PROCESS_NAME = ".internal.local.CrashProcessNameContentProvider"
        const val FAULT_SEAMS = "co.electriccoin.zcash.ui.debug.FaultSeamsLoader"
        const val FIREBASE = "com.google.firebase.provider.FirebaseInitProvider"
    }
}
