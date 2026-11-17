package co.electriccoin.zcash.ui.common.util

import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import java.security.MessageDigest

/**
 * QA test-infrastructure (debug-only): structured one-line inbound-message events, emitted on the
 * `InboundDiag` logcat tag so an on-device runner can machine-assert "phone B received exactly one
 * chat message" (the receive-side mirror of [PaymentDiag]) instead of trusting prose.
 *
 * Event vocabulary (single-line, `event {k=v, ...}`):
 *  - `inbound_message_added {msgId, txid, peer_fp, contentHash, transport, outgoing}` — a
 *    [ChatMessage] was finalized into the in-memory conversation state, either by the on-chain
 *    derive pass (`convertToConversations`, transport=chain) or by the NOSTR NIP-17 inbound path
 *    (`observeNostrInbound`, transport=nostr).
 *
 * The event is STATELESS: `msgId` is a one-way 8-hex fingerprint ([fpOf]) of the message's stable
 * string identity (on-chain: the txid string; NOSTR: `nmsg-<rumorId>`), so no registry is needed.
 * The fingerprint preserves DISTINCTNESS — which is all the runner's exactly-once dedup needs —
 * without ever emitting the raw id (the on-chain id IS the full 64-hex txid; logging it raw would
 * publicly link the QA wallet to an on-chain transaction in captured evidence bundles). The
 * on-chain derive pass re-runs every scan, so the same msgId is re-emitted each pass — the runner
 * dedups on `msgId`; re-emission after a process restart is deliberate (it proves re-derivation
 * from chain for the restart-durability scenario).
 *
 * SECURITY: never logs the message plaintext, the memo, a raw address, or a raw message id.
 * `contentHash` is a one-way 8-hex-char SHA-256 prefix of the DECRYPTED display-ready
 * `message.text` (content identity without content disclosure, mirroring PaymentDiag's `dest_fp`
 * rule); `peer_fp` is the same one-way prefix of the peer address; `msgId` is the same one-way
 * prefix of the stable id; txids go through [redactTxId]. Memos are never touched.
 *
 * RELEASE IMPACT: none. Every entry point is gated on [enabled] == `BuildConfig.DEBUG`; in release
 * builds every method is an immediate no-op (no hashing, no Log calls). ReleaseHookAbsenceTest
 * asserts this gate stays in place.
 */
object InboundDiag {
    private const val TAG = "InboundDiag"

    /** `transport` value for messages derived from the on-chain memo scan. */
    const val TRANSPORT_CHAIN: String = "chain"

    /** `transport` value for messages delivered via NOSTR NIP-17. */
    const val TRANSPORT_NOSTR: String = "nostr"

    private const val FP_HEX_CHARS = 8

    /** Single debug gate — every public entry point returns immediately when this is false. */
    private val enabled: Boolean
        get() = BuildConfig.DEBUG

    // ── lifecycle events ─────────────────────────────────────────────────────────────────────────

    /**
     * `inbound_message_added` — [message] was finalized into conversation state over [transport]
     * ([TRANSPORT_CHAIN] or [TRANSPORT_NOSTR]). Call immediately after the add; never before a
     * skip/dedup decision.
     */
    @Suppress("TooGenericExceptionCaught")
    fun logMessageAdded(message: ChatMessage, transport: String) {
        if (!enabled) return
        try {
            val txid = message.txId?.txIdString()?.redactTxId() ?: "none"
            emit(
                "inbound_message_added {msgId=${fpOf(message.id)}, txid=$txid, " +
                    "peer_fp=${fpOf(message.peerAddress)}, contentHash=${fpOf(message.text)}, " +
                    "transport=$transport, outgoing=${message.isOutgoing}}"
            )
        } catch (_: Throwable) {
            // Diagnostics must never break a message flow.
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** SINGLE emission chokepoint (ReleaseHookAbsenceTest pins the gate + the lone Log call). */
    private fun emit(event: String) {
        if (!enabled) return
        Log.i(TAG, event)
    }

    /**
     * One-way fingerprint: first [FP_HEX_CHARS] hex chars of SHA-256(value). NEVER the value
     * itself (LogRedaction rule: no raw addresses, no message plaintext in logs).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun fpOf(value: String): String =
        try {
            if (value.isEmpty()) {
                "none"
            } else {
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(FP_HEX_CHARS)
            }
        } catch (_: Throwable) {
            // Diagnostics must never break a message flow.
            "err"
        }
}
