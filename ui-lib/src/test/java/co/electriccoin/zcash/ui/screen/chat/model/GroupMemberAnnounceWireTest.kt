package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 8 MA1, design test 1 (MEMBER_ANNOUNCE_DESIGN.md v2 §2) — the wire format of the member announce (`GP`), the
 * roster query (`GQ`) and the signed leave (`GL`).
 *
 * - `GP`: `ZMSG:3.0:GROUP:GP:<gid>:{"v":1,"o":"A"|"R","m":<UA>,"n":<Long>,"e":<Int>,"sig":<b64>[,"np":<hex64>]}`,
 *   '/' written unescaped (as GM), at most 512 UTF-8 bytes or not built at all; the parser is strict (exact key set,
 *   JSON integers for v / n / e, a shaped gid) and never accepts what the builder refuses.
 * - The signed data is exactly `GP|1|<gid>|<o>|<m>|<n>|<e>|<np or empty>`.
 * - `GQ` is `{"v":1}` and nothing else.
 * - `GL` may carry a pairwise `sig` over `GL|1|<gid>|<leaver>|<ts>` (ts in SECONDS, as the wire has always written
 *   it); the signature is left out when the memo would pass 512 bytes.
 *
 * All addresses, keys and group ids are synthetic.
 */
class GroupMemberAnnounceWireTest {
    private val api = AnnounceApi

    private fun ua(
        len: Int,
        fill: Char = 'q'
    ) = "u1" + fill.toString().repeat(len - 2)

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** A GP payload written by hand, so the parser can be fed what no builder would write. */
    @Suppress("LongParameterList")
    private fun payload(
        v: String = "1",
        o: String = "\"A\"",
        m: String = "\"$SUBJECT\"",
        n: String = "$N",
        e: String = "7",
        sig: String? = "\"$SIG\"",
        np: String? = null,
        extra: String = "",
    ): String =
        buildString {
            append("{\"v\":$v,\"o\":$o,\"m\":$m,\"n\":$n,\"e\":$e")
            if (sig != null) append(",\"sig\":$sig")
            if (np != null) append(",\"np\":$np")
            append(extra)
            append("}")
        }

    private fun parse(p: String) = api.parseGroupMemberAnnouncePayload(GID, p)

    @Test
    fun `GP and GQ are known group message codes`() {
        assertNotNull("fromCode(\"GP\")", GroupMessageType.fromCode("GP"))
        assertNotNull("fromCode(\"GQ\")", GroupMessageType.fromCode("GQ"))
        assertTrue(GroupMessageType.fromCode("GP") != GroupMessageType.fromCode("GQ"))
    }

