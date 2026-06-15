package co.electriccoin.zcash.ui.screen.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local, persistent AI history model. Chat transcripts and the image gallery are kept until the
 * user explicitly clears them (never auto-discarded). Text transcripts are persisted as JSON in
 * [AiPreferences] (EncryptedSharedPreferences); generated image BYTES are written to app-private
 * filesDir/ai_images/<id>.png (so prefs stays small) with only metadata kept in JSON.
 */

/** One turn in a chat conversation. [role] is "user" or "assistant". [failed] marks a user turn
 *  whose send errored (no reply arrived) — rendered greyed with a Retry affordance, never silently
 *  left looking "sent". */
data class AiChatTurn(
    val role: String,
    val content: String,
    val ts: Long,
    val failed: Boolean = false,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** A persisted chat conversation — one entry in the "previous chats" list. */
data class AiConversation(
    val id: String,
    val title: String,
    val model: String,
    val turns: List<AiChatTurn>,
    val updatedAt: Long,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** A short title derived from the first user message. */
        fun titleFrom(firstUserMessage: String): String =
            firstUserMessage.trim().take(40).ifEmpty { "New chat" }
    }
}

/** A persisted generated image. Bytes live at filesDir/ai_images/<id>.png; [url] is set only for
 *  url-mode results (the backend returns base64 today, so [url] is normally null). */
data class AiImageItem(
    val id: String,
    val prompt: String,
    val model: String,
    val url: String?,
    val ts: Long,
    val chargedUsd: Double = 0.0,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

// ── JSON (de)serialization (org.json — already a module dependency) ──────────────────────────────
// Parsing is defensive: a malformed entry is skipped, never crashes history load.

fun List<AiConversation>.conversationsToJson(): String {
    val arr = JSONArray()
    forEach { c ->
        val turns = JSONArray()
        c.turns.forEach { t ->
            turns.put(JSONObject().put("role", t.role).put("content", t.content).put("ts", t.ts).put("failed", t.failed))
        }
        arr.put(
            JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("model", c.model)
                .put("updatedAt", c.updatedAt)
                .put("turns", turns),
        )
    }
    return arr.toString()
}

fun parseConversations(json: String?): List<AiConversation> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val turnsArr = o.optJSONArray("turns") ?: JSONArray()
            val turns = (0 until turnsArr.length()).mapNotNull { j ->
                val t = turnsArr.optJSONObject(j) ?: return@mapNotNull null
                val role = t.optString("role"); val content = t.optString("content")
                if (role.isEmpty()) null else AiChatTurn(role, content, t.optLong("ts"), t.optBoolean("failed", false))
            }
            AiConversation(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                title = o.optString("title", "Chat"),
                model = o.optString("model", ""),
                turns = turns,
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }.getOrDefault(emptyList())
}

fun List<AiImageItem>.imagesToJson(): String {
    val arr = JSONArray()
    forEach { im ->
        val o = JSONObject()
            .put("id", im.id)
            .put("prompt", im.prompt)
            .put("model", im.model)
            .put("ts", im.ts)
            .put("chargedUsd", im.chargedUsd)
        if (im.url != null) o.put("url", im.url)
        arr.put(o)
    }
    return arr.toString()
}

fun parseImages(json: String?): List<AiImageItem> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            AiImageItem(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                prompt = o.optString("prompt", ""),
                model = o.optString("model", ""),
                url = if (o.isNull("url")) null else o.optString("url").ifEmpty { null },
                ts = o.optLong("ts"),
                chargedUsd = o.optDouble("chargedUsd", 0.0),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * App-private store for generated image bytes. Files live in filesDir/ai_images (NOT cacheDir — these
 * must survive until the user deletes them). App-private + FLAG_SECURE'd screen; wiped by DestroyManager
 * along with the rest of filesDir.
 */
class AiImageStore(context: Context) {
    private val appContext = context.applicationContext

    // Lazy so the constructor stays cheap: filesDir access + mkdirs() is a disk hit (~StrictMode
    // DiskReadViolation, ~150ms) that must NOT run on the main thread when AndroidAiTab builds this in
    // composition. First access happens from AiTabVM on Dispatchers.IO, so the dir is created off-main.
    private val dir by lazy { File(appContext.filesDir, "ai_images").apply { mkdirs() } }

    fun save(id: String, base64: String): Boolean {
        // Cap the (untrusted, API-supplied) base64 before decoding: Base64.decode allocates a buffer
        // proportional to input length, so a hostile multi-hundred-MB response would OOM the process
        // (and catching OutOfMemoryError after the heap is exhausted is unreliable). A real generated
        // PNG is far under this bound.
        if (base64.length > MAX_IMAGE_BASE64_LEN) {
            Log.w("AiImageStore", "rejecting oversized AI image payload (${base64.length} chars > $MAX_IMAGE_BASE64_LEN)")
            return false
        }
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            File(dir, "$id.png").writeBytes(bytes)
            true
        }.onFailure { Log.w("AiImageStore", "failed to save AI image $id: ${it.message}") }
            .getOrDefault(false)
    }

    fun loadBitmap(id: String): Bitmap? = runCatching {
        val f = File(dir, "$id.png")
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()

    fun delete(id: String) {
        runCatching { File(dir, "$id.png").delete() }
    }

    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        // ~12M base64 chars ≈ a 9 MB decoded image — far above any real generated PNG, well below a
        // memory-exhausting payload.
        const val MAX_IMAGE_BASE64_LEN = 12_000_000
    }
}
