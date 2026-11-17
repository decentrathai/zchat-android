package co.electriccoin.zcash.global

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-706: the app's start-up runs only in the main process.
 *
 * After an uncaught exception Android starts the crash-report process (`<package>:crash`) for the receiver
 * that writes the report. `ZcashApplication.onCreate` runs there too, and without a check it builds the whole
 * dependency graph a second time: a second synchronizer on the same wallet database, a second TEX checker and
 * a second copy of the recovery phrase in memory, beside the main process the user has just reopened.
 *
 * The rule is "only the main process starts the app", not "skip the crash process": any further process of
 * the app gets the same answer. A process name that could not be read counts as main, so the app can never
 * refuse to start over it.
 */
class AppStartRunsOnlyInTheMainProcessTest {
    // (1) The pure rule. Reached by name, so that this file compiles before the rule exists.

    @Test
    fun the_package_name_itself_is_the_main_process() {
        assertTrue(isMain(PACKAGE, PACKAGE), "the main process must start the app")
    }

    @Test
    fun the_crash_report_process_is_not_the_main_process() {
        assertFalse(isMain("$PACKAGE:crash", PACKAGE), "the crash-report process must not start the app")
    }

    @Test
    fun any_other_process_of_the_app_is_not_the_main_process() {
        assertFalse(isMain("$PACKAGE:sync", PACKAGE), "a further process of the app must not start the app")
        assertFalse(isMain("$PACKAGE:", PACKAGE))
    }

    @Test
    fun another_packages_name_is_not_the_main_process() {
        assertFalse(isMain("xyz.zsend.zchat.foss", PACKAGE))
        assertFalse(isMain("$PACKAGE.other", PACKAGE))
        assertFalse(isMain("", PACKAGE))
    }

    @Test
    fun a_process_name_that_could_not_be_read_counts_as_main() {
        assertTrue(isMain(null, PACKAGE), "the app must never refuse to start because a name could not be read")
    }

    // (2) The source scan of ZcashApplication.onCreate. It says nothing about what stands between
    // super.onCreate() and configureLogging().

    @Test
    fun exactly_one_statement_leaves_onCreate_and_it_asks_the_rule() {
        val body = onCreate()
        val returns = Regex("""\breturn\b""").findAll(body).count()
        assertEquals(1, returns, "onCreate must leave early in exactly one place")
        assertTrue(CHECK.containsMatchIn(body), "onCreate does not return when MainProcess.isMain(...) is false")
        val arguments = CHECK.find(body)!!.groupValues[1]
        assertTrue(
            Regex("""\bpackageName\b""").containsMatchIn(arguments),
            "the rule is not asked with the app's own package name"
        )
    }

    @Test
    fun the_check_stands_after_the_logging_setup_and_before_everything_that_starts_the_app() {
        val body = onCreate()
        val check = CHECK.find(body)?.range?.first ?: throw AssertionError("no main-process check in onCreate")
        val logging = body.indexOf("configureLogging()")
        assertTrue(logging >= 0, "onCreate no longer sets up logging")
        assertTrue(logging < check, "the check must stand after configureLogging(): the crash receiver logs")
        STARTERS.forEach { starter ->
            val at = body.indexOf(starter)
            assertTrue(at >= 0, "\"$starter\" is no longer in onCreate: this test pins a list that moved")
            assertTrue(check < at, "\"$starter\" runs before the main-process check")
        }
        val firstInit = body.indexOf(".init()")
        assertTrue(firstInit >= 0)
        assertTrue(check < firstInit, "an init() call runs before the main-process check")
        assertEquals(
            INITS.size,
            Regex("""\.init\(\)""").findAll(body).count(),
            "onCreate has an init() call this test does not know"
        )
    }

