package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * Bridge 1 (B3): every free (Open, NIP-17) message carries the rumor tag ["zchat_cap","ironwood"],
 * so a peer can tell an Ironwood-capable ZChat from 2.12.4 without anything visible on the relay.
 *
 * Rules pinned here:
 * - the tag lives ONLY in the kind-14 rumor (encrypted twice), never in the seal or the gift wrap;
 *   it carries no version number;
 * - a 2.12.4 phone reads the tagged message exactly as before: [receiveAs2124] is a verbatim copy of
 *   `Nip17.receive` at a1fb4136f (v2.12.4), which returns the same content, and an id equal to
 *   [Nip17.SentMessage.rumorId];
 * - content that looks like a tag is carried unchanged and never turns into a tag;
 * - a message near NIP-44's 65,535-byte plaintext limit either sends and round-trips, or is refused
 *   with an [IllegalArgumentException] before anything leaves the phone;
 * - [Nip17.receive] exposes the rumor's tags, and a tags array it can't read never makes it drop the
 *   message.
 */
class Nip17SendsCapabilityTagTest {
    private val alice = NOSTRIdentity.fromSeed(SEED_ALICE)
    private val bob = NOSTRIdentity.fromSeed(SEED_BOB)
    private val bobHex = bob.publicKey.hex()

    private fun sendToBob(content: String, createdAtSec: Long = 1_790_000_000L) =
        Nip17.send(
            senderPriv = alice.privateKey,
            senderPub = alice.publicKey,
            recipientPub = bob.publicKey,
            content = content,
            createdAtSec = createdAtSec,
        )

    @Test
    fun capability_tag_is_in_the_rumor_only_never_in_the_seal_or_the_wrap() {
        val sent = sendToBob("hello from an Ironwood phone")
        val layers = openLayers(sent.giftWrapJson)

        assertEquals(
            "rumor tags: the recipient p tag, then the capability tag (two strings, no version)",
            """[["p","$bobHex"],["zchat_cap","ironwood"],["zchat_cap","gp1"],["zchat_cap","zatt1"]]""",
            rawTags(layers.rumorJson),
        )
        assertEquals("seal carries no tags", "[]", layers.seal.tagsRaw)
        assertEquals("wrap carries only the recipient p tag", """[["p","$bobHex"]]""", layers.wrap.tagsRaw)
        for (outer in listOf(sent.giftWrapJson, layers.sealJson)) {
            assertFalse("capability tag leaked outside the rumor", outer.contains("zchat_cap"))
            assertFalse("capability value leaked outside the rumor", outer.contains("ironwood"))
        }
    }

    @Test
    fun capability_tag_is_on_every_send_including_expiring_call_signals() {
        val sent =
            Nip17.send(
                senderPriv = alice.privateKey,
                senderPub = alice.publicKey,
                recipientPub = bob.publicKey,
                content = "CALL:offer",
                expiresAtSec = 1_790_000_060L,
            )
        val layers = openLayers(sent.giftWrapJson)
        assertTrue(rawTags(layers.rumorJson).contains("""["zchat_cap","ironwood"]"""))
        assertEquals(
            """[["p","$bobHex"],["expiration","1790000060"]]""",
            layers.wrap.tagsRaw,
        )
    }

    @Test
    fun a_2124_receiver_reads_the_tagged_message_identically_and_agrees_on_the_id() {
        val text = "Open chat line — with accents: áéí and an emoji 😀"
        val sent = sendToBob(text, createdAtSec = 1_790_000_123L)

        val old = receiveAs2124(sent.giftWrapJson, bob.privateKey)
        assertEquals(text, old.content)
        assertEquals(alice.publicKey.toList(), old.senderPubkey.toList())
        assertEquals(1_790_000_123L, old.createdAtSec)
        assertEquals("2.12.4 must compute the sender's rumor id", sent.rumorId, old.rumorId)
        assertEquals(64, sent.rumorId.length)

        // The id is the NIP-01 id of the rumor as it travels (tag included), so both sides agree.
        val rumorJson = openLayers(sent.giftWrapJson).rumorJson
        assertEquals(nip01Id(rumorJson), sent.rumorId)

        val now = Nip17.receive(sent.giftWrapJson, bob.privateKey)
        assertEquals(text, now.content)
        assertEquals(sent.rumorId, now.rumorId)
        assertEquals(old.eventId, now.eventId)
    }

    @Test
    fun receive_exposes_the_rumor_tags() {
        val sent = sendToBob("tags please")
        val received = Nip17.receive(sent.giftWrapJson, bob.privateKey)
        assertEquals(
            listOf(listOf("p", bobHex), listOf("zchat_cap", "ironwood"), listOf("zchat_cap", "gp1"), listOf("zchat_cap", "zatt1")),
            received.rumorTags,
        )
    }

