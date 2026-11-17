package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.onboarding.TeamNostrKey
import co.electriccoin.zcash.ui.screen.onboarding.ZchatTeamConstants

/**
 * ZC-1883 (owner W-146, "Block Team calls"): whether an incoming call signal comes from the built-in ZChat Team contact
 * or the old Team contact, so the receive gate drops it in every mode. The Team is the peer address or the address it
 * resolves to (an alias), matched by [ZchatTeamConstants.isTeamAddress], or, when a Team relay key is configured
 * ([TeamNostrKey.isConfigured]), the sender's relay key. Pure.
 */
object TeamCallBlock {
    fun isTeamSender(
        peer: String,
        resolvedPeer: String,
        senderPubkeyHex: String,
        teamAddress: String = ZchatTeamConstants.ADDRESS,
        teamPubkeyHex: String = ZchatTeamConstants.NOSTR_PUBKEY,
        oldTeamSha256: String = ZchatTeamConstants.OLD_TEAM_ADDRESS_SHA256,
    ): Boolean =
        ZchatTeamConstants.isTeamAddress(peer, teamAddress, oldTeamSha256) ||
            ZchatTeamConstants.isTeamAddress(resolvedPeer, teamAddress, oldTeamSha256) ||
            (TeamNostrKey.isConfigured(teamPubkeyHex) && senderPubkeyHex.equals(teamPubkeyHex, ignoreCase = true))
}
