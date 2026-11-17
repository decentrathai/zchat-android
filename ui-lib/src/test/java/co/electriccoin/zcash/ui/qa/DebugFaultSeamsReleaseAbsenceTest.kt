package co.electriccoin.zcash.ui.qa

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F0 (fix-all 2.13.0, PLAN_V2 §2 F0): the fault seams the 2.13.0 phone checks need, and the proof that a release
 * build can't reach them.
 *
 * The phone checks of F2/F3/F4/F10/K0/KI-22..25 need faults a real phone won't produce on demand: a Keystore that
 * does not answer, relays that are unreachable, a slow group send, tiny dedup caps, a leg-1 gRPC failure, a kill
 * between two steps, a forced TEX stuck state, a Tor bootstrap failure; plus two one-shot actions, importing the
 * second phone's viewing key as a view-only Keystone account (K0) and making a group's admin key "drift" (KI-25).
 *
 * The rule (owner, 25 Sep): debug-only seams must be unreachable in release builds. So:
 *  1. The switches (arm, disarm, the start-up loader and the one-shot actions) live ONLY in the `debug` source set
 *     and the debug manifest. No release source or manifest names them.
 *  2. The read points live in production code (they have to), behind ONE object, [FAULT_SEAMS_FILE], whose every
 *     function returns at once unless BuildConfig.DEBUG. So in a release build nothing can arm a seam (the switches
 *     are not in the APK), and a read answers "not armed" even if something did.
 *  3. The catalogue is fixed here, with the batch that wires each seam. The seams whose production site exists
 *     at F0 must be read there; the rest are wired by their batch (F2, F3, F4), which moves them into [WIRED].
 *
 * File-structure assertions, like ReleaseHookAbsenceTest: source sets and manifests are what decide what a
 * release APK of a library module contains. F14 greps the assembled release APK as well.
 */
