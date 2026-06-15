package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Maps a raw transaction memo to a human-readable label, hiding ZCHAT wire-protocol payloads
 * (ZMSG / ZBOOT / ZFILE / ZREACT / …) so wallet-level screens — e.g. the transaction detail —
 * never show the raw "ZMSG|v4|…" / "ZBOOT|…" string the way the chat used to leak it.
 *
 * Plain (non-protocol) memos are returned unchanged. The decoded conversation content itself is
 * not reconstructed here — that lives in the chat screen, which decrypts via the E2E ratchet — so
 * an encrypted ZCHAT message is shown as a neutral "ZCHAT message" label rather than ciphertext.
 */
fun memoDisplayText(memo: String): String {
    val m = memo.trim()
    val zfileStart = m.indexOf("ZFILE|")
    return when {
        m.contains("ZBOOT|") -> "🔐 Secure connection request"
        zfileStart >= 0 ->
            ZFILEMessage.parse(m.substring(zfileStart))?.let { "📎 ${it.displayText}" } ?: "📎 Attachment"
        m.startsWith("ZREACT|") -> "Reaction"
        m.startsWith("ZRCPT|") -> "Read receipt"
        m.startsWith("ZSTAT|") -> "Status update"
        m.startsWith("ZREQ|") -> "💰 Payment request"
        m.startsWith("ZTL|") || m.startsWith("ZUNLOCK|") -> "🔒 Time-locked message"
        m.startsWith("ZMSG|") || m.startsWith("ZMSG:") -> "🔐 ZCHAT message"
        else -> memo
    }
}
