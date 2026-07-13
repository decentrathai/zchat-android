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
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Thin client for the ZCHAT AI proxy at api.zsend.xyz.
 *
 * Talks to our backend's /api/v1/ai/X routes, which then proxy to Venice.ai
 * with our master VENICE_ADMIN_KEY server-side. The Android app NEVER holds the
 * Venice key — only its own per-device bearer token issued by /ai/auth/register.
 *
 * Token lifecycle:
 *   1. First app launch (or first AI tab open): POST /ai/auth/register -> token
 *   2. Token persisted in EncryptedSharedPreferences (key "ai_bearer_token")
 *   3. All subsequent /ai/X calls send the token as Authorization: Bearer X
 *
 * Money:
 *   Backend grants $0.20 free credit on register; debits balanceMicroUsd per query.
 *   On 402 Payment Required, surface "Top up" CTA to user.
 */
class AiApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    init {
        // The base URL carries the API key (Authorization header) and request/response bodies — it
        // MUST be TLS. Reject a plaintext endpoint at construction so a misconfig/test mock can't
        // silently downgrade to cleartext.
        require(baseUrl.startsWith("https://")) { "AiApiClient base URL must use https" }
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

    /** Release the underlying Ktor engine (threads + connection pool). Call from the owner's teardown. */
    fun close() = httpClient.close()

    suspend fun register(walletPubkey: String? = null): RegisterResult = withContext(Dispatchers.IO) {
        try {
            val resp = httpClient.post("$baseUrl/api/v1/ai/auth/register") {
                contentType(ContentType.Application.Json)
                // Body is JSON either way — backend tolerates an empty object too.
                val payload = JSONObject().apply {
                    if (walletPubkey != null) put("walletPubkey", walletPubkey)
                }
                setBody(payload.toString())
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                return@withContext RegisterResult.Failure("HTTP ${resp.status.value}: $text")
            }
            val obj = JSONObject(text)
            RegisterResult.Success(
                userId = obj.getString("userId"),
                token = obj.getString("token"),
                balanceMicroUsd = obj.optLong("balanceMicroUsd", 200_000L),
                rebound = obj.optBoolean("rebound", false),
            )
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
            RegisterResult.Failure(e.message ?: "Network error")
        }
    }

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
                return@withContext BalanceResult.Failure("HTTP ${resp.status.value}: $text")
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
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "balance failed", e)
            BalanceResult.Failure(e.message ?: "Network error")
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
                return@withContext ModelsResult.Failure("HTTP ${resp.status.value}")
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
            ModelsResult.Failure(e.message ?: "Network error")
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
                    return@execute when (resp.status.value) {
                        402 -> parseOutOfCredit(text)
                        429 -> ChatResult.Failure("Rate limited — try again in a moment.")
                        else -> {
                            val errMsg = runCatching { JSONObject(text).optString("error", text) }.getOrDefault(text)
                            ChatResult.Failure("HTTP ${resp.status.value}: $errMsg")
                        }
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
            ChatResult.Failure(e.message ?: "Network error")
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
        Log.e(TAG, "chat parse failed", e)
        ChatResult.Failure("Malformed reply from server")
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
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
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
                return@withContext when (resp.status.value) {
                    402 -> ImageResult.OutOfCredit(outOfCreditMessage(text))
                    429 -> ImageResult.Failure("Rate limited — try again in a moment.")
                    else -> {
                        val errMsg = runCatching { JSONObject(text).optString("error", text) }.getOrDefault(text)
                        ImageResult.Failure("HTTP ${resp.status.value}: $errMsg")
                    }
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
            ImageResult.Failure(e.message ?: "Network error")
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
                return@withContext TopupAddressResult.Failure("HTTP ${resp.status.value}: $text")
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
        } catch (e: Exception) {
            Log.e(TAG, "topupAddress failed", e)
            TopupAddressResult.Failure(e.message ?: "Network error")
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
                return@withContext TopupHistoryResult.Failure("HTTP ${resp.status.value}: $text")
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
        } catch (e: Exception) {
            Log.e(TAG, "topupHistory failed", e)
            TopupHistoryResult.Failure(e.message ?: "Network error")
        }
    }

    // Build a specific out-of-credit message from the backend's structured 402 body
    // ({error, balanceMicroUsd, estChargeMicroUsd}) instead of echoing a raw server string.
    private fun parseOutOfCredit(text: String): ChatResult.OutOfCredit = ChatResult.OutOfCredit(outOfCreditMessage(text))

    // Build a specific out-of-credit message from the backend's structured 402 body
    // ({error, balanceMicroUsd, estChargeMicroUsd}). Shared by /ai/chat and /ai/image.
    private fun outOfCreditMessage(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val rawErr = o?.optString("error", "").orEmpty()
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
        const val DEFAULT_BASE_URL = "https://api.zsend.xyz"

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
    val provider: String = "Other",
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
     *   free  -> "Free"
     */
    fun priceLabel(): String = when {
        isFreeTier -> "Free"
        isImage -> imagePerCallUsd?.let { "$${"%.3f".format(it)}/image" } ?: ""
        inputPer1mUsd > 0 || outputPer1mUsd > 0 ->
            "$${"%.2f".format(inputPer1mUsd)} in / $${"%.2f".format(outputPer1mUsd)} out per 1M tokens"
        else -> ""
    }

    /** Compact price for the collapsed picker chip (limited width): "$0.012/img", "$2.01/$6.32 ·1M", "Free". */
    fun priceLabelShort(): String = when {
        isFreeTier -> "Free"
        isImage -> imagePerCallUsd?.let { "$${"%.3f".format(it)}/img" } ?: ""
        inputPer1mUsd > 0 || outputPer1mUsd > 0 ->
            "$${"%.2f".format(inputPer1mUsd)}/$${"%.2f".format(outputPer1mUsd)} ·1M"
        else -> ""
    }

    companion object {
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
            return PROVIDER_PREFIXES.firstOrNull { id.startsWith(it.first) }?.second ?: "Other"
        }
    }
}

sealed class RegisterResult {
    data class Success(val userId: String, val token: String, val balanceMicroUsd: Long, val rebound: Boolean = false) : RegisterResult()
    data class Failure(val error: String) : RegisterResult()
}

sealed class BalanceResult {
    data class Success(
        val balanceUsd: Double,
        val freeTrialAvailable: Boolean,
        /** True = trial account (whitelist gating applies); null = backend didn't say. */
        val onFreeTrial: Boolean? = null,
    ) : BalanceResult()
    data class Failure(val error: String) : BalanceResult()
}

sealed class ModelsResult {
    data class Success(val models: List<VeniceModel>) : ModelsResult()
    data class Failure(val error: String) : ModelsResult()
}

sealed class ChatResult {
    data class Success(
        val reply: String,
        val chargedUsd: Double,
        val balanceAfterUsd: Double,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    ) : ChatResult()
    data class OutOfCredit(val error: String) : ChatResult()
    data class Failure(val error: String) : ChatResult()
}

sealed class TopupAddressResult {
    data class Success(
        val address: String,
        val memo: String,
        val tiers: List<Int>,
        val zecUsdPrice: Double? = null,
    ) : TopupAddressResult()
    data class Failure(val error: String) : TopupAddressResult()
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
    data class Failure(val error: String) : TopupHistoryResult()
}

sealed class ImageResult {
    data class Success(
        val imageUrl: String?,
        val b64Json: String?,
        val chargedUsd: Double,
        val balanceAfterUsd: Double,
    ) : ImageResult()
    data class OutOfCredit(val error: String) : ImageResult()
    data class Failure(val error: String) : ImageResult()
}
