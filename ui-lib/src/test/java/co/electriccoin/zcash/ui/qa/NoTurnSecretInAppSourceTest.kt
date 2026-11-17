package co.electriccoin.zcash.ui.qa

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * GUARD (a control: it passes at the TURN-CREDS base and must keep passing). TURN-CREDS (fix-all 2.13.0, train T5;
 * turn-creds SPEC §3.1 G1, register ZC-234, W-13).
 *
 * ZChat 2.12.4 shipped coturn's shared TURN secret in VoiceCallManager.kt and minted relay credentials locally with
 * HMAC-SHA1. The D13 app commit (13 Jul) removed it: the app fetches short-lived credentials from our backend
 * (TurnCredentialsClient) and holds no TURN secret. This guard keeps it that way, over every module's non-test
 * sources (kt, java, xml, json, properties) and the Gradle files:
 *  - no TURN_SHARED_SECRET, TURN_STATIC_AUTH_SECRET, static-auth-secret or use-auth-secret, and no HmacSHA1;
 *  - no quoted literal (16-256 characters) whose SHA-256 starts with the leaked value's 16-hex prefix (only the
 *    prefix is stored here: a full digest would look like a secret to the publish scanners);
 *  - the credentials come from https://api.zsend.xyz, and the only TURN password set is the fetched one
 *    (`creds.password`).
 */
class NoTurnSecretInAppSourceTest {
    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !(File(dir, "settings.gradle.kts").isFile && File(dir, "gradle.properties").isFile)) {
            dir = dir.parentFile
        }
        dir ?: fail("repo root not found above the test working dir")
    }

    /** Every non-test source file of every module, plus the Gradle build files. */
    private val scanned: List<File> by lazy {
        val modules = repoRoot.listFiles().orEmpty().filter { it.isDirectory && it.resolve("src").isDirectory }
        val sources =
            modules.flatMap { module ->
                module
                    .resolve("src")
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name !in TEST_SOURCE_SETS }
                    .flatMap { set ->
                        set
                            .walkTopDown()
                            .onEnter { it.name != "build" }
                            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
                            .toList()
                    }
            }
        val gradle =
            repoRoot
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules") }
                .filter { it.isFile && it.name.endsWith(".gradle.kts") }
                .toList() +
                listOf(repoRoot.resolve("gradle.properties")) +
                repoRoot
                    .resolve("gradle")
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension == "toml" }
        (sources + gradle).distinct()
    }

    @Test
    fun `sanity - the scan covers the app, ui-lib and the Gradle files`() {
        val paths = scanned.map { it.relativeTo(repoRoot).path }
        assertTrue(paths.any { it.endsWith("ui-lib/src/main/java/co/electriccoin/zcash/ui/call/VoiceCallManager.kt") }, "not scanned")
        assertTrue(paths.any { it.startsWith("app/src/main/") }, "app/src/main not scanned")
        assertTrue(paths.any { it == "gradle.properties" }, "gradle.properties not scanned")
        assertTrue(paths.any { it == "build.gradle.kts" }, "build.gradle.kts not scanned")
        assertTrue(paths.size > 500, "suspiciously few files scanned: ${paths.size}")
    }

    @Test
    fun `no TURN secret name, coturn secret option or HMAC-SHA1 in the app`() {
        val hits =
            scanned.flatMap { file ->
                val text = file.readText()
                FORBIDDEN.filter { it in text }.map { "${file.relativeTo(repoRoot).path}: $it" }
            }
        assertEquals(emptyList(), hits, "a TURN secret (or a local credential mint) is back in the app")
    }

    @Test
    fun `no literal is the leaked TURN secret`() {
        val literal = Regex(""""([^"\\\n]{16,256})"""")
        val hits =
            scanned.flatMap { file ->
                literal
                    .findAll(file.readText())
                    .filter { sha256Hex(it.groupValues[1]).startsWith(LEAKED_SHA256_PREFIX) }
                    .map { file.relativeTo(repoRoot).path } // never the value
                    .toList()
            }
        assertEquals(emptyList(), hits, "the TURN secret leaked in 2.12.4 is in these files again")
    }

    @Test
    fun `TURN credentials come only from the backend`() {
        val client = scanned.firstOrNull { it.name == "TurnCredentialsClient.kt" } ?: fail("TurnCredentialsClient.kt not found")
        assertTrue(
            "\"https://api.zsend.xyz/api/v1/turn/credentials\"" in client.readText(),
            "TurnCredentialsClient must fetch from https://api.zsend.xyz/api/v1/turn/credentials",
        )
        val passwords =
            scanned.flatMap { file ->
                Regex("""\.setPassword\(([^)]*)\)""").findAll(file.readText()).map { "${file.name}: ${it.groupValues[1].trim()}" }.toList()
            }
        assertEquals(listOf("VoiceCallManager.kt: creds.password"), passwords, "a TURN password must come only from the fetched creds")
    }

    private companion object {
        val TEST_SOURCE_SETS = setOf("test", "androidTest", "testFixtures", "sharedTest")
        val SOURCE_EXTENSIONS = setOf("kt", "java", "xml", "json", "properties", "kts", "toml")
        val FORBIDDEN = listOf("TURN_SHARED_SECRET", "TURN_STATIC_AUTH_SECRET", "static-auth-secret", "use-auth-secret", "HmacSHA1")

        /** The first 16 hex characters of the SHA-256 of the value 2.12.4 shipped (git show v2.12.4, VoiceCallManager.kt). */
        const val LEAKED_SHA256_PREFIX = "02f12ea6c15bc897"

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
