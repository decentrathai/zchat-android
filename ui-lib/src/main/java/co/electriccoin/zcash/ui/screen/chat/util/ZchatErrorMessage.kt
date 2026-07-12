package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.MemoTooLongException

/**
 * SINGLE SOURCE OF TRUTH for turning a failed send / load into a user-facing chat message.
 *
 * Every ZCHAT surface that used to interpolate a raw exception (`e.message` / `e.toString()`) into
 * a toast, an Error state, or an error banner routes through here instead, so the UI NEVER shows a
 * framework class name like "co.electriccoin…TransactionProposalNotCreatedException: AmountTooSmall
 * (value=0)". The mapper walks the whole cause chain (a wrapped SDK error is still classified) and
 * matches specific, ZCHAT-voiced cases BEFORE giving up to the fallback.
 *
 * Money-safety / branding invariant: this is presentation-only. It never changes what is sent or
 * spent — it only decides which words the user reads.
 */

private const val AMOUNT_TOO_SMALL_MESSAGE =
    "This message needs a tiny amount of ZEC (min 0.00001) to send on-chain. Pick a non-zero " +
        "amount, or switch this chat to Tunnel/Open to message for free."

private const val MEMO_TOO_LONG_MESSAGE =
    "This message is too long to send on-chain. Shorten it."

private const val NETWORK_HICCUP_MESSAGE =
    "Network hiccup — check your connection and try again."

/**
 * Map a failed [Throwable] to a user-facing chat message, NEVER leaking raw exception text.
 *
 * Walks the whole cause chain: if a case matches, returns its ZCHAT-voiced copy; otherwise returns
 * [fallback]. A Throwable's own message is never returned verbatim — even a clean-looking message
 * could carry technical/sensitive text — so an unclassified failure always degrades to [fallback].
 */
fun Throwable.toZchatUserMessage(fallback: String): String {
    val causes = generateSequence<Throwable>(this) { it.cause }.take(20).toList()
    // MemoTooLongException's message ("Memo chunk N/M is X bytes, over the 512-byte…") does NOT
    // contain the class name, so classify it by TYPE anywhere in the chain.
    if (causes.any { it is MemoTooLongException }) return MEMO_TOO_LONG_MESSAGE
    val text = causes.joinToString("\n") { it.message ?: "" }
    return classifyZchatErrorText(text) ?: fallback
}

/**
 * Map an already-surfaced error STRING (e.g. a SendMessageState.Error message reaching the toast
 * layer) to a user-facing chat message. A clean, hand-written ZCHAT string passes through unchanged;
 * anything that still looks like raw framework text (FQCN / "Exception") is replaced with [fallback].
 */
fun String.toZchatUserMessage(fallback: String): String =
    classifyZchatErrorText(this)
        ?: if (isBlank() || looksLikeRawExceptionText(this)) fallback else this

/**
 * Ordered, specific classification shared by both entry points. Returns null when nothing specific
 * matched, so each caller can apply its own terminal policy (Throwable → always fallback; String →
 * preserve-if-clean). Specific cases are matched BEFORE the generic raw-exception catch-all.
 */
private fun classifyZchatErrorText(text: String): String? =
    when {
        text.contains("Insufficient balance", ignoreCase = true) ||
            text.contains("InsufficientFunds", ignoreCase = true) ||
            text.contains("Insufficient amount of ZEC", ignoreCase = true) ||
            text.contains("additional change output", ignoreCase = true) ->
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE

        text.contains("confirm on-chain", ignoreCase = true) ||
            text.contains("pending", ignoreCase = true) ->
            CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE

        text.contains("AmountTooSmall", ignoreCase = true) ||
            text.contains("zero amount", ignoreCase = true) ||
            text.contains("zero-amount", ignoreCase = true) ->
            AMOUNT_TOO_SMALL_MESSAGE

        text.contains("memo limit", ignoreCase = true) ||
            text.contains("MemoTooLong", ignoreCase = true) ||
            text.contains("512-byte", ignoreCase = true) ->
            MEMO_TOO_LONG_MESSAGE

        text.contains("submit", ignoreCase = true) ||
            text.contains("network", ignoreCase = true) ||
            text.contains("Grpc", ignoreCase = true) ||
            text.contains("connection", ignoreCase = true) ->
            NETWORK_HICCUP_MESSAGE

        else -> null
    }

/** True when a string still looks like a raw framework exception (FQCN or the word "Exception"). */
private fun looksLikeRawExceptionText(text: String): Boolean =
    text.contains("co.electriccoin", ignoreCase = true) ||
        text.contains("cash.z.ecc", ignoreCase = true) ||
        text.contains("org.zecdev", ignoreCase = true) ||
        text.contains("java.", ignoreCase = true) ||
        text.contains("kotlin.", ignoreCase = true) ||
        text.contains("Exception", ignoreCase = true)
