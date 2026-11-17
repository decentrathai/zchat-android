package co.electriccoin.zcash.ui.screen.chat.datasource

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 fix-commit tests): the started record of a payment-request fulfil gains the
 * stored transaction ids and expiries. A record written before F5 (no `txIds`, no `expiries`) loads unchanged, with no
 * ids, so Round 8 P2's rule still settles it; a record with ids survives a round trip; an expiry that cannot be read is
 * left out (it is never judged by it). All values are synthetic.
 */
class FulfilStartedOldJsonLoadsTest {
    private val oldJson =
        """{"attemptHeight":3200000,"peerHash":"aa11","amountZat":40000,"selfHash":"bb22","startedAt":1727000000000}"""

    @Test
    fun a_record_written_before_f5_loads_unchanged_and_without_ids() {
        val record = assertNotNull(ZchatPreferences.decodeFulfilStarted(JSONObject(oldJson)))
        assertEquals(
            ZchatPreferences.FulfilStarted(
                attemptHeight = 3_200_000L,
                peerHash = "aa11",
                amountZatoshi = 40_000L,
                selfHash = "bb22",
                startedAtMillis = 1_727_000_000_000L,
            ),
            record,
        )
        assertEquals(emptyList(), record.txIds)
        assertEquals(emptyMap(), record.expiries)
    }

    @Test
    fun a_record_with_ids_survives_a_round_trip_and_one_without_ids_writes_none() {
        val withIds =
            ZchatPreferences.FulfilStarted(
                attemptHeight = -1L,
                peerHash = "aa11",
                amountZatoshi = 1_000L,
                selfHash = "bb22",
                startedAtMillis = 5L,
                txIds = listOf("t1", "t2"),
                expiries = mapOf("t1" to 3_200_040L),
            )
        assertEquals(withIds, ZchatPreferences.decodeFulfilStarted(JSONObject(ZchatPreferences.encodeFulfilStarted(withIds).toString())))

        val old = assertNotNull(ZchatPreferences.decodeFulfilStarted(JSONObject(oldJson)))
        val encoded = ZchatPreferences.encodeFulfilStarted(old)
        assertFalse(encoded.has("txIds"))
        assertFalse(encoded.has("expiries"))
        val before = JSONObject(oldJson)
        assertEquals(before.keys().asSequence().toSet(), encoded.keys().asSequence().toSet())
        for (key in before.keys()) assertEquals(before.get(key).toString(), encoded.get(key).toString(), key)
    }

    @Test
    fun an_unreadable_expiry_is_left_out_and_a_record_without_a_peer_is_none() {
        val json = JSONObject(oldJson).put("txIds", org.json.JSONArray(listOf("t1"))).put("expiries", JSONObject().put("t1", "x"))
        val record = assertNotNull(ZchatPreferences.decodeFulfilStarted(json))
        assertEquals(listOf("t1"), record.txIds)
        assertEquals(emptyMap(), record.expiries)

        assertNull(ZchatPreferences.decodeFulfilStarted(JSONObject(oldJson).put("peerHash", "")))
    }
}
