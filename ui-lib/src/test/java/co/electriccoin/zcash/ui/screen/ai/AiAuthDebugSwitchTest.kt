package co.electriccoin.zcash.ui.screen.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-KEY-D (W-53; AI-SIGNIN-KEY-SPEC 4.7): the debug-only switch for the AI server's address and service name.
 *
 * One small object gives the client its address and its service name. It exists twice, once per build type, because
 * build fields of one build type cannot be read from shared code (the other variant would not compile):
 *   - ui-lib/src/release/java: the two constants and nothing else. A release build cannot read the switch.
 *   - ui-lib/src/debug/java: the constants, unless the build was made with BOTH -PaiAuthBaseUrl and
 *     -PaiAuthAudience; only then, and only here, http://127.0.0.1:<port> is accepted.
 * The module's build types are debug and release (its source sets at RED: androidTest, debug, foss, main, store,
 * test). A further build type needs the release file too.
 */
class AiAuthDebugSwitchTest {
    private val debug by lazy { AiSignInApi.read(AiSignInApi.variantAiFile("debug", FILE)) }
    private val release by lazy { AiSignInApi.read(AiSignInApi.variantAiFile("release", FILE)) }

    @Test
    fun the_object_exists_once_per_build_type_and_not_in_shared_code() {
        assertTrue(DECLARATION in debug)
        assertTrue(DECLARATION in release)
        val declaring =
            AiSignInApi
                .productSources()
                .filter { DECLARATION in it.readText() }
                .map { it.relativeTo(AiSignInApi.uiLib).invariantSeparatorsPath.substringBefore("/java/") }
                .sorted()
        assertEquals(listOf("src/debug", "src/release"), declaring)
        // Every build type of the module has its file: a source set that is neither a flavor nor a test is one.
        val sourceSets =
            AiSignInApi.uiLib
                .resolve("src")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
        assertEquals(
            listOf("androidTest", "debug", "foss", "main", "release", "store", "test"),
            sourceSets.sorted(),
        )
    }

    @Test
    fun the_release_file_holds_the_two_constants_and_no_switch() {
        assertTrue("\"$ADDRESS\"" in release)
        assertTrue("\"$SERVICE\"" in release)
        listOf("BuildConfig", "AI_AUTH", "aiAuth", "Property", "http://", "127.0.0.1", "localhost").forEach { word ->
            assertFalse("the release file names $word", word in release)
        }
        assertTrue(
            "the release file must refuse every cleartext address",
            Regex("""fun allowsCleartext\(\w+: String\): Boolean = false""").containsMatchIn(release),
        )
    }

    @Test
    fun the_debug_file_returns_the_constants_unless_both_properties_were_given() {
        assertTrue("\"$ADDRESS\"" in debug)
        assertTrue("\"$SERVICE\"" in debug)
        assertTrue("BuildConfig.AI_AUTH_BASE_URL" in debug)
        assertTrue("BuildConfig.AI_AUTH_AUDIENCE" in debug)
        assertTrue(
            "the debug file must ask for BOTH build fields in one condition",
            Regex("""AI_AUTH_BASE_URL\.isNotEmpty\(\) && BuildConfig\.AI_AUTH_AUDIENCE\.isNotEmpty\(\)""")
                .containsMatchIn(debug),
        )
        // This suite runs on a debug build made without the two properties: the object answers with the constants
        // and accepts no cleartext address, not even the loopback one.
        assertEquals(ADDRESS, AiSignInApi.endpoint("getBaseUrl"))
        assertEquals(SERVICE, AiSignInApi.endpoint("getServiceName"))
        listOf("http://127.0.0.1:8787", "http://127.0.0.1", "http://10.0.2.2:8787", "http://api.zsend.xyz").forEach {
            assertEquals("cleartext to $it without the switch", false, AiSignInApi.endpoint("allowsCleartext", it))
        }
    }

    @Test
    fun only_the_loopback_address_with_a_port_can_ever_be_cleartext() {
        val pattern = Regex("""Regex\("{3}(.+?)"{3}\)""").find(debug)?.groupValues?.get(1)
        assertTrue("the debug file has no one pattern for the address it accepts", pattern != null)
        val loopback = Regex(pattern.orEmpty())

        assertTrue(loopback.matches("http://127.0.0.1:8787"))
        listOf(
            "http://127.0.0.1",
            "http://127.0.0.1:8787/path",
            "http://127.0.0.1.example.com:80",
            "http://127x0x0x1:8787",
            "http://localhost:8787",
            "http://10.0.2.2:8787",
            "https://127.0.0.1:8787",
            // An address with a user part, put together here (the commit scanner reads the whole as a credential).
            "http://127.0.0.1:8787" + "@" + "example.com",
        ).forEach { assertFalse("$it must not be accepted", loopback.matches(it)) }
    }

    @Test
    fun the_two_build_fields_belong_to_the_debug_type_only() {
        val gradle = AiSignInApi.read(AiSignInApi.uiLib.resolve("build.gradle.kts"))
        val debugBlock = gradle.substringAfter("getByName(\"debug\")", "").substringBefore("\n        }")

        listOf("AI_AUTH_BASE_URL" to "aiAuthBaseUrl", "AI_AUTH_AUDIENCE" to "aiAuthAudience").forEach { (field, property) ->
            assertTrue("$field is not a build field of the debug type", field in debugBlock)
            assertTrue("$field is not fed by -P$property", property in debugBlock)
            assertEquals("$field is defined once", 1, Regex.fromLiteral("\"$field\"").findAll(gradle).count())
        }
    }

    @Test
    fun shared_code_names_the_object_only_and_the_https_rule_asks_it() {
        val shared = AiSignInApi.productSources().filter { "/src/main/" in it.invariantSeparatorsPath }
        assertEquals(
            "shared code reads a build field of one build type",
            emptyList<String>(),
            shared.filter { "AI_AUTH_" in it.readText() }.map { it.name },
        )
        val client = AiSignInApi.read(AiSignInApi.mainAiFile("AiApiClient.kt"))
        assertTrue(
            "AiApiClient's 'https only' rule does not ask the object",
            Regex("""require\(baseUrl\.startsWith\("https://"\) \|\| AiAuthEndpoint\.allowsCleartext\(baseUrl\)\)""")
                .containsMatchIn(client),
        )
        assertTrue("AiApiClient does not take its address from the object", "AiAuthEndpoint.baseUrl" in client)
        assertEquals(ADDRESS, AiApiClient.DEFAULT_BASE_URL)
    }

    private companion object {
        const val FILE = "AiAuthEndpoint.kt"
        const val DECLARATION = "object AiAuthEndpoint"
        const val ADDRESS = "https://api.zsend.xyz"
        const val SERVICE = "api.zsend.xyz"
    }
}
