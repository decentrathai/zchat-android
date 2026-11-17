package co.electriccoin.zcash.ui.call

/**
 * ZC-1468: the rules for switching a connected audio call to video by a second OFFER/ANSWER under the same callId. Pure;
 * VoiceCallManager holds one per call ([isCaller] = this phone placed the call) and asks it on its call thread.
 *
 * - [startUpgrade]: an upgrade OFFER may be sent only in InCall, one at a time.
 * - [onRemoteOffer]: an OFFER in InCall is answered once the call carries video; in an audio call it is held and the user
 *   asked first (ZC-1904, owner W-148: ASK, ended once by [finishAsk]); elsewhere it is ignored. Glare (both phones
 *   offering, so both users tapped video): the caller keeps its offer, the callee rolls its own back and answers.
 * - [onRemoteDecline]: the other phone's user declined our upgrade (or its question timed out); we roll back once.
 * - [onRemoteAnswer]: only the answer to our own pending upgrade is applied.
 * - [shouldRollBack]: an upgrade with no answer after [UPGRADE_TIMEOUT_MS] is rolled back, once (a 2.12.4 phone drops
 *   the OFFER and never answers; a 2.13 phone asks for up to [PROMPT_TIMEOUT_MS]); the audio call goes on.
 */
class CallRenegotiation(
    private val isCaller: Boolean,
) {
    enum class OfferDecision { ANSWER, ASK, IGNORE, ROLLBACK_LOCAL_THEN_ANSWER }

    private var pendingSinceMs: Long? = null

    // ZC-1904: an upgrade OFFER of the other phone is held while this phone's user is asked.
    private var asking = false

    /** True when this phone may send its upgrade OFFER now; the upgrade is then pending from [nowMs]. */
    fun startUpgrade(
        state: VoiceCallManager.CallState,
        nowMs: Long,
    ): Boolean {
        if (state !is VoiceCallManager.CallState.InCall || pendingSinceMs != null || asking) return false
        pendingSinceMs = nowMs
        return true
    }

    /** What to do with a renegotiation OFFER that arrives in [state]. */
    fun onRemoteOffer(state: VoiceCallManager.CallState): OfferDecision {
        if (state !is VoiceCallManager.CallState.InCall) return OfferDecision.IGNORE
        if (asking) return OfferDecision.IGNORE
        if (pendingSinceMs == null) {
            if (state.isVideo) return OfferDecision.ANSWER
            asking = true
            return OfferDecision.ASK
        }
        if (isCaller) return OfferDecision.IGNORE
        pendingSinceMs = null
        return OfferDecision.ROLLBACK_LOCAL_THEN_ANSWER
    }

    /** True only for the first ANSWER to our pending upgrade; it is no longer pending afterwards. */
    fun onRemoteAnswer(state: VoiceCallManager.CallState): Boolean {
        if (state !is VoiceCallManager.CallState.InCall || pendingSinceMs == null) return false
        pendingSinceMs = null
        return true
    }

    /** ZC-1904: true once when the open question ends (Accept, Decline or the prompt timeout; the first one wins). */
    fun finishAsk(): Boolean {
        if (!asking) return false
        asking = false
        return true
    }

    /** ZC-1904: true only once, for a VIDEO_DECLINE to our own pending upgrade in a connected call; it is then over. */
    fun onRemoteDecline(state: VoiceCallManager.CallState): Boolean {
        if (state !is VoiceCallManager.CallState.InCall || pendingSinceMs == null) return false
        pendingSinceMs = null
        return true
    }

    /** Ends a pending upgrade that could not be sent (no camera, setup error). */
    fun cancelUpgrade() {
        pendingSinceMs = null
    }

    /** True once, when the pending upgrade has waited [UPGRADE_TIMEOUT_MS] without an answer. */
    fun shouldRollBack(nowMs: Long): Boolean {
        val since = pendingSinceMs ?: return false
        if (nowMs - since < UPGRADE_TIMEOUT_MS) return false
        pendingSinceMs = null
        return true
    }

    companion object {
        /** ZC-1904: the caller's wait; the other phone asks its user first (was 5 s under ZC-1468). */
        const val UPGRADE_TIMEOUT_MS = 30_000L

        /** ZC-1904: how long the question stays on the other phone; shorter than [UPGRADE_TIMEOUT_MS]. */
        const val PROMPT_TIMEOUT_MS = 25_000L

        /** ZC-1904: the VIDEO_DECLINE payloads; any other payload reads as [DECLINE_TIMEOUT]. */
        const val DECLINE_BY_USER = "declined"
        const val DECLINE_TIMEOUT = "timeout"

        /**
         * ZC-1937: the VIDEO_DECLINE payload "<why>:<tag>", the tag naming the upgrade OFFER it answers (a short hash of
         * that OFFER's SDP, which both phones hold byte for byte), so a late decline of an earlier upgrade cannot end a
         * newer one. 2.12.4 never sends an in-call OFFER nor VIDEO_DECLINE and rejects the VIDEO_DECLINE type.
         */
        @JvmStatic
        fun declinePayload(
            why: String,
            offerSdp: String,
        ): String = "$why$TAG_SEPARATOR${offerTag(offerSdp)}"

        /** ZC-1937: true only for a decline tagged with [ownOfferSdp], our pending upgrade OFFER; untagged is false. */
        @JvmStatic
        fun declineMatchesOffer(
            payload: String,
            ownOfferSdp: String?,
        ): Boolean {
            if (ownOfferSdp == null) return false
            val tag = payload.substringAfter(TAG_SEPARATOR, missingDelimiterValue = "")
            return tag.isNotEmpty() && tag == offerTag(ownOfferSdp)
        }

        /** ZC-1937: the reason part of a VIDEO_DECLINE payload. */
        @JvmStatic
        fun declineReason(payload: String): String = payload.substringBefore(TAG_SEPARATOR)

        private const val TAG_SEPARATOR = ':'
        private const val TAG_HEX_CHARS = 16

        private fun offerTag(sdp: String): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(sdp.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(TAG_HEX_CHARS)
    }
}
