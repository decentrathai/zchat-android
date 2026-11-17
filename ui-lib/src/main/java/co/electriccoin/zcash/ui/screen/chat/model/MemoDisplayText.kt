package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * Maps a raw transaction memo to a human-readable label, hiding ZCHAT wire-protocol payloads
 * (ZMSG / ZBOOT / ZFILE / ZREACT / …) so wallet-level screens — e.g. the transaction detail —
 * never show the raw "ZMSG|v4|…" / "ZBOOT|…" string the way the chat used to leak it.
 *
 * Plain (non-protocol) memos are returned unchanged. The decoded conversation content itself is
 * not reconstructed here — that lives in the chat screen, which decrypts via the E2E ratchet — so
 * an encrypted ZCHAT message is shown as a neutral "ZCHAT message" label rather than ciphertext.
 */
fun memoDisplayText(memo: String): StringResource {
    val m = memo.trim()
    val zfileStart = m.indexOf("ZFILE|")
    return when {
        m.contains("ZBOOT|") -> stringRes(R.string.memo_display_secure_connection_request)
        zfileStart >= 0 ->
            // A readable file is named by its kind and size, the translated label the chat rows use (ZC-1733,
            // ZC-1756: ZFILEMessage.typeLabelRes); an unreadable one gets the generic translated label (ZC-1704).
            ZFILEMessage.parse(m.substring(zfileStart))?.let { file ->
                stringRes(file.typeLabelRes, file.sizeText)
            } ?: stringRes(R.string.memo_display_attachment)
        m.startsWith("ZREACT|") -> stringRes(R.string.memo_display_reaction)
        m.startsWith("ZRCPT|") -> stringRes(R.string.memo_display_read_receipt)
        m.startsWith("ZSTAT|") -> stringRes(R.string.memo_display_status_update)
        // QA2 Q2-50: the payment of a request (ZREQ_FULFILL|<request id>) is named, never shown raw; U7 review
        // follow-up (Normal 4): with the translated resource.
        m.startsWith("ZREQ_FULFILL|") -> stringRes(R.string.memo_display_paid_request)
        m.startsWith("ZREQ|") -> stringRes(R.string.memo_display_payment_request)
        m.startsWith("ZTL|") || m.startsWith("ZUNLOCK|") -> stringRes(R.string.memo_display_time_locked)
        m.startsWith("ZEXP|") || m.startsWith("ZMODE|") -> stringRes(R.string.memo_display_control_message)
        // ZC-1704: every protocol label is a translated resource.
        m.startsWith("ZMSG|") || m.startsWith("ZMSG:") -> stringRes(R.string.memo_display_zchat_message)
        else -> stringRes(memo)
    }
}
