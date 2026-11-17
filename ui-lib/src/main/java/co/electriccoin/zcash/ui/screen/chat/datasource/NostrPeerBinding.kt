package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.UnifiedAddressCheck

/**
 * M1 (fix-all 2.13.0, train T4b): one relay (NOSTR) key, one address.
 *
 * Every relay message is routed to a chat by its sender's relay key ([ZchatPreferences.findPeerByNostrPubkey]). A key
 * bound to two addresses used to route to whichever came first in a hash map: on the Fold, a contact code cut to 102
 * characters bound the HONOR's key to a broken copy of its address, and every HONOR reply went to that copy, a second
 * Shielded chat that quoted 34,000 zatoshi for a free contact. This file holds the pure rules: which address may take a
 * key ([isBindable]), which of several bound addresses a key routes to ([choose]), and the one bind every site uses
 * ([bindPeerNostrKey]).
 */
object NostrPeerBinding {
    /**
     * Whether a relay key may be bound to [address]: a mainnet Unified Address ("u1…") must pass the Bech32m check; any
     * other form (Sapling, testnet, or a test fixture) is left to the SDK checks of the path that uses it.
     */
    fun isBindable(address: String): Boolean = !address.startsWith(UA_PREFIX) || UnifiedAddressCheck.isMainnetUnifiedAddress(address)

    /** What [choose] ranks an address by, after [isBindable]. */
    data class Facts(
        val isContact: Boolean,
        val hasStoredMode: Boolean,
        val lastRowMillis: Long,
    )

    /**
     * The one address a relay key bound to [candidates] routes to: an address that passes [isBindable] first, then a
     * saved contact, then one with a stored mode, then the one with the most recent row, then the smallest string. The
     * same set always gives the same answer. [facts] is read only when there is more than one candidate.
     */
    fun choose(
        candidates: Collection<String>,
        facts: (String) -> Facts,
    ): String? {
        val distinct = candidates.distinct()
        if (distinct.size <= 1) return distinct.firstOrNull()
        val known = distinct.associateWith(facts)
        val rule =
            compareByDescending<String> { isBindable(it) }
                .thenByDescending { known.getValue(it).isContact }
                .thenByDescending { known.getValue(it).hasStoredMode }
                .thenByDescending { known.getValue(it).lastRowMillis }
                .thenBy { it }
        return distinct.sortedWith(rule).first()
    }

    private const val UA_PREFIX = "u1"
}

/** What [bindPeerNostrKey] did. [otherPeer] is the address the key already belongs to, for [NostrBindResult.KEY_BOUND_ELSEWHERE]. */
data class NostrBindOutcome(
    val result: NostrBindResult,
    val otherPeer: String? = null,
)

enum class NostrBindResult {
    /** The key is now bound to the address. */
    BOUND,

    /** The address already had this key; its relay was refreshed. */
    ALREADY_BOUND_SAME,

    /** The address had a different key: flagged as key-changed and unverified, never rebound (unchanged rule). */
    KEY_CHANGED,

    /** The key belongs to another address. Nothing was written. */
    KEY_BOUND_ELSEWHERE,

    /** Our own address, or our own relay key. Nothing was written. */
    SELF,

    /** The address fails [NostrPeerBinding.isBindable]. Nothing was written. */
    INVALID_ADDRESS,
}

