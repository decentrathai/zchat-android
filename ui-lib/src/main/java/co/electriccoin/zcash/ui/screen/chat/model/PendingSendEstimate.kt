package co.electriccoin.zcash.ui.screen.chat.model

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming

/**
 * Pure (no Android / Compose deps) helper for the "Sending after confirmation" affordance shown on a
 * queued/pending outgoing message — one that is waiting for the previous transaction's change notes
 * to confirm on-chain before it can be spent.
 *
 * The progress bar is intentionally coarse: a Zcash block is ~75s and we cannot know when the next
 * block will land, so progress is clamped to a near-but-never-complete fraction and never implies
 * "done". The labels show no block countdown (R2-7): see [labelRes] and [tunnelWaitLabelRes].
 *
 * Kept side-effect-free so it can be JVM-unit-tested without Robolectric (labels are resource ids).
 */
object PendingSendEstimate {
    /** Approximate Zcash target block interval, in seconds (before NU7; a bar with no number, ZC-114). */
    const val AVERAGE_BLOCK_SECONDS: Long = ChainTiming.PRE_NU7_BLOCK_SECONDS

    /**
     * Progress is clamped to this ceiling while still waiting — a pending send never renders a full
     * bar, since "full" should only mean confirmed/sent.
     */
    const val MAX_PROGRESS: Float = 0.95f

    /**
     * Coarse 0..[MAX_PROGRESS] progress for a message that has been queued for [elapsedSeconds].
     *
     * Returns `null` when no usable elapsed time is available — the caller should fall back to an
     * indeterminate bar in that case. A single block (~75s) maps roughly linearly to the ceiling;
     * messages that have waited longer than one block stay pinned at the ceiling (the chain is just
     * taking longer than average) rather than wrapping or completing.
     */
    fun progressFor(elapsedSeconds: Long): Float? {
        if (elapsedSeconds < 0L) return null
        val raw = elapsedSeconds.toFloat() / AVERAGE_BLOCK_SECONDS.toFloat()
        return raw.coerceIn(0f, MAX_PROGRESS)
    }

    /**
     * Convenience overload that derives elapsed seconds from a queued-at epoch millis and a "now"
     * epoch millis. Returns `null` (→ indeterminate) when the inputs are non-sensical (now before
     * queued).
     */
    fun progressFor(queuedAtMillis: Long, nowMillis: Long): Float? {
        val elapsed = (nowMillis - queuedAtMillis) / 1000L
        return progressFor(elapsed)
    }

    /** R2-7 (c): after this long an on-chain send is "still sending", with what to do. */
    const val SLOW_AFTER_SECONDS: Long = 180L

    /** R2-7 (c): after this long a Tunnel message still waiting for the contact's handshake says what to do. */
    const val TUNNEL_STALLED_AFTER_SECONDS: Long = 600L

    /**
     * Label for an on-chain send still in flight. There is no block countdown: a send can wait for its ZEC
     * to confirm (several blocks, up to four five-minute waits before it fails with a reason), so the old
     * one-block countdown was wrong for minutes on end. After [SLOW_AFTER_SECONDS] the label says how
     * long it has been and what to do (the string takes the minutes, [elapsedMinutes]).
     */
    @StringRes
    fun labelRes(elapsedSeconds: Long): Int =
        if (elapsedSeconds >= SLOW_AFTER_SECONDS) R.string.chat_pending_onchain_slow else R.string.chat_pending_onchain

    /**
     * Label for a Tunnel message waiting for the contact's secure handshake. It never ended before; after
     * [TUNNEL_STALLED_AFTER_SECONDS] it says how long, and to ask the contact to open ZChat 2.13 or newer.
     */
    @StringRes
    fun tunnelWaitLabelRes(elapsedSeconds: Long): Int =
        if (elapsedSeconds >= TUNNEL_STALLED_AFTER_SECONDS) {
            R.string.chat_pending_tunnel_stalled
        } else {
            R.string.chat_pending_tunnel
        }

    /**
     * R10A-2 (F-R9P-3): the pending line under an outgoing row, chosen from the row alone.
     * - A photo, file or voice note still uploading ([AttachmentIds.isUpload]) says it is uploading. Nothing is on the
     *   chain or on a relay while the blob uploads, in any mode, so it never says it is on the Zcash network.
     * - A Tunnel message waiting for the contact's handshake ("tunnel-wait-") keeps [tunnelWaitLabelRes].
     * - A relay send ("nostr-out-", or a relay row keyed by its rumor id, "nmsg-": a Retry of an Open message, the
     *   optimistic bubble; ZC-1073) says it is going over the relays.
     * - Everything else is an on-chain send ([labelRes]).
     * The labels that take a number take [elapsedMinutes]; the others ignore it.
     */
    @StringRes
    fun rowLabelRes(
        messageId: String,
        txId: String?,
        elapsedSeconds: Long,
    ): Int =
        when {
            AttachmentIds.isUpload(messageId) -> R.string.chat_pending_upload
            txId == null && messageId.startsWith(TUNNEL_WAIT_PREFIX) -> tunnelWaitLabelRes(elapsedSeconds)
            txId == null && isRelayRowId(messageId) -> R.string.chat_pending_relay
            else -> labelRes(elapsedSeconds)
        }

    /** True when the row's line is not tied to Zcash blocks (an upload, a Tunnel wait or a relay send): no block-time bar. */
    fun isOffChainRow(
        messageId: String,
        txId: String?,
    ): Boolean =
        AttachmentIds.isUpload(messageId) ||
            (txId == null && (messageId.startsWith(TUNNEL_WAIT_PREFIX) || isRelayRowId(messageId)))

    /** ZC-1073: a relay row before its publish answered ("nostr-out-") or keyed by its shared rumor id ("nmsg-", #202). */
    private fun isRelayRowId(messageId: String): Boolean =
        messageId.startsWith(RELAY_PREFIX) || messageId.startsWith(RELAY_ROW_PREFIX)

    private const val TUNNEL_WAIT_PREFIX = "tunnel-wait-"
    private const val RELAY_PREFIX = "nostr-out-"
    private const val RELAY_ROW_PREFIX = "nmsg-"

    /** Whole minutes waited, the argument of the "after N min" labels. */
    fun elapsedMinutes(elapsedSeconds: Long): Int = (elapsedSeconds.coerceAtLeast(0L) / 60L).toInt()
}
