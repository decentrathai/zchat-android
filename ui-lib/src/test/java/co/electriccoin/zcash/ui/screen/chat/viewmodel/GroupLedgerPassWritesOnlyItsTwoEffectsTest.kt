package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-G (ZC-112 part 2; SPEC §7.3 item 12) — source scan: the group side of the ledger's history pass can never be a way
 * to a payment, and writes only its two visibility effects.
 *
 * The code between F8G-LEDGER-GROUP-BEGIN and F8G-LEDGER-GROUP-END is everything the pass runs for the group kinds
 * beside the ledger's own writes. It must not build a proposal, call a sender, publish to a relay or write an owed-key
 * mark. Its only store writes are one checked roster write ("Invite failed") and one announce compare-and-set (the
 * pair is owed again), each under the group's lock. After a proof, the user's quoted tap is the only way to a payment.
 */
class GroupLedgerPassWritesOnlyItsTwoEffectsTest {
    private val region = LedgerSources.region("F8G-LEDGER-GROUP")

    @Test
    fun `the region names no sender, payer, relay publish or owed-key write`() {
        assertTrue(
            "sanity: the region holds the two effects",
            region.contains("fun replayInviteEffect(") && region.contains("fun replayAnnounceEffect("),
        )
        for (forbidden in listOf(
            "createChunkedMessageProposal",
            "sendMemoWithBlockRetry",
            "sendGroupMemoWithRetry",
            "sendAutomaticHandshakeMemoOutcome",
            "sendGroupControlMemo",
            "NostrChatBridge.publish",
            ".publish(",
            "requestPass",
            "resendTo",
            "askBeforePaying",
            "confirmPaidAction",
            ".launch",
            "recordKeyOwed",
            "pendingKeyEpoch",
            "pendingKeyTxIds",
            "setGroupKeyEpoch",
            "saveGroupKey",
            "setGroupRemovalFloor",
            "setGroupMessageSpendConsent",
            "setConsentedToHandshakeSpend",
        )) {
            assertTrue("the group side of the ledger pass must never reach `$forbidden`", !region.contains(forbidden))
        }
    }

    @Test
    fun `its only store writes are one checked roster write and one announce compare-and-set, each under the group lock`() {
        val writes =
            Regex("""zchatPreferences\.(set|add|commit|remove|clear|save|delete|store|mark|restore|cas)\w*\(""")
                .findAll(region)
                .map { it.value }
                .toList()
        assertEquals(listOf("zchatPreferences.saveGroupMembersDurably("), writes)
        assertEquals("one announce compare-and-set", 1, Regex("""announcer\.cas\(""").findAll(region).count())
        assertEquals("the group lock, once per effect", 2, Regex("""\bwithGroupLock\(groupId\) \{""").findAll(region).count())
        for (effect in listOf("replayInviteEffect", "replayAnnounceEffect")) {
            val body = region.substringAfter("fun $effect(").substringBefore("\n    }\n")
            val lock = body.indexOf("withGroupLock(groupId) {")
            val reread = body.indexOf("if (!stillNotSent(ledger, entry)) return@withGroupLock LedgerEffect.KEEP")
            val write = maxOf(body.indexOf("saveGroupMembersDurably("), body.indexOf("announcer.cas("))
            assertTrue("$effect reads the entry again under the group lock before it writes", lock in 0 until reread && reread < write)
        }
        assertTrue("no other lock is taken", !region.contains("synchronized(") && !region.contains(".withLock"))
    }

    @Test
    fun `the pass calls the group side by name, and the receiver's key rule is not part of this batch`() {
        val pass = LedgerSources.region("F8-LEDGER-PASS")
        for (call in listOf(
            "replayInviteEffect(ledger, entry)",
            "replayAnnounceEffect(ledger, entry)",
            "settleGroupKeySibling(ledger, judged)",
            "keptForRotation(ledger, entry)",
            "noteDroppedGroupEntry(entry)",
        )) {
            assertTrue("the pass does not call `$call`", pass.contains(call))
        }
        assertTrue("a running group Resend is left alone by the pass", pass.contains("entry.key in GroupViewModel.groupResendsInFlight"))
        // The old-key decision: a key whose number is not above the receiver's is still ignored (the owner's decision).
        assertTrue(LedgerSources.chatViewModel.contains("not newer than current"))
    }
}
