package co.electriccoin.zcash.ui.call

import java.security.SecureRandom

/**
 * Wire format for WebRTC signalling delivered over NIP-17 DMs.
 *
 *     ZCALL|v1|<callId>|<type>|<payload>
 *
 * See [CallSignalEnvelopeTest] for shape and edge-case expectations.
 */
enum class CallSignalType(val code: String) {
    OFFER("OFFER"),
    ANSWER("ANSWER"),
    ICE("ICE"),
    HANGUP("HANGUP"),
    RING("RING");

    companion object {
        fun fromCode(code: String): CallSignalType? = entries.find { it.code == code }
    }
}

data class CallSignalEnvelope(
    val callId: String,
    val type: CallSignalType,
    val payload: String,
) {
    init {
        require(CALL_ID_PATTERN.matches(callId)) { "callId must be $CALL_ID_LEN lowercase hex chars" }
    }

    fun serialize(): String = "$PREFIX$callId|${type.code}|$payload"

    companion object {
        const val PREFIX = "ZCALL|v1|"

        // Hard upper bound on the signal payload. An OFFER/ANSWER carries an SDP blob and (unlike
        // ICE, which VoiceCallManager caps at MAX_ICE_PAYLOAD=1024) had NO size limit — a contact
        // could send a multi-megabyte SDP to OOM/ANR the WebRTC native layer with no user
        // interaction. Real SDP is a few KB even with many codecs/candidates; 64KB is generous
        // headroom while shutting the resource-exhaustion door. Applied to every type at parse time.
        const val MAX_SDP_PAYLOAD = 64 * 1024

        // 128 bits of CSPRNG entropy — overkill for a per-call nonce but cheap, and the
        // critic flagged the prior 64-bit value as guessable for an attacker who knows
        // the rough window of an active call.
        private const val CALL_ID_LEN = 32
        private val CALL_ID_PATTERN = Regex("^[0-9a-f]{$CALL_ID_LEN}$")
        private val RNG: SecureRandom = SecureRandom()

        fun isSignal(content: String): Boolean = content.startsWith(PREFIX)

        fun parse(raw: String): CallSignalEnvelope? {
            if (!raw.startsWith(PREFIX)) return null
            val body = raw.removePrefix(PREFIX)
            // body = "<callId>|<type>|<payload-with-arbitrary-pipes>"
            val firstPipe = body.indexOf('|')
            if (firstPipe < 0) return null
            val callId = body.substring(0, firstPipe)
            if (!CALL_ID_PATTERN.matches(callId)) return null
            val rest = body.substring(firstPipe + 1)
            val secondPipe = rest.indexOf('|')
            if (secondPipe < 0) return null
            val typeStr = rest.substring(0, secondPipe)
            val type = CallSignalType.fromCode(typeStr) ?: return null
            val payload = rest.substring(secondPipe + 1)
            // Reject oversized payloads before constructing anything downstream (SDP DoS guard).
            if (payload.length > MAX_SDP_PAYLOAD) return null
            return runCatching { CallSignalEnvelope(callId = callId, type = type, payload = payload) }
                .getOrNull()
        }

        fun newCallId(): String {
            val bytes = ByteArray(CALL_ID_LEN / 2)
            RNG.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
