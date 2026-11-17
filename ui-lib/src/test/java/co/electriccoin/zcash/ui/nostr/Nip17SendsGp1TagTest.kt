package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Round 8 MA1 (MEMBER_ANNOUNCE_DESIGN.md v2 §2.4) — every NIP-17 message this build sends says it can read the group
 * member announce: the rumor's tags are exactly `["p", <recipient>]`, `["zchat_cap","ironwood"]` and
 * `["zchat_cap","gp1"]`. The 2.13.0 test builds already send `ironwood` but cannot read a GP, which is why `gp1` is
 * a tag of its own. Like `ironwood` it lives only inside the encrypted kind-14 rumor, never on the seal or the wrap,
 * and carries no version number. Receiving is unchanged (Nip17SendsCapabilityTagTest).
 */
class Nip17SendsGp1TagTest {
    private val alice = NOSTRIdentity.fromSeed(ByteArray(64) { (0x21 + it).toByte() })
    private val bob = NOSTRIdentity.fromSeed(ByteArray(64) { (0x90 - it).toByte() })
    private val bobHex = bob.publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.unhex(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun send(
        content: String,
        expiresAtSec: Long? = null
    ) = Nip17.send(
        senderPriv = alice.privateKey,
        senderPub = alice.publicKey,
        recipientPub = bob.publicKey,
        content = content,
        createdAtSec = 1_790_000_000L,
        expiresAtSec = expiresAtSec,
    )

    private fun rumorTagsOf(giftWrapJson: String): Triple<List<List<String>>, String, String> {
        val wrap = NostrEvent.verify(giftWrapJson)
        val sealJson = Nip44Encryption.decrypt(wrap.content, bob.privateKey, wrap.pubkeyHex.unhex())
        val seal = NostrEvent.verify(sealJson)
        val rumorJson = Nip44Encryption.decrypt(seal.content, bob.privateKey, seal.pubkeyHex.unhex())
        return Triple(NostrEvent.parseTags(rumorJson), sealJson, giftWrapJson)
    }

    @Test
    fun `the sent rumor carries p, the ironwood tag and the gp1 tag, in that order`() {
        val (tags, _, _) = rumorTagsOf(send("hello group").giftWrapJson)
        assertEquals(
            listOf(listOf("p", bobHex), listOf("zchat_cap", "ironwood"), listOf("zchat_cap", "gp1"), listOf("zchat_cap", "zatt1")),
            tags,
        )
    }

    @Test
    fun `the gp1 tag is on every send, including an expiring call signal`() {
        val (tags, _, _) = rumorTagsOf(send("CALL:offer", expiresAtSec = 1_790_000_060L).giftWrapJson)
        assertEquals(listOf(listOf("zchat_cap", "gp1"), listOf("zchat_cap", "zatt1")), tags.takeLast(2))
    }

    @Test
    fun `the gp1 tag never leaves the encrypted rumor`() {
        val (tags, sealJson, wrapJson) = rumorTagsOf(send("inside only").giftWrapJson)
        assertEquals("fixture: the rumor has the tag", true, tags.contains(listOf("zchat_cap", "gp1")))
        for (outer in listOf(sealJson, wrapJson)) {
            // Structural, not a substring search: the outer content is random base64 ciphertext that can contain "gp1".
            val outerTags = NostrEvent.parseTags(outer)
            assertFalse(outerTags.any { tag -> "gp1" in tag || "zchat_cap" in tag })
        }
    }

    @Test
    fun `the receiver sees the gp1 tag and the same content`() {
        val sent = send("roster please")
        val received = Nip17.receive(sent.giftWrapJson, bob.privateKey)
        assertEquals("roster please", received.content)
        assertEquals(sent.rumorId, received.rumorId)
        assertEquals(
            listOf(listOf("p", bobHex), listOf("zchat_cap", "ironwood"), listOf("zchat_cap", "gp1"), listOf("zchat_cap", "zatt1")),
            received.rumorTags,
        )
        assertEquals(
            "2.12.4 reads the same content",
            "roster please",
            Nip17SendsCapabilityTagTest.receiveAs2124(sent.giftWrapJson, bob.privateKey).content
        )
    }
}
