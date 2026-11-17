package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-598 (train-T7a2 review of 14 Oct, finding 4; the ZC-952 bounds) — at a full kept list, when the incoming
 * refused entry takes a place from another unverified chat ([GroupAdminCheck.withKeptChange]) and two chats hold the
 * most entries, the place comes from the chat whose newest entry is the most recent (the one sending now), never from
 * the quieter chat.
 *
 * The list: flooder A and the admin's own chat ADMIN (unverified, at another address than the stored one) hold 16
 * entries each, B holds 15 and C one: 48. ADMIN's entries are the oldest; A sent its newest a moment ago. B adds one.
 * Before: of the tie A / ADMIN the chat whose newest entry is the oldest gives its place, so ADMIN's oldest removal
 * leaves, and a flooder can repeat that. Wanted: A gives its place, ADMIN keeps all 16.
 *
 * All values are synthetic.
 */
class KeptListTieTakesThePlaceOfTheNewestSenderTest {
    private val now = 1_800_000_000_000L
    private val finished: (String) -> Int? = { 2 }

    private fun entries(
        chat: String,
        count: Int,
        firstKept: Long,
    ): List<KeptAdminChange> =
        (0 until count).map { i ->
            KeptAdminChange(
                id = "synthetic-tie-$chat-$i",
                kind = if (i % 2 == 0) KeptAdminChange.KICK else KeptAdminChange.KEY,
                epoch = 10 + i / 2,
                payload = "synthetic",
                from = chat,
                by = chat,
                kept = firstKept + i,
            )
        }

    @Test
    fun `on a tie the chat with the most recent entry gives its place, never the admin's quieter chat`() {
        val admin = entries(ADMIN, 16, now - 600_000)
        val a = entries(A, 16, now - 300_000).dropLast(1) + entries(A, 16, now - 1_000).last()
        val b = entries(B, 15, now - 400_000)
        val c = entries(C, 1, now - 500_000)
        val kept = admin + a + b + c
        assertEquals("fixture: the list is not full", KeptAdminChange.MAX_ENTRIES, kept.size)
        val incoming = entries(B, 16, now).last().copy(id = "synthetic-tie-b-incoming", kept = now)

        val after = GroupAdminCheck.withKeptChange(kept, incoming, finished, now)

        assertTrue("the incoming entry was not kept", after.any { it.id == incoming.id })
        assertEquals(KeptAdminChange.MAX_ENTRIES, after.size)
        assertEquals("the admin's chat gave a place on a tie", 16, after.count { it.by == ADMIN })
        assertEquals("the chat that sent last did not give the place", 15, after.count { it.by == A })
    }

    private companion object {
        const val ADMIN = "usyntheticzc952tieadminchat1"
        const val A = "usyntheticzc952tieflooder0a1"
        const val B = "usyntheticzc952tieflooder0b1"
        const val C = "usyntheticzc952tieflooder0c1"
    }
}
