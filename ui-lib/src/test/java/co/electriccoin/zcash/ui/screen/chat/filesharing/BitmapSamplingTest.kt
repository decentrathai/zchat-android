package co.electriccoin.zcash.ui.screen.chat.filesharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitmapSamplingTest {

    @Test
    fun `returns null for invalid dimensions`() {
        assertNull(BitmapSampling.calculateInSampleSize(0, 0, 1024))
        assertNull(BitmapSampling.calculateInSampleSize(-1, 100, 1024))
        assertNull(BitmapSampling.calculateInSampleSize(100, 0, 1024))
    }

    @Test
    fun `sample is 1 when image already smaller than reqPx`() {
        assertEquals(1, BitmapSampling.calculateInSampleSize(512, 512, 1024))
        assertEquals(1, BitmapSampling.calculateInSampleSize(100, 2000, 2048))
    }

    @Test
    fun `sample is 1 when image is slightly larger than reqPx`() {
        // 1025 > 1024 but halving to 512 goes below reqPx, so keep at full res.
        assertEquals(1, BitmapSampling.calculateInSampleSize(1025, 1025, 1024))
    }

    @Test
    fun `sample is 2 for exactly double reqPx`() {
        assertEquals(2, BitmapSampling.calculateInSampleSize(2048, 2048, 1024))
    }

    @Test
    fun `sample scales to power of two for large images`() {
        // 4000 / 2 = 2000 >= 1024 → sample=2, / 2 = 1000 < 1024 → stop
        assertEquals(2, BitmapSampling.calculateInSampleSize(4000, 4000, 1024))
        // 4096 / 2 = 2048 >= 1024 → sample=2, / 2 = 1024 >= 1024 → sample=4, / 2 = 512 < 1024 → stop
        assertEquals(4, BitmapSampling.calculateInSampleSize(4096, 4096, 1024))
    }

    @Test
    fun `sample is bounded by the smaller edge`() {
        // Wide image: 8000x1000. halfW=4000, halfH=500. Loop condition uses AND, so both must be >= reqPx.
        // For reqPx=512: halfH/1=500 < 512, loop doesn't run → sample=1.
        assertEquals(1, BitmapSampling.calculateInSampleSize(8000, 1000, 512))
    }

    @Test
    fun `sample is always a power of two`() {
        val cases = listOf(
            Triple(3000, 4500, 800),
            Triple(1024, 768, 400),
            Triple(5000, 5000, 1024),
        )
        for ((w, h, req) in cases) {
            val s = BitmapSampling.calculateInSampleSize(w, h, req)!!
            assertTrue(s > 0 && (s and (s - 1)) == 0, "sample=$s not a power of two for w=$w h=$h req=$req")
        }
    }
}
