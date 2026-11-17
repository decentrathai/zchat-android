package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.call.CallSignalEnvelope
import co.electriccoin.zcash.ui.call.CallSignalType
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ZC-1883 (owner W-146, "Block Team calls"): a call signal from the built-in ZChat Team contact must never ring, in any
 * mode. The Team peer is the address of the build input `BuildConfig.ZCHAT_TEAM_ADDRESS` (the address onboarding adds,
 * OnboardingGuideVM.completeOnboarding, and binds the Team relay key to through TeamNostrKey.bind). The fixture binds a
 * synthetic relay key to that address, as TeamNostrKey.bind or a handshake would, and drives [NostrChatBridge.dispatch].
 * A normal contact with a held relay key still rings in every mode.
 *
 * Fable review of ZC-1883: the Team is also matched through the peer's alias (prefs.resolvePeerAddress), and a dropped
 * Team signal is marked seen so a relay replay is not judged again.
 */
class Zc1883TeamCallSignalNeverRingsTest {
    private companion object {
        const val TEAM_PUBKEY = "fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98"
        const val FRIEND_PUBKEY = "0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d0a0b0c0d"
        const val FRIEND_ADDRESS = "usyntheticfriendaddressnotours"
        const val ALIAS_PUBKEY = "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"
        const val ALIAS_ADDRESS = "usyntheticoldteamreceiveaddress"
        val MODES = listOf(ConversationMode.VAULT, ConversationMode.TUNNEL, ConversationMode.OPEN)
        const val CALL_ID = "fedcba9876543210fedcba9876543210"
    }

    private val teamAddress: String = BuildConfig.ZCHAT_TEAM_ADDRESS

    private val seen = mutableListOf<String>()

    private fun prefs(mode: ConversationMode): ZchatPreferences {
        val keyOf = mapOf(teamAddress to TEAM_PUBKEY, FRIEND_ADDRESS to FRIEND_PUBKEY, ALIAS_ADDRESS to ALIAS_PUBKEY)
        val peerOf = keyOf.entries.associate { (address, key) -> key to address }
        val aliasOf = mapOf(ALIAS_ADDRESS to teamAddress)
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "findPeerByNostrPubkey" -> peerOf[args!![0] as String]
                "getPeerNostrPubkey" -> keyOf[args!![0] as String]
                "getConversationMode" -> mode
                "getConversationModeOrNull" -> mode
                "hasSeenNostrEvent" -> false
                "isNostrPubkeyBlocked" -> false
                "isSelfAddress" -> false
                "markNostrEventSeen" -> {
                    seen += args!![0] as String
                    null
                }
                "resolvePeerAddress" -> (args!![0] as String).let { aliasOf[it] ?: it }
                "toString" -> "Zc1883Prefs"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences
    }

    private fun delivered(
        mode: ConversationMode,
        sender: String,
        type: CallSignalType,
        eventId: String,
    ): Int {
        var count = 0
        NostrChatBridge.registerCallSignalHandler { _, _ -> count++ }
        NostrChatBridge.dispatch(
            NostrInboxManager.InboundDm(
                senderPubkeyHex = sender,
                content = CallSignalEnvelope(CALL_ID, type, "audio").serialize(),
                createdAtSec = System.currentTimeMillis() / 1000,
                eventId = eventId,
                rumorId = "r-$eventId",
            ),
            prefs(mode),
        )
        return count
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterCallSignalHandler()
    }

    @Test
    fun `a call signal from the Team relay key never rings, in Shielded, Tunnel and Open`() {
        for (mode in MODES) {
            for (type in CallSignalType.entries) {
                val eventId = "e-zc1883-team-$mode-${type.code}"
                assertEquals(
                    "$type from the ZChat Team in $mode must never reach the call handler",
                    0,
                    delivered(mode, TEAM_PUBKEY, type, eventId),
                )
                assertTrue("a dropped Team signal is marked seen ($eventId)", eventId in seen)
            }
        }
    }

    @Test
    fun `a call signal from an address that resolves to the Team never rings`() {
        for (mode in MODES) {
            assertEquals(
                "a RING from an alias of the ZChat Team in $mode must never reach the call handler",
                0,
                delivered(mode, ALIAS_PUBKEY, CallSignalType.RING, "e-zc1883-alias-$mode"),
            )
        }
    }

    @Test
    fun `control - a normal contact with a held relay key still rings, in Shielded, Tunnel and Open`() {
        for (mode in MODES) {
            val eventId = "e-zc1883-friend-$mode"
            assertEquals(
                "a RING from a normal contact in $mode must ring",
                1,
                delivered(mode, FRIEND_PUBKEY, CallSignalType.RING, eventId),
            )
            assertTrue("a delivered signal is marked seen ($eventId)", eventId in seen)
        }
    }
}