    @Test
    fun receive_of_a_2124_style_rumor_without_the_tag_has_no_capability_tag() {
        val wrap = handBuiltWrap(tagsJson = """[["p","$bobHex"]]""", content = "sent by 2.12.4")
        val received = Nip17.receive(wrap, bob.privateKey)
        assertEquals("sent by 2.12.4", received.content)
        assertEquals(listOf(listOf("p", bobHex)), received.rumorTags)
    }

    @Test
    fun an_unreadable_tags_array_never_drops_the_message() {
        // Another NIP-17 client may put numbers, objects or nested arrays in its tags. The message
        // must still arrive; only the tags we can't read are left out.
        for (tags in listOf(
            """[["p","$bobHex"],["n",5]]""",
            """[["p","$bobHex"],{"k":"v"}]""",
            """[["p","$bobHex"],[["nested"]]]""",
            """[]""",
        )) {
            val wrap = handBuiltWrap(tagsJson = tags, content = "from another client")
            val received = Nip17.receive(wrap, bob.privateKey)
            assertEquals("tags=$tags", "from another client", received.content)
            assertFalse("tags=$tags", received.rumorTags.any { it.firstOrNull() == "zchat_cap" })
        }
    }

    @Test
    fun tag_like_content_is_carried_unchanged_and_never_becomes_a_tag() {
        val tricky =
            listOf(
                """["zchat_cap","ironwood"]""",
                """"tags":[["zchat_cap","ironwood2"]]""",
                """{"id":"00","tags":[["zchat_cap","x"]],"content":"inner"}""",
                "\"],[\"zchat_cap\",\"evil\"]]",
                "zchat_cap ironwood",
                "back\\slash \"quote\" \n newline \t tab",
            )
        for (content in tricky) {
            val sent = sendToBob(content)
            val old = receiveAs2124(sent.giftWrapJson, bob.privateKey)
            val now = Nip17.receive(sent.giftWrapJson, bob.privateKey)
            assertEquals("2.12.4 content for <$content>", content, old.content)
            assertEquals("HEAD content for <$content>", content, now.content)
            assertEquals(sent.rumorId, old.rumorId)
            assertEquals(sent.rumorId, now.rumorId)
            assertEquals(
                "exactly the real capability tags, for <$content>",
                listOf(listOf("zchat_cap", "ironwood"), listOf("zchat_cap", "gp1"), listOf("zchat_cap", "zatt1")),
                now.rumorTags.filter { it.firstOrNull() == "zchat_cap" },
            )
        }
    }

    @Test
    fun the_longest_message_the_composer_allows_sends_with_the_tag() {
        // The composer caps input at 5,000 characters; 3-byte UTF-8 characters are the worst case.
        val content = "€".repeat(5_000)
        val sent = sendToBob(content)
        val old = receiveAs2124(sent.giftWrapJson, bob.privateKey)
        assertEquals(content, old.content)
        assertEquals(sent.rumorId, old.rumorId)
        assertTrue(rawTags(openLayers(sent.giftWrapJson).rumorJson).contains("zchat_cap"))
    }

    @Test
    fun a_message_near_the_65535_byte_limit_sends_or_is_refused_cleanly() {
        // The gift wrap encrypts the whole seal, so the real ceiling on content is lower than 65,535.
        // Find it, then check both sides of it.
        var lo = 1
        var hi = 65_535
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (trySend("a".repeat(mid)) != null) lo = mid else hi = mid - 1
        }
        val largest = lo
        assertTrue("largest sendable content is $largest bytes", largest in 30_000 until 65_535)

        val atLimit = "a".repeat(largest)
        val sent = sendToBob(atLimit)
        assertEquals(atLimit, receiveAs2124(sent.giftWrapJson, bob.privateKey).content)
        val now = Nip17.receive(sent.giftWrapJson, bob.privateKey)
        assertEquals(atLimit, now.content)
        assertEquals(sent.rumorId, now.rumorId)
        assertTrue(rawTags(openLayers(sent.giftWrapJson).rumorJson).contains("""["zchat_cap","ironwood"]"""))