    @Test
    fun `A and R round trip through the builder and the strict parser, with and without np`() {
        for (op in listOf(MaOp.A, MaOp.R)) {
            for (np in listOf(null, NP)) {
                val memo = api.createGroupMemberAnnounceMessage(GID, op, SUBJECT, N, 7, SIG, np)
                assertNotNull("op=$op np=$np", memo)
                assertTrue(memo!!.startsWith("ZMSG:3.0:GROUP:GP:$GID:"))
                assertEquals(GroupMessageType.fromCode("GP"), ZMSGGroupProtocol.parseMessageType(memo))
                assertEquals(GID, ZMSGGroupProtocol.parseGroupId(memo))
                assertEquals(
                    MaAnn(GID, op, SUBJECT, N, 7, SIG, np),
                    api.parseGroupMemberAnnouncePayload(GID, ZMSGGroupProtocol.parsePayload(memo)!!),
                )
                val keys = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!).keys().asSequence().toSet()
                assertEquals(
                    if (np == null) setOf("v", "o", "m", "n", "e", "sig") else setOf("v", "o", "m", "n", "e", "sig", "np"),
                    keys,
                )
            }
        }
    }

    @Test
    fun `an on-chain GP fits 512 bytes at L 213 and L 300 with a signature made entirely of slashes`() {
        val slashes = "/".repeat(96)
        // gid 29, n 13 digits, e 2 digits, sig 96: the §2.1 table.
        for ((len, expected) in listOf(213 to 413, 300 to 500)) {
            val memo = api.createGroupMemberAnnounceMessage(GID, MaOp.A, ua(len), 1_790_000_000_000L, 12, slashes)
            assertNotNull("L=$len", memo)
            assertFalse("'/' must be written unescaped, L=$len", memo!!.contains("\\/"))
            assertEquals("memo bytes at L=$len", expected, bytes(memo))
            assertTrue(bytes(memo) <= ZMSGConstants.MAX_MEMO_SIZE)
            assertEquals(
                slashes,
                api.parseGroupMemberAnnouncePayload(GID, ZMSGGroupProtocol.parsePayload(memo)!!)!!.signature,
            )
        }
        // with np (relay only): 485 bytes at L = 213
        val relay = api.createGroupMemberAnnounceMessage(GID, MaOp.A, ua(213), 1_790_000_000_000L, 12, slashes, NP)
        assertEquals(485, bytes(relay!!))
    }

    @Test
    fun `the builder refuses a memo above 512 bytes`() {
        val slashes = "/".repeat(96)
        val atLimit = api.createGroupMemberAnnounceMessage(GID, MaOp.A, ua(312), 1_790_000_000_000L, 12, slashes)
        assertEquals(512, bytes(atLimit!!))
        assertNull(
            "one byte over the limit is never built",
            api.createGroupMemberAnnounceMessage(GID, MaOp.A, ua(313), 1_790_000_000_000L, 12, slashes),
        )
        assertNull(
            "np pushes L=300 to 572 bytes",
            api.createGroupMemberAnnounceMessage(GID, MaOp.R, ua(300), 1_790_000_000_000L, 12, slashes, NP),
        )
    }

    @Test
    fun `the builder never builds what the parser rejects`() {
        for (bad in listOf("u1abc|def" + "q".repeat(20), "E2E_INIT:" + "q".repeat(30), "u1a,b" + "q".repeat(20), "short")) {
            assertNull("subject <$bad>", api.createGroupMemberAnnounceMessage(GID, MaOp.A, bad, N, 7, SIG))
        }
        assertNull("np not hex", api.createGroupMemberAnnounceMessage(GID, MaOp.A, SUBJECT, N, 7, SIG, "z".repeat(64)))
        assertNull("n < 1", api.createGroupMemberAnnounceMessage(GID, MaOp.A, SUBJECT, 0, 7, SIG))
        assertNull("e < 0", api.createGroupMemberAnnounceMessage(GID, MaOp.A, SUBJECT, N, -1, SIG))
        assertNull("empty sig", api.createGroupMemberAnnounceMessage(GID, MaOp.A, SUBJECT, N, 7, ""))
        assertNull("gid not shaped", api.createGroupMemberAnnounceMessage("gid1", MaOp.A, SUBJECT, N, 7, SIG))
    }

    @Test
    fun `the parser accepts the hand-written reference payload`() {
        assertEquals(MaAnn(GID, MaOp.A, SUBJECT, N, 7, SIG, null), parse(payload()))
        assertEquals(MaAnn(GID, MaOp.R, SUBJECT, N, 0, SIG, NP), parse(payload(o = "\"R\"", e = "0", np = "\"$NP\"")))
    }

    @Test
    fun `the parser rejects a subject with a delimiter`() {
        for (m in listOf("u1abc|" + "q".repeat(20), "u1abc:" + "q".repeat(20), "u1abc," + "q".repeat(20), "E2E_INIT:" + "q".repeat(20))) {
            assertNull("m=<$m>", parse(payload(m = "\"$m\"")))
        }
        assertNull("m too short", parse(payload(m = "\"u1abc\"")))
        assertNull("m not a string", parse(payload(m = "12345678901234567890123")))
    }

    @Test
    fun `the parser rejects a malformed np`() {
        for (np in listOf("\"${"g".repeat(64)}\"", "\"${"A".repeat(64)}\"", "\"${"a".repeat(63)}\"", "\"${"a".repeat(65)}\"", "5")) {
            assertNull("np=$np", parse(payload(np = np)))
        }
    }

    @Test
    fun `the parser rejects an op other than A or R`() {
        for (o in listOf("\"X\"", "\"a\"", "\"AR\"", "\"\"", "1", "null")) {
            assertNull("o=$o", parse(payload(o = o)))
        }
    }

    @Test
    fun `the parser rejects a version other than the integer 1`() {
        for (v in listOf("2", "0", "\"1\"", "1.0", "1.5")) {
            assertNull("v=$v", parse(payload(v = v)))
        }
    }

    @Test
    fun `the parser rejects n or e given as a string or a double`() {
        for (n in listOf("\"$N\"", "1.79E12", "$N.0", "1.5")) {
            assertNull("n=$n", parse(payload(n = n)))
        }
        for (e in listOf("\"7\"", "7.0", "7.5")) {
            assertNull("e=$e", parse(payload(e = e)))
        }
    }

    @Test
    fun `the parser rejects n below 1 and e below 0`() {
        assertNull(parse(payload(n = "0")))
        assertNull(parse(payload(n = "-5")))
        assertNull(parse(payload(e = "-1")))
        assertNotNull("n = 1 is the lowest valid number", parse(payload(n = "1")))
        assertNotNull("e = 0 is valid", parse(payload(e = "0")))
    }

    @Test
    fun `the parser rejects any unknown key and a missing or empty sig`() {
        assertNull(parse(payload(extra = ",\"x\":1")))
        assertNull(parse(payload(extra = ",\"sender\":\"$SUBJECT\"")))
        assertNull(parse(payload(sig = null)))
        assertNull(parse(payload(sig = "\"\"")))
        assertNull(parse("not json"))
        assertNull(parse("[]"))
    }

    @Test
    fun `the parser rejects a group id not shaped like generateGroupId output`() {
        val good = payload()
        for (gid in listOf("gid1", "zgrp_XYZ", "zgrp_" + "a".repeat(23), "zgrp_" + "A".repeat(24), "zgrp_" + "a".repeat(25), "")) {
            assertNull("gid=<$gid>", api.parseGroupMemberAnnouncePayload(gid, good))
            assertFalse("gid=<$gid>", api.isGroupIdShaped(gid))
        }
        val generated = GroupInfo.generateGroupId("u1creator")
        assertTrue(api.isGroupIdShaped(generated))
        assertNotNull(api.parseGroupMemberAnnouncePayload(generated, good))
    }

    @Test
    fun `a GP memo never contains E2E_INIT`() {
        for (len in listOf(20, 100, 213, 300)) {
            for (op in listOf(MaOp.A, MaOp.R)) {
                val memo = api.createGroupMemberAnnounceMessage(GID, op, ua(len, 'E'), N, 7, SIG)
                assertNotNull(memo)
                assertFalse(memo!!.contains("E2E_INIT:"))
            }
        }
    }

    @Test
    fun `the signed data is exactly GP 1 gid o m n e np`() {
        assertEquals("GP|1|$GID|A|$SUBJECT|$N|7|", api.groupMemberAnnounceSignedData(GID, MaOp.A, SUBJECT, N, 7, null))
        assertEquals("GP|1|$GID|R|$SUBJECT|$N|0|$NP", api.groupMemberAnnounceSignedData(GID, MaOp.R, SUBJECT, N, 0, NP))
    }

    @Test
    fun `a signature over the signed data verifies after the round trip and fails for another subject`() {
        val admin = E2EEncryption.generateKeyPair()
        val data = api.groupMemberAnnounceSignedData(GID, MaOp.A, SUBJECT, N, 3, null)
        val sig = E2EEncryption.sign(admin.privateKey, data)
        val memo = api.createGroupMemberAnnounceMessage(GID, MaOp.A, SUBJECT, N, 3, sig)!!
        val parsed = api.parseGroupMemberAnnouncePayload(GID, ZMSGGroupProtocol.parsePayload(memo)!!)!!
        val rebuilt =
            api.groupMemberAnnounceSignedData(parsed.groupId, parsed.op, parsed.subject, parsed.n, parsed.epoch, parsed.nostrPubkey)
        assertTrue(E2EEncryption.verify(admin.publicKey, rebuilt, parsed.signature))
        val other = api.groupMemberAnnounceSignedData(GID, MaOp.A, ua(40, 'z'), N, 3, null)
        assertFalse(E2EEncryption.verify(admin.publicKey, other, parsed.signature))
        val removed = api.groupMemberAnnounceSignedData(GID, MaOp.R, SUBJECT, N, 3, null)
        assertFalse("an A signature never verifies as an R", E2EEncryption.verify(admin.publicKey, removed, parsed.signature))
    }

    @Test
    fun `GQ round trips and its parser is strict`() {
        val memo = api.createGroupRosterQueryMessage(GID)
        assertEquals("ZMSG:3.0:GROUP:GQ:$GID:{\"v\":1}", memo)
        assertEquals(GroupMessageType.fromCode("GQ"), ZMSGGroupProtocol.parseMessageType(memo))
        assertTrue(api.parseGroupRosterQueryPayload(GID, ZMSGGroupProtocol.parsePayload(memo)!!))
        for (bad in listOf("{\"v\":2}", "{\"v\":\"1\"}", "{\"v\":1.0}", "{\"v\":1,\"x\":0}", "{}", "nope")) {
            assertFalse(bad, api.parseGroupRosterQueryPayload(GID, bad))
        }
        assertFalse(api.parseGroupRosterQueryPayload("gid1", "{\"v\":1}"))
    }

    @Test
    fun `the signed GL covers GL 1 gid leaver ts and carries the signature`() {
        val leaver = ua(213, 'l')
        val ts = 1_790_000_000L
        assertEquals("GL|1|$GID|$leaver|$ts", api.groupLeaveSignedData(GID, leaver, ts))
        val member = E2EEncryption.generateKeyPair()
        val sig = E2EEncryption.sign(member.privateKey, api.groupLeaveSignedData(GID, leaver, ts))
        val memo = api.createGroupLeaveMessage(GID, leaver, sig, ts)
        assertTrue(bytes(memo) <= ZMSGConstants.MAX_MEMO_SIZE)
        assertEquals(GroupMessageType.GROUP_LEAVE, ZMSGGroupProtocol.parseMessageType(memo))
        val parsed = ZMSGGroupProtocol.parseGroupLeavePayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals(leaver, parsed.leaver)
        assertEquals(ts, parsed.timestamp)
        assertEquals(sig, api.leaveSignature(parsed))
        assertTrue(E2EEncryption.verify(member.publicKey, api.groupLeaveSignedData(GID, parsed.leaver, parsed.timestamp), sig))
    }

    @Test
    fun `a GL without a signature is the legacy two-key payload`() {
        val leaver = ua(213, 'l')
        val memo = api.createGroupLeaveMessage(GID, leaver, null, 1_790_000_000L)
        val json = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
        assertEquals(setOf("leaver", "ts"), json.keys().asSequence().toSet())
        assertEquals(1_790_000_000L, json.getLong("ts"))
        assertNull(api.leaveSignature(ZMSGGroupProtocol.parseGroupLeavePayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!))
    }

    @Test
    fun `the GL signature is dropped when the memo would pass 512 bytes`() {
        val leaver = ua(420, 'l')
        val sig = "/".repeat(96)
        val memo = api.createGroupLeaveMessage(GID, leaver, sig, 1_790_000_000L)
        assertTrue(bytes(memo) <= ZMSGConstants.MAX_MEMO_SIZE)
        assertEquals(setOf("leaver", "ts"), JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!).keys().asSequence().toSet())
        val short = api.createGroupLeaveMessage(GID, ua(213, 'l'), sig, 1_790_000_000L)
        assertFalse("'/' in a kept signature is written unescaped", short.contains("\\/"))
        assertEquals(sig, JSONObject(ZMSGGroupProtocol.parsePayload(short)!!).getString("sig"))
    }

    private companion object {
        const val GID = "zgrp_0123456789abcdef01234567"
        const val N = 1_790_000_000_123L
        const val SIG = "MEUCIQD+ab/cd=="
        val SUBJECT = "u1" + "s".repeat(211)
        val NP = "0123456789abcdef".repeat(4)
    }
}
