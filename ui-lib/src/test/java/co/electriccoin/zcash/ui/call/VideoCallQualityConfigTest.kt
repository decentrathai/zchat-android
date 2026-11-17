package co.electriccoin.zcash.ui.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1470 (OT213n B3; owner findings 2 Nov, point 10: "The quality is very bad. Of the video"). At 669cf19e1 the camera
 * captures 640x480 (4:3 VGA) at 30 fps (VoiceCallManager VIDEO_WIDTH / VIDEO_HEIGHT / VIDEO_FPS), with no
 * maxBitrateBps, no degradationPreference and no codec preference, so WebRTC starts near 300 kbps with VP8 and BALANCED
 * degradation, and the 4:3 picture fills a 20:9 screen (lanes/L4_calls.md).
 *
 * Wanted (FIX_PLAN REVISION 1, B3), a pure `object VideoQualityConfig` in `co.electriccoin.zcash.ui.call`:
 * - WIDTH 1280, HEIGHT 720 (16:9), FPS 30;
 * - MAX_BITRATE_BPS about 2 Mbps (1.5 to 2.5 Mbps);
 * - DEGRADATION_PREFERENCE named MAINTAIN_RESOLUTION (org.webrtc's enum or an own one);
 * - PREFERRED_CODECS: a list of codec names, "H264" first, "VP8" still in it (a 2.12.4 peer shares the same factory);
 * - `fun pickCaptureSize(supported: List<Pair<Int, Int>>): Pair<Int, Int>`: the supported 16:9 size nearest 1280x720,
 *   1280x720 when the camera reports nothing;
 * and VoiceCallManager uses it: no 640x480 constant left, VideoQualityConfig read, maxBitrateBps and
 * degradationPreference set on the video sender, setCodecPreferences called, a getStats probe logged.
 *
 * Reached by name, so this class compiles before the fix. Whether the picture looks better is a two-phone check.
 */
class VideoCallQualityConfigTest {
    private val config: Pair<Class<*>, Any> by lazy {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.call.VideoQualityConfig") }.getOrNull()
                ?: fail("ZC-1470: VideoQualityConfig does not exist; video is 640x480 with WebRTC's default bitrate")
        val instance: Any = cls.getField("INSTANCE").get(null) ?: fail("VideoQualityConfig must be a Kotlin object")
        cls to instance
    }

    /** A `const val` / `@JvmField` (static field) or a plain `val` (getter on INSTANCE). */
    private fun value(name: String): Any? {
        val (cls, instance) = config
        runCatching { return cls.getField(name).get(null) }
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        return runCatching { cls.getMethod(getter).invoke(instance) }.getOrElse { fail("ZC-1470: VideoQualityConfig.$name is missing") }
    }

    private fun number(name: String): Long = (value(name) as? Number)?.toLong() ?: fail("ZC-1470: VideoQualityConfig.$name is not a number")

    @Suppress("UNCHECKED_CAST")
    private fun pick(vararg sizes: Pair<Int, Int>): Pair<Int, Int> {
        val (cls, instance) = config
        val method = cls.getMethod("pickCaptureSize", List::class.java)
        return method.invoke(instance, sizes.toList()) as Pair<Int, Int>
    }

    @Test
    fun `capture is 720p at 30 fps in 16 to 9`() {
        assertEquals(1280L, number("WIDTH"), "ZC-1470: capture width")
        assertEquals(720L, number("HEIGHT"), "ZC-1470: capture height")
        assertEquals(30L, number("FPS"), "ZC-1470: capture frame rate")
        assertEquals(number("WIDTH") * 9, number("HEIGHT") * 16, "ZC-1470: the capture must be 16:9")
    }

    @Test
    fun `the encoder may use about 2 Mbps`() {
        val max = number("MAX_BITRATE_BPS")
        assertTrue(max in 1_500_000L..2_500_000L, "ZC-1470: MAX_BITRATE_BPS must be about 2 Mbps, was $max")
    }

    @Test
    fun `resolution is kept under pressure`() {
        val pref = value("DEGRADATION_PREFERENCE") ?: fail("ZC-1470: DEGRADATION_PREFERENCE is null")
        val name = (pref as? Enum<*>)?.name ?: pref.toString()
        assertEquals("MAINTAIN_RESOLUTION", name, "ZC-1470: degradation preference")
    }

    @Test
    fun `H264 is preferred and VP8 stays available`() {
        val codecs = (value("PREFERRED_CODECS") as? List<*>)?.map { it.toString() } ?: fail("ZC-1470: PREFERRED_CODECS is not a list")
        assertEquals("H264", codecs.firstOrNull(), "ZC-1470: H264 (hardware) must be the first codec")
        assertTrue("VP8" in codecs, "ZC-1470: VP8 must stay in the list")
    }

    @Test
    fun `the nearest supported 16 to 9 size is captured`() {
        assertEquals(1280 to 720, pick(640 to 480, 1280 to 720, 1920 to 1080), "720p when the camera has it")
        assertEquals(960 to 540, pick(640 to 480, 960 to 540, 1920 to 1080), "the 16:9 size nearest 720p")
        assertEquals(1920 to 1080, pick(640 to 480, 1280 to 960, 1920 to 1080), "the only 16:9 size")
        assertEquals(1280 to 720, pick(), "1280x720 when the camera reports nothing")
    }

    @Test
    fun `the call manager uses the config`() {
        val manager = callDir.resolve("VoiceCallManager.kt").readText()
        assertTrue(!Regex("""VIDEO_WIDTH\s*=\s*640""").containsMatchIn(manager), "ZC-1470: VoiceCallManager still captures 640 wide")
        assertTrue("VideoQualityConfig." in manager, "ZC-1470: VoiceCallManager must read VideoQualityConfig")
        assertTrue("maxBitrateBps" in manager, "ZC-1470: the video sender must get maxBitrateBps")
        assertTrue("degradationPreference" in manager, "ZC-1470: the video sender must get a degradationPreference")
        assertTrue("setCodecPreferences" in manager, "ZC-1470: the video transceiver must prefer H264")
        assertTrue("getStats" in manager, "ZC-1470: a stats probe must log the real resolution and fps")
    }

    private val callDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                val candidate = module.resolve("src/main/java/co/electriccoin/zcash/ui/call")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = current.parentFile
        }
        fail("could not locate ui-lib's call package from ${start.path}")
    }
}
