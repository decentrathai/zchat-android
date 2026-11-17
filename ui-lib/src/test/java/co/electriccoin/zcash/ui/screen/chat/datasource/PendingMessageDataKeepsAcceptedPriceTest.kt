package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ZC-1271: a first message waiting for its funds resumes from the stored row after a restart, so the total the user
 * accepted is on disk with it (`acceptedPriceZatoshi`, reached by name until it exists). Rows without it, every row older
 * builds wrote, read as null: a null is never a price anyone accepted. Every id, text and address is synthetic.
 */
class PendingMessageDataKeepsAcceptedPriceTest {
    private fun accepted(row: ZchatPreferences.PendingMessageData): Long? {
        val getter =
            assertNotNull(
                ZchatPreferences.PendingMessageData::class.java.methods.firstOrNull { it.name == "getAcceptedPriceZatoshi" },
                "the stored row has no accepted price (no PendingMessageData.acceptedPriceZatoshi)",
            )
        return getter.invoke(row) as Long?
    }

    @Test
    fun `the accepted price survives a write and a read`() {
        val read =
            ZchatPreferences.PendingMessageData.fromJson(
                """{"id":"pending_1","text":"synthetic waits","timestampMillis":1700000000000,""" +
                    """"peerAddress":"usyntheticjsonpeer1271","acceptedPriceZatoshi":51000}""",
            )
        assertEquals(51_000L, accepted(read), "the accepted price was not read")
        val again =
            ZchatPreferences.PendingMessageData.fromJson(ZchatPreferences.PendingMessageData.toJson(read))
        assertEquals(51_000L, accepted(again), "the accepted price was not written")
    }

    @Test
    fun `a row an older build wrote has no accepted price`() {
        val old =
            ZchatPreferences.PendingMessageData.fromJson(
                """{"id":"pending_2","text":"synthetic old","timestampMillis":1700000000000,""" +
                    """"peerAddress":"usyntheticjsonpeer1271"}""",
            )
        assertEquals(null, accepted(old))
    }
}
