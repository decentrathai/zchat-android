package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 10a R10A-3 (F-R9P-5) — with no network, a failed upload takes about 2 min 18 s to show as failed.
 *
 * The DNS lookup fails at once. The shared HTTP client (HttpClientProviderImpl.configureHttpClient) installs
 * HttpRequestRetry { retryOnExceptionOrServerErrors(4); exponentialDelay() } on every client, so each host's
 * UnknownHostException is tried 5 times with about 1+2+4+8 s of backoff, roughly 17 s a host. FileUploadManager then
 * walks 7 Blossom hosts and 1 NIP-96 host in turn: 8 x ~17 s. The Seeker log shows host failures 17-18 s apart. The
 * same retry re-sends a whole encrypted body (up to 25 MB) up to 5 times per host on a timeout or a 5xx.
 *
 * The host list is the upload's retry. Wanted: an upload request is sent once per host, so offline the upload fails
 * after one immediate DNS failure per listed server (9 since ZC-249) and the row turns FAILED with Retry (R9-4).
 */
class UploadFailsFastOfflineTest {
    private val identity = NOSTRIdentity.fromSeed(ByteArray(64) { (0x5a + it).toByte() })
    private val ciphertext = ByteArray(8 * 1024).also { SecureRandom().nextBytes(it) }

    @Test
    fun `offline, every upload host is asked once and the upload fails`() =
        runBlocking {
            val offline = OfflineServers()
            val manager = FileUploadManager(identity, offline.provider)
            val outcome = manager.upload(ciphertext, FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE)

            assertTrue(outcome is UploadOutcome.Failure, "an offline upload did not fail: $outcome")
            val servers = manager.blossomServers + manager.nip96Servers
            assertEquals(9, servers.size, "fixture sanity: 7 Blossom servers and 2 NIP-96 servers")
            // ZC-249: nostr.download and files.sovbit.host serve both protocols, so they are asked once per listed server.
            val listedPerHost = servers.groupingBy { java.net.URI(it).host }.eachCount()
            for ((host, listed) in listedPerHost) {
                val asked = offline.askedPerHost[host]?.get() ?: 0
                assertEquals(
                    listed,
                    asked,
                    "$host was asked $asked times: the client-level retry re-tries a failed upload request with backoff " +
                        "before the host list moves on (all: ${offline.askedPerHost})",
                )
            }
            assertEquals(listedPerHost.keys, offline.askedPerHost.keys, "a host outside the upload lists was asked")
        }

    @Test
    fun `the Blossom and NIP-96 upload requests turn off the client-level retry`() {
        for (file in listOf("BlossomClient.kt", "NIP96Client.kt")) {
            val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/$file")
            assertTrue(source.contains("noRetry()"), "$file's upload request still inherits the shared client's retry")
        }
    }

    // --------------------------------------------------------------- harness

    /** No network: every request fails its DNS lookup at once, as on a phone in airplane mode. */
    private class OfflineServers {
        val askedPerHost: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

        private val interceptor =
            Interceptor { chain ->
                val host = chain.request().url.host
                askedPerHost.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet()
                throw java.net.UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname")
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient =
                    HttpClient(OkHttp) {
                        engine { addInterceptor(interceptor) }
                        // Mirrors HttpClientProviderImpl.configureHttpClient (the retry every production client gets),
                        // with a 1 ms constant delay instead of exponentialDelay() so the test stays fast.
                        install(HttpRequestRetry) {
                            maxRetries = 4
                            retryOnExceptionOrServerErrors(4)
                            constantDelay(millis = 1, randomizationMs = 0)
                        }
                    }
            }
    }
}
