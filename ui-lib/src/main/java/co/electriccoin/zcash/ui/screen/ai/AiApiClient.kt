package co.electriccoin.zcash.ui.screen.ai

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Locale

/**
 * Thin client for the ZCHAT AI proxy at api.zsend.xyz.
 *
 * Talks to our backend's /api/v1/ai/X routes, which then proxy to Venice.ai
 * with our master VENICE_ADMIN_KEY server-side. The Android app NEVER holds the
 * Venice key — only its own bearer token, which [AiAccountAccess] gets by the wallet's sign-in key.
 *
 * Token lifecycle:
 *   1. [AiAccountAccess] signs in with the sign-in key -> token
 *   2. Token persisted in EncryptedSharedPreferences ([AiPreferences])
 *   3. All subsequent /ai/X calls send the token as Authorization: Bearer X
 *
 * Money:
 *   Backend grants $0.20 free credit to a new account; debits balanceMicroUsd per query.
 *   On 402 Payment Required, surface "Top up" CTA to user.
 */
class AiApiClient(
    private val baseUrl: String = AiAuthEndpoint.baseUrl,
) {
    init {
        // The base URL carries the API key (Authorization header) and request/response bodies — it
        // MUST be TLS. Reject a plaintext endpoint at construction so a misconfig/test mock can't
        // silently downgrade to cleartext.
        // AI-KEY-D: the one exception is asked from the endpoint object. A release build's object answers "no" to
        // every address; a debug build made with both sign-in switches accepts the loopback address with a port.
        require(baseUrl.startsWith("https://") || AiAuthEndpoint.allowsCleartext(baseUrl)) {
            "AiApiClient base URL must use https"
        }
    }

    // Image generation (and large chat completions) on Venice can run 30–120s. The bare HttpClient()
    // used the engine's short default socket timeout, surfacing as "Socket timeout has expired" on
    // slow image models (e.g. qwen-image). Give requests a generous window; connect stays short.
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
        }
    }

    /**
     * AI-KEY-S: the one request function of [AiAccountAccess]: status and body of a key call; a transport failure throws.
     * It logs a fixed text and the exception's class only: an exception's text can carry the address of a call.
     */
    suspend fun request(
        method: String,
        path: String,
        accessKey: String?,
        body: String?,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val resp =
                    httpClient.request("$baseUrl$path") {
                        this.method = HttpMethod.parse(method)
                        if (accessKey != null) header(HttpHeaders.Authorization, "Bearer $accessKey")
                        if (body != null) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                        timeout { requestTimeoutMillis = SHORT_REQUEST_TIMEOUT_MS }
                    }
                resp.status.value to resp.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "An AI key request failed: " + e.javaClass.simpleName)
                throw e
            }
        }

    /** Release the underlying Ktor engine (threads + connection pool). Call from the owner's teardown. */
    fun close() = httpClient.close()

    suspend fun balance(token: String): BalanceResult = withContext(Dispatchers.IO) {
        try {
            val resp = httpClient.get("$baseUrl/api/v1/ai/balance") {
                header(HttpHeaders.Authorization, "Bearer $token")
                // Small GET — fail fast so the VM's bounded retry can kick in, instead of one hung
                // request pinning the UI in "loading" for the full 180s default.
                timeout { requestTimeoutMillis = SHORT_REQUEST_TIMEOUT_MS }
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                val refused = refusesAccessKey(resp.status.value, text)
                return@withContext BalanceResult.Failure(readableHttpFailure(resp.status.value, text), refused)
            }
            val obj = JSONObject(text)
            val balanceUsd = sanitizeUsd(obj.optDouble("balanceUsd", -1.0))
            if (balanceUsd < 0.0) {
                return@withContext BalanceResult.Failure("Invalid balance in response")
            }
            BalanceResult.Success(
                balanceUsd = balanceUsd,
                freeTrialAvailable = obj.optBoolean("freeTrialAvailable", false),
                // Per-user trial flag (true = trial account, cheap-model whitelist applies). Only
                // trust it when the backend actually sends it; null = unknown (older backend).
                onFreeTrial = if (obj.has("onFreeTrial")) obj.optBoolean("onFreeTrial") else null,
                // AI-KEY-S: an old server sends neither field, and that says nothing about the account's key.
                signInKeySent = obj.has("signInKeyId"),
                signInKeyId = if (obj.isNull("signInKeyId")) null else obj.optString("signInKeyId"),
                // GPT 5: only a real JSON Boolean says anything.
                paid = obj.opt("paid") as? Boolean,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "balance failed", e)
            BalanceResult.Failure(readableTransportFailure(e))
        }
    }

    suspend fun listModels(token: String): ModelsResult = withContext(Dispatchers.IO) {
        try {
            val resp = httpClient.get("$baseUrl/api/v1/ai/models") {
                header(HttpHeaders.Authorization, "Bearer $token")
                // Fail fast (see balance) — the VM retries with backoff on transient failures.
                timeout { requestTimeoutMillis = SHORT_REQUEST_TIMEOUT_MS }
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                val refused = refusesAccessKey(resp.status.value, text)
                return@withContext ModelsResult.Failure(readableHttpFailure(resp.status.value, text), refused)
            }
            val obj = JSONObject(text)
            val arr = obj.optJSONArray("data") ?: JSONArray()
            val models = mutableListOf<VeniceModel>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val spec = m.optJSONObject("model_spec") ?: JSONObject()
                val caps = spec.optJSONObject("capabilities") ?: JSONObject()
                // Backend tags every model with zchat_model_type ('image'|'text'), zchat_uncensored,
                // and attaches zchat_pricing ONLY when the model is actually usable (priced). Use those
                // instead of fragile id-prefix heuristics so chat/image pickers list the right models
                // and can show price + an "Uncensored" badge.
                val pricing = m.optJSONObject("zchat_pricing")
                val modelId = m.getString("id")
                models += VeniceModel(
                    id = modelId,
                    contextTokens = spec.optInt("availableContextTokens", 0),
                    supportsVision = caps.optBoolean("supportsVision", false),
                    name = spec.optString("name").ifBlank { modelId },
                    provider = VeniceModel.providerOf(modelId),
                    isImage = m.optString("zchat_model_type") == "image",
                    priced = pricing != null,
                    uncensored = m.optBoolean("zchat_uncensored", false),
                    imagePerCallUsd = pricing?.let { if (it.isNull("imagePerCallUsd")) null else it.optDouble("imagePerCallUsd").takeIf { v -> v > 0 } },
                    inputPer1mUsd = pricing?.optDouble("inputPer1mUsd", 0.0) ?: 0.0,
                    outputPer1mUsd = pricing?.optDouble("outputPer1mUsd", 0.0) ?: 0.0,
                    isFreeTier = pricing?.optBoolean("isFreeTier", false) ?: false,
                    // Trial (free-tier account) whitelist membership. Prefer the backend's flag when
                    // exposed; fall back to the mirrored FREE_TIER_MODEL_IDS list otherwise.
                    trialEligible = if (m.has("zchat_trial_eligible")) {
                        m.optBoolean("zchat_trial_eligible")
                    } else {
                        modelId in FREE_TIER_MODEL_IDS
                    },
                )
            }
            ModelsResult.Success(models)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listModels failed", e)
            ModelsResult.Failure(readableTransportFailure(e))
        }
    }

    /**
     * Send a chat completion. [history] is the FULL conversation so far (oldest → newest, including
     * the just-typed user message), giving the model real multi-turn context. Prior turns are
     * persisted locally and replayed here on every send.
     *
     * When [onDelta] is non-null the request asks for SSE streaming (`stream: true`) and invokes
     * [onDelta] (on the IO dispatcher) for every content chunk as it arrives, so the UI can render
     * the reply token-by-token. If the backend answers with a plain JSON body instead of an SSE
     * stream (proxy without streaming support), this transparently falls back to the non-streaming
     * parse — the reply then just arrives in one piece, exactly like before.
     */
    suspend fun chat(
        token: String,
        model: String,
        history: List<AiChatTurn>,
        maxTokens: Int = 1024,
        onDelta: ((String) -> Unit)? = null,
    ): ChatResult = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            history.forEach { turn ->
                messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .apply { if (onDelta != null) put("stream", true) }
                .toString()
            httpClient.preparePost("$baseUrl/api/v1/ai/chat") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    val text = resp.bodyAsText()
                    return@execute when {
                        resp.status.value == 402 && !isServiceOutage(text) -> parseOutOfCredit(text)
                        resp.status.value == 429 -> ChatResult.Failure("Rate limited — try again in a moment.")
                        else -> ChatResult.Failure(readableHttpFailure(resp.status.value, text), refusesAccessKey(resp.status.value, text))
                    }
                }
                val isSse = resp.contentType()?.match(ContentType.Text.EventStream) == true
                if (onDelta != null && isSse) {
                    readChatStream(resp, onDelta)
                } else {
                    parseChatBody(resp.bodyAsText())
                }
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation (Stop button / VM teardown) into a Failure result — the
            // caller handles the stopped state itself.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            ChatResult.Failure(readableTransportFailure(e))
        }
    }

    /** Parse a complete (non-streaming) chat-completion JSON body into a [ChatResult]. */
    private fun parseChatBody(text: String): ChatResult = try {
        val obj = JSONObject(text)
        val choice = obj.getJSONArray("choices").getJSONObject(0)
        val reply = choice.getJSONObject("message").getString("content")
        val meta = obj.optJSONObject("zchat_meta")
        val usage = obj.optJSONObject("usage")
        ChatResult.Success(
            reply = reply,
            // Sanitize untrusted money fields. charged falls back to 0 (no charge); balance falls
            // back to -1 = "unknown" (NOT 0.0, which would falsely read as out-of-credit) so the
            // ViewModel recomputes from local state — mirrors the image endpoint.
            chargedUsd = sanitizeUsd(meta?.optDouble("chargedUsd", 0.0) ?: 0.0, fallback = 0.0),
            balanceAfterUsd = sanitizeUsd(meta?.optDouble("balanceAfterUsd", -1.0) ?: -1.0),
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
        )
    } catch (e: Exception) {
        // A 2xx that is not the service's JSON (e.g. a proxy's HTML page): the service is not answering.
        Log.e(TAG, "chat parse failed", e)
        ChatResult.Failure(SERVICE_UNAVAILABLE_MESSAGE)
    }

    /**
     * Consume an OpenAI-compatible SSE stream (`data: {chunk}` lines, terminated by `data: [DONE]`),
     * feeding content deltas to [onDelta] and accumulating the full reply. Money/usage metadata is
     * read from whichever chunk carries it (typically the last); when the proxy doesn't attach
     * zchat_meta in stream mode the money fields keep their "unknown" fallbacks and the ViewModel
     * refreshes the balance afterwards.
     */
    private suspend fun readChatStream(resp: HttpResponse, onDelta: (String) -> Unit): ChatResult {
        val channel = resp.bodyAsChannel()
        val reply = StringBuilder()
        var chargedUsd = 0.0
        var balanceAfterUsd = -1.0
        var promptTokens = 0
        var completionTokens = 0
        // ZC-1409: only `data: [DONE]` ends a reply. A stream that closes or resets before it is cut off: a reply with
        // content is returned marked [ChatResult.Success.cutOff] (never as a complete reply, and the partial is kept,
        // so Retry does not resend and pay for the same prompt); a reset before any content stays a Failure.
        var sawDone = false
        while (!channel.isClosedForRead) {
            val line =
                try {
                    channel.readLine()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (reply.isEmpty()) throw e
                    Log.w(TAG, "chat stream cut off after ${reply.length} chars: ${e.javaClass.simpleName}")
                    null
                } ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") {
                sawDone = true
                break
            }
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            val delta = obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                .orEmpty()
            if (delta.isNotEmpty()) {
                reply.append(delta)
                onDelta(delta)
            }
            obj.optJSONObject("usage")?.let { u ->
                promptTokens = u.optInt("prompt_tokens", promptTokens)
                completionTokens = u.optInt("completion_tokens", completionTokens)
            }
            obj.optJSONObject("zchat_meta")?.let { meta ->
                chargedUsd = sanitizeUsd(meta.optDouble("chargedUsd", 0.0), fallback = 0.0)
                balanceAfterUsd = sanitizeUsd(meta.optDouble("balanceAfterUsd", -1.0))
            }
        }
        if (reply.isEmpty()) {
            return ChatResult.Failure("The model returned an empty reply — try again or pick another model.")
        }
        return ChatResult.Success(
            reply = reply.toString(),
            chargedUsd = chargedUsd,
            balanceAfterUsd = balanceAfterUsd,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cutOff = !sawDone,
        )
    }

    suspend fun image(
        token: String,
        model: String,
        prompt: String,
        size: String = "1024x1024",
    ): ImageResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("n", 1)
                .put("size", size)
                .toString()
            val resp = httpClient.post("$baseUrl/api/v1/ai/image") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                return@withContext when {
                    resp.status.value == 402 && !isServiceOutage(text) -> ImageResult.OutOfCredit(outOfCreditMessage(text))
                    resp.status.value == 429 -> ImageResult.Failure("Rate limited — try again in a moment.")
                    else -> ImageResult.Failure(readableHttpFailure(resp.status.value, text), refusesAccessKey(resp.status.value, text))
                }
            }
            val obj = JSONObject(text)
            // Venice returns OpenAI-compat shape: {data:[{url|b64_json}]}
            val arr = obj.optJSONArray("data") ?: JSONArray()
            val first = if (arr.length() > 0) arr.getJSONObject(0) else null
            val imageUrl = first?.optString("url", "")?.ifEmpty { null }
            val b64 = first?.optString("b64_json", "")?.ifEmpty { null }
            if (imageUrl == null && b64 == null) {
                // 2xx but no image — the model produced nothing, typically because it refused the
                // prompt (content policy) or the request was filtered. Tell the user explicitly so
                // it doesn't look like a silent no-op; suggest a different model/prompt.
                return@withContext ImageResult.Failure(
                    "No image was returned. The selected model may have refused this prompt — try a different image model or rephrase.",
                )
            }
            val meta = obj.optJSONObject("zchat_meta")
            ImageResult.Success(
                imageUrl = imageUrl,
                b64Json = b64,
                // Sanitize untrusted money fields (see chat endpoint).
                chargedUsd = sanitizeUsd(meta?.optDouble("chargedUsd", 0.0) ?: 0.0, fallback = 0.0),
                balanceAfterUsd = sanitizeUsd(meta?.optDouble("balanceAfterUsd", -1.0) ?: -1.0),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "image failed", e)
            ImageResult.Failure(readableTransportFailure(e))
        }
    }

    suspend fun topupAddress(token: String): TopupAddressResult = withContext(Dispatchers.IO) {
        try {
            val resp = httpClient.get("$baseUrl/api/v1/ai/topup/address") {
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout { requestTimeoutMillis = SHORT_REQUEST_TIMEOUT_MS }
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                val refused = refusesAccessKey(resp.status.value, text)
                return@withContext TopupAddressResult.Failure(readableTopupAddressFailure(resp.status.value, text), refused)
            }
            val obj = JSONObject(text)
            val tiers = obj.optJSONArray("tiers") ?: JSONArray()
            val tiersList = mutableListOf<Int>()
            for (i in 0 until tiers.length()) tiersList += tiers.getInt(i)
            val zecUsd = if (obj.isNull("zecUsdPrice")) null else obj.optDouble("zecUsdPrice", -1.0).takeIf { it > 0 }
            TopupAddressResult.Success(
                address = obj.getString("address"),
                memo = obj.getString("memo"),
                tiers = tiersList,
                zecUsdPrice = zecUsd,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "topupAddress failed", e)
            TopupAddressResult.Failure(readableTransportFailure(e))
        }
    }

    /**
     * Fetch the caller's top-up deposit history: GET /api/v1/ai/topup/status returns
     * `{deposits: [{id, zecTxId, zatoshi, usdCredited, status, createdAt, creditedAt}]}`
     * (newest first, capped at 20 server-side). Any HTTP/network/parse failure yields
     * [TopupHistoryResult.Failure] — never a crash — so the sheet can show a Retry state.
     */
    suspend fun topupHistory(token: String): TopupHistoryResult = withContext(Dispatchers.IO) {
        try {
            val resp = httpClient.get("$baseUrl/api/v1/ai/topup/status") {
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout { requestTimeoutMillis = SHORT_REQUEST_TIMEOUT_MS }
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                val refused = refusesAccessKey(resp.status.value, text)
                return@withContext TopupHistoryResult.Failure(readableHttpFailure(resp.status.value, text), refused)
            }
            val obj = JSONObject(text)
            val arr = obj.optJSONArray("deposits") ?: JSONArray()
            val deposits = mutableListOf<AiTopupEntry>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                deposits += AiTopupEntry(
                    id = d.optString("id"),
                    zecTxId = if (d.isNull("zecTxId")) "" else d.optString("zecTxId"),
                    // zatoshi is serialized as a string (BigInt server-side); negative/garbage → 0.
                    zatoshi = d.optString("zatoshi").toLongOrNull()?.takeIf { it >= 0 } ?: 0L,
                    // Untrusted money field: sanitize like balance/charge; -1.0 = unknown → UI shows "—".
                    usdCredited = sanitizeUsd(d.optDouble("usdCredited", -1.0)),
                    status = d.optString("status", "pending"),
                    createdAtMillis = parseIsoMillis(if (d.isNull("createdAt")) null else d.optString("createdAt")),
                    creditedAtMillis = parseIsoMillis(if (d.isNull("creditedAt")) null else d.optString("creditedAt")),
                )
            }
            TopupHistoryResult.Success(deposits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "topupHistory failed", e)
            TopupHistoryResult.Failure(readableTransportFailure(e))
        }
    }

    // Build a specific out-of-credit message from the backend's structured 402 body
    // ({error, balanceMicroUsd, estChargeMicroUsd}) instead of echoing a raw server string.
    private fun parseOutOfCredit(text: String): ChatResult.OutOfCredit = ChatResult.OutOfCredit(outOfCreditMessage(text))

    // Build a specific out-of-credit message from the backend's structured 402 body
    // ({error, balanceMicroUsd, estChargeMicroUsd}). Shared by /ai/chat and /ai/image.
    private fun outOfCreditMessage(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val rawErr = backendErrorSentence(text).orEmpty()
        val balUsd = o?.optLong("balanceMicroUsd", -1L)?.takeIf { it >= 0 }?.let { it / 1_000_000.0 }
        val needUsd = o?.optLong("estChargeMicroUsd", -1L)?.takeIf { it >= 0 }?.let { it / 1_000_000.0 }
        return if (balUsd != null && needUsd != null) {
            "Your ${usd(balUsd)} balance is too low — this request needs about ${usd(needUsd)}. Top up to continue."
        } else {
            rawErr.ifEmpty { "Insufficient credit. Please top up." }
        }
    }

    companion object {
        private const val TAG = "AiApiClient"
        const val DEFAULT_BASE_URL = AiAuthEndpoint.DEFAULT_BASE_URL

        // Short per-request window for cheap GETs (balance/models/top-up). The 180s client default
        // stays only for chat/image POSTs, whose generations legitimately run long.
        private const val SHORT_REQUEST_TIMEOUT_MS = 15_000L

        /**
         * Mirror of the backend's FREE_TIER_MODELS whitelist (server.ts): the models a trial account
         * (no credited deposit) may use on /ai/chat. Used only when the catalog doesn't carry the
         * per-model `zchat_trial_eligible` flag yet — keep in sync with the backend.
         */
        val FREE_TIER_MODEL_IDS: Set<String> = setOf(
            "venice-uncensored",
            "venice-uncensored-1-2",
            "qwen3-coder-480b-a35b-instruct-turbo",
            "qwen3-235b-a22b-instruct-2507",
            "llama-3.3-70b",
        )

        // Upper bound on any single USD value from the backend. Balances/charges are cents-to-dollars;
        // anything beyond this is a hostile/buggy response, not a real amount.
        private const val MAX_USD = 1_000_000.0

        /**
         * Sanitize a USD value parsed from the (untrusted) backend before it touches balance/charge
         * state. Rejects NaN, Infinity, negatives, and absurd magnitudes — returning [fallback]
         * (default -1.0 = "unknown", which the ViewModel treats as "recompute from local state")
         * so a compromised/buggy endpoint can't corrupt the displayed balance or charge accounting.
         */
        fun sanitizeUsd(v: Double, fallback: Double = -1.0): Double =
            if (v.isFinite() && v in 0.0..MAX_USD) v else fallback

        /**
         * What the user reads when the AI service itself cannot answer: an HTML error page (Cloudflare
         * serves one when the backend behind the tunnel is down), any 5xx, or Venice refusing the
         * service's own calls (relayed as 502 "Venice error (402)" when the service's Venice account is
         * unpaid — the user's credit is not the problem, so this never says "top up").
         * The UI swaps this exact sentence for the localized one (see localizeAiMessage).
         */
        const val SERVICE_UNAVAILABLE_MESSAGE = "The AI service is temporarily unavailable. Please try again later."

        /**
         * What the user reads when top-ups are paused: GET /ai/topup/address answers 503 while the
         * server has no top-up address (owner decision 2026-09-10: AI top-ups are paused server-side).
         */
        const val TOPUP_PAUSED_MESSAGE = "Top-ups are temporarily paused. Your balance and chats are safe."

        /**
         * What the user reads when the request never got an answer: no connection, a DNS or TLS failure, a
         * timeout. The UI swaps this exact sentence for the localized one (see localizeAiMessage).
         */
        const val NETWORK_UNREACHABLE_MESSAGE = "Could not reach the AI service. Check your connection and try again."

        /**
         * AI-KEY-S (spec 7.1): resource key to the fixed English sentence [AiAccountAccess] gives; each equals its
         * resource word for word (AiSignInErrorsHaveTextsTest), and localizeAiMessage swaps it.
         */
        val SIGN_IN_TEXTS: Map<String, String> =
            mapOf(
                "ai_signin_wallet_not_ready" to "ZCHAT is still opening your wallet. Tap Retry in a moment.",
                "ai_signin_server_not_ready" to "ZCHAT's AI service cannot sign this wallet in yet. Try again later.",
                "ai_signin_too_many" to "Too many AI sign-in attempts from this network. Try again later.",
                "ai_signup_limit" to "ZCHAT cannot open another new AI account from this network right now. Try again later.",
                "ai_signin_refused" to "The AI service did not accept this wallet's sign-in. Try again later.",
                "ai_signin_elsewhere" to "This wallet's AI account was just opened on another phone. Tap Retry to use it here.",
                "ai_notice_other_wallet_dropped" to
                    "The AI account this phone used before is one that ZCHAT could not match to this wallet, and it held no " +
                    "paid credit. ZCHAT opened this wallet's own AI account.",
                "ai_notice_key_parked" to
                    "This phone holds a key to an AI account that may hold paid credit and that ZCHAT cannot match to this " +
                    "wallet. ZCHAT keeps the key and does not use it. This wallet has its own AI account. If that credit is " +
                    "yours, write to ZCHAT support: after a check of the wallet it belongs to, Try to link opens it. If " +
                    "support had to remove someone else's sign-in key from that account, use Look for AI credit from before " +
                    "2.13 instead.",
                "ai_notice_new_account_after_refusal" to
                    "This phone's key to its AI account no longer works, and this recovery phrase is not linked to that " +
                    "account. ZCHAT opened a new AI account. Credit of the earlier account is not deleted: it stays on " +
                    "ZCHAT's server.",
                "ai_notice_link_lost" to
                    "This AI account is no longer linked to your recovery phrase. This phone's key still opens it. Link " +
                    "again asks ZCHAT's server to link it.",
            )

        /**
         * AI-KEY-P: the sentences of the other accounts of one wallet, each word for word its resource (spec 7.1). A map of
         * its own: [SIGN_IN_TEXTS] is pinned to the keys of AI-KEY-S.
         */
        val OTHER_ACCOUNT_TEXTS: Map<String, String> =
            mapOf(
                "ai_notice_trial_account_left" to
                    "This wallet's recovery phrase already had an AI account. ZCHAT now uses that one; the trial account this " +
                    "phone used before is left.",
                "ai_notice_two_paid_accounts" to
                    "This wallet has two AI accounts that may hold paid credit. This phone uses one of them; your recovery phrase " +
                    "opens the other. ZCHAT support can join them into one after a check that this wallet is yours. Until then " +
                    "keep this phone's app data: its key is the only way to the account it uses.",
                "ai_notice_parked_key_dead" to
                    "A key this phone kept for another AI account no longer works, so ZCHAT removed it. If that credit is yours, " +
                    "Look for AI credit from before 2.13 is the way to it.",
                "ai_notice_parked_key_opened" to "The AI account this phone kept a key for is now this wallet's AI account.",
                "ai_old_credit_none" to "ZCHAT's server found no AI credit from before 2.13 that it can open for this wallet.",
                "ai_old_credit_opened" to "ZCHAT found this wallet's earlier AI account and linked it to your recovery phrase.",
                "ai_old_credit_needs_check" to
                    "ZCHAT's server has an AI account from before 2.13 for this wallet that may hold paid credit. It is still tied " +
                    "to the phone that used it: open ZCHAT 2.13 or newer on that phone once. If that phone is gone, write to ZCHAT " +
                    "support: after a check that this wallet is yours, this button opens the account.",
                "ai_old_credit_two_accounts" to
                    "ZCHAT's server found an earlier AI account for this wallet, and this wallet's current AI account may hold paid " +
                    "credit too. ZCHAT support can join the two after a check that this wallet is yours.",
                "ai_topup_not_linked" to
                    "Top-ups work only for an AI account that is linked to this wallet's recovery phrase. This one is not.",
            )

        /** ZC-1209: what the tap on "Link again" came to, each word for word its resource. A map of its own: the other two are pinned. */
        val LINK_AGAIN_TEXTS: Map<String, String> =
            mapOf(
                "ai_link_again_done" to "This AI account is linked to your recovery phrase again.",
                "ai_link_again_not_done" to
                    "ZCHAT's server did not link this AI account to your recovery phrase. This phone's key still opens it.",
            )

        /** AI-KEY-S: the server refused this access key (401); a page in front of the server says nothing about the key. */
        internal fun refusesAccessKey(
            status: Int,
            body: String,
        ): Boolean = status == HTTP_UNAUTHORIZED && !looksLikeHtml(body)

        // A backend error sentence longer than this is not a sentence the user should read.
        private const val MAX_BACKEND_ERROR_CHARS = 200

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_SERVICE_UNAVAILABLE = 503

        /**
         * Turn a non-2xx answer into one sentence the user can read. Never returns a status code, HTML,
         * JSON or an upstream (Venice) error string: outages become [SERVICE_UNAVAILABLE_MESSAGE], and a
         * 4xx keeps the backend's own error sentence only when it is a plain sentence.
         */
        fun readableHttpFailure(status: Int, body: String): String =
            if (status >= HTTP_SERVER_ERROR || isServiceOutage(body)) {
                SERVICE_UNAVAILABLE_MESSAGE
            } else {
                backendErrorSentence(body) ?: SERVICE_UNAVAILABLE_MESSAGE
            }

        /**
         * Turn an exception from a request into one sentence the user can read. Never the exception's own
         * text: a timeout's message carries the request URL, and a 2xx that is not the service's JSON (a
         * Cloudflare page) fails in the parser with the page quoted. Transport failures (every Ktor
         * timeout is an IOException) become [NETWORK_UNREACHABLE_MESSAGE]; anything else means the
         * service answered with something it never sends, so [SERVICE_UNAVAILABLE_MESSAGE].
         */
        fun readableTransportFailure(e: Throwable): String =
            if (e is IOException) NETWORK_UNREACHABLE_MESSAGE else SERVICE_UNAVAILABLE_MESSAGE

        /**
         * GET /ai/topup/address: the backend's own 503 means top-ups are paused. A 503 that is an HTML
         * page comes from Cloudflare (the backend is down), so that one is an outage, not a pause.
         */
        fun readableTopupAddressFailure(status: Int, body: String): String =
            if (status == HTTP_SERVICE_UNAVAILABLE && !looksLikeHtml(body)) {
                TOPUP_PAUSED_MESSAGE
            } else {
                readableHttpFailure(status, body)
            }

        /**
         * True when the body says the service cannot answer, whatever the status: an HTML page, or
         * the backend relaying an upstream (Venice) refusal. A 402 carrying one of these is the
         * service's problem, not the user's credit.
         */
        fun isServiceOutage(body: String): Boolean {
            if (looksLikeHtml(body)) return true
            val error = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
            return error.trimStart().startsWith("Venice", ignoreCase = true)
        }

        private fun looksLikeHtml(body: String): Boolean {
            val head = body.trimStart().take(MAX_BACKEND_ERROR_CHARS).lowercase(Locale.ROOT)
            return head.startsWith("<") || "<html" in head || "<!doctype" in head
        }

        /** The backend's `{"error": "…"}` sentence when it is plain text a user can read; null otherwise. */
        private fun backendErrorSentence(body: String): String? {
            val error = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("").trim()
            val plain =
                error.isNotEmpty() &&
                    error.length <= MAX_BACKEND_ERROR_CHARS &&
                    error.none { it == '{' || it == '}' || it == '<' } &&
                    !error.startsWith("Venice", ignoreCase = true)
            return error.takeIf { plain }
        }

        /** Compact USD: 2 decimals for amounts ≥ 1¢, else 4 so tiny per-request costs aren't shown as $0.00. */
        fun usd(v: Double): String = if (v >= 0.01) "$${"%.2f".format(v)}" else "$${"%.4f".format(v)}"

        /** Parse an ISO-8601 timestamp (backend `Date.toISOString()`) into epoch millis; null on garbage. */
        private fun parseIsoMillis(iso: String?): Long? =
            if (iso.isNullOrEmpty()) null else runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    }
}

