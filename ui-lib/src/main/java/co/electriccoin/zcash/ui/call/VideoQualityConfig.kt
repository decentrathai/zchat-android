package co.electriccoin.zcash.ui.call

import org.webrtc.RtpParameters
import kotlin.math.abs

/**
 * ZC-1470: what a video call sends. It was 640x480 (4:3) with WebRTC's start bitrate, BALANCED degradation and VP8
 * first, which looked soft and was stretched over a 20:9 screen. Now: 1280x720 (16:9) at 30 fps, or the camera's
 * supported 16:9 size nearest to it; up to 2 Mbps; resolution kept under pressure (frame rate drops first); hardware
 * H264 first, VP8 kept for any phone without it. No signalling change, so a 2.12.4 peer is unaffected.
 */
object VideoQualityConfig {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
    const val MAX_BITRATE_BPS = 2_000_000

    /** How often a video call logs its real resolution, frame rate and limitation reason. */
    const val STATS_INTERVAL_MS = 10_000L

    @JvmField
    val DEGRADATION_PREFERENCE: RtpParameters.DegradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

    /** Codec names in the order the video transceiver prefers them; codecs not named here keep their order after these. */
    @JvmField
    val PREFERRED_CODECS: List<String> = listOf("H264", "VP8")

    /**
     * The capture size: among the camera's [supported] sizes (width to height), the 16:9 one whose pixel count is
     * nearest to [WIDTH] x [HEIGHT]; with no 16:9 size, the nearest of all; with none reported, [WIDTH] x [HEIGHT].
     */
    fun pickCaptureSize(supported: List<Pair<Int, Int>>): Pair<Int, Int> {
        val valid = supported.filter { (w, h) -> w > 0 && h > 0 }
        if (valid.isEmpty()) return WIDTH to HEIGHT
        val wide = valid.filter { (w, h) -> w.toLong() * HEIGHT == h.toLong() * WIDTH }
        val target = WIDTH.toLong() * HEIGHT
        return (wide.ifEmpty { valid }).minBy { (w, h) -> abs(w.toLong() * h - target) }
    }
}
