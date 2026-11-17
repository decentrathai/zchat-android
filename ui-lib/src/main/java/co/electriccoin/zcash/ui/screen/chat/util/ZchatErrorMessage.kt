package co.electriccoin.zcash.ui.screen.chat.util

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SaplingCrossingException
import co.electriccoin.zcash.ui.common.model.UnreadableProposalException
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.MemoTooLongException
import co.electriccoin.zcash.ui.screen.chat.usecase.OwnAddressChatSendRefusedException
import co.electriccoin.zcash.ui.screen.chat.usecase.SpendAuthCancelledException

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

internal const val AMOUNT_TOO_SMALL_MESSAGE =
    "This message needs a tiny amount of ZEC (min 0.00001) carried with it to send on-chain. Pick a " +
        "non-zero amount."

internal const val MEMO_TOO_LONG_MESSAGE =
    "This message is too long to send on-chain. Shorten it."

internal const val NETWORK_HICCUP_MESSAGE =
    "Network hiccup — check your connection and try again."

/**
 * C4 (HONOR check 3b) — the #248 spend-auth prompt (PIN / biometrics) before a value send was cancelled or failed.
 * The use case throws [SpendAuthCancelledException] before the SDK is asked to store anything, so this send was not
 * made. It used to fall through to each caller's fallback ("Failed to send message"), which reads as a failure.
 */
internal const val SPEND_AUTH_NOT_COMPLETED_MESSAGE =
    "Not sent — the PIN or biometric check wasn't completed."

/**
 * Round 8 D8 (REVIEW_7C F33) — a chat send refused before anything was stored, whose [message] is a sentence written
 * for the user (a ChatSendCopy constant or RestoreKexPolicy.STILL_READING_HISTORY). [toZchatUserMessage] shows it as
 * it is: the mapper never shows an exception's own message, so these refusals were read as "Network hiccup — check
 * your connection and try again." (their text mentions the connection) or as the caller's "Failed to …" fallback.
 */
class UserFacingSendRefusal(
    message: String,
) : IllegalStateException(message)

/**
 * Map a failed [Throwable] to a user-facing chat message, NEVER leaking raw exception text.
 *
 * Walks the whole cause chain: if a case matches, returns its ZCHAT-voiced copy; otherwise returns
 * [fallback]. A Throwable's own message is never returned verbatim — even a clean-looking message
 * could carry technical/sensitive text — so an unclassified failure always degrades to [fallback].
 */
fun Throwable.toZchatUserMessage(fallback: String): String {
    val causes = generateSequence<Throwable>(this) { it.cause }.take(20).toList()
    // Round 8 D8 (F33): a refusal written for the user is shown as it is, before any text rule can re-label it.
    causes.firstOrNull { it is UserFacingSendRefusal }?.message?.let { return it }
    // MemoTooLongException's message ("Memo chunk N/M is X bytes, over the 512-byte…") does NOT
    // contain the class name, so classify it by TYPE anywhere in the chain.
    if (causes.any { it is MemoTooLongException }) return MEMO_TOO_LONG_MESSAGE
    // OrchardSealedException carries hand-written ZCHAT copy. Select it by TYPE so that no send
    // surface (new-chat first message, reply, reaction, invite, unlock, payment request) degrades
    // the sealed-pool refusal to the generic retry prompt — that prompt says nothing about Ironwood.
    // A proposal the SDK funded from the sealed pool would publish its amount; say so by name.
    if (causes.any { it is PublicCrossingException }) return PublicCrossingException.PUBLIC_CROSSING_MESSAGE
    // R2-2 (c): a Sapling-funded payment into another pool is refused for the same reason; say so by name.
    if (causes.any { it is SaplingCrossingException }) return SaplingCrossingException.SAPLING_CROSSING_MESSAGE
    // R4-6 (11): a proposal ZChat could not read is refused fail-closed. Select it by TYPE so it never
    // degrades to the generic retry prompt: no retry fixes it, and the user must learn nothing was sent.
    if (causes.any { it is UnreadableProposalException }) return UnreadableProposalException.UNREADABLE_PROPOSAL_MESSAGE
    // C4 (HONOR check 3b): the user cancelled (or failed) the PIN / biometric prompt; this send never reached the SDK.
    if (causes.any { it is SpendAuthCancelledException }) return SPEND_AUTH_NOT_COMPLETED_MESSAGE
    // K0-2 (2.13.0): the send chokepoint refused a chat send because a Keystone account is selected (chat signing is
    // closed in this version). Thrown before anything is built or stored; say so and say where to switch, instead of
    // the caller's generic "Failed to …".
    if (causes.any {
            it is UnsupportedOperationException &&
                it.message == CreateChunkedMessageProposalUseCase.KEYSTONE_AUTO_SEND_REFUSED_MESSAGE
        }
    ) {
        return ChatSendCopy.KEYSTONE_CHATS_CLOSED
    }
    // T3-extra: the send chokepoint refused a chat send to one of our own addresses (rule E), before anything was built.
    if (causes.any { it is OwnAddressChatSendRefusedException }) return ChatSendCopy.OWN_ADDRESS_NOT_PAID
    if (causes.any { it is NetworkUpgradeRequiredException }) return NetworkUpgradeRequiredException.MESSAGE
    causes.filterIsInstance<OrchardSealedException>().firstOrNull()?.let {
        return if (it.ironwoodPending > Zatoshi(0)) {
            OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE
        } else {
            OrchardSealedException.ORCHARD_SEALED_MESSAGE
        }
    }
    // D1 (E2): a shortfall the send chokepoint worded (wait / add ZEC / payment) is shown as it was chosen. Its cause
    // can be the rejection that proves nothing was stored, whose text embeds the SDK's "Insufficient balance", and
    // joined with it a "still confirming, wait" notice was re-labelled "add ZEC".
    causes
        .filterIsInstance<InsufficientFundsException>()
        .firstOrNull { !it.message.isNullOrBlank() }
        ?.let { return classifyZchatErrorText(it.message.orEmpty()) ?: fallback }
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
        // ZC-1417: the shortfall with the SDK's figures is shown as it was worded.
        ChatSendCopy.messageShortfallFigures(text) != null -> text

        text.contains(NetworkUpgradeRequiredException.MESSAGE, ignoreCase = true) -> NetworkUpgradeRequiredException.MESSAGE

        // R4-6 (11): first, because the fail-closed refusal names pools and amounts and would otherwise
        // be re-labelled by one of the broader matches below.
        text.contains("could not check which Zcash pools", ignoreCase = true) ->
            UnreadableProposalException.UNREADABLE_PROPOSAL_MESSAGE

        // Both sealed-pool copies come before the broader "pending" match below, and the CONFIRMING
        // copy comes first because it also contains the words "sealed Orchard pool": the other order
        // re-labels "wait for confirmation" as "migrate" on the toast path.
        text.contains("funded from the sealed Orchard pool", ignoreCase = true) ->
            PublicCrossingException.PUBLIC_CROSSING_MESSAGE

        text.contains("paid from your Sapling balance into another pool", ignoreCase = true) ->
            SaplingCrossingException.SAPLING_CROSSING_MESSAGE

        text.contains("Ironwood funds are still confirming", ignoreCase = true) ->
            OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE

        text.contains("sealed Orchard pool", ignoreCase = true) ->
            OrchardSealedException.ORCHARD_SEALED_MESSAGE

        // C-6: a payment shortfall keeps its payment copy. Matched before the generic shortfall below,
        // whose copy offers "switch this chat to Tunnel/Open", which cannot make a payment free.
        text.contains(CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MARKER, ignoreCase = true) ->
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE

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
