package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.screen.chat.datasource.NostrBindResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyStore
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1460 (OT213b owner finding, 2 Nov 2026; lane L3; plan critique M6; FIX_PLAN B4 "ZC-1460 plumbing"): the built-in
 * ZChat Team contact is only a Zcash address. No relay (Nostr) key exists for it anywhere, so a newcomer who picks the
 * Team contact in Open hits "needs code" and the chat falls back to a paid mode.
 *
 * What it must be (plumbing only; the owner supplies the value and who answers it):
 * - a build input `ZCHAT_TEAM_NOSTR_PUBKEY` in BuildConfig for every variant, read as `ZchatTeamConstants.NOSTR_PUBKEY`;
 *   the value is empty (off) or exactly 64 hex characters (an x-only public key; never a secret);
 * - one bind rule, `co.electriccoin.zcash.ui.screen.onboarding.TeamNostrKey` (a Kotlin object) with
 *   `bind(prefs: ZchatPreferences, teamAddress: String, pubkeyHex: String, relayUrl: String?): NostrBindResult?`:
 *   empty or not 64 hex = off (null, nothing written); otherwise the M1 `bindPeerNostrKey`, so our own relay key (the
 *   Team's own phone) is refused (SELF, nothing written);
 * - onboarding calls that rule when it adds the Team contact (secondary source check).
 *
 * The new names are read by reflection so the batch's tests compile before the fix. Every key here is synthetic.
 */
class TeamNostrKeyBuildInputTest {
    private val team = SyntheticUnifiedAddress.mainnet(14_601)
    private val key = RelayKeyBinding.relayKey(14_601)

    // ------------------------------------------------------------------ the build input

    @Test
    fun `the Team relay key build input exists and is empty or 64 hex`() {
        val buildConfig = Class.forName("co.electriccoin.zcash.ui.BuildConfig")
        val field =
            runCatching { buildConfig.getField("ZCHAT_TEAM_NOSTR_PUBKEY") }.getOrNull()
                ?: fail("BuildConfig has no ZCHAT_TEAM_NOSTR_PUBKEY build input (no Team relay key can ever be configured)")
        val value = field.get(null) as? String ?: fail("ZCHAT_TEAM_NOSTR_PUBKEY is not a String")
        assertTrue(
            value.isEmpty() || HEX64.matches(value),
            "ZCHAT_TEAM_NOSTR_PUBKEY is neither empty (off) nor 64 hex characters (length ${value.length})",
        )
        assertEquals(value, teamConstantKey(), "ZchatTeamConstants.NOSTR_PUBKEY is not the build input")
    }

    // ------------------------------------------------------------------ the bind rule

    @Test
    fun `an empty Team key is off - nothing is bound`() {
        val store = RelayKeyStore()
        assertNull(bind(store.proxy, team, "", RelayKeyBinding.RELAY), "an empty Team key bound something")
        assertTrue(store.writes.isEmpty(), "an empty Team key wrote to the store: ${store.writes}")
    }

    @Test
    fun `a malformed Team key is off - nothing is bound`() {
        val store = RelayKeyStore()
        assertNull(bind(store.proxy, team, "abc123", RelayKeyBinding.RELAY), "a malformed Team key bound something")
        assertTrue(store.writes.isEmpty(), "a malformed Team key wrote to the store: ${store.writes}")
    }

    @Test
    fun `a set Team key is bound to the Team address with its relay`() {
        val store = RelayKeyStore()
        assertEquals(NostrBindResult.BOUND, bind(store.proxy, team, key, RelayKeyBinding.RELAY))
        assertEquals(key, store.pubkeys[team], "the Team key is not bound to the Team address")
        assertEquals(RelayKeyBinding.RELAY, store.relays[team], "the Team relay is not stored")
    }

    @Test
    fun `the Team key is refused when it is our own relay key`() {
        val store = RelayKeyStore().apply { ownRelayKeys += key }
        assertEquals(
            NostrBindResult.SELF,
            bind(store.proxy, team, key, RelayKeyBinding.RELAY),
            "our own relay key was bound to the Team contact",
        )
        assertTrue(store.pubkeys.isEmpty(), "our own relay key was written as the Team's key")
    }

    // ------------------------------------------------------------------ onboarding wiring (secondary)

    @Test
    fun `onboarding binds the Team key through the one rule when it adds the Team contact`() {
        val source =
            listOf(
                File("src/main/java/co/electriccoin/zcash/ui/screen/onboarding/viewmodel/OnboardingGuideVM.kt"),
                File("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/onboarding/viewmodel/OnboardingGuideVM.kt"),
            ).firstOrNull { it.isFile }?.readText() ?: fail("OnboardingGuideVM.kt not found from ${File(".").absolutePath}")
        assertTrue(source.contains("TeamNostrKey.bind("), "onboarding adds the Team contact without binding the Team relay key")
    }

    // ------------------------------------------------------------------ reflection on the planned names

    private fun teamConstantKey(): String {
        val cls = ZchatTeamConstants::class.java
        val viaGetter = runCatching { cls.getMethod("getNOSTR_PUBKEY").invoke(ZchatTeamConstants) as String }.getOrNull()
        val viaField = runCatching { cls.getField("NOSTR_PUBKEY").get(null) as String }.getOrNull()
        return viaGetter ?: viaField ?: fail("ZchatTeamConstants has no NOSTR_PUBKEY")
    }

    private fun bind(
        prefs: ZchatPreferences,
        teamAddress: String,
        pubkeyHex: String,
        relayUrl: String?,
    ): NostrBindResult? {
        val cls =
            runCatching { Class.forName(TEAM_NOSTR_KEY) }.getOrNull()
                ?: fail("$TEAM_NOSTR_KEY does not exist (no Team relay key bind rule)")
        val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull() ?: fail("$TEAM_NOSTR_KEY is not an object")
        val method =
            runCatching {
                cls.getMethod("bind", ZchatPreferences::class.java, String::class.java, String::class.java, String::class.java)
            }.getOrNull() ?: fail("$TEAM_NOSTR_KEY has no bind(ZchatPreferences, String, String, String?)")
        return try {
            method.invoke(instance, prefs, teamAddress, pubkeyHex, relayUrl) as NostrBindResult?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        const val TEAM_NOSTR_KEY = "co.electriccoin.zcash.ui.screen.onboarding.TeamNostrKey"
        val HEX64 = Regex("[0-9a-fA-F]{64}")
    }
}
