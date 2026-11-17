package co.electriccoin.zcash.ui.call

/**
 * ZC-409: the call signals that reach the callee before the RING of their call.
 *
 * A caller sends RING and then OFFER (and its ICE candidates) as separate messages; each is published on its own to
 * three relays, and the callee's relay pool hands over the first copy of each, so the OFFER or ICE can arrive before
 * the RING. [VoiceCallManager] used to drop them, the callee then had no OFFER to answer, and the call ended at the
 * 45 s setup timeout. This store keeps them until the RING arrives.
 *
 * Per (sender, callId) it keeps the first OFFER, the last [maxIcePerCall] ICE payloads (as the raw payload strings;
 * the oldest is evicted, the slower relay candidate arrives last) and whether a HANGUP came. It keeps at most
 * [MAX_CALLS] calls (the oldest is dropped) and drops a call [KEEP_MS] after its first signal, which is longer than the
 * setup timeout. Nothing here rings, answers or applies anything: [VoiceCallManager.handleSignal]'s RING handler
 * takes the entry, and only the user's Accept applies the OFFER.
 *
 * Pure (no Android, no WebRTC) and not thread-safe: the call manager uses it only on its one call thread. The caller
 * passes the time, from a clock that never goes back.
 */
class EarlyCallSignals(
    private val maxIcePerCall: Int,
    private val maxIcePayloadChars: Int,
) {
    /** What was held for one call when its RING arrived. */
    data class Held(
        val offer: CallSignalEnvelope?,
        val ice: List<String>,
        val hungUp: Boolean,
    )

    private data class Key(
        val sender: String,
        val callId: String,
    )

    private class Entry(
        val firstAtMs: Long,
    ) {
        var offer: CallSignalEnvelope? = null
        val ice: MutableList<String> = mutableListOf()
        var hungUp: Boolean = false
    }

    // Insertion order = the order of each call's first signal, so the first key is the oldest call.
    private val entries = LinkedHashMap<Key, Entry>()

    /**
     * Keeps an OFFER, ICE or HANGUP of a call that is not the active one. Returns false when the signal is not kept: a
     * RING or ANSWER (never held), or an ICE payload longer than [maxIcePayloadChars] (the call manager would refuse
     * it anyway).
     */
    fun hold(
        sender: String,
        envelope: CallSignalEnvelope,
        nowMs: Long,
    ): Boolean {
        when (envelope.type) {
            CallSignalType.OFFER, CallSignalType.HANGUP -> Unit
            CallSignalType.ICE -> if (maxIcePerCall <= 0 || envelope.payload.length > maxIcePayloadChars) return false
            CallSignalType.RING, CallSignalType.ANSWER, CallSignalType.VIDEO_DECLINE -> return false
        }
        dropExpired(nowMs)
        val key = Key(sender, envelope.callId)
        val entry = entries[key] ?: newEntry(key, nowMs)
        when (envelope.type) {
            CallSignalType.OFFER -> if (entry.offer == null) entry.offer = envelope
            CallSignalType.ICE -> {
                while (entry.ice.size >= maxIcePerCall) entry.ice.removeAt(0)
                entry.ice += envelope.payload
            }
            CallSignalType.HANGUP -> entry.hungUp = true
            CallSignalType.RING, CallSignalType.ANSWER, CallSignalType.VIDEO_DECLINE -> Unit
        }
        return true
    }

    /** What is held for this sender's call, removed from the store; null when nothing is held (or it is too old). */
    fun take(
        sender: String,
        callId: String,
        nowMs: Long,
    ): Held? {
        dropExpired(nowMs)
        val entry = entries.remove(Key(sender, callId)) ?: return null
        return Held(offer = entry.offer, ice = entry.ice.toList(), hungUp = entry.hungUp)
    }

    private fun newEntry(
        key: Key,
        nowMs: Long,
    ): Entry {
        while (entries.size >= MAX_CALLS) entries.remove(entries.keys.first())
        return Entry(nowMs).also { entries[key] = it }
    }

    private fun dropExpired(nowMs: Long) {
        entries.values.removeAll { nowMs - it.firstAtMs >= KEEP_MS }
    }

    companion object {
        /** Calls held at once; a fifth drops the oldest. */
        const val MAX_CALLS = 4

        /** A call is dropped this long after its first signal (the setup timeout is 45 s). */
        const val KEEP_MS = 60_000L
    }
}
