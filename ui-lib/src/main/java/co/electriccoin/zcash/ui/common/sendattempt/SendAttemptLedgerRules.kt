package co.electriccoin.zcash.ui.common.sendattempt

/**
 * F8 (ZC-112, ZC-121): the pure rules of the send-attempt ledger: the key, the gate's answers, what each ledger write
 * turns an entry into, and what the history pass does with a chain verdict. Nothing here sends, pays or writes.
 */
object SendAttemptLedgerRules {
    fun key(
        kind: SendAttemptKind,
        subject: String,
        identity: String,
    ): String = kind.name + "|" + subject + "|" + identity

    /** The id an attempt is entered under in [SendAttemptProcess]: it carries the attempt's own marker, so it is unique. */
    fun flightId(
        key: String,
        startedAt: Long,
    ): String = "ledger:$key|$startedAt"

    /** The row id an attempt's proof is kept under in [SendAttemptProof] (with its marker). */
    fun proofRow(key: String): String = "ledger:$key"

    /**
     * The gate. No entry and no [retryOf]: go on. A NOT_SENT entry whose marker is [retryOf]: go on (the quote was
     * shown for exactly this failed state). Anything else is PENDING: a LIVE or MINED entry, a NOT_SENT one met by an
     * automatic attempt or by another [retryOf], and a [retryOf] whose entry is gone. Never "sent".
     */
    fun gate(
        entry: SendAttemptEntry?,
        retryOf: Long?,
    ): SendAttemptGate =
        when {
            entry == null && retryOf == null -> SendAttemptGate.Proceed(null)
            entry != null &&
                retryOf != null &&
                entry.state == SendAttemptState.NOT_SENT &&
                entry.startedAtMillis == retryOf -> SendAttemptGate.Proceed(retryOf)
            else -> SendAttemptGate.Pending
        }

    /**
     * The entry after the ids commit of [claim], or null when the entry the commit rests on is not there any more. A
     * fresh attempt adds the ids to its own LIVE entry. A retry replaces the NOT_SENT entry it was confirmed for by a
     * new LIVE entry that keeps the old ids as superseded.
     */
    fun afterIdsCommit(
        existing: SendAttemptEntry?,
        claim: SendAttemptClaim,
        txIds: List<String>,
        expiries: Map<String, Long>,
    ): SendAttemptEntry? {
        existing ?: return null
        val replaces = claim.replaces
        if (replaces == null) {
            if (existing.state != SendAttemptState.LIVE || existing.startedAtMillis != claim.startedAt) return null
            return existing.copy(txIds = (existing.txIds + txIds).distinct(), expiries = existing.expiries + expiries)
        }
        if (existing.state != SendAttemptState.NOT_SENT || existing.startedAtMillis != replaces) return null
        return SendAttemptEntry(
            kind = existing.kind,
            subject = existing.subject,
            identity = existing.identity,
            startedAtMillis = claim.startedAt,
            attemptHeight = claim.attemptHeight,
            processId = claim.processId,
            accountHash = claim.accountHash,
            txIds = txIds.distinct(),
            expiries = expiries,
            supersededTxIds = (existing.txIds + existing.supersededTxIds).distinct(),
            payload = claim.tag.payload ?: existing.payload,
            reason = claim.tag.reason ?: existing.reason,
        )
    }

    /** Only a LIVE entry of this marker that holds no ids is ever discarded by its sender. */
    fun mayDiscard(
        entry: SendAttemptEntry,
        startedAt: Long,
    ): Boolean = entry.state == SendAttemptState.LIVE && entry.startedAtMillis == startedAt && entry.txIds.isEmpty()

    fun mined(
        entry: SendAttemptEntry,
        height: Long,
    ): SendAttemptEntry? =
        when (entry.state) {
            SendAttemptState.LIVE, SendAttemptState.MINED ->
                entry.copy(
                    state = SendAttemptState.MINED,
                    minedHeight = maxOf(height, entry.minedHeight ?: height),
                    provenNotSentAtHeight = null,
                )
            SendAttemptState.NOT_SENT -> null
        }

    fun notSent(
        entry: SendAttemptEntry,
        tip: Long,
    ): SendAttemptEntry? =
        if (entry.state == SendAttemptState.LIVE) {
            entry.copy(state = SendAttemptState.NOT_SENT, provenNotSentAtHeight = tip, minedHeight = null)
        } else {
            null
        }

    fun reopened(entry: SendAttemptEntry): SendAttemptEntry? =
        if (entry.state == SendAttemptState.MINED) {
            entry.copy(state = SendAttemptState.LIVE, minedHeight = null, provenNotSentAtHeight = null, releaseAnchorHeight = null)
        } else {
            null
        }

