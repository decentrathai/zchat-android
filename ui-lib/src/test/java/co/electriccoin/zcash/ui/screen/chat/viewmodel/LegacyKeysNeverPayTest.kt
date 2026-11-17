package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72, SPEC_F_20261019 rule R9 with SPEC_DECISIONS D8, RED test 13, written as a guard (skill money-path): nothing W-72
 * adds pays. The behaviour is pinned where it happens: the first start (LegacyKeyMarkLivesWithTheKeyTest, a store pass with
 * no proposal path), the guard and "Mark as verified" (LegacyKeyMarkKeepsTheRelayIdentityTest: 0 proposals in Open, Tunnel
 * and Shielded), the probe (LegacyProbeRidesOnTheNextOnChainSendTest: the same outputs and amounts as without a mark, one
 * proposal per message, nothing over the relays), the clear (LegacyMarkClearsOnlyByTheProbeAnswerTest: 0 proposals). This
 * guard pins the code shape that keeps it so: the functions W-72 adds call no sender, no proposal and no automatic
 * handshake; a held ZBOOT is handed on only with the relays-only reply rule; the probe is chosen only inside the on-chain
 * send that already carries the confirmed quote (doSendMessage's SR-A sendQuote), after its chat-id call, and never for a
 * control memo or a first message.
 */
class LegacyKeysNeverPayTest {
    private val vm by lazy { ChatResourceText.source("$CHAT/viewmodel/ChatViewModel.kt") }
    private val store by lazy { ChatResourceText.source("$CHAT/datasource/ZchatPreferences.kt") }

    private val payCalls =
        listOf(
            "createChunkedMessageProposal(",
            "payKexAckOnce(",
            "payOwedKex(",
            "sendAutomaticHandshakeMemo(",
            "sendKEXAckMessage(",
            "sendMemoWithBlockRetry(",
            "submitTransaction(",
            "createZip321Proposal(",
            "QUOTED_IN_SHIELDED",
            "doSendMessage(",
        )

    private fun body(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        assertTrue("no $signature", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val from = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(from, i + 1)
            }
            i++
        }
        throw AssertionError("unbalanced $signature")
    }

    @Test
    fun `(13) the functions W-72 adds call no sender, no proposal and no automatic handshake`() {
        val bodies =
            listOf(
                body(vm, "private fun legacyOnChainRoute("),
                body(vm, "private fun noteLegacyKeyEvidence("),
                body(vm, "private fun clearLegacyMarkByAnswer("),
                body(vm, "private fun minedAtOrBeforeUpgrade("),
                body(store, "private fun markLegacyKeysOnce("),
                body(store, "override fun startLegacyProbe("),
                body(store, "override fun releaseLegacyKeyWithBoot("),
            )
        for (b in bodies) for (call in payCalls) assertFalse("a W-72 function calls $call:\n${b.take(200)}", b.contains(call))
    }

    @Test
    fun `(13) a held ZBOOT is handed on with the relays-only reply only, by the tap and by the clear`() {
        for (sig in listOf("fun markE2EVerified(", "private fun clearLegacyMarkByAnswer(")) {
            val b = body(vm, sig)
            val handOns = Regex("""routeIncomingBoot\(""").findAll(b).count()
            val relayOnly = Regex("""replyRule = ZbootOnChainRule\.RELAY_ONLY""").findAll(b).count()
            assertTrue("$sig hands a ZBOOT on without the relays-only reply", handOns == relayOnly && handOns > 0)
        }
    }

    @Test
    fun `(13) the probe is chosen only inside the quoted on-chain send, never for a control memo or a first message`() {
        val callers = Regex("""legacyOnChainRoute\(""").findAll(vm).count()
        assertTrue("legacyOnChainRoute is called from more than the send and its definition", callers == 2)
        val send = body(vm, "private fun doSendMessage(")
        assertTrue("the probe is not chosen in doSendMessage", send.contains("legacyOnChainRoute("))
        assertTrue(
            "the probe may ride on a control memo or a first message",
            send.contains("if (controlMemo || chatFirstMessage) null else legacyOnChainRoute("),
        )
        assertTrue("doSendMessage lost its confirmed quote", send.contains("sendQuote = sendQuote"))
    }

    private companion object {
        const val CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
    }
}
