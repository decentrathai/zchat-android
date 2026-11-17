package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 2.6, 5.3a, 6.2a: the store cases) — a conversation id belongs to one
 * address for good, and an id is this phone's own only when its own-id record says so.
 *
 * Today `setConversationMapping` writes `conv:<id>` unconditionally ("Write ONLY the conv→peer direction"), so a memo
 * under an id this phone made for M, claiming address X, moves the id to X; `setConversationId` writes both directions
 * unconditionally; and nothing records which ids this phone generated, so this phone's own key exchange can go out
 * under an id a stranger chose.
 *
 * Wanted (5.3a items 1 to 3): an id's address is written once, at the store, for every caller; the two generators
 * write `own:<id>` with the id in one commit and never hand out an id that already has an entry; one restore function
 * writes `conv:`, `peer:` (only when the address has none) and `own:` together, only for an id with no entry; and
 * `ownConversationIdForStart` returns an own-made id for the address, making a new one (with its "first message not
 * sent" mark) when the address's id is foreign, leaving the old `conv:` entry in place.
 *
 * The real store functions run over an in-memory file ([ConversationIdStoreFixture]). Ids and addresses are synthetic.
 */
class ConversationIdNeverMovesTest {
    private val m = "u1synthetic-fcid-contact-m"
    private val x = "u1synthetic-fcid-claimed-x"
    private val a = "u1synthetic-fcid-contact-a"
    private val idC = "CCCC1111"
    private val idF = "FFFF2222"

    // ---- (a) the store: an id's address is written once ------------------------------------------------------------

    @Test
    fun `(a) setConversationMapping never moves an id to another address`() {
        val f = ConversationIdStoreFixture()
        f.store.setConversationMapping(idC, m)

        f.store.setConversationMapping(idC, x)

        assertEquals("an inbound memo moved the id this phone keeps for M to the address it claims", m, f.conv(idC))
        assertNull("the refused write still gave X an id", f.peer(x))
    }

    @Test
    fun `(a) setConversationId never moves an id to another address`() {
        val f = ConversationIdStoreFixture()
        f.store.setConversationMapping(idC, m)

        f.store.setConversationId(x, idC)

        assertEquals("setConversationId moved the id this phone keeps for M to another address", m, f.conv(idC))
        assertNull("the refused write still gave X an id", f.peer(x))
    }

    @Test
    fun `(a) guard - with the same address, or no entry, both write as before`() {
        val f = ConversationIdStoreFixture()
        f.store.setConversationMapping(idC, m)
        f.store.setConversationMapping(idC, m)
        assertEquals(m, f.conv(idC))
        assertEquals("peer: is filled when the address has none, as before", idC, f.peer(m))

        f.store.setConversationId(m, idF)
        assertEquals("setConversationId writes both directions for a new id, as before", m, f.conv(idF))
        assertEquals(idF, f.peer(m))
        f.store.setConversationId(m, idC)
        assertEquals("the same address: both directions written as before", idC, f.peer(m))
        assertEquals(m, f.conv(idC))
    }

    // ---- (b) the generators write the own-id record --------------------------------------------------------------

    @Test
    fun `(b) getOrCreateConversationId records a new id as own in the same commit`() {
        val f = ConversationIdStoreFixture()

        val (id, isNew) = f.store.getOrCreateConversationId(a)

        assertTrue(isNew)
        assertEquals("a generated id has no own-id record for its address", a, f.own(id))
        assertTrue(
            "the own-id record was not written in the same commit as the id: ${f.file.commits}",
            f.file.commits.any { "conv:$id" in it && "own:$id" in it },
        )
    }

    @Test
    fun `(b) getOrCreateConversationIdForMessage records a new id as own in the same commit`() {
        val f = ConversationIdStoreFixture()

        val (id, sendAsInit) = f.store.getOrCreateConversationIdForMessage(a)

        assertTrue(sendAsInit)
        assertEquals("a generated id has no own-id record for its address", a, f.own(id))
        assertTrue(
            "the own-id record was not written in the same commit as the id: ${f.file.commits}",
            f.file.commits.any { "conv:$id" in it && "own:$id" in it && "initpending:$a" in it },
        )
    }

    @Test
    fun `(b) guard - an existing id is returned unchanged and gets no own-id record`() {
        val f = ConversationIdStoreFixture()
        f.store.setConversationMapping(idF, a)

        assertEquals(idF, f.store.getOrCreateConversationId(a).first)
        assertEquals(idF, f.store.getOrCreateConversationIdForMessage(a).first)
        assertNull("an id that came in was recorded as own", f.own(idF))
    }

    // ---- 5.3a item 3: the own id for a start ---------------------------------------------------------------------

    @Test
    fun `(k) ownConversationIdForStart replaces a foreign id with an own-made one and keeps the old entry`() {
        val f = ConversationIdStoreFixture()
        f.store.setConversationMapping(idF, a) // a memo from A (or a stranger naming A) gave A this id first

        val result = f.call("ownConversationIdForStart", a)
        assertNotNull(
            "the store has no ownConversationIdForStart(address): this phone's key exchange goes out under any id it finds",
            result
        )
        val id = result!!.getOrThrow() as String?

        assertNotNull("no own id was made for the start", id)
        assertNotEquals("the start kept the foreign id", idF, id)
        assertEquals(a, f.own(id!!))
        assertEquals(a, f.conv(id))
        assertEquals(id, f.peer(a))
        assertEquals("the new id's first message is marked not sent", id, f.initPending(a))
        assertEquals("the old conv: entry stays, so the contact's memos under it are filed as before", a, f.conv(idF))
        assertTrue(
            "peer:, conv:, own: and the mark were not written in one commit: ${f.file.commits}",
            f.file.commits.any { it.containsAll(listOf("peer:$a", "conv:$id", "own:$id", "initpending:$a")) },
        )
        assertEquals("an own id is returned unchanged the next time", id, f.call("ownConversationIdForStart", a)!!.getOrThrow())
    }

    @Test
    fun `(k) guard - ownConversationIdForStart returns an own id the generators made`() {
        val f = ConversationIdStoreFixture()
        val (made, _) = f.store.getOrCreateConversationIdForMessage(a)

        val result = f.call("ownConversationIdForStart", a)
        assertNotNull("the store has no ownConversationIdForStart(address)", result)
        assertEquals(made, result!!.getOrThrow())
    }

    // ---- 5.3a item 2: the restore write ----------------------------------------------------------------------------

    @Test
    fun `(g) restoreOwnConversationId records an id met first in an own send, and never one that has an entry`() {
        val f = ConversationIdStoreFixture()
        val restore = f.call("restoreOwnConversationId", idC, a)
        assertNotNull("the store has no restoreOwnConversationId(id, address)", restore)
        assertEquals(a, f.conv(idC))
        assertEquals(idC, f.peer(a))
        assertEquals(a, f.own(idC))
        assertTrue(
            "conv:, peer: and own: were not written in one commit: ${f.file.commits}",
            f.file.commits.any { it.containsAll(listOf("conv:$idC", "peer:$a", "own:$idC")) },
        )

        f.store.setConversationMapping(idF, m) // the id came in first
        f.call("restoreOwnConversationId", idF, m)!!.getOrThrow()
        assertNull("an id that already had an entry was recorded as own", f.own(idF))
    }

    // ---- (m) a phone updated in place ------------------------------------------------------------------------------

    @Test
    fun `(m) guard - ids written before the update have no record and still route as before`() {
        val f = ConversationIdStoreFixture()
        f.file
            .edit()
            .putString("conv:$idC", m)
            .putString("peer:$m", idC)
            .putString("conv:$idF", a)
            .commit()

        assertEquals(m, f.store.getPeerByConversationId(idC))
        assertEquals(a, f.store.getPeerByConversationId(idF))
        assertEquals(idC, f.store.getConversationId(m))
        assertTrue("no own-id record is invented for an old id", f.ownKeys().isEmpty())
    }
}
