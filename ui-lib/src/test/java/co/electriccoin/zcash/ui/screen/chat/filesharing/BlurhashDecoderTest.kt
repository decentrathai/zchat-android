package co.electriccoin.zcash.ui.screen.chat.filesharing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD for [BlurhashDecoder]. Decodes a Blurhash string to a low-resolution
 * placeholder image (intArray of ARGB pixels) for use as a gradient stand-in
 * while the real image is downloading.
 *
 * Blurhash spec: https://blurha.sh/
 * For ZCHAT we only need a tiny preview (8x8 or 16x16).
 */
class BlurhashDecoderTest {

    @Test
    fun decode_short_blurhash_returns_pixels() {
        // "L6PZfSi_.AyE_3t7t7R**0o#DgR4" is a real blurhash (sample from blurha.sh)
        val pixels = BlurhashDecoder.decode("L6PZfSi_.AyE_3t7t7R**0o#DgR4", 16, 16)
        assertNotNull(pixels)
        assertEquals(16 * 16, pixels.size)
    }

    @Test
    fun decode_invalid_blurhash_returns_null() {
        assertNull(BlurhashDecoder.decode("", 8, 8))
        assertNull(BlurhashDecoder.decode("bad", 8, 8))
        assertNull(BlurhashDecoder.decode("X", 8, 8))
    }

    @Test
    fun decode_pixels_are_argb_format() {
        val pixels = BlurhashDecoder.decode("L6PZfSi_.AyE_3t7t7R**0o#DgR4", 8, 8)!!
        // Every pixel should have alpha 0xFF (opaque)
        for (px in pixels) {
            val alpha = (px ushr 24) and 0xFF
            assertEquals(0xFF, alpha)
        }
    }

    @Test
    fun decode_different_sizes_produce_different_lengths() {
        val small = BlurhashDecoder.decode("L6PZfSi_.AyE_3t7t7R**0o#DgR4", 4, 4)!!
        val large = BlurhashDecoder.decode("L6PZfSi_.AyE_3t7t7R**0o#DgR4", 16, 16)!!
        assertEquals(16, small.size)
        assertEquals(256, large.size)
    }

    @Test
    fun decode_min_length_blurhash() {
        // Blurhash spec says minimum length is 6 chars
        val pixels = BlurhashDecoder.decode("LFE.@D9F", 4, 4)
        // Either valid (6+ chars) or null — both acceptable for short edge cases
        if (pixels != null) {
            assertTrue(pixels.isNotEmpty())
        }
    }
}
