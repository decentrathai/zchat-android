package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.screen.chat.datasource.TeamContactMigration
import java.security.MessageDigest

object ZchatTeamConstants {
    /**
     * Flavor-resolved (ui-lib/build.gradle.kts network flavors): `zcashmainnet` carries the
     * production ZChat Team UA (byte-identical to the previously hardcoded constant);
     * `zcashtestnet` carries a valid testnet UA so the auto-added Team contact is a usable chat
     * destination on that flavor instead of a mainnet address every testnet send rejects.
     */
    val ADDRESS: String = BuildConfig.ZCHAT_TEAM_ADDRESS

    /** ZC-1460: the Team's relay (Nostr) public key, 64 hex, from the build input; empty = off ([TeamNostrKey]). */
    val NOSTR_PUBKEY: String = BuildConfig.ZCHAT_TEAM_NOSTR_PUBKEY
    const val NAME = "ZChat Team"

    /** The old Team address, known only by the sha256 of its trimmed lower-case form ([TeamContactMigration]). */
    const val OLD_TEAM_ADDRESS_SHA256: String = TeamContactMigration.OLD_TEAM_ADDRESS_SHA256

    /**
     * ZC-1883 (owner W-146, "Block Team calls"): whether [address] is the built-in Team contact ([ADDRESS]) or the old
     * Team contact (by [OLD_TEAM_ADDRESS_SHA256]). Calls with either are off: their call signals never ring and their
     * chat shows no Call buttons and no "calls locked" banner.
     */
    fun isTeamAddress(
        address: String,
        teamAddress: String = ADDRESS,
        oldTeamSha256: String = OLD_TEAM_ADDRESS_SHA256,
    ): Boolean = matchesTeamAddress(address, teamAddress) || isOldTeamAddress(address, oldTeamSha256)

    /** Pure: sha256 of [address] trimmed and lower-cased is [oldTeamSha256]; a blank [address] never matches. */
    fun isOldTeamAddress(
        address: String,
        oldTeamSha256: String = OLD_TEAM_ADDRESS_SHA256,
    ): Boolean = address.isNotBlank() && sha256(address.trim().lowercase()) == oldTeamSha256

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Pure: [address] is [teamAddress], ignoring case and surrounding spaces; a blank [teamAddress] matches nothing. */
    fun matchesTeamAddress(
        address: String,
        teamAddress: String,
    ): Boolean = teamAddress.isNotBlank() && address.trim().equals(teamAddress.trim(), ignoreCase = true)
}
