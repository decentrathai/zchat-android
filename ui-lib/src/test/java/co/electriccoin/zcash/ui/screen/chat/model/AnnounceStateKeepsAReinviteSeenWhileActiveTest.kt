package co.electriccoin.zcash.ui.screen.chat.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-294 (r8-findings SPECS §9.3) — the stored announce state keeps what a re-invite seen while the group is still
 * active needs: the ids of such invites (`seen_active_invite_ids`) and the newest one of the creator
 * (`pending_reinvite`: its id, its source time, the invite and, over the relays, the seal's sender).
 *
 * Both are optional in the stored JSON: a state written before they existed reads as before, and a kept re-invite that
 * cannot be read is dropped without losing the rest of the state. The JSON is written and read by key name here, so
 * this file does not depend on how the product types name the fields. All values are synthetic.
 */
class AnnounceStateKeepsAReinviteSeenWhileActiveTest {
    private val api = AnnounceApi

    private val before =
        MaState(
            hw = 1_790_000_000_900L,
            rosterAnnounced = true,
            selfJoinN = 1_790_000_000_100L,
            selfJoinEpoch = 2,
            usedInviteIds = setOf("txid-gi-1"),
            members = mapOf("usyntheticmember1" to MaEntry(n = 1_790_000_000_900L, op = MaOp.A, epoch = 2)),
        )

    private val invite =
        JSONObject()
            .put("name", "Synthetic group")
            .put("inviter", "usyntheticcreator1")
            .put("key_epoch", 3)
            .put("k2", "c3ludGhldGljLXdyYXBwZWQta2V5")
            .toString()

    private fun kept(
        id: String = "event-gi-3",
        sender: String? = "usyntheticcreator1",
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("source_ms", 1_790_000_020_000L)
            .put("invite", invite)
            .apply { sender?.let { put("sender", it) } }

    private fun stored(change: JSONObject.() -> Unit): String = JSONObject(api.announceStateToJson(before)).apply(change).toString()

    /** [json] read into the state and written again, as every save of the state does. */
    private fun readAndWritten(json: String): JSONObject = JSONObject(api.announceStateToJson(api.announceStateFromJson(json)))

    private fun strings(array: JSONArray?): Set<String> = array?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    @Test
    fun `the ids of invites seen while active and the kept re-invite survive a read and a write of the state`() {
        val json =
            stored {
                put("seen_active_invite_ids", JSONArray().put("event-gi-3").put("txid-gi-2"))
                put("pending_reinvite", kept())
            }

        val again = readAndWritten(json)

        assertEquals(
            "the ids of invites seen while the group was active were lost",
            setOf("event-gi-3", "txid-gi-2"),
            strings(again.optJSONArray("seen_active_invite_ids")),
        )
        val pending = again.optJSONObject("pending_reinvite")
        assertTrue("the kept re-invite was lost", pending != null)
        assertEquals("event-gi-3", pending!!.optString("id"))
        assertEquals(1_790_000_020_000L, pending.optLong("source_ms"))
        assertEquals("the kept invite is not the one received", invite, pending.optString("invite"))
        assertEquals("usyntheticcreator1", pending.optString("sender"))
        assertEquals("the used invite ids changed", setOf("txid-gi-1"), strings(again.optJSONArray("used_invite_ids")))
        assertEquals("the rest of the state changed", before.hw, again.optLong("hw"))
        // A second read and write changes nothing more.
        assertEquals(again.toString(), readAndWritten(again.toString()).toString())
    }

    @Test
    fun `an on-chain re-invite is kept without a seal sender`() {
        val again = readAndWritten(stored { put("pending_reinvite", kept(id = "txid-gi-2", sender = null)) })

        val pending = again.optJSONObject("pending_reinvite")
        assertTrue("the kept on-chain re-invite was lost", pending != null)
        assertEquals("txid-gi-2", pending!!.optString("id"))
        assertFalse("an on-chain re-invite got a seal sender", pending.has("sender"))
    }

    @Test
    fun `control - a state stored before these fields existed reads as before and writes neither of them`() {
        val json = api.announceStateToJson(before)

        assertEquals(before, api.announceStateFromJson(json))
        val again = readAndWritten(json)
        assertFalse("an empty set of seen ids is written", again.has("seen_active_invite_ids"))
        assertFalse("a re-invite appeared from nothing", again.has("pending_reinvite"))
    }

    @Test
    fun `control - a kept re-invite that cannot be read is dropped and the rest of the state is kept`() {
        val unreadable =
            listOf<JSONObject.() -> Unit>(
                { put("pending_reinvite", "not an object") },
                { put("pending_reinvite", kept().apply { remove("id") }) },
                { put("pending_reinvite", kept().put("id", "")) },
                { put("pending_reinvite", kept().apply { remove("source_ms") }) },
                { put("pending_reinvite", kept().put("source_ms", 0)) },
                { put("pending_reinvite", kept().put("source_ms", "soon")) },
                { put("pending_reinvite", kept().apply { remove("invite") }) },
                { put("pending_reinvite", kept().put("invite", "not json")) },
            )
        for ((i, change) in unreadable.withIndex()) {
            val json = stored(change)

            val state = api.announceStateFromJson(json)
            assertEquals("case $i: the rest of the state was lost", before.hw, state.hw)
            assertEquals("case $i: the used invite ids were lost", before.usedInviteIds, state.usedInviteIds)
            assertEquals("case $i: the member records were lost", before.members, state.members)
            assertFalse("case $i: an unreadable re-invite was kept", readAndWritten(json).has("pending_reinvite"))
        }
    }
}
