package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.screen.chat.datasource.NostrBindResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.bindPeerNostrKey

/**
 * ZC-1460: the one rule that gives the built-in ZChat Team contact its relay (Nostr) key, so a newcomer can chat with
 * the Team free in Open. The key is the build input `ZCHAT_TEAM_NOSTR_PUBKEY` ([ZchatTeamConstants.NOSTR_PUBKEY]).
 */
object TeamNostrKey {
    private val HEX64 = Regex("[0-9a-fA-F]{64}")

    /** Whether [pubkeyHex] is a configured key: exactly 64 hex characters. Empty (or anything else) is off. */
    fun isConfigured(pubkeyHex: String): Boolean = HEX64.matches(pubkeyHex)

    /**
     * Binds [pubkeyHex] (and [relayUrl]) to [teamAddress] through the M1 bind, which refuses our own relay key (the
     * Team's own phone: SELF), a key another address holds and an address that fails the address check. Off (null,
     * nothing written) when the key is not [isConfigured].
     */
    fun bind(
        prefs: ZchatPreferences,
        teamAddress: String,
        pubkeyHex: String,
        relayUrl: String?,
    ): NostrBindResult? {
        if (!isConfigured(pubkeyHex)) return null
        return prefs.bindPeerNostrKey(teamAddress, pubkeyHex, relayUrl).result
    }
}