    @Test
    fun the_process_name_is_read_with_the_helper_inside_a_catch() {
        val body = onCreate()
        val check = CHECK.find(body)?.range?.first ?: throw AssertionError("no main-process check in onCreate")
        val before = body.substring(body.indexOf("configureLogging()").coerceAtLeast(0), check)
        val read = "ProcessNameCompat.getProcessName(this)"
        assertEquals(1, Regex(Regex.escape(read)).findAll(body).count(), "the process name is read once")
        assertTrue(read in before, "the name is not read between the logging setup and the check")
        // The helper throws when the name cannot be determined; that case must reach the rule as null.
        val caught =
            Regex("""runCatching\s*\{[^{}]*""" + Regex.escape(read) + """[^{}]*\}\s*\.getOrNull\(\)""")
                .containsMatchIn(before) ||
                Regex("""try\s*\{[^{}]*""" + Regex.escape(read) + """[^{}]*\}\s*catch\b""").containsMatchIn(before)
        assertTrue(caught, "ProcessNameCompat.getProcessName can throw: read it inside a catch")
    }

    // (3) The guard: which processes this app has at all.

    @Test
    fun only_the_crash_library_declares_a_second_process_and_it_is_the_crash_one() {
        val root = ChatResourceText.repoRoot
        val main = File(root, "crash-android-lib/src/main/AndroidManifest.xml").readText()
        CRASH_COMPONENTS.forEach { (tag, name) ->
            val element =
                Regex("<$tag\\b[^>]*>").findAll(main).map { it.value }.firstOrNull { name in it }
                    ?: throw AssertionError("crash-android-lib's manifest no longer declares $name")
            assertTrue(
                "android:process=\":crash\"" in element,
                "$name no longer runs in the crash-report process"
            )
        }
        val declaring =
            root
                .walkTopDown()
                .onEnter { it.name !in SKIPPED_FOLDERS }
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .filter { "android:process" in it.readText() }
                .toList()
        assertTrue(declaring.isNotEmpty())
        declaring.forEach { manifest ->
            val path = manifest.relativeTo(root).invariantSeparatorsPath
            assertTrue(
                path.startsWith("crash-android-lib/"),
                "$path declares android:process: every process of the app but the main one must stay out of the start"
            )
            Regex("""android:process="([^"]*)"""").findAll(manifest.readText()).forEach {
                assertEquals(":crash", it.groupValues[1], "$path declares a process other than the crash one")
            }
        }
    }

    private fun onCreate(): String =
        SourceScan.braceBlock(
            SourceScan.stripComments(
                ChatResourceText.source("app/src/main/java/co/electriccoin/zcash/app/ZcashApplication.kt")
            ),
            "override fun onCreate()"
        )

    private fun isMain(
        processName: String?,
        packageName: String
    ): Boolean {
        val rule =
            try {
                Class.forName(RULE)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("$RULE does not exist: no rule says which process starts the app", e)
            }
        val function = rule.getMethod("isMain", String::class.java, String::class.java)
        return function.invoke(rule.getField("INSTANCE").get(null), processName, packageName) as Boolean
    }

    private companion object {
        const val PACKAGE = "xyz.zsend.zchat.foss.debug"
        const val RULE = "co.electriccoin.zcash.global.MainProcess"

        val CRASH_COMPONENTS = listOf("provider" to "CrashProcessNameContentProvider", "receiver" to "ExceptionReceiver")

        /** `if (!MainProcess.isMain(<arguments>)) return`, with or without braces. */
        val CHECK = Regex("""if\s*\(\s*!\s*MainProcess\.isMain\(([^()]*)\)\s*\)\s*\{?\s*return\b""")

        val INITS =
            listOf(
                "flexaRepository.init()",
                "homeMessageCacheRepository.init()",
                "walletSnapshotRepository.init()",
                "applicationStateRepository.init()"
            )

        val STARTERS =
            listOf(
                "startKoin",
                "configureAnalytics()"
            ) + INITS +
                listOf(
                    "observeSynchronizerError()",
                    "observeForegroundForNostrInbox()",
                    "get<TexSendMonitor>().startForProcess()"
                )

        val SKIPPED_FOLDERS = setOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules")
    }
}