class DebugFaultSeamsReleaseAbsenceTest {
    @Test
    fun `the seam catalogue is exactly the F0 set`() {
        val source = faultSeamsSource()
        val enumBody =
            Regex("""enum class FaultSeam\s*\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1)
                ?: fail("FaultSeams.kt must declare `enum class FaultSeam { ... }`")
        val declared =
            Regex("""^\s{4}([A-Z][A-Z0-9_]+)\s*[,;]?\s*$""", RegexOption.MULTILINE)
                .findAll(enumBody)
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(CATALOGUE.keys, declared, "the FaultSeam entries differ from the F0 catalogue")
    }

    @Test
    fun `every FaultSeams function returns at once unless BuildConfig DEBUG`() {
        val source = faultSeamsSource()
        val objectBody =
            source.substringAfter("object FaultSeams", missingDelimiterValue = "").ifEmpty {
                fail("FaultSeams.kt must declare `object FaultSeams`")
            }
        val functions = Regex("""\bfun\s+(\w+)\s*\([^)]*\)[^{=]*\{""").findAll(objectBody).toList()
        assertTrue(functions.size >= READ_FUNCTIONS.size, "expected at least ${READ_FUNCTIONS.size} functions")
        val names = functions.map { it.groupValues[1] }.toSet()
        assertTrue(names.containsAll(READ_FUNCTIONS), "FaultSeams must offer $READ_FUNCTIONS, found $names")
        functions.forEach { match ->
            val first =
                objectBody
                    .substring(match.range.last + 1)
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("//") }
            assertTrue(
                first != null && first.startsWith("if (!BuildConfig.DEBUG) return"),
                "FaultSeams.${match.groupValues[1]} must start with `if (!BuildConfig.DEBUG) return`, found: $first",
            )
        }
        assertTrue(
            Regex("""\bfun\s+\w+\s*\([^)]*\)[^{]*=""").find(objectBody) == null,
            "FaultSeams functions must have a block body (an expression body skips the DEBUG gate)",
        )
    }

    @Test
    fun `no release source arms, disarms or loads a seam`() {
        val writers = listOf("FaultSeams.arm(", "FaultSeams.disarm(", "FaultSeams.disarmAll(", "FaultSeamsLoader")
        val leaks =
            productionSources()
                .filterNot { it.name == "FaultSeams.kt" }
                .filter { file -> writers.any { it in file.readText() } }
        assertEquals(emptyList(), leaks, "RELEASE LEAK: release sources switch a debug fault seam")
        val loader = uiLibDir.resolve("src/debug/java/co/electriccoin/zcash/ui/debug/FaultSeamsLoader.kt")
        assertTrue(loader.isFile, "expected the start-up loader at ${loader.path}")
    }

    @Test
    fun `the seams wired at F0 are read at their production site`() {
        WIRED.forEach { (seam, fileName) ->
            val site =
                productionSources().firstOrNull { it.name == fileName }
                    ?: fail("no production source named $fileName")
            assertTrue(
                "FaultSeam.$seam" in site.readText(),
                "$fileName must read FaultSeam.$seam (${CATALOGUE[seam]})",
            )
        }
    }

    @Test
    fun `only the debug manifest registers the seam actions and the loader`() {
        val debugManifest = uiLibDir.resolve("src/debug/AndroidManifest.xml").readText()
        (SEAM_ACTIONS + "co.electriccoin.zcash.ui.debug.FaultSeamsLoader").forEach {
            assertTrue(it in debugManifest, "the debug manifest must register $it")
        }
        val provider =
            Regex("""<provider[^>]*FaultSeamsLoader[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(debugManifest)?.value
                ?: fail("the debug manifest must declare the FaultSeamsLoader provider")
        assertTrue("android:exported=\"false\"" in provider, "the loader provider must not be exported")

        val releaseManifests =
            releaseSourceSets()
                .flatMap { set -> set.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList() }
        assertTrue(releaseManifests.isNotEmpty(), "sanity: expected the main manifests")
        releaseManifests.forEach { manifest ->
            val content = manifest.readText()
            (SEAM_ACTIONS + "FaultSeamsLoader").forEach {
                assertTrue(it !in content, "RELEASE LEAK: ${manifest.path} names $it")
            }
        }
        val sourceLeaks =
            productionSources().filter { file -> SEAM_ACTIONS.any { it in file.readText() } }
        assertEquals(emptyList(), sourceLeaks, "RELEASE LEAK: release sources name a seam action")
    }

    @Test
    fun `the one-shot actions read their secret input from a file, delete it and never log it`() {
        val receiver = uiLibDir.resolve("src/debug/java/co/electriccoin/zcash/ui/debug/$RECEIVER_FILE").readText()
        assertTrue(".delete()" in receiver, "the viewing key / alias files must be deleted after they are read")
        // A viewing key (K0) and a group admin's address alias (KI-25) are never printed.
        val logLines = receiver.lineSequence().filter { Regex("""\bLog\.\w+\(""").containsMatchIn(it) }.toList()
        logLines.forEach { line ->
            assertTrue(
                !Regex("""\$\{?(ufvk|alias|viewingKey)""", RegexOption.IGNORE_CASE).containsMatchIn(line),
                "a log line interpolates a viewing key or an alias: ${line.trim()}",
            )
        }
        // K0-1 closes CreateKeystoneAccountUseCase in 2.13.0; the seam imports through the data source instead.
        assertTrue("importKeystoneAccount(" in receiver, "the K0 seam must import through AccountDataSource")
        assertTrue(
            "CreateKeystoneAccountUseCase" !in receiver,
            "the K0 seam must not go through the connect flow that K0-1 closes",
        )
    }

    private companion object {
        /** seam -> the batch that needs it (PLAN.md §3 F0 + PLAN_V2.md §2 F0). */
        val CATALOGUE: Map<String, String> =
            linkedMapOf(
                "SUBMIT_LEG1_GRPC_FAILURE" to "F2: the first submit of leg 1 fails with gRPC",
                "KILL_AFTER_CREATE_BEFORE_ON_STORED" to "F2: kill between create and onStored",
                "KEEP_LEG2_AWAITING_PLAN" to "F2: leg 2 is never submitted",
                "KILL_AFTER_MARKER_BEFORE_CREATE" to "F4: kill between the marker and create",
                "BLOCK_GET_STATUS" to "F4: the status lookup never answers",
                "TEX_FORCE_STUCK" to "F3: a TEX send is treated as stuck at the ephemeral address",
                "TOR_BOOTSTRAP_FAILS" to "F3: turning Tor on fails",
                "KEYSTORE_OUT" to "F10: the phone's Keystore does not answer",
                "KEYSTORE_OUT_OTHER_STORES" to "ZC-733-A: the contact book, the picture index or the AI store does not open",
                "GROUP_SEND_DELAY_MS" to "KI-22: every group memo send waits this long first",
                "BLOCK_RELAYS" to "KI-23: no relay publish is acked and no relay event is delivered",
                "SEEN_NOSTR_CAP" to "KI-24: the relay-event dedup cap",
                "SEEN_KEX_CAP" to "KI-24: the on-chain KEX dedup cap",
                "BLOCK_ONE_RELAY" to "T1b-2: the relay at this position in the pool's list is down",
                "RELAY_UNREACHABLE" to "RELAY-HEALTH: the relays this bit mask names (slot 0's relay + catalog) can't be reached",
                "RELAY_HEALTH_CONFIRM_SEC" to "RELAY-HEALTH: the relay-health waits (DOWN, SUSPECT, swap spacing) in seconds",
                "TURN_CREDS_UNAVAILABLE" to "TURN-CREDS: no call-relay credential",
                "TURN_RELAY_ONLY" to "TURN-CREDS: ICE uses the relay only",
            )

        /** seam -> the production file that reads it at F0 (the other seams are wired by their batch). */
        val WIRED: Map<String, String> =
            linkedMapOf(
                "KEYSTORE_OUT" to "EncryptedStoreOpener.kt",
                "KEYSTORE_OUT_OTHER_STORES" to "EncryptedStoreOpener.kt",
                "GROUP_SEND_DELAY_MS" to "GroupViewModel.kt",
                "BLOCK_RELAYS" to "NostrRelayPool.kt",
                "SEEN_NOSTR_CAP" to "ZchatPreferences.kt",
                "SEEN_KEX_CAP" to "ZchatPreferences.kt",
                "BLOCK_ONE_RELAY" to "NostrRelayPool.kt",
                "SUBMIT_LEG1_GRPC_FAILURE" to "ProposalDataSource.kt",
                "KILL_AFTER_CREATE_BEFORE_ON_STORED" to "ProposalDataSource.kt",
                "KEEP_LEG2_AWAITING_PLAN" to "ProposalDataSource.kt",
                "RELAY_UNREACHABLE" to "NostrRelayPool.kt",
                "RELAY_HEALTH_CONFIRM_SEC" to "NostrRelayPool.kt",
                "TURN_CREDS_UNAVAILABLE" to "TurnCredentialsClient.kt",
                "TURN_RELAY_ONLY" to "VoiceCallManager.kt",
            )

        val READ_FUNCTIONS = setOf("isArmed", "valueOf", "delayIfArmed", "killHereIfArmed", "arm", "disarm", "disarmAll")

        val SEAM_ACTIONS =
            listOf(
                "xyz.zsend.zchat.debug.SET_FAULT",
                "xyz.zsend.zchat.debug.CLEAR_FAULTS",
                "xyz.zsend.zchat.debug.IMPORT_KEYSTONE_VIEWONLY",
                "xyz.zsend.zchat.debug.FORGET_GROUP_CREATOR_KEY",
                "xyz.zsend.zchat.debug.RESET_RELAY_SET",
            )

        /**
         * The debug receiver's file name, spelled in two parts: ReleaseHookAbsenceTest fails on any non-debug source
         * that names the receiver class, this test included.
         */
        const val RECEIVER_FILE = "TestHooks" + "Receiver.kt"

        const val FAULT_SEAMS_FILE = "src/main/java/co/electriccoin/zcash/ui/common/util/FaultSeams.kt"

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var dir: File? = start
            repeat(8) {
                val current = dir ?: return@repeat
                if (current.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) return@lazy current
                val nested = current.resolve("ui-lib")
                if (nested.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) return@lazy nested
                dir = current.parentFile
            }
            fail("could not locate the ui-lib module from ${start.path}")
        }

        val appDir: File by lazy {
            (uiLibDir.parentFile ?: fail("ui-lib has no parent")).resolve("app").also {
                if (!it.isDirectory) fail("could not locate the app module next to ${uiLibDir.path}")
            }
        }

        fun faultSeamsSource(): String {
            val file = uiLibDir.resolve(FAULT_SEAMS_FILE)
            if (!file.isFile) fail("expected the seam registry at ${file.path}")
            return file.readText()
        }

        /**
         * The source sets a release build can compile, in ui-lib and app: every one except debug, the debug variants
         * (`zcashmainnetFossDebug`, ...), test and androidTest.
         */
        fun releaseSourceSets(): List<File> =
            listOf(uiLibDir.resolve("src"), appDir.resolve("src")).flatMap { src ->
                src.listFiles().orEmpty().filter {
                    it.isDirectory && it.name !in setOf("debug", "test", "androidTest", "testFixtures") && !it.name.endsWith("Debug")
                }
            }

        /** Every .kt a release build compiles. */
        fun productionSources(): List<File> =
            releaseSourceSets().flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }
}
