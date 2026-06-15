package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-17 private DM = NIP-59 gift-wrap of a NIP-44-sealed rumor.
 *
 * Three layers:
 *   1. Rumor (kind 14): unsigned event with the actual message; carries created_at + p tag.
 *   2. Seal (kind 13): signed by SENDER. content = NIP-44(serialize(rumor), sender_priv, recipient_pub).
 *   3. Gift wrap (kind 1059): signed by a one-time RANDOM key. content = NIP-44(serialize(seal),
 *      random_priv, recipient_pub). created_at randomized within last 48h to defeat timing
 *      correlation; p tag = recipient_pub.
 *
 * The recipient unwraps in reverse, verifies the seal's BIP-340 signature, and reads
 * the rumor. The gift wrap signature is by a random one-time key so the sender's
 * identity is never visible to relays.
 */
class Nip17GiftWrapTest {

    @Test
    fun `seal and unwrap round-trip recovers original DM`() {
        val alice = NOSTRIdentity.fromSeed(SEED_ALICE)
        val bob = NOSTRIdentity.fromSeed(SEED_BOB)

        val dm = Nip17.send(
            senderPriv = alice.privateKey,
            senderPub = alice.publicKey,
            recipientPub = bob.publicKey,
            content = "Tunnel handshake complete. Hi Bob.",
            createdAtSec = 1_780_000_000L,
        )

        // Wire format = signed kind-1059 JSON event ready to push to a relay.
        val received = Nip17.receive(dm.giftWrapJson, recipientPriv = bob.privateKey)

        assertEquals("Tunnel handshake complete. Hi Bob.", received.content)
        assertEquals(alice.publicKey.toList(), received.senderPubkey.toList())
        assertEquals(1_780_000_000L, received.createdAtSec)
    }

    @Test
    fun `wire envelope hides the real sender pubkey`() {
        val alice = NOSTRIdentity.fromSeed(SEED_ALICE)
        val bob = NOSTRIdentity.fromSeed(SEED_BOB)

        val dm = Nip17.send(
            senderPriv = alice.privateKey,
            senderPub = alice.publicKey,
            recipientPub = bob.publicKey,
            content = "secret",
        )

        // The kind-1059 envelope is signed by a random key, not Alice.
        val pubkeyHex = NostrEvent.parsePubkey(dm.giftWrapJson)
        assertNotEquals(alice.publicKey.toHexLower(), pubkeyHex)
    }

    @Test
    fun `gift wrap created_at is jittered into the past`() {
        val alice = NOSTRIdentity.fromSeed(SEED_ALICE)
        val bob = NOSTRIdentity.fromSeed(SEED_BOB)

        val now = 1_780_000_000L
        val dm = Nip17.send(
            senderPriv = alice.privateKey,
            senderPub = alice.publicKey,
            recipientPub = bob.publicKey,
            content = "x",
            createdAtSec = now,
        )
        val wrapTs = NostrEvent.parseCreatedAt(dm.giftWrapJson)
        val twoDays = 2L * 24 * 60 * 60
        assertTrue(
            "wrap ts $wrapTs should be in [now-2d, now]",
            wrapTs in (now - twoDays)..now,
        )
    }

    @Test
    fun `mismatched recipient can't decrypt`() {
        val alice = NOSTRIdentity.fromSeed(SEED_ALICE)
        val bob = NOSTRIdentity.fromSeed(SEED_BOB)
        val charlie = NOSTRIdentity.fromSeed(SEED_CHARLIE)

        val dm = Nip17.send(
            senderPriv = alice.privateKey,
            senderPub = alice.publicKey,
            recipientPub = bob.publicKey,
            content = "for Bob's eyes only",
        )

        var threw = false
        try {
            Nip17.receive(dm.giftWrapJson, recipientPriv = charlie.privateKey)
        } catch (t: Throwable) {
            threw = true
        }
        assertTrue("Charlie must NOT decrypt Bob's DM", threw)
    }

    @Test
    fun `forged seal signature fails verification`() {
        val alice = NOSTRIdentity.fromSeed(SEED_ALICE)
        val bob = NOSTRIdentity.fromSeed(SEED_BOB)
        val mallory = NOSTRIdentity.fromSeed(SEED_CHARLIE)

        // Mallory tries to send Bob a DM but claims to be Alice in the seal payload.
        val forged = Nip17.sendForgingSender(
            actualSenderPriv = mallory.privateKey,
            claimedSenderPub = alice.publicKey,
            recipientPub = bob.publicKey,
            content = "Bob, this isn't really from Alice",
        )

        var threw = false
        try {
            Nip17.receive(forged.giftWrapJson, recipientPriv = bob.privateKey)
        } catch (t: Throwable) {
            threw = true
        }
        assertTrue("Forged seal sig must throw at receive()", threw)
    }

    companion object {
        private val SEED_ALICE = ByteArray(64) { (0x10 + it).toByte() }
        private val SEED_BOB = ByteArray(64) { (0xA0 - it).toByte() }
        private val SEED_CHARLIE = ByteArray(64) { (0x50 + it).toByte() }
    }
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