        for (size in listOf(largest + 1, 65_535, 65_536, 70_000)) {
            try {
                sendToBob("a".repeat(size))
                fail("content of $size bytes must be refused")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "refusal names the limit for $size bytes: ${e.message}",
                    e.message.orEmpty().contains("65535"),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun trySend(content: String): Nip17.SentMessage? =
        try {
            sendToBob(content)
        } catch (_: IllegalArgumentException) {
            null
        }

    private class Layers(
        val wrap: NostrEvent.Event,
        val sealJson: String,
        val seal: NostrEvent.Event,
        val rumorJson: String,
    )

    private fun openLayers(giftWrapJson: String): Layers {
        val wrap = NostrEvent.verify(giftWrapJson)
        val sealJson = Nip44Encryption.decrypt(wrap.content, bob.privateKey, wrap.pubkeyHex.unhex())
        val seal = NostrEvent.verify(sealJson)
        val rumorJson = Nip44Encryption.decrypt(seal.content, bob.privateKey, seal.pubkeyHex.unhex())
        return Layers(wrap, sealJson, seal, rumorJson)
    }

    /**
     * A wrap built by hand the way `Nip17.wrapAndSeal` does, around a rumor with any [tagsJson],
     * to model a 2.12.4 sender or another NIP-17 client.
     */
    private fun handBuiltWrap(tagsJson: String, content: String): String {
        val pub = alice.publicKey.hex()
        val canonical = """[0,"$pub",1790000000,14,$tagsJson,"$content"]"""
        val rumor =
            """{"id":"${sha256Hex(canonical)}","pubkey":"$pub","created_at":1790000000,""" +
                """"kind":14,"tags":$tagsJson,"content":"$content"}"""
        val seal =
            NostrEvent.signAndSerialize(
                pubkeyHex = pub,
                privateKey = alice.privateKey,
                createdAtSec = 1_790_000_000L,
                kind = 13,
                tags = emptyList(),
                content = Nip44Encryption.encrypt(rumor, alice.privateKey, bob.publicKey),
            )
        val wrapPriv = ByteArray(32) { (0x33 + it).toByte() }
        val wrapPub =
            fr.acinq.secp256k1.Secp256k1
                .pubkeyCreate(wrapPriv)
                .copyOfRange(1, 33)
        return NostrEvent.signAndSerialize(
            pubkeyHex = wrapPub.hex(),
            privateKey = wrapPriv,
            createdAtSec = 1_789_990_000L,
            kind = 1059,
            tags = listOf(listOf("p", bobHex)),
            content = Nip44Encryption.encrypt(seal, wrapPriv, bob.publicKey),
        )
    }

    /** The rumor's tags array exactly as serialized (bracket-matched, string-aware). */
    private fun rawTags(json: String): String {
        val start = json.indexOf("\"tags\":").also { assertTrue("no tags field", it >= 0) } + 7
        var depth = 0
        var i = start
        while (i < json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i + 1)
                }
                '"' -> {
                    i++
                    while (json[i] != '"') {
                        if (json[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        error("unterminated tags")
    }

    /** NIP-01 id recomputed from the rumor's own fields. */
    private fun nip01Id(rumorJson: String): String {
        val pub = NostrEvent.parsePubkey(rumorJson)
        val createdAt = NostrEvent.parseCreatedAt(rumorJson)
        val kind = NostrEvent.parseKind(rumorJson)
        val contentStart = rumorJson.indexOf("\"content\":") + "\"content\":".length
        val contentJson = rumorJson.substring(contentStart, rumorJson.length - 1)
        return sha256Hex("[0,\"$pub\",$createdAt,$kind,${rawTags(rumorJson)},$contentJson]")
    }

    companion object {
        private val SEED_ALICE = ByteArray(64) { (0x10 + it).toByte() }
        private val SEED_BOB = ByteArray(64) { (0xA0 - it).toByte() }

        /**
         * VERBATIM copy of `Nip17.receive` at a1fb4136f (v2.12.4), frozen here so this test keeps
         * modelling a 2.12.4 phone after HEAD's receive changes. Only the name and the return type
         * differ (2.12.4's ReceivedMessage had no rumorTags; the default is used).
         */
        @Suppress("MaxLineLength")
        fun receiveAs2124(giftWrapJson: String, recipientPriv: ByteArray): Nip17.ReceivedMessage {
            val wrap = NostrEvent.verify(giftWrapJson)
            require(wrap.kind == 1059) { "not a kind-1059 gift wrap" }
            val sealJson =
                Nip44Encryption.decrypt(
                    b64Payload = wrap.content,
                    recipientPriv = recipientPriv,
                    senderXOnlyPub = wrap.pubkeyHex.unhex(),
                )
            val seal = NostrEvent.verify(sealJson)
            require(seal.kind == 13) { "inner event is not a seal" }
            val rumorJson =
                Nip44Encryption.decrypt(
                    b64Payload = seal.content,
                    recipientPriv = recipientPriv,
                    senderXOnlyPub = seal.pubkeyHex.unhex(),
                )
            val rumorPubkey = NostrEvent.parsePubkey(rumorJson)
            require(rumorPubkey == seal.pubkeyHex) {
                "rumor pubkey ($rumorPubkey) doesn't match seal signer (${seal.pubkeyHex}) — possible forgery"
            }
            val content = NostrEvent.parseContent(rumorJson)
            val createdAt = NostrEvent.parseCreatedAt(rumorJson)
            return Nip17.ReceivedMessage(
                content = content,
                senderPubkey = seal.pubkeyHex.unhex(),
                createdAtSec = createdAt,
                eventId = wrap.id,
                rumorId = NostrEvent.parseId(rumorJson),
            )
        }

        private fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).hex()

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun String.unhex(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
