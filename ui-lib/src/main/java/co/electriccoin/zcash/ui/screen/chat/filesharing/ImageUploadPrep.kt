package co.electriccoin.zcash.ui.screen.chat.filesharing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Prepares a picked image for upload without ever loading the full source into the JVM heap.
 *
 * Why this helper exists:
 *   The previous flow did `inputStream.readBytes()` then `BitmapFactory.decodeByteArray(...)` on
 *   the whole blob. A 50MP HEIC source can be 30-200 MB; readBytes() OOMs before any decode.
 *
 * Strategy:
 *   1. Query the content provider for the file size (if available). Reject above [maxSourceBytes].
 *   2. Open the URI a first time with `inJustDecodeBounds = true` — reads metadata only.
 *   3. Compute [BitmapSampling.calculateInSampleSize] against [targetEdgePx].
 *   4. Open the URI a second time with the chosen `inSampleSize` to produce a downscaled bitmap.
 *   5. JPEG-compress at [jpegQuality] (memory-bounded by the now-downscaled bitmap, not source).
 *   6. Recycle the bitmap aggressively.
 *
 * For sources below [compressBytesThreshold], read straight through without re-decoding.
 */
object ImageUploadPrep {

    const val DEFAULT_TARGET_EDGE_PX = 1920          // Reasonable max for chat-shared images
    const val DEFAULT_JPEG_QUALITY = 80
    const val DEFAULT_COMPRESS_THRESHOLD_BYTES = 500_000L
    const val DEFAULT_MAX_SOURCE_BYTES = 100_000_000L // 100 MB hard cap on source file

    /**
     * @return the bytes ready to encrypt + upload, or null if the source is unreadable / oversized.
     */
    fun prepare(
        contentResolver: ContentResolver,
        uri: Uri,
        targetEdgePx: Int = DEFAULT_TARGET_EDGE_PX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
        compressBytesThreshold: Long = DEFAULT_COMPRESS_THRESHOLD_BYTES,
        maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES,
    ): ByteArray? {
        val sourceSize = querySize(contentResolver, uri)
        Log.d(TAG, "prepare: uri=$uri sourceSize=$sourceSize")
        // Treat an UNKNOWN size (querySize returned null — provider didn't report SIZE) as OVER the
        // limit: blindly decoding a file whose size we can't verify risks OOM on an oversized source.
        // ?: (maxSourceBytes + 1) forces rejection when the size is null.
        if ((sourceSize ?: (maxSourceBytes + 1)) > maxSourceBytes) {
            Log.w(TAG, "reject: sourceSize=$sourceSize > max $maxSourceBytes (unknown size treated as over-limit)")
            return null
        }

        if (sourceSize != null && sourceSize <= compressBytesThreshold) {
            return contentResolver.openInputStream(uri)?.use { it.readBytes() }.also {
                if (it == null) Log.w(TAG, "reject: openInputStream returned null (small-file path)")
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri)
        if (boundsStream == null) {
            Log.w(TAG, "reject: openInputStream returned null (bounds path)")
            return null
        }
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "reject: bounds decode failed (w=${bounds.outWidth} h=${bounds.outHeight} mime=${bounds.outMimeType})")
            return null
        }

        val sample = BitmapSampling.calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            reqPx = targetEdgePx,
        ) ?: run {
            Log.w(TAG, "reject: sample calc returned null")
            return null
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
        if (decoded == null) {
            Log.w(TAG, "reject: sampled decode returned null (sample=$sample)")
            return null
        }

        return try {
            val out = ByteArrayOutputStream()
            decoded.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        } finally {
            decoded.recycle()
        }
    }

    private const val TAG = "ZCHAT_FILE"

    private fun querySize(contentResolver: ContentResolver, uri: Uri): Long? =
        runCatching {
            // A file:// URI (e.g. the share-sheet / forward path builds Uri.fromFile() from our own cache
            // copy) has no content provider, so ContentResolver.query() returns null → the caller would
            // treat the unknown size as over-limit and reject EVERY shared image. Resolve the size from
            // the file directly instead.
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                uri.path?.let { File(it).length() }?.takeIf { it > 0 }
            } else {
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx < 0 || cursor.isNull(idx)) null else cursor.getLong(idx)
                }
            }
        }.getOrNull()
}
