package co.electriccoin.zcash.ui.screen.chat.model

import org.json.JSONException
import org.json.JSONObject

/**
 * Round 8 P1 (PHONE_TRIAGE F3) — what the needs-join record of a group invite says about why it waits for Join
 * ([co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.getGroupInviteNeedsJoin]).
 *
 * - Without [RELAY_FAILED] (Round 8 D4): the invite came from an address we never chatted with. Nothing was tried.
 * - With [RELAY_FAILED] true: the free relay reply was tried and could not go. Join tries the relays again; only a
 *   reply that [canSendOnChain] can be paid. A group join is never paid under standing handshake consent.
 */
object GroupInviteWaitRecord {
    /** The record field set when the free relay reply could not go. */
    const val RELAY_FAILED = "relay_failed"

    /** Whether [record] (a needs-join record, or null) says the free relay reply could not go. */
    fun relayFailed(record: String?): Boolean =
        record != null &&
            try {
                JSONObject(record).optBoolean(RELAY_FAILED, false)
            } catch (_: JSONException) {
                false
            }

    /** ZC-517: only the decoded memo's UTF-8 bytes decide whether this waiting reply fits one on-chain memo. */
    fun canSendOnChain(record: String?): Boolean =
        record != null &&
            try {
                val memo = JSONObject(record).opt("memo") as? String
                memo != null &&
                    memo.isNotEmpty() &&
                    memo.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE
            } catch (_: JSONException) {
                false
            }
}