data class VeniceModel(
    val id: String,
    val contextTokens: Int,
    val supportsVision: Boolean,
    /** Human-friendly display name (from model_spec.name; falls back to id). */
    val name: String = id,
    /** Provider/family bucket for the grouped picker (B16), derived from the id. "Other" if unknown. */
    val provider: String = OTHER_PROVIDER,
    val isImage: Boolean = false,
    val priced: Boolean = true,
    val uncensored: Boolean = false,
    /** Per-image price in USD (image models). Null for text models. */
    val imagePerCallUsd: Double? = null,
    /** Per-1M-token input price in USD (text models). */
    val inputPer1mUsd: Double = 0.0,
    /** Per-1M-token output price in USD (text models). */
    val outputPer1mUsd: Double = 0.0,
    /** True if covered by the free tier (no charge). */
    val isFreeTier: Boolean = false,
    /** True if a TRIAL account (no credited deposit yet) may use this model on /ai/chat. */
    val trialEligible: Boolean = false,
) {
    /**
     * Full, self-explanatory price label for the dropdown rows. Labels input vs output and spells out
     * the unit so "$2.01/$6.32 per 1M" is no longer cryptic:
     *   image -> "$0.012/image"
     *   chat  -> "$2.01 in / $6.32 out per 1M tokens"
     *   free  -> the free word
     *
     * ZC-1219: the words come from string resources, read by the caller: [freeText], [perImageFormat] (one argument,
     * the price) and [inOutFormat] (two arguments, the input and the output price).
     */
    fun priceLabel(
        freeText: String,
        perImageFormat: String,
        inOutFormat: String
    ): String =
        when {
            isFreeTier -> freeText
            isImage -> imagePerCallUsd?.let { perImageFormat.format("$${"%.3f".format(it)}") } ?: ""
            inputPer1mUsd > 0 || outputPer1mUsd > 0 ->
                inOutFormat.format("$${"%.2f".format(inputPer1mUsd)}", "$${"%.2f".format(outputPer1mUsd)}")
            else -> ""
        }

    /** Compact price for the collapsed picker chip (limited width): "$0.012/img", "$2.01/$6.32 ·1M", or [freeText]. */
    fun priceLabelShort(freeText: String): String =
        when {
            isFreeTier -> freeText
            isImage -> imagePerCallUsd?.let { "$${"%.3f".format(it)}/img" } ?: ""
            inputPer1mUsd > 0 || outputPer1mUsd > 0 ->
                "$${"%.2f".format(inputPer1mUsd)}/$${"%.2f".format(outputPer1mUsd)} ·1M"
            else -> ""
        }

    companion object {
        /** The provider bucket of a model whose id matches no known family (ZC-1219: a code value, not shown as is). */
        const val OTHER_PROVIDER = "Other"

        // B16: derive a provider/family bucket from the model id so the picker can GROUP the ~120 flat
        // models. Venice's catalog carries no usable provider field, so this is an id-prefix heuristic —
        // display-only (worst case is a model landing in "Other"); it never affects selection (by id).
        // Longest/most-specific prefixes first.
        private val PROVIDER_PREFIXES: List<Pair<String, String>> = listOf(
            "openai-gpt" to "OpenAI", "gpt-oss" to "OpenAI", "gpt-image" to "OpenAI", "gpt" to "OpenAI", "o1" to "OpenAI", "o3" to "OpenAI",
            "google-gemma" to "Gemma", "gemma" to "Gemma", "gemini" to "Gemini",
            "zai-org" to "GLM", "z-ai" to "GLM", "glm" to "GLM",
            "nano-banana" to "Nano Banana", "claude" to "Claude", "anthropic" to "Claude",
            "grok" to "Grok", "qwen" to "Qwen", "mistral" to "Mistral", "llama" to "Llama",
            "deepseek" to "DeepSeek", "kimi" to "Kimi", "minimax" to "MiniMax",
            "nvidia" to "NVIDIA", "venice" to "Venice", "hermes" to "Hermes", "dolphin" to "Dolphin",
            "flux" to "FLUX", "lustify" to "Lustify", "krea" to "Krea", "recraft" to "Recraft",
            "seedream" to "Seedream", "luma" to "Luma", "wan-" to "Wan", "ideogram" to "Ideogram",
            "hunyuan" to "Hunyuan", "pony" to "Pony", "sd" to "Stable Diffusion", "stable-diffusion" to "Stable Diffusion",
        )

        fun providerOf(rawId: String): String {
            val id = rawId.removePrefix("e2ee-").lowercase()
            return PROVIDER_PREFIXES.firstOrNull { id.startsWith(it.first) }?.second ?: OTHER_PROVIDER
        }
    }
}

