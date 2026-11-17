package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ZC-1337 (phone matrix 1 Nov, 01:29, the worst of that run): HONOR release held Seeker's ZBOOT ("Connection held back"), so
 * Seeker's relay key was bound to no contact. Seeker's free message of 01:29:11 said "Sent to the network"; on HONOR
 * dispatch found no peer for that key and routeUnknownPubkeyAsRequest dropped it as "not an INIT". The line then moves on, so
 * after "Mark as verified" nothing brings it back: at 01:38 it was still missing while a later message arrived in 9 s.
 *
 * Must hold: a relay message from the key a held ZBOOT names is kept (held, or a redelivery asked for) until the hold ends. A
 * stranger's is still dropped. The seam is reached by name, as FundsConfirmingChat does, so this compiles before the fix:
 * `ZchatPreferences.findPeerByHeldBootNostrPubkey(hex)`, the contact whose held ZBOOT names that relay key, or null.
 */
class RelayMessageDuringHeldConnectionIsNotLostTest {
    private companion object {
        val HELD_SENDER = "ab".repeat(32)
        val STRANGER = "cd".repeat(32)
        const val PEER = "usyntheticzc1337heldpeer"
    }

    private val seen = mutableListOf<String>()

    private fun store(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "findPeerByNostrPubkey" -> null
                "findPeerByHeldBootNostrPubkey" -> if (args!![0] == HELD_SENDER) PEER else null
                "markNostrEventSeen" -> {
                    seen += args!![0] as String
                    null
                }
                "toString" -> "HeldConnectionStore"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
            }
        } as ZchatPreferences

    private fun dm(
        sender: String,
        id: String,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = sender,
        content = "synthetic free message during the hold",
        createdAtSec = System.currentTimeMillis() / 1000,
        eventId = id,
        rumorId = "r-$id",
    )

    private fun drain() {
        NostrChatBridge.consumePendingRedelivery()
    }

    @Before
    fun setUp() = drain()

    @After
    fun tearDown() = drain()

    @Test
    fun `control - a stranger's message is still dropped`() {
        NostrChatBridge.dispatch(dm(STRANGER, "zc1337-stranger"), store())

        assertTrue("fixture: the bridge kept a stranger's message", NostrChatBridge.isRelayBacklogSettled())
    }

    @Test
    fun `a message from the key a held ZBOOT names is kept for after the hold`() {
        NostrChatBridge.dispatch(dm(HELD_SENDER, "zc1337-held"), store())

        assertFalse(
            "dropped: the sender reads 'Sent to the network', the receiver never gets it, and the relay line moves past it",
            NostrChatBridge.isRelayBacklogSettled(),
        )
        assertTrue("marked handled though no chat took it", "zc1337-held" !in seen)
    }
}
