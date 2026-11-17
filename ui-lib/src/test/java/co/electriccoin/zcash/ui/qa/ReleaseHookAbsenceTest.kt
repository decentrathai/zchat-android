package co.electriccoin.zcash.ui.qa

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HOOK-ABSENT-RELEASE (P0): the QA test seams introduced for the ZC-101 on-device harness must be
 * UNREACHABLE in release builds.
 *
 * Two seams are pinned:
 *  1. [TestHooksReceiver] (`xyz.zsend.zchat.debug.INJECT_ACK_PROPOSAL`) — must exist ONLY in the
 *     `debug` source set (bytecode absent from release) and be registered ONLY in the debug
 *     manifest, protected by a signature-level permission. No non-debug source may even reference
 *     it, so no release code path can reach it reflectively.
 *  2. [PaymentDiag] — lives in `main` (call sites are production files), so its emission must be
 *     hard-gated on `BuildConfig.DEBUG` through a single logging chokepoint.
 *  3. [InboundDiag] — the receive-side mirror of PaymentDiag (inbound-message QA oracle), same
 *     placement (`main`, production call sites in ChatViewModel), same rule: emission hard-gated on
 *     `BuildConfig.DEBUG` through a single logging chokepoint, so a RELEASE build emits nothing on
 *     the `InboundDiag` tag.
 *
 * These are FILE-STRUCTURE assertions (source sets + manifests are exactly what decides what a
 * release APK contains for a library module), so the test is variant-independent and runs in every
 * unit-test task. If someone moves the receiver into `main`, registers it in another manifest,
 * references it from production code (by class name OR by the raw action string — a dynamically
 * `registerReceiver`ed hook needs neither the manifest nor the class name, only the action), or
 * un-gates PaymentDiag, this test fails the build.
 *
 * ARTIFACT-LEVEL GATE (run at release time, complements this source-level test): after
 * `assemble<Variant>Release`, verify the ASSEMBLED release manifest contains ZERO `<receiver>`
 * elements for the hook and that the action string is absent — e.g.
 * `aapt2 dump xmltree <release.apk> --file AndroidManifest.xml` (or `apkanalyzer manifest print
 * <release.apk>`) piped to `grep -c` for `TestHooksReceiver` / the [HOOK_ACTION] string, both
 * required to be `0`. That checks the actual shipped binary (post-manifest-merge, post-shrink), the
 * one thing a source/manifest test can only infer.
 */
class ReleaseHookAbsenceTest {

    // ── Seam 1: TestHooksReceiver ────────────────────────────────────────────────────────────────

    @Test
    fun `no release-reachable manifest registers the TestHooks receiver or its action`() {
        val nonDebugManifests =
            (uiLibDir.resolve("src").manifestsExcludingDebug() + appDir.resolve("src").manifestsExcludingDebug())
        assertTrue(nonDebugManifests.isNotEmpty(), "sanity: expected at least the main manifests")
        nonDebugManifests.forEach { manifest ->
            val content = manifest.readText()
            assertTrue(
                RECEIVER_CLASS_NAME !in content && HOOK_ACTION !in content,
                "RELEASE LEAK: ${manifest.path} mentions the debug-only TestHooks receiver/action"
            )
        }
    }

    @Test
    fun `TestHooks receiver class exists only in the debug source set`() {
        val debugReceiver =
            uiLibDir.resolve("src/debug/java/co/electriccoin/zcash/ui/debug/TestHooksReceiver.kt")
        assertTrue(debugReceiver.isFile, "expected the receiver at ${debugReceiver.path}")

        val leaks = nonDebugKtSourcesReferencing(RECEIVER_CLASS_NAME)
        assertEquals(
            emptyList(),
            leaks,
            "RELEASE LEAK: non-debug sources reference $RECEIVER_CLASS_NAME — release code could reach the hook"
        )
    }

    @Test
    fun `no non-debug source references the hook action string`() {
        // A dynamically-registered receiver (registerReceiver) needs neither the manifest nor the
        // class name — only the action string. So scan non-debug sources (ui-lib AND app) for the
        // raw [HOOK_ACTION] too, not just the class name.
        val leaks = nonDebugKtSourcesReferencing(HOOK_ACTION)
        assertEquals(
            emptyList(),
            leaks,
            "RELEASE LEAK: non-debug sources reference the hook action $HOOK_ACTION — a release code "
                + "path could register/fire the hook dynamically without the debug manifest"
        )
    }

