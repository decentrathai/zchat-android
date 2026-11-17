package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.TexApi
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture
import co.electriccoin.zcash.ui.common.tex.txid
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The number beside "about M minutes" names the same F4 boundary as M. */
class WaitingTextsNameTheBlockTheirMinutesCountToTest {
    private companion object {
        const val E = 5_000L
        val TX_ID: String = txid(0xF7)
    }

    @Test
    fun detail_waiting_resource_names_the_dead_block_from_a_real_checker_pass() =
        runBlocking {
            val deadBlock = E + SendAttemptRules.REORG_MARGIN
            val wallet =
                FakeTexWallet().apply {
                    tip = E - 20
                    transactions =
                        listOf(
                            TexApi.facts(
                                txId = TX_ID,
                                mined = null,
                                expiry = E,
                                outputs = listOf(TexApi.output("u1syntheticplainrecipient0002", isOwn = false, kind = "OTHER")),
                            ),
                        )
                }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
            TexApi.pass(monitor)
            val methods = Class.forName("co.electriccoin.zcash.ui.screen.transactiondetail.PlainSendAgainKt").methods
            val method = methods.first { it.name == "plainSendFooter" && it.parameterCount in 2..3 }
            val check = TexSameBlockFixture.check(monitor)
            val footer =
                if (method.parameterCount == 3) {
                    method.invoke(null, TX_ID, check, false) as ErrorFooter
                } else {
                    method.invoke(null, TX_ID, check) as ErrorFooter
                }
            val minutes = SendAttemptRules.minutesUntilDead(E, E - 20)
            assertEquals(ChainTiming.minutesBetween(E - 20, deadBlock), minutes)
            assertEquals(
                StringResource.ByResource(R.string.transaction_detail_waiting_body, listOf(deadBlock.toString(), minutes)),
                footer.subtitle,
            )
            val helper =
                SendAttemptRules::class.java.methods
                    .firstOrNull { it.name == "deadBlock" && it.parameterCount == 1 }
                    ?: fail("SendAttemptRules.deadBlock(expiry) is missing")
            assertEquals(deadBlock, helper.invoke(SendAttemptRules, E))
        }

    @Test
    fun progress_and_chat_row_use_the_dead_block_helper() {
        val progress =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactionprogress/TransactionProgressVM.kt")
        assertTrue(
            Regex("""send_confirmation_waiting_subtitle,\s*SendAttemptRules\.deadBlock\(outcome\.expiryHeight\)""")
                .containsMatchIn(progress),
            "the progress subtitle names expiry while its minutes count to expiry + 10",
        )
        val chat =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        val row = chat.substringAfter("R.string.chat_send_row_waiting_for_network,").take(240)
        assertTrue(
            Regex("""SendAttemptRules\s*\.deadBlock\(expiry\)""").containsMatchIn(row),
            "the chat row names expiry while its minutes count to expiry + 10",
        )
    }
}
