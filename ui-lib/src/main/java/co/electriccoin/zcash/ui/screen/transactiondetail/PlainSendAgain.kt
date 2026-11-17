package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
import co.electriccoin.zcash.ui.common.tex.PlainSendVerdict
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.forTransaction
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol

/*
 * F5 (fix-all 2.13.0, train T5; SPECS §1.2): what the transaction detail says about a plain Wallet send of the ZCHAT
 * account, from the TEX checker's last pass (TexCheck, F4's rules), and when its "Send again" belongs to the chat.
 */

/**
 * The footer of the plain send [txId]: it can still be mined ("Not confirmed yet", with the block after which it cannot
 * and about how long that is), nothing is known yet ("checking"), or it is proven never mined ("Not sent — nothing was
 * paid"). Null for a mined send, a TEX leg (which has its own footer), a send the pass did not judge, or no pass yet.
 */
@JvmOverloads
fun plainSendFooter(
    txId: String,
    check: TexCheck?,
    expired: Boolean = false,
): ErrorFooter? {
    if (check == null || check.statuses.forTransaction(txId) != null) return null
    return when (val verdict = check.plainSends[txId]) {
        null -> null
        PlainSendVerdict.NotSent ->
            ErrorFooter(
                title = stringRes(R.string.transaction_detail_not_sent_title),
                subtitle = stringRes(R.string.transaction_detail_not_sent_body),
            )
        is PlainSendVerdict.Waiting -> {
            val expiry = verdict.expiryHeight
            val tip = verdict.tip
            ErrorFooter(
                title =
                    if (expired) {
                        stringRes(R.string.transaction_detail_expired_checking_title)
                    } else {
                        stringRes(R.string.transaction_detail_waiting_title)
                    },
                subtitle =
                    if (expired) {
                        stringRes(R.string.transaction_detail_checking_body)
                    } else if (expiry != null && tip != null) {
                        stringRes(
                            R.string.transaction_detail_waiting_body,
                            SendAttemptRules.deadBlock(expiry).toString(),
                            SendAttemptRules.minutesUntilDead(expiry, tip),
                        )
                    } else {
                        stringRes(R.string.transaction_detail_checking_body)
                    },
                // QA2 Q2-04 (ZC-1455): a send that can still be mined is waiting, not an error; an expired one is.
                isError = expired,
            )
        }
    }
}

/**
 * Whether the next attempt of the send [txId] belongs to the chat, so the Wallet offers no "Send again" for it: it is
 * proven never mined and its memo is the chat's (a ZMSG envelope, a request's `ZREQ_FULFILL|` payment, a key rotation),
 * or its memo was not read ([memos] null with [memoCount] > 0: any doubt answers yes). The chat already owns that one
 * new attempt (the row's Retry, the request's Pay again, the automatic handshake re-arm); two doors for one message
 * could pay twice. A mined chat transaction keeps Zashi's repeat payment.
 */
fun chatOwnsTheNextAttempt(
    txId: String,
    check: TexCheck?,
    memos: List<String>?,
    memoCount: Int,
): Boolean {
    if (check == null || check.plainSends[txId] != PlainSendVerdict.NotSent || txId in check.minedSends) return false
    if (memos == null) return memoCount > 0
    return memos.any(::isChatOwnedMemo)
}

/** A memo the chat layer sends and settles itself. */
fun isChatOwnedMemo(memo: String): Boolean {
    val text = memo.trim()
    return ZMSGProtocol.isRecognizedZmsgEnvelope(text) ||
        text.startsWith(FULFIL_MEMO_PREFIX) ||
        text.startsWith(KEY_ROTATION_MEMO_PREFIX)
}

private const val FULFIL_MEMO_PREFIX = "ZREQ_FULFILL|"
private const val KEY_ROTATION_MEMO_PREFIX = "ZBOOT|"
