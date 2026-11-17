package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Test
import kotlin.test.assertEquals

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.3 fix tests, §1.4 step 2): the send-attempt fields F4 adds to a stored row are
 * read with defaults, so a row an older build wrote loads unchanged, and a row written now keeps every field.
 * Every id, text and address is synthetic.
 */
class PendingMessageDataOldJsonLoadsTest {
    @Test
    fun `a row a 2_12_4 build wrote loads with the legacy defaults`() {
        val json =
            """{"id":"pending_1","text":"synthetic old hello","timestampMillis":1700000000000,""" +
                """"peerAddress":"usyntheticjsonpeer0001"}"""
        assertEquals(
            ZchatPreferences.PendingMessageData(
                id = "pending_1",
                text = "synthetic old hello",
                timestampMillis = 1_700_000_000_000L,
                peerAddress = "usyntheticjsonpeer0001",
            ),
            ZchatPreferences.PendingMessageData.fromJson(json),
        )
    }

    @Test
    fun `a row an earlier 2_13_0 build wrote loads unchanged`() {
        val json =
            """{"id":"pending_2","text":"synthetic tracked hello","timestampMillis":1700000000001,""" +
                """"peerAddress":"usyntheticjsonpeer0002","isOutgoing":true,"isPending":false,"status":"FAILED",""" +
                """"sendAmountZatoshi":50000,"submitTracked":true,"submitStartedAtMillis":1700000000002,""" +
                """"submitAttemptHeight":3200000,"submittedTxIds":["aa11"],"failureReason":"synthetic reason"}"""
        assertEquals(
            ZchatPreferences.PendingMessageData(
                id = "pending_2",
                text = "synthetic tracked hello",
                timestampMillis = 1_700_000_000_001L,
                peerAddress = "usyntheticjsonpeer0002",
                isOutgoing = true,
                isPending = false,
                status = "FAILED",
                sendAmountZatoshi = 50_000L,
                submitTracked = true,
                submitStartedAtMillis = 1_700_000_000_002L,
                submitAttemptHeight = 3_200_000L,
                submittedTxIds = listOf("aa11"),
                failureReason = "synthetic reason",
            ),
            ZchatPreferences.PendingMessageData.fromJson(json),
        )
    }

    @Test
    fun `a row written now keeps every field`() {
        val row =
            ZchatPreferences.PendingMessageData(
                id = "pending_3",
                text = "synthetic full hello",
                timestampMillis = 1_700_000_000_003L,
                peerAddress = "usyntheticjsonpeer0003",
                isOutgoing = true,
                isPending = true,
                status = "SENDING",
                replyToId = "synthetic-reply-to",
                replyToPreview = "synthetic preview",
                sendAmountZatoshi = 20_000L,
                submitTracked = true,
                submitStartedAtMillis = 1_700_000_000_004L,
                submitAttemptHeight = 3_200_001L,
                submittedTxIds = listOf("bb22", "cc33"),
                failureReason = null,
                submittedExpiries = mapOf("bb22" to 3_200_041L, "cc33" to 3_200_042L),
                attemptProcessId = "synthetic-process",
                attemptAccountHash = "00112233aabbccdd",
                releaseAnchorHeight = 3_200_010L,
                supersededTxIds = listOf("dd44"),
                resolvedNotSentAtHeight = 3_200_060L,
            )
        assertEquals(row, ZchatPreferences.PendingMessageData.fromJson(ZchatPreferences.PendingMessageData.toJson(row)))
    }
}