sealed class BalanceResult {
    data class Success(
        val balanceUsd: Double,
        val freeTrialAvailable: Boolean,
        /** True = trial account (whitelist gating applies); null = backend didn't say. */
        val onFreeTrial: Boolean? = null,
        /** AI-KEY-S: the server sent [signInKeyId] at all; an old server does not. */
        val signInKeySent: Boolean = false,
        val signInKeyId: String? = null,
        /** AI-KEY-S: the server's rule of "paid"; null = not said. */
        val paid: Boolean? = null,
    ) : BalanceResult()

    data class Failure(
        val error: String,
        val refused: Boolean = false,
    ) : BalanceResult()
}

sealed class ModelsResult {
    data class Success(val models: List<VeniceModel>) : ModelsResult()

    data class Failure(
        val error: String,
        val refused: Boolean = false,
    ) : ModelsResult()
}

sealed class ChatResult {
    data class Success(
        val reply: String,
        val chargedUsd: Double,
        val balanceAfterUsd: Double,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        // ZC-1409: the stream ended before `data: [DONE]`; [reply] is the part that arrived.
        val cutOff: Boolean = false,
    ) : ChatResult()
    data class OutOfCredit(val error: String) : ChatResult()

    data class Failure(
        val error: String,
        val refused: Boolean = false,
    ) : ChatResult()
}

