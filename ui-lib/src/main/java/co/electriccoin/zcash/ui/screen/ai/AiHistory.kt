package co.electriccoin.zcash.ui.screen.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import co.electriccoin.zcash.ui.screen.chat.datasource.UntaggedChatMarks
import co.electriccoin.zcash.ui.screen.chat.datasource.chatOwnerOf
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
 *  left looking "sent". [cutOff] (ZC-1409) marks an assistant reply whose stream ended before it was
 *  complete: the part that arrived, shown as cut off and never as a whole reply. */
data class AiChatTurn(
    val role: String,
    val content: String,
    val ts: Long,
    val failed: Boolean = false,
    val cutOff: Boolean = false,
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
            val turn =
                JSONObject()
                    .put("role", t.role)
                    .put("content", t.content)
                    .put("ts", t.ts)
                    .put("failed", t.failed)
            // ZC-1409: written only when set, so a saved history without cut-off replies keeps its shape.
            if (t.cutOff) turn.put("cutOff", true)
            turns.put(turn)
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
                if (role.isEmpty()) {
                    null
                } else {
                    AiChatTurn(
                        role = role,
                        content = content,
                        ts = t.optLong("ts"),
                        failed = t.optBoolean("failed", false),
                        cutOff = t.optBoolean("cutOff", false),
                    )
                }
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

/** The wallet that owns the chats now, from the plain owner file (see [chatOwnerOf]). AI-KEY-S: the fence reads this too. */
internal fun chatOwnerOfPhone(context: Context): String? = chatOwnerOf { context.getSharedPreferences(it, Context.MODE_PRIVATE) }

/**
 * App-private store for generated image bytes. Files live in filesDir/ai_images (NOT cacheDir — these
 * must survive until the user deletes them). App-private + FLAG_SECURE'd screen; wiped by DestroyManager
 * along with the rest of filesDir.
 */
class AiImageStore(context: Context) {
    private val appContext = context.applicationContext

    // Lazy so the constructor stays cheap: filesDir access is a disk hit that must NOT run on the main thread when
    // AndroidAiTab builds this in composition. First access happens from AiTabVM on Dispatchers.IO ([prepare]).
    // ZC-718: the flat folder of 2.12.4; each wallet's pictures are in <flat>/<owner tag>/.
    private val flat by lazy { File(appContext.filesDir, "ai_images") }

    /**
     * ZC-718: the folder of the wallet that owns the chats now, worked out at EACH call (the owner can be written while
     * the AI tab is open): files/ai_images/<owner tag>/ for a wallet's tag, the flat folder while no owner is recorded
     * (an install from 2.12.4) or the owner says nobody. Call under [IO_LOCK].
     */
    private fun folder(): File {
        val owner = chatOwnerOfPhone(appContext)
        return if (owner != null && OWNER_TAG.matches(owner)) File(flat, owner) else flat
    }

    /**
     * ZC-718: the first read of the owner file, made off the main thread (AiTabVM's first read of the AI store).
     * ZC-1080: it never throws (AiTabVM runs it with no catch); an owner file that cannot be read now is read again by
     * the next call, whose own catch answers it.
     */
    @Suppress("TooGenericExceptionCaught")
    fun prepare() {
        try {
            synchronized(IO_LOCK) { folder() }
        } catch (e: Exception) {
            Log.w("AiImageStore", "the owner of the chats cannot be read yet (${e.javaClass.simpleName})")
        }
    }

    fun save(id: String, base64: String): Boolean {
        // Cap the (untrusted, API-supplied) base64 before decoding: Base64.decode allocates a buffer
        // proportional to input length, so a hostile multi-hundred-MB response would OOM the process
        // (and catching OutOfMemoryError after the heap is exhausted is unreliable). A real generated
        // PNG is far under this bound.
        if (base64.length > MAX_IMAGE_BASE64_LEN) {
            Log.w("AiImageStore", "rejecting oversized AI image payload (${base64.length} chars > $MAX_IMAGE_BASE64_LEN)")
            return false
        }
        return synchronized(IO_LOCK) {
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val dir = folder().apply { mkdirs() }
                File(dir, "$id.png").writeBytes(bytes)
                true
            }.onFailure { Log.w("AiImageStore", "failed to save AI image $id: ${it.message}") }
                .getOrDefault(false)
        }
    }

    fun loadBitmap(id: String): Bitmap? =
        synchronized(IO_LOCK) {
            runCatching {
                fileFor(id)?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }.getOrNull()
        }

    /**
     * ZC-718: the file picture [id] is read from, or null: this wallet's folder first, then the flat folder of 2.12.4,
     * never another wallet's folder.
     */
    fun fileFor(id: String): File? =
        synchronized(IO_LOCK) {
            val own = File(folder(), "$id.png")
            val old = File(flat, "$id.png")
            when {
                own.isFile -> own
                old.isFile -> old
                else -> null
            }
        }

    fun delete(id: String) {
        synchronized(IO_LOCK) {
            runCatching { fileFor(id)?.delete() }
        }
    }

    /** "Clear all images": this wallet's folder only (or the flat folder while no wallet owns the chats). */
    fun clearAll() {
        synchronized(IO_LOCK) {
            runCatching { folder().listFiles()?.filter { it.isFile }?.forEach { it.delete() } }
        }
    }

    /**
     * ZC-718: gives every flat picture (2.12.4's) to [owner]: moved into its folder. True when nothing is left flat.
     * Throws nothing a caller must handle: a picture that cannot be moved stays where it is and is read there.
     */
    fun moveFlatPicturesTo(owner: String): Boolean =
        synchronized(IO_LOCK) {
            if (!OWNER_TAG.matches(owner)) return@synchronized true
            val pictures = flat.listFiles()?.filter { it.isFile }.orEmpty()
            if (pictures.isEmpty()) return@synchronized true
            val dir = File(flat, owner)
            if (!dir.isDirectory && !dir.mkdirs()) return@synchronized false
            pictures.map { it.renameTo(File(dir, it.name)) }.all { it }
        }

    /**
     * ZC-718: the Reset's clear of [owner]'s pictures: its folder and every flat picture; another wallet's folder stays.
     * True when they are gone.
     */
    fun deleteWalletPictures(owner: String): Boolean =
        synchronized(IO_LOCK) {
            val folderGone = !OWNER_TAG.matches(owner) || File(flat, owner).let { !it.exists() || it.deleteRecursively() }
            val flatGone =
                flat
                    .listFiles()
                    ?.filter { it.isFile }
                    .orEmpty()
                    .map { it.delete() }
                    .all { it }
            folderGone && flatGone
        }

    /**
     * ZC-718: [tagger] (the kept chat copies' tagger) with one side step: when it tagged everything with an owner, the
     * flat pictures move into that owner's folder. A move that fails is logged and never changes the tagger's answer
     * (a failed rename must not stop wallet setup, a Reset or Choose Server); a picture left flat is read where it is.
     */
    @Suppress("TooGenericExceptionCaught")
    fun movingFlatPictures(tagger: UntaggedChatMarks): UntaggedChatMarks =
        UntaggedChatMarks { owner ->
            val tagged = tagger.tagUntaggedWith(owner)
            if (tagged) {
                val moved =
                    try {
                        moveFlatPicturesTo(owner)
                    } catch (e: Exception) {
                        false
                    }
                if (!moved) Log.w("AiImageStore", "Some AI pictures that name no wallet could not be moved; they stay where they are")
            }
            tagged
        }

    private companion object {
        /**
         * ZC-718: ONE lock for the whole process (it was one per object): save, load, delete, clear, the auto-delete
         * (which deletes through [delete]), the mover and the Reset's clear take it, whatever object they use.
         */
        val IO_LOCK = Any()

        /** A wallet's owner tag (chatOwnerTag: 64 hex characters). */
        val OWNER_TAG = Regex("[0-9a-f]{64}")

        // ~12M base64 chars ≈ a 9 MB decoded image — far above any real generated PNG, well below a
        // memory-exhausting payload.
        const val MAX_IMAGE_BASE64_LEN = 12_000_000
    }
}
