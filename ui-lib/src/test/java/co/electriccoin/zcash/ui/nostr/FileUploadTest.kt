package co.electriccoin.zcash.ui.nostr

import io.ktor.client.HttpClient
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileUploadTest {

    private val testSeed = ByteArray(64) { it.toByte() }

    @Test
    fun uploadOutcome_Success_contains_url_and_sha256() {
        val result = UploadOutcome.Success(
            url = "https://nostr.build/abc",
            sha256 = "deadbeef12345678abcdef0123456789"
        )
        assertTrue(result.url.isNotEmpty())
        assertTrue(result.sha256.isNotEmpty())
    }

    @Test
    fun uploadOutcome_Failure_contains_error_and_server() {
        val result = UploadOutcome.Failure(
            error = "Upload failed",
            serverUrl = "https://nostr.build"
        )
        assertTrue(result.error.isNotEmpty())
        assertTrue(result.serverUrl.isNotEmpty())
    }

    @Test
    fun fileUploadManager_has_ordered_server_lists() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val manager = FileUploadManager(identity, FakeHttpClientProvider())
        assertNotNull(manager)
        assertTrue(manager.nip96Servers.isNotEmpty())
        assertTrue(manager.blossomServers.isNotEmpty())
    }

    @Test
    fun nip98_auth_header_is_nonempty_base64() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val header = identity.signNIP98Event("https://nostr.build/api/v2/media", "POST")
        assertTrue(header.isNotEmpty())
        // Should be valid base64
        Base64.getDecoder().decode(header)
    }

    @Test
    fun blossom_auth_header_is_nonempty_base64() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val header = identity.signBlossomAuthEvent(
            sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sizeBytes = 1024L
        )
        assertTrue(header.isNotEmpty())
        // Should be valid base64
        Base64.getDecoder().decode(header)
    }

    @Test
    fun sha256Hex_produces_64_char_hex_string() {
        val data = "test data".toByteArray()
        val hash = FileUploadManager.sha256Hex(data)
        assertEquals(64, hash.length, "SHA-256 hex should be 64 chars, was ${hash.length}")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "SHA-256 hex should be lowercase hex")
    }

    @Test
    fun sha256Hex_is_deterministic() {
        val data = "hello world".toByteArray()
        val hash1 = FileUploadManager.sha256Hex(data)
        val hash2 = FileUploadManager.sha256Hex(data)
        assertEquals(hash1, hash2)
    }

    @Test
    fun sha256Hex_known_value() {
        // SHA-256 of empty byte array is well-known
        val hash = FileUploadManager.sha256Hex(byteArrayOf())
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash,
            "SHA-256 of empty input should match known value"
        )
    }

    @Test
    fun nip96Servers_starts_with_nostr_build() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val manager = FileUploadManager(identity, FakeHttpClientProvider())
        assertEquals("https://nostr.build", manager.nip96Servers.first())
    }

    @Test
    fun blossomServers_prefers_primal_then_includes_band() {
        // The list is intentionally ordered primal-first (nostr.build has been slow/500ing);
        // see FileUploadManager.blossomServers. blossom.band remains a configured fallback.
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val manager = FileUploadManager(identity, FakeHttpClientProvider())
        assertEquals("https://blossom.primal.net", manager.blossomServers.first())
        assertTrue(manager.blossomServers.contains("https://blossom.band"))
    }
}

/**
 * Fake [co.electriccoin.zcash.ui.common.provider.HttpClientProvider] for tests
 * that don't make real network calls.
 */
private class FakeHttpClientProvider : co.electriccoin.zcash.ui.common.provider.HttpClientProvider {
    override suspend fun create(): HttpClient = HttpClient()
}
