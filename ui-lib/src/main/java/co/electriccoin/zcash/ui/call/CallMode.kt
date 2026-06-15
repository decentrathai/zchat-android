package co.electriccoin.zcash.ui.call

/**
 * Audio-only vs audio+video call. Announced by the caller in the RING payload so the
 * callee's incoming UI can offer a video-answer before the OFFER SDP arrives.
 *
 * [fromWire] is deliberately lenient: an empty or unknown payload (a pre-video peer, or a
 * malformed signal) falls back to [AUDIO] — never a crash, never a silent upgrade to
 * video (which would open the camera unexpectedly).
 */
enum class CallMode(val wire: String) {
    AUDIO("audio"),
    VIDEO("video");

    val isVideo: Boolean get() = this == VIDEO

    companion object {
        fun fromWire(s: String): CallMode = entries.firstOrNull { it.wire == s } ?: AUDIO
    }
}
