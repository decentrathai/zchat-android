package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import co.electriccoin.zcash.ui.screen.chat.filesharing.BitmapSampling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64

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

    // ZC-733-A: the holder opens the index through the one opener (first touch off the main thread, same contract as
    // ContactBookImpl). No public function here throws its error: a read that fails answers "no picture".
    private val store = LazyEncryptedStore(appContext, PREFS_NAME)

    // Lazy over the holder: the first successful open is kept; a failed one throws the holder's error each time.
    private val prefs: SharedPreferences by lazy { store.get() }

    /** ZC-733-B: true while the picture index cannot be opened (its holder's flag): the pictures line is shown. */
    val unavailable: StateFlow<Boolean> get() = store.unavailable

    /** ZC-733-A: the start-up check's ask (counted, may set the index aside with a copy); its caller catches. */
    fun askAtStart() {
        store.askAtStart()
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

    fun setSelfAvatar(bytes: ByteArray): Boolean {
        val ok = setAvatar(KEY_SELF, SELF_FILE_NAME, bytes)
        // A fresh photo supersedes any pending removal tombstone.
        if (ok) runCatching { prefs.edit().remove(KEY_SELF_REMOVED_AT).apply() }
        return ok
    }

    fun getSelfAvatar(): ByteArray? = getAvatar(KEY_SELF)

    fun removeSelfAvatar() {
        removeAvatar(KEY_SELF)
        // Record a tombstone so the removal has a monotonic "version" to broadcast + dedup against each
        // contact's delivered version (SR-B1; shared with the set path; set clears this, remove clears the entry).
        runCatching { prefs.edit().putLong(KEY_SELF_REMOVED_AT, System.currentTimeMillis()).apply() }
    }

    /** Tombstone version (ms) of a pending self-avatar removal, or null if a photo is set / never removed. */
    fun selfAvatarRemovedAt(): Long? =
        try {
            if (hasSelfAvatar()) null else prefs.getLong(KEY_SELF_REMOVED_AT, 0L).takeIf { it > 0L }
        } catch (_: Exception) {
            // ZC-733-A: an index that cannot be read has no pending removal to send (the launch tick sends nothing).
            null
        }

    // ---- Group avatars (UI restricts editing to the group admin) ----

    fun setGroupAvatar(groupId: String, bytes: ByteArray): Boolean =
        setAvatar(groupKey(groupId), groupFileName(groupId), bytes)

    fun getGroupAvatar(groupId: String): ByteArray? = getAvatar(groupKey(groupId))

    fun removeGroupAvatar(groupId: String) = removeAvatar(groupKey(groupId))

    // ---- Presence checks (cheap — read the index only, never decode the image bytes) ----
    // Used by the photo chooser to show a "Set up photo" state when nothing is set yet, instead of
    // offering Change/Remove for a photo that doesn't exist.

    fun hasSelfAvatar(): Boolean = hasAvatar(KEY_SELF)

    fun hasContactAvatar(address: String): Boolean = hasAvatar(contactKey(address))

    fun hasGroupAvatar(groupId: String): Boolean = hasAvatar(groupKey(groupId))

    // ---- Self-avatar broadcast tracking (process-death resilience for ZPROF propagation) ----
    // A picker-triggered broadcast can be lost if the OS kills the app while the image picker is open
    // (Honor/Huawei aggressive memory management). We compare the self avatar's updatedAt "version"
    // against the version each contact was delivered (SR-B1), so a lost send retries on next launch and a
    // contact skipped while its chat was Shielded is sent it once the chat leaves Shielded.

    /** updatedAt (ms) of the current self avatar, or null if none is set. Cheap version for the gate. */
    fun selfAvatarUpdatedAt(): Long? =
        try {
            prefs.getString(KEY_SELF, null)?.let {
                JSONObject(it).optLong(FIELD_UPDATED_AT, 0L).takeIf { t -> t > 0L }
            }
        } catch (_: Exception) {
            null
        }

    fun getLastBroadcastSelfAt(): Long =
        try {
            prefs.getLong(KEY_LAST_BROADCAST_SELF_AT, 0L)
        } catch (_: Exception) {
            // ZC-733-A: an index that cannot be read answers "never sent" and the caller writes nothing on it.
            0L
        }

    fun setLastBroadcastSelfAt(updatedAt: Long) {
        runCatching { prefs.edit().putLong(KEY_LAST_BROADCAST_SELF_AT, updatedAt).apply() }
    }

    /**
     * SR-B1 — the self-avatar version (a set's [selfAvatarUpdatedAt] or a removal's [selfAvatarRemovedAt]) that a relay
     * took for [peerAddress], or 0 when none did. This, not [getLastBroadcastSelfAt], decides whether a contact is sent
     * the avatar: a contact whose chat is Shielded is skipped and stays behind until its chat leaves Shielded.
     */
    fun getDeliveredSelfVersion(peerAddress: String): Long = runCatching { prefs.getLong(deliveredKey(peerAddress), 0L) }.getOrDefault(0L)

    fun setDeliveredSelfVersion(
        peerAddress: String,
        version: Long,
    ) {
        runCatching { prefs.edit().putLong(deliveredKey(peerAddress), version).apply() }
    }

    /**
     * SR-B1 upgrade: once, marks [peers] (the contacts that are not Shielded, with a relay key) as having the version
     * the old global marker records, so the first launch after the upgrade re-sends nothing in bulk. A later call is a
     * no-op. With no marker (nothing was ever delivered) nothing is seeded.
     */
    fun seedDeliveredSelfVersionsOnce(peers: Collection<String>) {
        runCatching {
            if (prefs.getBoolean(KEY_DELIVERED_SEEDED, false)) return
            val last = getLastBroadcastSelfAt()
            val edit = prefs.edit()
            if (last > 0L) {
                peers.forEach { peer ->
                    if (prefs.getLong(deliveredKey(peer), 0L) == 0L) edit.putLong(deliveredKey(peer), last)
                }
            }
            edit.putBoolean(KEY_DELIVERED_SEEDED, true).apply()
        }
    }

    private fun deliveredKey(address: String) = "self_delivered_${sha256Hex(address.trim().lowercase())}"

    // ---- Wipe ----

    /**
     * Removes every stored avatar + the encrypted index. commit() (not apply()) — mirrors
     * ContactBookImpl.clearAll(): a wallet reset may kill the process right after, and the data must
     * be gone on disk before that, not pending an async flush.
     */
    fun clearAll() {
        // ZC-733-B: through the holder: an open index is cleared, one that is not open is erased as a file.
        runCatching { store.wipe() }
        runCatching { avatarsDir().deleteRecursively() }
        _version.value += 1
    }

    /**
     * P2e: the avatar index and the image files, as stores that move with the wallet that owns them.
     * Files travel as base64 entries (file name -> bytes); putting them back replaces the whole folder.
     */
    fun walletScopedStores(): List<WalletScopedStore> =
        listOf(
            store.walletStore(PREFS_NAME) { prefs },
            object : WalletScopedStore {
                override val storeId = AVATAR_FILES_STORE_ID

                override fun exportEntries(): Map<String, Any?> =
                    avatarsDir()
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && !it.name.endsWith(".tmp") }
                        .associate { it.name to BASE64_ENCODER.encodeToString(it.readBytes()) }

                override fun importEntries(entries: Map<String, Any?>) {
                    val dir = avatarsDir()
                    dir.listFiles().orEmpty().forEach { check(it.deleteRecursively()) { "Avatar ${it.name} could not be removed" } }
                    entries.forEach { (name, value) ->
                        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "Bad avatar file name" }
                        File(dir, name).writeBytes(BASE64_DECODER.decode(value as String))
                    }
                }
            },
        )

    /** P2e: the stores were rewritten — redraw every avatar. */
    fun reloadWalletScopedStores() {
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

    /** Presence check that mirrors getAvatar's null-on-missing-file semantics without reading bytes. */
    private fun hasAvatar(key: String): Boolean =
        try {
            val raw = prefs.getString(key, null)
            raw != null && File(avatarsDir(), JSONObject(raw).getString(FIELD_FILE)).isFile
        } catch (_: Exception) {
            false
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
        internal const val PREFS_NAME = "zchat_avatar_index_enc"
        private const val AVATARS_DIR = "avatars"
        private const val AVATAR_FILES_STORE_ID = "zchat_avatar_files"
        private val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
        private val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()
        private const val KEY_SELF = "self"
        private const val SELF_FILE_NAME = "self.jpg"
        private const val FIELD_FILE = "file"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val KEY_LAST_BROADCAST_SELF_AT = "self_last_broadcast_at"
        private const val KEY_SELF_REMOVED_AT = "self_removed_at"
        private const val KEY_DELIVERED_SEEDED = "self_delivered_seeded"
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
