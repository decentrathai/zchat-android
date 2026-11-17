package co.electriccoin.zcash.ui.nostr

/**
 * RELAY-HEALTH (2.13.0, W-11/W-15): the public relays the app may use, compiled in. No remote list and no user entry:
 * each was checked before it was listed (29 Sep 2026: it serves kind-1059 reads for a random key without AUTH, accepts
 * a gift-wrap back-dated by 47 h from a fresh key, honours `since`, and kept the event), and is re-checked before each
 * release.
 *
 * Order matters: slot 1 starts with the first, slot 2 with the second, and a replacement is the first one that is not
 * active and not cooling down (RelayHealthPolicy).
 */
object RelayCatalog {
    /** Slot 1 by default: replaced relay.damus.io, which refuses every kind-1059 read with auth-required. */
    const val PRIMAL = "wss://relay.primal.net"

    /** Slot 2 by default, the compat slot: the only public relay ZChat 2.12.4 reads, so it keeps slot 2 when it works. */
    const val NOS_LOL = "wss://nos.lol"

    val PUBLIC: List<String> =
        listOf(
            PRIMAL,
            NOS_LOL,
            "wss://nostr.mom",
            "wss://relay.nos.social",
            "wss://nostr.oxtr.dev",
            "wss://offchain.pub",
            "wss://nostr-pub.wellorder.net",
        )
}
