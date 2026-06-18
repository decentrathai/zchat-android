package co.electriccoin.zcash.ui.screen.chat.routing

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRouterTest {

    private val payload = "ZMSG|v4|ABC12345|INIT|u1xxx|hello"
    private val ourPub = "a".repeat(64)
    private val ourRelay = "wss://relay.damus.io"
    private val theirPub = "b".repeat(64)
    private val theirRelay = "wss://nos.lol"
    private val convId = "ABC12345"

    @Test
    fun `Vault always goes shielded`() {
        val plan = routeOutgoing(
            payload = payload, mode = ConversationMode.VAULT,
            peerNostrPubkeyHex = theirPub, peerRelayUrl = theirRelay, ourBootSent = false,
            ourNostrPubkeyHex = ourPub, ourRelayUrl = ourRelay, convId = convId,
        )
        assertEquals(SendPlan.ShieldedMemo(payload), plan)
    }

    @Test
    fun `Open with known peer pubkey + relay routes to NOSTR`() {
        val plan = routeOutgoing(
            payload = payload, mode = ConversationMode.OPEN,
            peerNostrPubkeyHex = theirPub, peerRelayUrl = theirRelay, ourBootSent = false,
            ourNostrPubkeyHex = ourPub, ourRelayUrl = ourRelay, convId = convId,
        )
        assertEquals(SendPlan.NostrDirect(payload, theirPub, theirRelay), plan)
    }

    @Test
    fun `Open without peer info degrades to shielded (no silent drop)`() {
        val plan = routeOutgoing(
            payload = payload, mode = ConversationMode.OPEN,
            peerNostrPubkeyHex = null, peerRelayUrl = null, ourBootSent = false,
            ourNostrPubkeyHex = ourPub, ourRelayUrl = ourRelay, convId = convId,
        )
        assertTrue(plan is SendPlan.ShieldedMemo)
    }

    @Test
    fun `Tunnel first message emits ZBOOT and queues payload`() {
        val plan = routeOutgoing(
            payload = payload, mode = ConversationMode.TUNNEL,
            peerNostrPubkeyHex = null, peerRelayUrl = null, ourBootSent = false,
            ourNostrPubkeyHex = ourPub, ourRelayUrl = ourRelay, convId = convId,
            ourBootSignature = "sigB64",
        )
        val expected = SendPlan.BootstrapThenQueue(
            // v3 wire format with epoch 0 — routeOutgoing doesn't thread a rotation epoch (it's an unused
            // builder path; ChatViewModel constructs ZBOOTs with the live epoch directly), so it defaults to 0.
            bootMemo = "ZBOOT|v3|$convId|$ourPub|$ourRelay|0|sigB64",
            queuedPayload = payload,
        )
        assertEquals(expected, plan)
    }

    @Test
    fun `Tunnel after bootstrap routes to NOSTR`() {
        val plan = routeOutgoing(
            payload = payload, mode = ConversationMode.TUNNEL,
            peerNostrPubkeyHex = theirPub, peerRelayUrl = theirRelay, ourBootSent = true,
            ourNostrPubkeyHex = ourPub, ourRelayUrl = ourRelay, convId = convId,
        )
        assertEquals(SendPlan.NostrDirect(payload, theirPub, theirRelay), plan)
    }

    @Test
    fun `Tunnel — we have sent our boot but peer has not — still queue`() {
        // Half-handshake: our ZBOOT shipped but peer's reply boot hasn't arrived. We
        // still don't know their NOSTR pubkey so the message has to wait.
        val plan = routeOutgoing(
            payload = payload, mode = ConversationMode.TUNNEL,
            peerNostrPubkeyHex = null, peerRelayUrl = null, ourBootSent = true,
            ourNostrPubkeyHex = ourPub, ourRelayUrl = ourRelay, convId = convId,
        )
        assertTrue(plan is SendPlan.BootstrapThenQueue)
    }
}
