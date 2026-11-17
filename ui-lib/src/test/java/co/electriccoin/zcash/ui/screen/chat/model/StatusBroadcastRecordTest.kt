package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.model.StatusBroadcastRecord.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * ZC-283 (fix-all 2.13.0, train T5b; r8-findings SPECS §2.4) — the status broadcast record: what each contact got of
 * one status text, by hashes only, read strictly, and never a reason to pay on doubt.
 *
 * All addresses are synthetic.
 */
class StatusBroadcastRecordTest {
    private fun sha256First32(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private val a = StatusBroadcastRecord.peerHash(PEER_A)
    private val b = StatusBroadcastRecord.peerHash(PEER_B)
    private val c = StatusBroadcastRecord.peerHash(PEER_C)

    @Test
    fun `a status and a contact are named by the first 32 hex characters of their SHA-256`() {
        assertEquals(sha256First32(STATUS), StatusBroadcastRecord.statusHash(STATUS))
        assertEquals(sha256First32(PEER_A), a)
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `what is written holds hashes only and reads back the same`() {
        val record =
            StatusBroadcastRecord(StatusBroadcastRecord.statusHash(STATUS))
                .with(b, Outcome.MAYBE)
                .with(a, Outcome.SENT)
        val written = record.encode()

        for (secret in listOf(PEER_A, PEER_B, STATUS)) assertFalse("the record holds $secret", secret in written)
        assertTrue(a in written && b in written)
        assertEquals(record, StatusBroadcastRecord.decode(written))
        val (first, second) = listOf(a, b).sorted()
        val code = { hash: String -> if (hash == a) "S" else "M" }
        assertEquals(
            "{\"v\":1,\"s\":\"${record.statusHash}\",\"p\":{\"$first\":\"${code(first)}\",\"$second\":\"${code(second)}\"}}",
            written,
        )
        val empty = StatusBroadcastRecord(record.statusHash)
        assertEquals("{\"v\":1,\"s\":\"${record.statusHash}\",\"p\":{}}", empty.encode())
        assertEquals(empty, StatusBroadcastRecord.decode(empty.encode()))
    }

    @Test
    fun `a contact is recorded, upgraded and withdrawn without touching the others`() {
        val start = StatusBroadcastRecord(StatusBroadcastRecord.statusHash(STATUS)).with(a, Outcome.SENT)
        val stamped = start.with(b, Outcome.MAYBE)

        assertEquals(mapOf(a to Outcome.SENT, b to Outcome.MAYBE), stamped.peers)
        assertEquals(mapOf(a to Outcome.SENT, b to Outcome.SENT), stamped.with(b, Outcome.SENT).peers)
        assertEquals(start, stamped.without(b))
        assertSame("nothing to withdraw: the same record, so nothing is written", start, start.without(b))
        assertSame("already recorded so: the same record", stamped, stamped.with(b, Outcome.MAYBE))
        assertEquals(1, stamped.count(listOf(a, b, c), Outcome.SENT))
        assertEquals(1, stamped.count(listOf(a, b, c), Outcome.MAYBE))
        assertEquals("a contact that left the list is not counted", 0, stamped.count(listOf(c), Outcome.SENT))
    }

    @Test
    fun `a value that is not exactly a written record is not read`() {
        val good = StatusBroadcastRecord(StatusBroadcastRecord.statusHash(STATUS)).with(a, Outcome.SENT).encode()
        val unreadable =
            listOf(
                "",
                "{}",
                "not json",
                good.dropLast(1),
                "$good ",
                good.replace("\"v\":1", "\"v\":2"),
                good.replace("\"S\"", "\"X\""),
                good.replace(a, a.uppercase()),
                good.replace(a, a.dropLast(1)),
                good.replace(a, PEER_A),
                // The same contact named twice.
                good.replace("\"p\":{", "\"p\":{\"$a\":\"M\","),
            )
        for (raw in unreadable) assertNull("read as a record: $raw", StatusBroadcastRecord.decode(raw))
    }

    @Test
    fun `nothing stored or a record of another text starts a new broadcast to everyone`() {
        val hash = StatusBroadcastRecord.statusHash(STATUS)
        val other = StatusBroadcastRecord(StatusBroadcastRecord.statusHash(OTHER_STATUS)).with(a, Outcome.SENT).encode()

        assertEquals(StatusBroadcastRecord(hash), StatusBroadcastRecord.startingFrom(null, STATUS, OTHER_STATUS, listOf(a, b)))
        assertEquals(StatusBroadcastRecord(hash), StatusBroadcastRecord.startingFrom(other, STATUS, OTHER_STATUS, listOf(a, b)))
        // Also when the text saved before was this one: the readable record says what was sent, and it was another text.
        assertEquals(StatusBroadcastRecord(hash), StatusBroadcastRecord.startingFrom(other, STATUS, STATUS, listOf(a, b)))
    }

    @Test
    fun `a record of the same text is the one the broadcast goes on from`() {
        val stored = StatusBroadcastRecord(StatusBroadcastRecord.statusHash(STATUS)).with(a, Outcome.SENT).with(b, Outcome.MAYBE)

        assertEquals(stored, StatusBroadcastRecord.startingFrom(stored.encode(), STATUS, OTHER_STATUS, listOf(a, b, c)))
    }

    @Test
    fun `an unreadable record counts every current contact as maybe sent for the text saved before`() {
        val sameText = StatusBroadcastRecord.startingFrom("garbage", STATUS, previousStatus = STATUS, listOf(a, b, c))

        assertEquals(StatusBroadcastRecord.statusHash(STATUS), sameText.statusHash)
        assertEquals(
            "doubt is never a second payment: nobody is left to send the same text to",
            mapOf(a to Outcome.MAYBE, b to Outcome.MAYBE, c to Outcome.MAYBE),
            sameText.peers,
        )
    }

    @Test
    fun `an unreadable record does not hold back a different text`() {
        val hash = StatusBroadcastRecord.statusHash(STATUS)

        assertEquals(StatusBroadcastRecord(hash), StatusBroadcastRecord.startingFrom("garbage", STATUS, OTHER_STATUS, listOf(a, b)))
        assertEquals(StatusBroadcastRecord(hash), StatusBroadcastRecord.startingFrom("garbage", STATUS, null, listOf(a, b)))
    }

    private companion object {
        const val PEER_A = "usyntheticzc283recordcontacta"
        const val PEER_B = "usyntheticzc283recordcontactb"
        const val PEER_C = "usyntheticzc283recordcontactc"
        const val STATUS = "synthetic zc283 record status"
        const val OTHER_STATUS = "synthetic zc283 other record status"
    }
}
