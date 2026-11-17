package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.TorLikeServers
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.security.SecureRandom
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-249 (HIGH) — with "Beta: Tor Protection" on, FileUploadManager walks the seven Blossom hosts first. A Blossom
 * upload is a PUT, which the SDK 3.0.0 Tor engine never sends ("UnsupportedHttpMethodOverTor"), so every one of them
 * fails, and each builds an isolated Tor client for nothing, before the NIP-96 hosts are tried. The only NIP-96 host
 * is nostr.build, which refuses opaque blobs on any transport (zc249-20260929/probe_nip96-run1.txt).
 *
 * Wanted: with Tor on (the setting HttpClientProviderImpl reads), only the NIP-96 hosts are tried, in order:
 * nostr.download/n96, then files.sovbit.host/upload (both store opaque blobs, probed 29 Sep 2026); no PUT reaches the
 * Tor engine and nothing falls back to clearnet. With Tor off, today's order stays: every Blossom host, then the NIP-96
 * hosts. nostr.build is off the upload list and stays on the inbound allow-list, like every host files went to.
 */
class TorUploadSkipsBlossomPutTest {
    private val identity = NOSTRIdentity.fromSeed(ByteArray(64) { (0x44 + it).toByte() })
    private val ciphertext = ByteArray(2_048).also { SecureRandom().nextBytes(it) }

    @Test
    fun `with Tor on, only the NIP-96 hosts are asked, in order, and no PUT reaches the Tor engine`() =
        runBlocking {
            val servers = TorLikeServers { 500 to """{"status":"error","message":"Server error, please try again later"}""" }

            val manager = FileUploadManager(identity, servers.provider(torOn = true))
            val outcome = manager.upload(ciphertext, FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE)

            val failure = outcome as? UploadOutcome.Failure ?: fail("every host answered 500, yet: $outcome")
            assertTrue(servers.attempts.none { it.startsWith("PUT ") }, "a Blossom PUT reached the Tor engine: ${servers.attempts}")
            assertEquals(
                listOf("POST $NOSTR_DOWNLOAD_N96", "POST $SOVBIT_UPLOAD"),
                servers.requests.map { "${it.method} ${it.url}" },
                "the Tor upload's requests (all attempts: ${servers.attempts})",
            )
            assertEquals(2, servers.enginesBuilt.get(), "a Tor client was built for a host that is never asked")
            assertTrue("nostr.download/n96" in failure.error && "files.sovbit.host/upload" in failure.error, failure.error)
        }

    /** Tor off keeps today's order, Blossom first; the NIP-96 hosts are the new ones. */
    @Test
    fun `with Tor off, every Blossom host is asked in today's order, then the two NIP-96 hosts`() =
        runBlocking {
            val direct = DirectServers()

            val outcome = FileUploadManager(identity, direct.provider).upload(ciphertext, FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE)

            assertTrue(outcome is UploadOutcome.Failure, "every host answered 500, yet: $outcome")
            assertEquals(
                TODAYS_BLOSSOM_HOSTS.map { "PUT $it/upload" } + listOf("POST $NOSTR_DOWNLOAD_N96", "POST $SOVBIT_UPLOAD"),
                direct.asked.toList(),
            )
        }

    @Test
    fun `the NIP-96 upload list is nostr download then files sovbit host, and every upload host is on the allow-list`() {
        val manager = FileUploadManager(identity, DirectServers().provider)
        assertEquals(listOf(NOSTR_DOWNLOAD_N96, SOVBIT_UPLOAD), manager.nip96Servers)
        assertFalse(manager.nip96Servers.any { java.net.URI(it).host == "nostr.build" }, "nostr.build refuses opaque blobs")
        assertEquals(TODAYS_BLOSSOM_HOSTS, manager.blossomServers, "the Blossom list changed")
        for (server in manager.blossomServers + manager.nip96Servers) {
            assertTrue(
                ZFILEMessage.isAllowedMediaUrl("$server/0123456789abcdef"),
                "files uploaded to $server are dropped by the recipient",
            )
        }
        assertTrue(
            ZFILEMessage.isAllowedMediaUrl("https://nostr.build/0123456789abcdef"),
            "files already sent via nostr.build no longer load",
        )
    }

    // --------------------------------------------------------------- harness

    /** The direct (OkHttp) client, answered offline: every request gets a 500. */
    private class DirectServers {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                asked += "${request.method} ${request.url.toString().trimEnd('/')}"
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Refused")
                    .body("""{"status":"error"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }

    private companion object {
        const val NOSTR_DOWNLOAD_N96 = "https://nostr.download/n96"
        const val SOVBIT_UPLOAD = "https://files.sovbit.host/upload"

        /** FileUploadManager.blossomServers as of R8-fixup F3 (probed 24-25 Sep 2026). */
        val TODAYS_BLOSSOM_HOSTS =
            listOf(
                "https://nostr.download",
                "https://blossom.yakihonne.com",
                "https://cdn.hzrd149.com",
                "https://files.sovbit.host",
                "https://blossom.primal.net",
                "https://blossom.band",
                "https://blossom.nostr.build",
            )
    }
}
