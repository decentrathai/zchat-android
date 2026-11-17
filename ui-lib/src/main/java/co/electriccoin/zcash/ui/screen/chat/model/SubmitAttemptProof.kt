package co.electriccoin.zcash.ui.screen.chat.model

/** Ambiguous submissions remain blocked until transaction-specific recovery exists. */
object SubmitAttemptProof {
    const val MAY_HAVE_BEEN_SENT =
        "This message may already have been sent. Retry is blocked to avoid paying twice. " +
            "Let your wallet sync and check the chat and transaction history."

    const val ROW_NOTICE = "May already have been sent — Retry is blocked to avoid paying twice"
}
