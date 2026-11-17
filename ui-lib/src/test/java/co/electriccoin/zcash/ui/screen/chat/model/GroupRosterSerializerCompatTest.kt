package co.electriccoin.zcash.ui.screen.chat.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA1, design test 4 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.0, §7) — stored data stays readable both ways.
 *
 * - A roster and a GroupInfo written by v2.12.4 load on this build (no new MemberStatus, no new required key).
 * - GroupInfo's only new key, `announcer_rebuilt`, round-trips, and is written only when true, so every other group
 *   serializes exactly as before.
 * - The announce state and the ledger (kept outside the roster JSON) ignore keys they do not know, and an unreadable
 *   one reads as empty, never as a crash.
 *
 * All addresses, keys and group ids are synthetic.
 */
class GroupRosterSerializerCompatTest {
    private val api = AnnounceApi

    @Test
    fun `a v2_12_4 roster loads unchanged`() {
        // As v2.12.4's serializeGroupMembers writes it (a1fb4136f): no pending_key_epoch, no pending_key_tx_ids.
        val json =
            """[{"address":"$CREATOR","public_key":"cHVi","joined_at":1700000000000,"status":"ACTIVE","is_admin":true},""" +
                """{"address":"$MEMBER","joined_at":1700000001000,"status":"INVITED","is_admin":false,"nickname":"Bo",""" +
                """"invite_status":"SENT"},{"address":"$LEAVER","joined_at":1700000002000,"status":"LEFT","is_admin":false}]"""
        val members = ZMSGGroupProtocol.deserializeGroupMembers(json)
        assertEquals(3, members.size)
        assertEquals(
            GroupMember(CREATOR, "cHVi", Instant.ofEpochMilli(1_700_000_000_000L), MemberStatus.ACTIVE, isAdmin = true),
            members[0],
        )
        assertEquals(
            GroupMember(
                MEMBER,
                null,
                Instant.ofEpochMilli(1_700_000_001_000L),
                MemberStatus.INVITED,
                nickname = "Bo",
                inviteStatus = InviteStatus.SENT
            ),
            members[1],
        )
        assertEquals(MemberStatus.LEFT, members[2].status)
        // and the roster written back is read the same way
        assertEquals(members, ZMSGGroupProtocol.deserializeGroupMembers(ZMSGGroupProtocol.serializeGroupMembers(members)))
    }

    @Test
    fun `a v2_12_4 GroupInfo loads, not rebuilt`() {
        val json =
            """{"group_id":"$GID","name":"Team","creator":"$CREATOR","created_at":1700000000000,""" +
                """"admin_policy":"CREATOR_ONLY","current_epoch":2,"group_key":"a2V5","is_active":true}"""
        val info = ZMSGGroupProtocol.deserializeGroupInfo(json)
        assertNotNull(info)
        assertEquals(GID, info!!.groupId)
        assertEquals(2, info.currentEpoch)
        assertNull(info.creatorPublicKey)
        assertFalse(info.removedByAdmin)
        assertFalse(api.announcerRebuilt(info))
    }

    @Test
    fun `announcer_rebuilt survives a round trip and is written only when true`() {
        val plain = GroupInfo(GID, "Team", CREATOR, Instant.ofEpochMilli(1_700_000_000_000L), currentEpoch = 1)
        val plainJson = ZMSGGroupProtocol.serializeGroupInfo(plain)
        assertFalse("a group that was not rebuilt serializes as before", JSONObject(plainJson).has("announcer_rebuilt"))

        val flagged = JSONObject(plainJson).put("announcer_rebuilt", true).toString()
        val rebuilt = ZMSGGroupProtocol.deserializeGroupInfo(flagged)!!
        assertTrue(api.announcerRebuilt(rebuilt))
        val again = ZMSGGroupProtocol.serializeGroupInfo(rebuilt)
        assertTrue(JSONObject(again).getBoolean("announcer_rebuilt"))
        assertEquals(rebuilt, ZMSGGroupProtocol.deserializeGroupInfo(again))
        assertEquals(plain, ZMSGGroupProtocol.deserializeGroupInfo(plainJson))
    }

    @Test
    fun `the announce state round trips and ignores unknown keys`() {
        val st =
            MaState(
                hw = 1_790_000_000_500L,
                rosterAnnounced = true,
                selfJoinN = 1_790_000_000_100L,
                selfJoinEpoch = 3,
                inactiveSince = 1_790_000_000L,
                usedInviteIds = setOf("txid-a", "event-b"),
                members =
                    mapOf(
                        MEMBER to
                            MaEntry(
                                n = 1_790_000_000_400L,
                                op = MaOp.A,
                                epoch = 3,
                                activatedAtMs = 1_790_000_000_400L,
                                activatedBy = MaBy.AUTH,
                                annForm = MEMBER
                            ),
                        LEAVER to MaEntry(n = 1_790_000_000_500L, op = MaOp.R, epoch = 3, leftAtMs = 1_790_000_000L, removedAtEpoch = 3),
                    ),
            )
        val json = api.announceStateToJson(st)
        assertEquals(st, api.announceStateFromJson(json))

        val o = JSONObject(json)
        o.put("from_a_later_build", JSONObject().put("x", 1))
        val members =
            o.getJSONObject(
                o
                    .keys()
                    .asSequence()
                    .toSet()
                    .single { o.get(it) is JSONObject && o.getJSONObject(it).has(MEMBER) }
            )
        members.getJSONObject(MEMBER).put("later_field", "y")
        assertEquals("unknown keys are ignored", st, api.announceStateFromJson(o.toString()))
        assertEquals(MaState(), api.announceStateFromJson(null))
        assertEquals(MaState(), api.announceStateFromJson("not json"))
    }

    @Test
    fun `the ledger round trips and ignores unknown keys`() {
        val ledger =
            listOf(
                MaLedger("k-r", MEMBER, "k-s", LEAVER, MaOp.A, MaLedgerState.CHAIN_SENT, listOf("tx1", "tx2"), 1_790_000_000_000L),
                MaLedger(null, CREATOR, null, MEMBER, MaOp.R, MaLedgerState.RELAY_UNCONFIRMED, emptyList(), 5L),
            )
        val json = api.announceLedgerToJson(ledger)
        assertEquals(ledger, api.announceLedgerFromJson(json))
        val arr = org.json.JSONArray(json)
        arr.getJSONObject(0).put("later_field", 1)
        assertEquals(ledger, api.announceLedgerFromJson(arr.toString()))
        assertEquals(emptyList<MaLedger>(), api.announceLedgerFromJson(null))
        assertEquals(emptyList<MaLedger>(), api.announceLedgerFromJson("{"))
    }

    private companion object {
        const val GID = "zgrp_0123456789abcdef01234567"
        val CREATOR = "u1" + "c".repeat(60)
        val MEMBER = "u1" + "m".repeat(60)
        val LEAVER = "u1" + "l".repeat(60)
    }
}