    @Test
    fun `debug manifest triple-gates the receiver`() {
        val debugManifest = uiLibDir.resolve("src/debug/AndroidManifest.xml").readText()

        // Gate 1 (placement) is the file's location itself; pin its content:
        assertTrue(HOOK_ACTION in debugManifest, "debug manifest must register the $HOOK_ACTION action")
        assertTrue(
            "android:permission=\"android.permission.DUMP\"" in debugManifest,
            "receiver must require the signature-level DUMP permission (shell-holdable, not app-obtainable)"
        )

        // Gate 2: runtime BuildConfig.DEBUG guard is the receiver's FIRST statement — anchored, not
        // a bare substring (a guard buried below other logic would be a real leak the substring
        // check would miss).
        val receiverSource =
            uiLibDir
                .resolve("src/debug/java/co/electriccoin/zcash/ui/debug/TestHooksReceiver.kt")
                .readText()
        val onReceiveOpen =
            Regex("""fun\s+onReceive\s*\([^)]*\)\s*\{""").find(receiverSource)
                ?: fail("could not locate onReceive( ... ) { in the receiver source")
        val firstStatement =
            receiverSource
                .substring(onReceiveOpen.range.last + 1)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals(
            "if (!BuildConfig.DEBUG) return",
            firstStatement,
            "the BuildConfig.DEBUG guard must be the FIRST statement of onReceive (found: $firstStatement)"
        )

        // Create-only invariant: the hook must never enter the auto-submit path.
        assertTrue(
            "directSubmit = false" in receiverSource,
            "the hook must invoke the create path with directSubmit = false (create-only, never submits)"
        )
    }

    // ── Seam 2: PaymentDiag ──────────────────────────────────────────────────────────────────────

    @Test
    fun `PaymentDiag emission is BuildConfig-DEBUG gated through a single chokepoint`() {
        val source =
            uiLibDir
                .resolve("src/main/java/co/electriccoin/zcash/ui/common/util/PaymentDiag.kt")
                .readText()

        assertTrue(
            source.contains(
                """
                |    private val enabled: Boolean
                |        get() = BuildConfig.DEBUG
                """.trimMargin()
            ),
            "PaymentDiag's enabled gate must be BuildConfig.DEBUG"
        )

        // Exactly ONE android.util.Log call site, inside the gated emit() chokepoint.
        val logCalls = Regex("""(?<![.\w])Log\.\w+\(""").findAll(source).toList()
        assertEquals(
            1,
            logCalls.size,
            "PaymentDiag must funnel every emission through the single emit() Log call, found: " +
                logCalls.map { it.value }
        )
        assertTrue(
            source.contains(
                """
                |    private fun emit(event: String) {
                |        if (!enabled) return
                |        Log.i(TAG, event)
                """.trimMargin()
            ),
            "emit() must gate on enabled BEFORE the Log call"
        )
        assertNoAlternateEmissionPath(source, "PaymentDiag")
    }

    // ── Seam 3: InboundDiag (receive-side mirror of PaymentDiag) ─────────────────────────────────

    @Test
    fun `InboundDiag emission is BuildConfig-DEBUG gated through a single chokepoint`() {
        val source =
            uiLibDir
                .resolve("src/main/java/co/electriccoin/zcash/ui/common/util/InboundDiag.kt")
                .readText()

        assertTrue(
            source.contains(
                """
                |    private val enabled: Boolean
                |        get() = BuildConfig.DEBUG
                """.trimMargin()
            ),
            "InboundDiag's enabled gate must be BuildConfig.DEBUG"
        )

        // Exactly ONE android.util.Log call site, inside the gated emit() chokepoint.
        val logCalls = Regex("""(?<![.\w])Log\.\w+\(""").findAll(source).toList()
        assertEquals(
            1,
            logCalls.size,
            "InboundDiag must funnel every emission through the single emit() Log call, found: " +
                logCalls.map { it.value }
        )
        assertTrue(
            source.contains(
                """
                |    private fun emit(event: String) {
                |        if (!enabled) return
                |        Log.i(TAG, event)
                """.trimMargin()
            ),
            "emit() must gate on enabled BEFORE the Log call"
        )

        // Public entry point must ALSO gate before doing any work (hashing/redaction), so release
        // builds pay zero cost and no un-gated helper can grow a second emission path.
        assertTrue(
            source.contains(
                """
                |    fun logMessageAdded(message: ChatMessage, transport: String) {
                |        if (!enabled) return
                """.trimMargin()
            ),
            "logMessageAdded must gate on enabled as its FIRST statement"
        )

        // No second emission path in ANY spelling: fully-qualified android.util.Log.x(...),
        // println/print, System.out/err, Timber (the lone-Log regex above deliberately rejects a
        // leading dot, so a qualified call would otherwise slip past the count).
        assertNoAlternateEmissionPath(source, "InboundDiag")

        // Privacy: the event line must never interpolate the raw text/peerAddress/id/memo — only
        // the one-way fingerprints and the redacted txid may appear in the emitted string.
        // message.id is NOT loggable either: on the chain transport it is the FULL 64-hex txid
        // (ChatViewModel sets messageId = tx.id.txIdString()), and the runner ships InboundDiag
        // lines in PR-attachable evidence bundles — a raw id would publicly link the QA wallet to
        // an on-chain transaction.
        assertTrue(
            "\${message.text}" !in source && "\${message.peerAddress}" !in source,
            "InboundDiag must never interpolate raw message.text or message.peerAddress into a log line"
        )
        assertTrue(
            "\${message.id}" !in source,
            "InboundDiag must never interpolate raw message.id (on-chain it IS the full txid) — " +
                "fingerprint it with fpOf(message.id)"
        )

        // Structural privacy rule (catches .take(n)-style partial leaks, not just the exact
        // substrings above): EVERY ${...} interpolation that touches `message.` must be wrapped in
        // fpOf( or redactTxId( — the only exception is the boolean message.isOutgoing flag.
        val offendingInterpolations =
            Regex("""\$\{([^}]+)}""")
                .findAll(source)
                .map { it.groupValues[1].trim() }
                .filter { expr ->
                    "message." in expr &&
                        expr != "message.isOutgoing" &&
                        !expr.startsWith("fpOf(") &&
                        !expr.startsWith("redactTxId(")
                }
                .toList()
        assertEquals(
            emptyList(),
            offendingInterpolations,
            "InboundDiag interpolates message-derived values without a one-way fpOf()/redactTxId() " +
                "wrapper — raw or partially-raw fields must never reach a log line"
        )
    }