sealed class TopupAddressResult {
    data class Success(
        val address: String,
        val memo: String,
        val tiers: List<Int>,
        val zecUsdPrice: Double? = null,
    ) : TopupAddressResult()

    data class Failure(
        val error: String,
        val refused: Boolean = false,
    ) : TopupAddressResult()
}

/** One AI top-up deposit as reported by GET /api/v1/ai/topup/status. */
data class AiTopupEntry(
    val id: String,
    /** On-chain ZEC transaction id of the deposit ("" if absent). */
    val zecTxId: String,
    /** Deposited amount in zatoshi (1 ZEC = 100,000,000 zatoshi). */
    val zatoshi: Long,
    /** USD credited to the AI balance; -1.0 = unknown/not-yet-credited. */
    val usdCredited: Double,
    /** Backend status: pending | confirmed | credited | rejected. */
    val status: String,
    val createdAtMillis: Long?,
    val creditedAtMillis: Long?,
)

sealed class TopupHistoryResult {
    data class Success(val deposits: List<AiTopupEntry>) : TopupHistoryResult()

    data class Failure(
        val error: String,
        val refused: Boolean = false,
    ) : TopupHistoryResult()
}

sealed class ImageResult {
    data class Success(
        val imageUrl: String?,
        val b64Json: String?,
        val chargedUsd: Double,
        val balanceAfterUsd: Double,
    ) : ImageResult()
    data class OutOfCredit(val error: String) : ImageResult()

    data class Failure(
        val error: String,
        val refused: Boolean = false,
    ) : ImageResult()
}
