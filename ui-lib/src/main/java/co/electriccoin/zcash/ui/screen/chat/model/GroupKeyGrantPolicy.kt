package co.electriccoin.zcash.ui.screen.chat.model

/**
 * KI-23 (ZC-020, ZC-124) — every decision about PAYING for the answer to a member's group-key request.
 *
 * A member's "sync group keys" tap asks the creator's phone for the current key. The answer goes over the relays for
 * free when it can. Only when it cannot, and the user allowed automatic setup payments, is it sent on-chain (one
 * transaction). This object says when that payment may happen at all. It never decides whether a member may RECEIVE
 * the key (that is [ZMSGGroupProtocol.mayGrantKeyRequest]) and it has no say over a payment the user tapped and was
 * quoted for (Sync keys, Resend key), the owed-key pass, the key messages after a removal or rotation, or the sealed
 * relay route: none of them asks it.
 *
 * The rules:
 * 1. A member who says it holds the current key is never paid for: relays only.
 * 2. One paid delivery per member per key: once the count of the grant key is 1 or more, relays only, whatever the
 *    send-attempt ledger says about that payment (there is no automatic second payment in 2.13.0).
 * 3. At most [ZchatPreferences.MAX_GROUP_KEY_GRANT_PER_WINDOW] paid grants a day for the whole wallet: when the
 *    budget is used up, relays only (and no consent question).
 *
 * Pure: no store, no clock, no ledger.
 */
object GroupKeyGrantPolicy {
    /** Why a request is answered over the relays only. */
    enum class RelayOnlyReason {
        /** The member's request says it already holds the current key. */
        HOLDS_CURRENT_KEY,

        /** A paid delivery of this key to this member was already attempted. */
        ALREADY_PAID,

        /** The wallet's daily budget of automatic paid grants is used up. */
        BUDGET_EXHAUSTED,
    }

    sealed interface GrantRoute {
        /** Free over the relays, or not at all: nothing is built, asked or paid on-chain. */
        data class RelayOnly(
            val reason: RelayOnlyReason,
        ) : GrantRoute

        /** The answer may be paid on-chain, once, under the user's standing consent. */
        data object MayPay : GrantRoute
    }

    /**
     * The key one paid delivery is counted under: the group, the member's canonical address and the number of the key
     * being delivered (not the number the member says it has). [canonicalize] is the store's address resolver, so two
     * spellings of one member's address are one key.
     */
    fun grantKey(
        groupId: String,
        requester: String,
        epoch: Int,
        canonicalize: (String) -> String = { it },
    ): String = "$groupId|${canonicalize(requester)}|$epoch"

    /**
     * @param haveEpoch the key number the member's request says it holds
     * @param currentEpoch the number of the key that would be delivered
     * @param paidCount paid deliveries of that key to that member already attempted
     * @param budgetAvailable whether the wallet's daily budget still has a unit
     */
    fun decide(
        haveEpoch: Int,
        currentEpoch: Int,
        paidCount: Int,
        budgetAvailable: Boolean,
    ): GrantRoute =
        when {
            haveEpoch >= currentEpoch -> GrantRoute.RelayOnly(RelayOnlyReason.HOLDS_CURRENT_KEY)
            paidCount >= 1 -> GrantRoute.RelayOnly(RelayOnlyReason.ALREADY_PAID)
            !budgetAvailable -> GrantRoute.RelayOnly(RelayOnlyReason.BUDGET_EXHAUSTED)
            else -> GrantRoute.MayPay
        }
}