    private companion object {
        const val RECEIVER_CLASS_NAME = "TestHooksReceiver"
        const val HOOK_ACTION = "xyz.zsend.zchat.debug.INJECT_ACK_PROPOSAL"

        /**
         * Unit tests run with the module directory as the working dir; walk upward so this also
         * works when a runner starts from the repository root (or an IDE from a nested dir).
         */
        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var dir: File? = start
            repeat(8) {
                val current = dir ?: return@repeat
                if (current.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) {
                    return@lazy current
                }
                val nested = current.resolve("ui-lib")
                if (nested.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) {
                    return@lazy nested
                }
                dir = current.parentFile
            }
            fail("could not locate the ui-lib module from ${start.path}")
        }

        val appDir: File by lazy {
            val parent = uiLibDir.parentFile ?: fail("ui-lib has no parent directory")
            parent.resolve("app").also {
                if (!it.isDirectory) fail("could not locate the app module next to ${uiLibDir.path}")
            }
        }

        val thisTestFile: File by lazy {
            uiLibDir.resolve("src/test/java/co/electriccoin/zcash/ui/qa/ReleaseHookAbsenceTest.kt")
        }

        /**
         * The lone-Log-call regex used by the chokepoint tests rejects a leading dot, so a
         * fully-qualified `android.util.Log.i(...)` call would NOT be counted — and neither would
         * println/System.out/Timber. Close every alternate spelling of an emission path:
         *  - "android.util.Log" may appear EXACTLY once, and only as the import statement;
         *  - no println(/print(, no System.out/System.err, no Timber, anywhere in the file.
         */
        fun assertNoAlternateEmissionPath(source: String, fileLabel: String) {
            val qualifiedLogLines =
                source.lineSequence().filter { "android.util.Log" in it }.map { it.trim() }.toList()
            assertEquals(
                listOf("import android.util.Log"),
                qualifiedLogLines,
                "$fileLabel: \"android.util.Log\" must appear exactly once, as the import — a " +
                    "fully-qualified Log call would bypass the single-chokepoint count"
            )
            listOf("println(", "print(", "System.out", "System.err", "Timber").forEach { banned ->
                assertTrue(
                    banned !in source,
                    "$fileLabel: forbidden emission path \"$banned\" found — all output must go " +
                        "through the single gated emit() Log chokepoint"
                )
            }
        }

        fun File.manifestsExcludingDebug(): List<File> =
            walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .filterNot { it.path.contains("${File.separator}debug${File.separator}") }
                .toList()

        /**
         * Non-debug `.kt` sources across BOTH the ui-lib and app modules that contain [needle]
         * (this test file itself excluded — it names the receiver/action deliberately).
         */
        fun nonDebugKtSourcesReferencing(needle: String): List<File> =
            (uiLibDir.resolve("src").walkTopDown() + appDir.resolve("src").walkTopDown())
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("${File.separator}debug${File.separator}") }
                .filterNot { it == thisTestFile }
                .filter { needle in it.readText() }
                .toList()
    }
}
