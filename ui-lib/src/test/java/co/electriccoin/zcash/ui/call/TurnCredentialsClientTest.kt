package co.electriccoin.zcash.ui.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D13 — server-issued TURN credentials. Pure-JVM tests for the response parsing and the
 * cache/expiry/stale-fallback logic of [TurnCredentialsClient]. The network path itself is
 * exercised by an injected fake fetcher (the real one is a thin ktor GET verified by compile).
 */
class TurnCredentialsClientTest {

    // ---------- parse ----------

    @Test
    fun `parse accepts a full backend response`() {
        val now = 1_000_000L
        val creds = TurnCredentialsClient.parse(
            """
            {
              "username": "1767229200",
              "password": "Ft07pFBCs4oGmm1/FhxNZS964zc=",
              "ttl": 3600,
              "uris": ["turn:relay.zsend.xyz:3478?transport=udp", "turn:relay.zsend.xyz:3478?transport=tcp"]
            }
            """.trimIndent(),
            now,
        )
        assertEquals("1767229200", creds?.username)
        assertEquals("Ft07pFBCs4oGmm1/FhxNZS964zc=", creds?.password)
        assertEquals(
            listOf(
                "turn:relay.zsend.xyz:3478?transport=udp",
                "turn:relay.zsend.xyz:3478?transport=tcp",
            ),
            creds?.uris,
        )
        assertEquals(now + 3_600_000L, creds?.expiresAtMs)
    }

    @Test
    fun `parse falls back to default uris when uris are absent or empty`() {
        val absent = TurnCredentialsClient.parse("""{"username":"1","password":"p","ttl":3600}""", 0L)
        assertEquals(TurnCredentialsClient.DEFAULT_TURN_URIS, absent?.uris)

        val empty = TurnCredentialsClient.parse("""{"username":"1","password":"p","ttl":3600,"uris":[]}""", 0L)
        assertEquals(TurnCredentialsClient.DEFAULT_TURN_URIS, empty?.uris)
    }

    @Test
    fun `parse defaults ttl to 3600 when absent`() {
        val creds = TurnCredentialsClient.parse("""{"username":"1","password":"p"}""", 500L)
        assertEquals(500L + 3_600_000L, creds?.expiresAtMs)
    }

    @Test
    fun `parse rejects missing or blank username or password`() {
        assertNull(TurnCredentialsClient.parse("""{"password":"p","ttl":3600}""", 0L))
        assertNull(TurnCredentialsClient.parse("""{"username":"1","ttl":3600}""", 0L))
        assertNull(TurnCredentialsClient.parse("""{"username":"","password":"p"}""", 0L))
        assertNull(TurnCredentialsClient.parse("""{"username":"1","password":""}""", 0L))
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(TurnCredentialsClient.parse("not json at all", 0L))
        assertNull(TurnCredentialsClient.parse("", 0L))
    }

    // ---------- TurnCredentials freshness ----------

    @Test
    fun `credential is fresh until 5 minutes before expiry and usable until expiry`() {
        val creds = TurnCredentials("u", "p", listOf("turn:x"), expiresAtMs = 1_000_000L)
        val slack = 300_000L
        assertTrue(creds.isFresh(1_000_000L - slack - 1))
        assertFalse(creds.isFresh(1_000_000L - slack))
        assertTrue(creds.isUsable(999_999L))
        assertFalse(creds.isUsable(1_000_000L))
    }

    // ---------- cache behavior ----------

    private class FakeFetcher {
        var calls = 0
        var result: TurnCredentials? = null
        var throwOnFetch = false
        var delayMs = 0L

        suspend fun fetch(): TurnCredentials? {
            calls++
            if (delayMs > 0) delay(delayMs)
            if (throwOnFetch) error("boom")
            return result
        }
    }

    @Test
    fun `get fetches once and serves the cache while fresh`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher()
        fetcher.result = TurnCredentials("u1", "p1", listOf("turn:x"), expiresAtMs = 3_600_000L)
        val client = TurnCredentialsClient(nowMs = { now }, fetcher = fetcher::fetch)

        assertEquals("u1", client.get()?.username)
        now = 1_000_000L // still > 5 min before expiry
        assertEquals("u1", client.get()?.username)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `get refetches once the cached credential enters the 5-minute slack window`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher()
        fetcher.result = TurnCredentials("u1", "p1", listOf("turn:x"), expiresAtMs = 3_600_000L)
        val client = TurnCredentialsClient(nowMs = { now }, fetcher = fetcher::fetch)
        client.get()

        now = 3_600_000L - 240_000L // 4 min before expiry: stale for reuse
        fetcher.result = TurnCredentials("u2", "p2", listOf("turn:x"), expiresAtMs = now + 3_600_000L)
        assertEquals("u2", client.get()?.username)
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `get returns the stale cached credential when a refetch fails and it has not expired`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher()
        fetcher.result = TurnCredentials("u1", "p1", listOf("turn:x"), expiresAtMs = 3_600_000L)
        val client = TurnCredentialsClient(nowMs = { now }, fetcher = fetcher::fetch)
        client.get()

        now = 3_600_000L - 240_000L // inside slack window → refetch attempted
        fetcher.result = null // backend down
        assertEquals("u1", client.get()?.username)
    }

    @Test
    fun `get returns null when a refetch fails and the cached credential has expired`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher()
        fetcher.result = TurnCredentials("u1", "p1", listOf("turn:x"), expiresAtMs = 3_600_000L)
        val client = TurnCredentialsClient(nowMs = { now }, fetcher = fetcher::fetch)
        client.get()

        now = 3_600_001L // past expiry
        fetcher.result = null
        assertNull(client.get())
    }

    @Test
    fun `get returns null when the first fetch fails`() = runTest {
        val fetcher = FakeFetcher() // result stays null
        val client = TurnCredentialsClient(nowMs = { 0L }, fetcher = fetcher::fetch)
        assertNull(client.get())
    }

    @Test
    fun `get returns null when the fetcher throws`() = runTest {
        val fetcher = FakeFetcher()
        fetcher.throwOnFetch = true
        val client = TurnCredentialsClient(nowMs = { 0L }, fetcher = fetcher::fetch)
        assertNull(client.get())
    }

    @Test
    fun `get is bounded by the 4-second fetch timeout`() = runTest {
        val fetcher = FakeFetcher()
        fetcher.delayMs = 10_000L // slower than the 4s bound (virtual time)
        fetcher.result = TurnCredentials("u1", "p1", listOf("turn:x"), expiresAtMs = 3_600_000L)
        val client = TurnCredentialsClient(nowMs = { 0L }, fetcher = fetcher::fetch)
        assertNull(client.get())
    }

    @Test
    fun `get falls back to a usable cached credential when the refetch times out`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher()
        fetcher.result = TurnCredentials("u1", "p1", listOf("turn:x"), expiresAtMs = 3_600_000L)
        val client = TurnCredentialsClient(nowMs = { now }, fetcher = fetcher::fetch)
        client.get()

        now = 3_600_000L - 240_000L // refetch due, cred still usable
        fetcher.delayMs = 10_000L // refetch hangs past the 4s bound
        assertEquals("u1", client.get()?.username)
    }
}
