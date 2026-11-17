package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.model.ChatCallAffordance
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.onboarding.ZchatTeamConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * ZC-1883 (owner W-146, "Block Team calls"), Fable review follow-ups. Pure rules:
 * - [ZchatTeamConstants.matchesTeamAddress]: the Team address match (blank build value never matches; case and
 *   surrounding spaces do not matter; another address does not match);
 * - [TeamCallBlock.isTeamSender]: the receive gate's Team test (the peer, its resolved alias, or, when a Team relay key
 *   is configured, the sender's key);
 * - [ChatCallAffordance]: one rule for the chat's Call buttons and its "calls locked" banner, both off on the Team chat.
 */
class Zc1883TeamCallRulesTest {
    private companion object {
        const val TEAM = "usyntheticteamaddressforthistestonly"
        const val OTHER = "usyntheticotheraddressforthistest"
        const val TEAM_KEY = "fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98"
        const val OTHER_KEY = "0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d"

        // Owner W-146 answer: the OLD Team contact is blocked too. It is known only by the sha256 of its trimmed,
        // lower-case address (TeamContactMigration.OLD_TEAM_ADDRESS_SHA256); the test uses a synthetic stand-in.
        const val OLD_TEAM = "usyntheticoldteamaddressstandin"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private val oldTeamSha = sha256(OLD_TEAM)

    @Test
    fun `old Team contact - matched by the hash of its trimmed lower-case address, never by blank or another address`() {
        assertTrue(ZchatTeamConstants.isOldTeamAddress(OLD_TEAM, oldTeamSha))
        assertTrue(ZchatTeamConstants.isOldTeamAddress("  ${OLD_TEAM.uppercase()} ", oldTeamSha))
        assertFalse(ZchatTeamConstants.isOldTeamAddress(OTHER, oldTeamSha))
        assertFalse(ZchatTeamConstants.isOldTeamAddress(" ", sha256("")))
        assertTrue(ZchatTeamConstants.isTeamAddress(OLD_TEAM, teamAddress = TEAM, oldTeamSha256 = oldTeamSha))
        assertTrue(ZchatTeamConstants.isTeamAddress(TEAM, teamAddress = TEAM, oldTeamSha256 = oldTeamSha))
        assertFalse(ZchatTeamConstants.isTeamAddress(OTHER, teamAddress = TEAM, oldTeamSha256 = oldTeamSha))
    }

    @Test
    fun `old Team contact - no ring, no Call buttons, no calls-locked banner`() {
        assertTrue(
            TeamCallBlock.isTeamSender(OLD_TEAM, OLD_TEAM, OTHER_KEY, teamAddress = TEAM, teamPubkeyHex = "", oldTeamSha256 = oldTeamSha),
        )
        assertTrue(
            TeamCallBlock.isTeamSender(OTHER, OLD_TEAM, OTHER_KEY, teamAddress = TEAM, teamPubkeyHex = "", oldTeamSha256 = oldTeamSha),
        )
        val isTeamChat = ZchatTeamConstants.isTeamAddress(OLD_TEAM, teamAddress = TEAM, oldTeamSha256 = oldTeamSha)
        for (mode in ConversationMode.entries) {
            assertFalse(ChatCallAffordance.buttonsShown(mode, hasNostrCallChannel = true, isTeamChat = isTeamChat))
            assertFalse(ChatCallAffordance.bannerCallsSupported(mode, isTeamChat = isTeamChat))
        }
    }

    @Test
    fun `the real build values - the old Team hash is the migration's constant`() {
        assertTrue(ZchatTeamConstants.OLD_TEAM_ADDRESS_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(ZchatTeamConstants.isTeamAddress(OTHER))
    }

    @Test
    fun `Team address match - exact, case, spaces, other address, blank build value`() {
        assertTrue(ZchatTeamConstants.matchesTeamAddress(TEAM, TEAM))
        assertTrue(ZchatTeamConstants.matchesTeamAddress(TEAM.uppercase(), TEAM))
        assertTrue(ZchatTeamConstants.matchesTeamAddress("  $TEAM \n", TEAM))
        assertTrue(ZchatTeamConstants.matchesTeamAddress(TEAM, " $TEAM "))
        assertFalse(ZchatTeamConstants.matchesTeamAddress(OTHER, TEAM))
        assertFalse("a blank build value matches nothing", ZchatTeamConstants.matchesTeamAddress("", ""))
        assertFalse("a blank build value matches nothing", ZchatTeamConstants.matchesTeamAddress(OTHER, "  "))
        assertFalse("a blank peer never matches", ZchatTeamConstants.matchesTeamAddress(" ", TEAM))
    }

    @Test
    fun `receive gate - the Team by peer, by resolved alias, or by its configured relay key`() {
        assertTrue(TeamCallBlock.isTeamSender(TEAM, TEAM, OTHER_KEY, teamAddress = TEAM, teamPubkeyHex = ""))
        assertTrue(TeamCallBlock.isTeamSender(OTHER, TEAM, OTHER_KEY, teamAddress = TEAM, teamPubkeyHex = ""))
        assertTrue(TeamCallBlock.isTeamSender(OTHER, OTHER, TEAM_KEY, teamAddress = TEAM, teamPubkeyHex = TEAM_KEY))
        assertTrue(
            TeamCallBlock.isTeamSender(OTHER, OTHER, TEAM_KEY.uppercase(), teamAddress = TEAM, teamPubkeyHex = TEAM_KEY),
        )
    }

    @Test
    fun `receive gate - a normal contact is not the Team, and an unset Team key matches no sender`() {
        assertFalse(TeamCallBlock.isTeamSender(OTHER, OTHER, OTHER_KEY, teamAddress = TEAM, teamPubkeyHex = TEAM_KEY))
        assertFalse(TeamCallBlock.isTeamSender(OTHER, OTHER, TEAM_KEY, teamAddress = TEAM, teamPubkeyHex = ""))
        assertFalse(TeamCallBlock.isTeamSender(OTHER, OTHER, "", teamAddress = TEAM, teamPubkeyHex = "not-a-key"))
        assertFalse(TeamCallBlock.isTeamSender(OTHER, OTHER, OTHER_KEY, teamAddress = "", teamPubkeyHex = ""))
    }

    @Test
    fun `chat call affordance - no Call buttons and no calls-locked banner on the Team chat, in any mode`() {
        for (mode in ConversationMode.entries) {
            for (channel in listOf(false, true)) {
                assertFalse(
                    "buttons on the Team chat ($mode, channel=$channel)",
                    ChatCallAffordance.buttonsShown(mode, channel, isTeamChat = true),
                )
            }
            assertFalse("calls-locked banner on the Team chat ($mode)", ChatCallAffordance.bannerCallsSupported(mode, isTeamChat = true))
        }
    }

    @Test
    fun `chat call affordance - a normal chat keeps the ZC-1880 rule`() {
        assertTrue(ChatCallAffordance.buttonsShown(ConversationMode.VAULT, hasNostrCallChannel = true, isTeamChat = false))
        assertFalse(ChatCallAffordance.buttonsShown(ConversationMode.VAULT, hasNostrCallChannel = false, isTeamChat = false))
        assertTrue(ChatCallAffordance.buttonsShown(ConversationMode.OPEN, hasNostrCallChannel = false, isTeamChat = false))
        assertTrue(ChatCallAffordance.buttonsShown(ConversationMode.TUNNEL, hasNostrCallChannel = false, isTeamChat = false))
        assertFalse(ChatCallAffordance.bannerCallsSupported(ConversationMode.VAULT, isTeamChat = false))
        assertTrue(ChatCallAffordance.bannerCallsSupported(ConversationMode.OPEN, isTeamChat = false))
        assertTrue(ChatCallAffordance.bannerCallsSupported(ConversationMode.TUNNEL, isTeamChat = false))
    }
}
