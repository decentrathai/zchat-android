package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 R20 (e) (ZC-112; SPEC §7.3 item 12) — source scan: the ledger's history pass can never be a way to a payment.
 *
 * The code between the markers F8-LEDGER-PASS-BEGIN and F8-LEDGER-PASS-END is everything the pass runs. It must not
 * build a proposal, call a sender or a payer, or publish to a relay. An automatic payment after a proof is the 2.13.1
 * design and needs its own review: in 2.13.0 the user's quoted tap is the only way.
 */
class SendAttemptReconcilerNeverSendsTest {
    @Test
    fun `the pass names no sender, payer or relay publish`() {
        val pass = LedgerSources.region("F8-LEDGER-PASS")
        assertTrue("sanity: the region holds the pass", pass.contains("fun reconcileSendAttemptLedger("))
        for (forbidden in listOf(
            "createChunkedMessageProposal",
            "sendMemoWithBlockRetry",
            "sendGroupMemoWithRetry",
            "sendAutomaticHandshakeMemoOutcome",
            "sendUserMemoOutcomeWithBlockRetry",
            "NostrChatBridge.publish",
            ".publish(",
            "sendKEXMessage",
            "sendKexOnce",
            "sendKEXAckMessage",
            "payKexAckOnce",
            "sendNostrBootHandshake",
            "payOwedKex",
            "retryOwedKeyExchange",
            "sendClaimedGroupAccept",
            "sendHeldGroupAccept",
            "sendJoinedGroupAccept",
            "joinGroupInvite",
            "retryHandshake",
            "runHandshakeRetry",
            "askBeforePaying",
            "confirmPaidAction",
            "processSendScope",
            ".launch",
        )) {
            assertTrue("the ledger pass must never reach `$forbidden`: it pays nothing and sends nothing", !pass.contains(forbidden))
        }
    }

    @Test
    fun `the pass is called once, right after the row reconcile, in the history pass`() {
        val calls = Regex("""\breconcileSendAttemptLedger\(""").findAll(LedgerSources.chatViewModel).count()
        assertTrue("one definition and one call ($calls found)", calls == 2)
        val inPass = LedgerSources.function("convertToConversationsInPass")
        val rows = inPass.indexOf("reconcileSendAttempts(historyScanComplete, messagesByPeer, pendingMsgs)")
        val ledger = inPass.indexOf("reconcileSendAttemptLedger(historyScanComplete, messagesByPeer, zbootPass)")
        assertTrue("the ledger pass follows the row reconcile in convertToConversationsInPass", rows in 0 until ledger)
        val between = inPass.substring(rows, ledger).lines().drop(1)
        assertTrue(
            "nothing but a comment stands between the two calls",
            between.all { it.isBlank() || it.trim().startsWith("//") },
        )
    }
}