/**
 * M1 — bind the relay key [pubkeyHex] (and [relayUrl], when given) to [peerAddress], at every site that learns a key
 * from a contact code, a scan or a key exchange. In order: an address that fails [NostrPeerBinding.isBindable] binds
 * nothing; our own address or our own relay key binds nothing; a key already bound to another address binds nothing
 * (reverse uniqueness; an address the #218 alias maps to the same peer counts as the same address), except that, when
 * [mayTakeFromBrokenHolder] is true, a holder that fails [NostrPeerBinding.isBindable] does not count (M1 fixup: a lone
 * cut copy from 2.12.4 would block the real address for good; the bind re-arms the one-time repair, which removes the
 * copy at the next start); an address that already has a different key is flagged key-changed and unverified and is not
 * rebound (the rule each site had). Runs under the store's lock, so two binds of one key cannot both pass the reverse
 * check.
 *
 * [mayTakeFromBrokenHolder] is true only where the user's own act supplies the code: the paste in New Chat and the scan
 * of a contact code. A received KEX or KEXACK leaves it false (T4b2-G1): the relay key does not sign the KEX, it is only
 * named in a memo field, so a stranger's first KEX naming a contact's key would otherwise take it from the cut copy,
 * route the contact's relay messages to the stranger's chat and, at the next start, move the contact's delivered rows
 * there. ZC-1466: a successful bind with [mayTakeFromBrokenHolder] also unblocks the key ([ZchatPreferences.unblockNostrPubkey]).
 */
fun ZchatPreferences.bindPeerNostrKey(
    peerAddress: String,
    pubkeyHex: String,
    relayUrl: String?,
    mayTakeFromBrokenHolder: Boolean = false,
): NostrBindOutcome =
    synchronized(this) {
        if (!NostrPeerBinding.isBindable(peerAddress)) return@synchronized NostrBindOutcome(NostrBindResult.INVALID_ADDRESS)
        if (isSelfAddress(peerAddress) || isOwnNostrPubkey(pubkeyHex)) return@synchronized NostrBindOutcome(NostrBindResult.SELF)
        val boundTo: String? = findPeerByNostrPubkey(pubkeyHex)
        // M1 fixup (G-M1-2): for the user's own paste or scan, a holder that fails isBindable (a copy of an address cut
        // by 2.12.4's lenient paste check) is no binding. findPeerByNostrPubkey ranks a bindable address first, so such a
        // holder means every holder is one. A KEX or KEXACK never takes the key from it (T4b2-G1).
        val brokenHolder = mayTakeFromBrokenHolder && boundTo != null && !NostrPeerBinding.isBindable(boundTo)
        if (boundTo != null && !brokenHolder && boundTo != peerAddress && !isSamePeer(boundTo, peerAddress)) {
            return@synchronized NostrBindOutcome(NostrBindResult.KEY_BOUND_ELSEWHERE, otherPeer = boundTo)
        }
        val existing = getPeerNostrPubkey(peerAddress)
        if (existing != null && !existing.equals(pubkeyHex, ignoreCase = true)) {
            setE2EKeyChanged(peerAddress, true)
            setE2EVerified(peerAddress, false)
            return@synchronized NostrBindOutcome(NostrBindResult.KEY_CHANGED)
        }
        if (existing == null) setPeerNostrPubkey(peerAddress, pubkeyHex)
        relayUrl?.let { setPeerNostrRelay(peerAddress, it) }
        // The key now routes here (a bindable address ranks first). The cut copy keeps its binding and its rows until
        // the next start, when the relay-key repair unbinds it and moves its delivered relay rows here; a screen that is
        // open now may still hold those rows.
        if (brokenHolder) rearmNostrKeyRepair()
        // ZC-1466: the user's own scan or paste of this contact's code is a deliberate trust act, so a block left by an
        // earlier Reject of this key goes (and its Declined entry). A KEX or KEXACK naming the key never unblocks it.
        if (mayTakeFromBrokenHolder) unblockNostrPubkey(pubkeyHex)
        NostrBindOutcome(if (existing == null) NostrBindResult.BOUND else NostrBindResult.ALREADY_BOUND_SAME)
    }

/** #218: two addresses the alias store maps to one canonical address are one peer. A store that answers no alias (null) is read as none. */
private fun ZchatPreferences.isSamePeer(
    a: String,
    b: String,
): Boolean {
    val canonicalA: String? = resolvePeerAddress(a)
    val canonicalB: String? = resolvePeerAddress(b)
    return (canonicalA ?: a) == (canonicalB ?: b)
}
