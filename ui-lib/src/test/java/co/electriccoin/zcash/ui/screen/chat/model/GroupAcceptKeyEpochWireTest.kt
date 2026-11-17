package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-506 Part A — the "I joined" reply (GROUP_ACCEPT) on the wire, with and without the key epoch of the invite it
 * answers (`key_epoch`: additive, optional, not signed).
 *
 * - Without a key epoch the builder writes what v2.12.4 wrote ([Frozen2124Accept], the verbatim 2.12.4 builder), byte
 *   for byte. Only tests and old stored replies use this form now.
 * - With one, the reply is written by the builder's own writer: the same frame, the keys in a fixed order, the value
 *   always present, and '/' never escaped. (Android's JSON writer turns every '/' into "\/"; the key and the signature
 *   are base64, so with that writer the size would depend on how many '/' they hold.) Any JSON reader reads it back.
 * - The signature covers the group, the address and the key, as before: it verifies unchanged.
 * - [ZMSGGroupProtocol.groupAcceptKeyEpoch] returns the value only for a whole number from 0 to Int.MAX_VALUE; anything
 *   else counts as absent, so the creator falls back to the time rule.
 *
 * All addresses, keys and group ids are synthetic.
 */
class GroupAcceptKeyEpochWireTest {
    private val address = "u1" + "q".repeat(176)

    private fun payloadOf(memo: String): String = ZMSGGroupProtocol.parsePayload(memo)!!

    @Test
    fun `without a key epoch the memo is byte for byte what v2_12_4 built`() {
        for ((pub, sig) in listOf("cHViQjY0" to "c2lnQjY0", "cHViQjY0" to "", "" to "", "a/b+c=" to "d/e/f==")) {
            val legacy = Frozen2124Accept.createGroupAcceptMessage(GID, address, pub, sig)
            assertEquals(legacy, ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, pub, sig))
            assertEquals(legacy, ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, pub, sig, keyEpoch = null))
            assertFalse(legacy.contains("key_epoch"))
        }
    }

    @Test
    fun `with a key epoch the memo is the fixed form, the value last`() {
        assertEquals(
            "ZMSG:3.0:GROUP:GA:$GID:{\"accepter\":\"$address\",\"accepter_pub\":\"cHViQjY0\",\"sig\":\"c2lnQjY0\",\"key_epoch\":7}",
            ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, "cHViQjY0", "c2lnQjY0", keyEpoch = 7),
        )
        // An empty signature is left out, as in the legacy form; the value is still written.
        assertEquals(
            "ZMSG:3.0:GROUP:GA:$GID:{\"accepter\":\"$address\",\"accepter_pub\":\"\",\"key_epoch\":0}",
            ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, "", keyEpoch = 0),
        )
    }

    @Test
    fun `every key epoch is written and read back, with the other fields unchanged`() {
        for (epoch in listOf(0, 1, 99, 99_999, Int.MAX_VALUE)) {
            val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, "cHViQjY0", "c2lnQjY0", keyEpoch = epoch)
            assertEquals(GroupMessageType.GROUP_ACCEPT, ZMSGGroupProtocol.parseMessageType(memo))
            assertEquals(GID, ZMSGGroupProtocol.parseGroupId(memo))
            val parsed = ZMSGGroupProtocol.parseGroupAcceptPayload(payloadOf(memo))!!
            assertEquals(address, parsed.accepter)
            assertEquals("cHViQjY0", parsed.accepterPublicKey)
            assertEquals("c2lnQjY0", parsed.signature)
            assertEquals(epoch, parsed.keyEpoch)
            assertEquals(epoch, ZMSGGroupProtocol.groupAcceptKeyEpoch(JSONObject(payloadOf(memo))))
        }
        val legacy = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, "cHViQjY0", "c2lnQjY0")
        assertNull(ZMSGGroupProtocol.parseGroupAcceptPayload(payloadOf(legacy))!!.keyEpoch)
    }

    @Test
    fun `a slash stays a slash, and the strings read back as they were`() {
        val key = "/".repeat(124)
        val sig = "/".repeat(96)
        val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, key, sig, keyEpoch = 2)
        assertFalse("a slash was escaped", memo.contains("\\/"))
        val json = JSONObject(payloadOf(memo))
        assertEquals(key, json.getString("accepter_pub"))
        assertEquals(sig, json.getString("sig"))
        assertEquals(address, json.getString("accepter"))
    }

    @Test
    fun `characters JSON must escape are escaped, so the memo always parses`() {
        val odd = "u1\"quote\\back\nline\ttab\u0001ctl/slash"
        val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, odd, odd, odd, keyEpoch = 5)
        val json = JSONObject(payloadOf(memo))
        assertEquals(odd, json.getString("accepter"))
        assertEquals(odd, json.getString("accepter_pub"))
        assertEquals(odd, json.getString("sig"))
        assertEquals(5, json.getInt("key_epoch"))
    }

    @Test
    fun `the signature of an accept with a key epoch verifies against the unchanged signed data`() {
        val member = E2EEncryption.generateKeyPair()
        val signed = ZMSGGroupProtocol.groupAcceptSignedData(GID, address, member.publicKey)
        assertEquals("GA|$GID|$address|${member.publicKey}", signed)
        val sig = E2EEncryption.sign(member.privateKey, signed)

        val parsed =
            ZMSGGroupProtocol.parseGroupAcceptPayload(
                payloadOf(ZMSGGroupProtocol.createGroupAcceptMessage(GID, address, member.publicKey, sig, keyEpoch = 4)),
            )!!

        assertTrue(
            "the signature no longer verifies",
            E2EEncryption.verify(
                member.publicKey,
                ZMSGGroupProtocol.groupAcceptSignedData(GID, parsed.accepter, parsed.accepterPublicKey),
                parsed.signature,
            ),
        )
        // The value is not signed: changing it leaves the signature valid (the creator's rule only ever refuses on it).
        assertEquals(4, parsed.keyEpoch)
    }

    @Test
    fun `groupAcceptKeyEpoch reads whole numbers from 0 to Int MAX and nothing else`() {
        fun read(value: String?): Int? =
            ZMSGGroupProtocol.groupAcceptKeyEpoch(
                JSONObject("{\"accepter\":\"a\"" + (value?.let { ",\"key_epoch\":$it" } ?: "") + "}"),
            )

        assertNull("absent", read(null))
        assertEquals(0, read("0"))
        assertEquals(1, read("1"))
        assertEquals(99_999, read("99999"))
        assertEquals(Int.MAX_VALUE, read("2147483647"))
        assertNull("negative", read("-1"))
        assertNull("over Int.MAX", read("2147483648"))
        assertNull("far over", read("99999999999999999999"))
        assertNull("fraction", read("1.5"))
        assertNull("a whole number written as a fraction", read("2.0"))
        assertNull("exponent", read("1e3"))
        assertNull("text", read("\"3\""))
        assertNull("text", read("\"abc\""))
        assertNull("null", read("null"))
        assertNull("boolean", read("true"))
        assertNull("object", read("{}"))
        assertNull("array", read("[1]"))
    }

    private companion object {
        const val GID = "zgrp_0a1b2c3d4e5f60718293a4b5"
    }
}
