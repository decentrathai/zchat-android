package co.electriccoin.zcash.ui.call

import android.util.Log
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * One short-lived TURN credential as issued by the backend (D13). [expiresAtMs] is derived
 * client-side from fetch time + the server's ttl, so wall-clock comparisons stay local.
 */
data class TurnCredentials(
    val username: String,
    val password: String,
    val uris: List<String>,
    val expiresAtMs: Long,
) {
    /**
     * Fresh = safe to hand to a NEW call without refetching. The 5-min slack matters because
     * coturn only checks expiry at ALLOCATE time — a call started on a nearly-expired cred keeps
     * relaying for its whole duration, but one started AFTER expiry gets no relay at all.
     */
    fun isFresh(nowMs: Long): Boolean = nowMs < expiresAtMs - FRESHNESS_SLACK_MS

    /** Usable = not yet expired; acceptable as a stale fallback when a refetch fails. */
    fun isUsable(nowMs: Long): Boolean = nowMs < expiresAtMs

    companion object {
        const val FRESHNESS_SLACK_MS = 300_000L // 5 min
    }
}

/**
 * Fetches short-lived coturn TURN-REST credentials from our backend
 * (GET https://api.zsend.xyz/api/v1/turn/credentials) so the shared secret no longer ships
 * in the APK (D13). Uses the Koin-registered Tor-aware [HttpClientProvider], so the fetch
 * inherits the user's Tor routing plus the shared retry/timeout policy.
 *
 * Behavior contract (relied on by [VoiceCallManager.buildIceServers]):
 *  - [get] NEVER throws and is bounded to [FETCH_TIMEOUT_MS] (4 s) end-to-end, so call setup
 *    can never stall behind a slow fetch — worst case it returns null and the call degrades
 *    to STUN-only (exactly the pre-D13 failure behavior).
 *  - The credential is cached in memory and reused while fresh (ttl minus 5-min slack —
 *    ~1 fetch/hour per device, matching the endpoint's 30/h/IP budget).
 *  - On fetch failure/timeout a stale-but-unexpired cached credential is still returned.
 */
class TurnCredentialsClient internal constructor(
    private val nowMs: () -> Long,
    private val fetcher: suspend () -> TurnCredentials?,
) {
    constructor(httpClientProvider: HttpClientProvider) : this(
        nowMs = { System.currentTimeMillis() },
        fetcher = { fetchOverNetwork(httpClientProvider) },
    )

    // Serializes fetches so a warm-up and a call-setup get() can't double-hit the endpoint;
    // the loser of the race just reads the winner's freshly cached credential.
    private val mutex = Mutex()
    private var cached: TurnCredentials? = null

    /** Current credential: fresh cache, else a bounded network fetch, else stale-but-usable, else null. */
    suspend fun get(): TurnCredentials? {
        // TURN-CREDS debug seam (a release build reads "not armed"): no credential, and none kept for later.
        if (FaultSeams.isArmed(FaultSeam.TURN_CREDS_UNAVAILABLE)) {
            mutex.withLock { cached = null }
            return null
        }
        val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            mutex.withLock {
                val cachedNow = cached?.takeIf { it.isFresh(nowMs()) }
                cachedNow ?: runCatching { fetcher() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                    ?.also { cached = it }
            }
        }
        return result ?: cached?.takeIf { it.isUsable(nowMs()) }
    }

    companion object {
        private const val TAG = "TurnCredentialsClient"
        private const val CREDENTIALS_URL = "https://api.zsend.xyz/api/v1/turn/credentials"
        private const val FETCH_TIMEOUT_MS = 4_000L
        private const val DEFAULT_TTL_SEC = 3_600L

        // Compiled-in default relay URIs (coturn on relay.zsend.xyz) — UDP first, TCP fallback for
        // networks that block UDP. Used only when the backend response omits/empties `uris`.
        val DEFAULT_TURN_URIS = listOf(
            "turn:relay.zsend.xyz:3478?transport=udp",
            "turn:relay.zsend.xyz:3478?transport=tcp",
        )

        private suspend fun fetchOverNetwork(provider: HttpClientProvider): TurnCredentials? = try {
            val body = provider.create().use { client -> client.get(CREDENTIALS_URL).bodyAsText() }
            parse(body, System.currentTimeMillis()).also {
                if (it == null) Log.w(TAG, "TURN credential response unparseable")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // expectSuccess=true in HttpClientProvider maps non-2xx (429/503/…) here too.
            Log.w(TAG, "TURN credential fetch failed: ${e.message}")
            null
        }

        /**
         * Parse the backend's `{username, password, ttl, uris}` JSON. Null on any malformed body;
         * absent/empty `uris` falls back to [DEFAULT_TURN_URIS]; absent ttl assumes 1 h.
         */
        internal fun parse(body: String, nowMs: Long): TurnCredentials? = runCatching {
            val obj = JSONObject(body)
            val username = obj.optString("username", "")
            val password = obj.optString("password", "")
            if (username.isEmpty() || password.isEmpty()) return@runCatching null
            val ttlSec = obj.optLong("ttl", DEFAULT_TTL_SEC)
            val urisArr = obj.optJSONArray("uris")
            val uris = buildList {
                if (urisArr != null) {
                    for (i in 0 until urisArr.length()) {
                        val u = urisArr.optString(i, "")
                        if (u.isNotEmpty()) add(u)
                    }
                }
            }.ifEmpty { DEFAULT_TURN_URIS }
            TurnCredentials(
                username = username,
                password = password,
                uris = uris,
                expiresAtMs = nowMs + ttlSec * 1_000L,
            )
        }.getOrNull()
    }
}
