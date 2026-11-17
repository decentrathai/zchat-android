package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 10b R10B-3 (F-R9C-2, two-phone checks 2026-09-25) — a photo received while its chat is open stays a file row.
 *
 * The file bubble decodes its inline image in a LaunchedEffect keyed on message.fileHash alone, so it runs once, when
 * the row first appears. A photo received with the chat open appears before its download ends (Seeker log:
 * 'Downloading' 11:33:51.278, 'Decrypted … caching' 11:33:51.773): the cache file is missing on that one run and
 * nothing runs the decode again when downloadAndCacheFile writes it. Photos are sent with an empty blurhash, so the
 * cache-miss branch draws the «📎 Image (…)» file row until the chat is reopened.
 *
 * Wanted: the inline decode runs again when the transfer state changes (progress ticks, is cleared when the download
 * ends, or the failure flag flips), as the full-screen viewer's decode already does. The screen has no Compose test
 * runtime here, so this reads the effect's keys from ChatDetailView's source.
 */
class LivePhotoDrawsInlineTest {
    private val view: String by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
    }

    /** The keys of the last LaunchedEffect before the file bubble's inline decode (the 800 px one on the cache file). */
    private fun inlineDecodeKeys(): List<String> {
        val bubble = view.indexOf("java.io.File(context.cacheDir, \"zchat_files/\${message.fileHash}\")")
        if (bubble < 0) fail("ChatDetailView no longer builds the file bubble's cache file from message.fileHash")
        val decode = view.indexOf("decodeSampledBitmap(cacheFile.absolutePath, reqPx = 800)", bubble)
        if (decode < 0) fail("the file bubble no longer decodes its cache file inline")
        val effect = view.lastIndexOf("LaunchedEffect(", decode)
        if (effect < bubble) fail("the file bubble's inline decode is no longer inside a LaunchedEffect")
        val open = effect + "LaunchedEffect(".length
        val close = view.indexOf(')', open)
        return view
            .substring(open, close)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun the_inline_photo_is_decoded_again_when_its_download_ends() {
        val keys = inlineDecodeKeys()
        assertTrue("message.fileHash" in keys, "the inline decode is no longer keyed on the file: $keys")
        assertTrue(
            "bubbleProgress" in keys,
            "the inline decode runs once, when the row appears; a photo that lands while the chat is open stays a " +
                "file row until the chat is reopened (keys: $keys)",
        )
        assertTrue(
            "downloadFailed" in keys,
            "the inline decode does not run again when a failed download is retried and lands (keys: $keys)",
        )
    }

    @Test
    fun the_bitmap_is_still_released_only_when_the_row_changes_file() {
        val bubble = view.indexOf("java.io.File(context.cacheDir, \"zchat_files/\${message.fileHash}\")")
        val decode = view.indexOf("decodeSampledBitmap(cacheFile.absolutePath, reqPx = 800)", bubble)
        val dispose = view.indexOf("DisposableEffect(", decode)
        assertTrue(dispose > decode, "the file bubble no longer releases its bitmap")
        assertTrue(
            view.startsWith("DisposableEffect(message.fileHash)", dispose),
            "the file bubble's bitmap release must stay keyed on the file alone",
        )
    }
}
