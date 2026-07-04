package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.ui.screen.chat.filesharing.BitmapSampling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Phase-1 (LOCAL-ONLY) avatar store for all three avatar kinds:
 *
 *  - a CONTACT's avatar (the viewer's local override for any peer, keyed by canonical address),
 *  - the user's OWN (self) avatar,
 *  - a GROUP's avatar (editable in the UI only by the group admin/creator).
 *
 * Image bytes live as app-private files under `filesDir/avatars/`:
 * `contact_<sha256(canonicalAddress)>.jpg`, `self.jpg`, `group_<groupId>.jpg` — each downscaled to a
 * 256x256 center-cropped JPEG (target < ~50KB) via the same [BitmapSampling] math the file-sharing
 * pipeline uses. A small index (key -> filename + updatedAtMillis) sits in an
 * EncryptedSharedPreferences, mirroring the [ContactBookImpl] pattern: who you talk to (and whose
 * photo you saved) is sensitive metadata, so the index never touches plaintext XML.
 *
 * Recomposition: [version] is bumped on every successful mutation; Compose reads it (see
 * `ZchatAvatar`) so any avatar change invalidates all on-screen avatars cheaply.
 *
 * Wipe path: `ResetZashiUseCase` calls [clearAll] alongside the other zchat_* stores on a true
 * wallet delete; `DestroyManager` already nukes `filesDir` + every SharedPreferences file, which
 * covers both the image files and the encrypted index.
 *
 * TODO(Phase 2 — propagation, do NOT ship in Phase 1): broadcast the SELF avatar to peers over
 * NOSTR (profile-style event, received as the peer's default unless locally overridden), and carry
 * the GROUP avatar in a SIGNED GROUP_INFO control message (#187 per-member KEX-sign auth) so only
 * the admin can push it. Local overrides in this store must always win over propagated values.
 */
class AvatarStore(context: Context) {

    private val appContext = context.applicationContext

    // Keystore-backed store built lazily — construction stays cheap; first touch does the expensive
    // Keystore/keyset disk reads and MUST happen off the main thread (same contract as ContactBookImpl).
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _version = MutableStateFlow(0)

    /** Bumped on every successful avatar mutation so Compose can recompose stored avatars. */
    val version: StateFlow<Int> = _version.asStateFlow()

    // ---- Contact avatars (viewer-local override for ANY peer) ----

    fun setContactAvatar(address: String, bytes: ByteArray): Boolean =
        setAvatar(contactKey(address), contactFileName(address), bytes)

    fun getContactAvatar(address: String): ByteArray? = getAvatar(contactKey(address))

    fun removeContactAvatar(address: String) = removeAvatar(contactKey(address))

    // ---- Self avatar ----

    fun setSelfAvatar(bytes: ByteArray): Boolean = setAvatar(KEY_SELF, SELF_FILE_NAME, bytes)

    fun getSelfAvatar(): ByteArray? = getAvatar(KEY_SELF)

    fun removeSelfAvatar() = removeAvatar(KEY_SELF)

    // ---- Group avatars (UI restricts editing to the group admin) ----

    fun setGroupAvatar(groupId: String, bytes: ByteArray): Boolean =
        setAvatar(groupKey(groupId), groupFileName(groupId), bytes)

    fun getGroupAvatar(groupId: String): ByteArray? = getAvatar(groupKey(groupId))

    fun removeGroupAvatar(groupId: String) = removeAvatar(groupKey(groupId))

    // ---- Wipe ----

    /**
     * Removes every stored avatar + the encrypted index. commit() (not apply()) — mirrors
     * ContactBookImpl.clearAll(): a wallet reset may kill the process right after, and the data must
     * be gone on disk before that, not pending an async flush.
     */
    fun clearAll() {
        runCatching { prefs.edit().clear().commit() }
        runCatching { avatarsDir().deleteRecursively() }
        _version.value += 1
    }

    // ---- Internals ----

    private fun setAvatar(key: String, fileName: String, bytes: ByteArray): Boolean {
        val jpeg = downscaleToAvatarJpeg(bytes) ?: return false
        return try {
            val file = File(avatarsDir(), fileName)
            // Write via a temp file + rename so a mid-write crash can't leave a truncated avatar
            // that the index claims is valid.
            val tmp = File(avatarsDir(), "$fileName.tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(file)) {
                // Cross-check: renameTo can fail on some filesystems — fall back to a direct write.
                file.writeBytes(jpeg)
                tmp.delete()
            }
            val entry = JSONObject().apply {
                put(FIELD_FILE, fileName)
                put(FIELD_UPDATED_AT, System.currentTimeMillis())
            }
            prefs.edit().putString(key, entry.toString()).apply()
            _version.value += 1
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getAvatar(key: String): ByteArray? =
        try {
            val raw = prefs.getString(key, null)
            if (raw == null) {
                null
            } else {
                val fileName = JSONObject(raw).getString(FIELD_FILE)
                val file = File(avatarsDir(), fileName)
                if (file.isFile) file.readBytes() else null
            }
        } catch (_: Exception) {
            // A reset/corrupted keyset or a missing file degrades to "no avatar" (placeholder),
            // never a crash in the chat list.
            null
        }

    private fun removeAvatar(key: String) {
        try {
            prefs.getString(key, null)?.let { raw ->
                runCatching { File(avatarsDir(), JSONObject(raw).getString(FIELD_FILE)).delete() }
            }
            prefs.edit().remove(key).apply()
        } catch (_: Exception) {
            // Best-effort removal; placeholder rendering handles any leftover state.
        }
        _version.value += 1
    }

    private fun avatarsDir(): File = File(appContext.filesDir, AVATARS_DIR).apply { mkdirs() }

    // Zcash bech32m addresses are canonically lowercase — same canonical key as ContactBookImpl so
    // an avatar saved from one surface (chat list) is found from another (compose picker).
    private fun contactKey(address: String) = "contact_${sha256Hex(address.trim().lowercase())}"

    private fun contactFileName(address: String) = "${contactKey(address)}.jpg"

    private fun groupKey(groupId: String) = "group_${sanitizeId(groupId)}"

    private fun groupFileName(groupId: String) = "${groupKey(groupId)}.jpg"

    /**
     * Group IDs are "zgrp_<hex>" today (filesystem-safe), but defend against any future/legacy id
     * containing path characters: anything outside [A-Za-z0-9_-] (or overly long) is hashed instead.
     */
    private fun sanitizeId(id: String): String =
        if (id.length in 1..MAX_RAW_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            id
        } else {
            sha256Hex(id)
        }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Center-crop to square + downscale to [AVATAR_SIZE_PX] and JPEG-compress, stepping the quality
     * down until the result fits [MAX_AVATAR_BYTES]. Sampling math reuses [BitmapSampling] (the same
     * helper the file-sharing image path uses) so the initial decode never inflates a huge source.
     */
    private fun downscaleToAvatarJpeg(bytes: ByteArray): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = BitmapSampling.calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                reqPx = AVATAR_SIZE_PX,
            ) ?: return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val side = minOf(decoded.width, decoded.height)
            if (side <= 0) {
                decoded.recycle()
                return null
            }
            val square = Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2,
                (decoded.height - side) / 2,
                side,
                side,
            )
            val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true)
            var quality = JPEG_QUALITY_START
            var out: ByteArray
            do {
                val buffer = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
                out = buffer.toByteArray()
                quality -= JPEG_QUALITY_STEP
            } while (out.size > MAX_AVATAR_BYTES && quality >= JPEG_QUALITY_MIN)
            if (scaled !== square) square.recycle()
            if (square !== decoded && decoded !== scaled) decoded.recycle()
            scaled.recycle()
            out
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "zchat_avatar_index_enc"
        private const val AVATARS_DIR = "avatars"
        private const val KEY_SELF = "self"
        private const val SELF_FILE_NAME = "self.jpg"
        private const val FIELD_FILE = "file"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val MAX_RAW_ID_LENGTH = 64

        /** Stored avatar edge (square). */
        const val AVATAR_SIZE_PX: Int = 256

        /** Soft size target for a stored avatar file. */
        const val MAX_AVATAR_BYTES: Int = 50_000

        private const val JPEG_QUALITY_START = 85
        private const val JPEG_QUALITY_STEP = 15
        private const val JPEG_QUALITY_MIN = 40
    }
}