    /** What the pass does with one entry. */
    sealed interface Step {
        /** Nothing is written. */
        data object Keep : Step

        /** Every id is mined, the highest at [height]: MINED with that height in one write. */
        data class Mined(
            val height: Long,
        ) : Step

        /** A MINED entry whose every id is reorg-deep: deleted (nothing was touched, so nothing is restored). */
        data object DeleteDeep : Step

        /** A MINED entry one of whose ids is unmined again or no longer listed: back to LIVE. */
        data object Reopen : Step

        /** The proof is complete: NOT_SENT. */
        data object NotSent : Step
    }

    /**
     * A MINED entry on a later pass: every id still listed mined keeps it (the highest height seen) until the tip is
     * [SendAttemptRules.reorgMargin] of that height past it (ZC-1554: kept in time across NU7), then it is deleted; any id listed unmined or no longer listed
     * sends it back to LIVE.
     */
    fun minedStep(
        entry: SendAttemptEntry,
        chain: ChainView,
    ): Step {
        val tip = chain.scannedTip ?: return Step.Keep
        val heights = entry.txIds.map { chain.byId[it]?.minedHeight }
        if (entry.txIds.isEmpty() || heights.any { it == null }) return Step.Reopen
        val highest = maxOf(heights.filterNotNull().max(), entry.minedHeight ?: 0L)
        return if (tip - highest >= SendAttemptRules.reorgMargin(highest)) Step.DeleteDeep else Step.Mined(highest)
    }

    /**
     * A LIVE entry: [verdict] is the resolver's and [proven] the proof's answer for it. Sent settles with the highest
     * mined height; a proven NotSent is committed; a partial, waiting, unattributed or unknown attempt is kept.
     */
    fun liveStep(
        entry: SendAttemptEntry,
        verdict: SendAttemptVerdict,
        proven: Boolean,
        chain: ChainView,
    ): Step =
        when (verdict) {
            SendAttemptVerdict.Sent -> {
                val highest = entry.txIds.mapNotNull { chain.byId[it]?.minedHeight }.maxOrNull()
                if (highest == null) Step.Keep else Step.Mined(highest)
            }
            SendAttemptVerdict.NotSent -> if (proven && chain.scannedTip != null) Step.NotSent else Step.Keep
            SendAttemptVerdict.Partial,
            SendAttemptVerdict.MinedUnattributed,
            SendAttemptVerdict.Unknown,
            is SendAttemptVerdict.Waiting,
            -> Step.Keep
        }

    /**
     * Whether the pass may judge [entry] at all. An entry with ids is judged only when the fresh read lists every one
     * of them (an id the wallet does not list is never proof: the wallet was replaced, not the chain). An entry with no
     * id is judged only when this build began it under a known account (the no-id rule); a record of an earlier build
     * with no id stays as it is.
     */
    fun judgeable(
        entry: SendAttemptEntry,
        chain: ChainView,
    ): Boolean =
        if (entry.txIds.isNotEmpty()) {
            entry.txIds.all { it in chain.byId }
        } else {
            entry.idsBeforeSubmit && entry.accountHash != null
        }

    /**
     * The no-id rule. An entry of this build with no ids that is not in flight never handed anything to the network,
     * so a mined own payment cannot be its transaction: every mined id counts as claimed elsewhere, and a mined
     * unrelated payment to the same contact can never leave the entry stuck. Unmined candidates still defer.
     */
    fun claimedForNoIdEntry(
        entry: SendAttemptEntry,
        chain: ChainView,
    ): Set<String> =
        if (entry.txIds.isEmpty() && entry.idsBeforeSubmit) {
            chain.transactions.filter { it.minedHeight != null }.mapTo(HashSet()) { it.txId }
        } else {
            emptySet()
        }

    /** The attempt the resolver judges for [entry]: one memo of 1,000 zatoshi to [peer] (or to nobody known). */
    fun attemptOf(
        entry: SendAttemptEntry,
        peer: String,
        thisProcess: String,
    ): SendAttempt =
        SendAttempt(
            peerAddress = peer,
            text = "",
            amountZatoshi = MEMO_CARRIER_ZATOSHI,
            rowTimeMillis = entry.startedAtMillis,
            markerMillis = entry.startedAtMillis,
            attemptHeight = entry.attemptHeight,
            txIds = entry.txIds,
            expiries = entry.expiries,
            legacy = false,
            anchorHeight = entry.releaseAnchorHeight,
            otherProcessSameAccount = entry.processId != null && entry.processId != thisProcess && entry.accountHash != null,
        )

    /** The carrier output of every memo these senders pay (HandshakeSpendConsent: 1,000 zatoshi). */
    const val MEMO_CARRIER_ZATOSHI = 1_000L
}
