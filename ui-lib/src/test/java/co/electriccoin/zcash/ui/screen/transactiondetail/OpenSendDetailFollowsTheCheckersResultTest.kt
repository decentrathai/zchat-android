package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.R
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The open detail sees both values published by one monitor's rising-tip passes. */
class OpenSendDetailFollowsTheCheckersResultTest {
    private companion object {
        const val E = 5_000L
        val TX_ID: String = txid(0xF9)
    }

    @Test
    fun the_same_monitor_changes_the_footer_after_the_second_rising_pass() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    transactions =
                        listOf(
                            TexApi.facts(
                                txId = TX_ID,
                                mined = null,
                                expiry = E,
                                outputs = listOf(TexApi.output("u1syntheticplainrecipient0003", isOwn = false, kind = "OTHER")),
                            ),
                        )
                }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
            val methods = Class.forName("co.electriccoin.zcash.ui.screen.transactiondetail.PlainSendAgainKt").methods
            val candidate = methods.firstOrNull { it.name == "plainSendFooter" && it.parameterCount == 3 }
            val method = candidate ?: methods.first { it.name == "plainSendFooter" && it.parameterCount == 2 }

            fun footer(check: Any?): ErrorFooter =
                if (method.parameterCount == 3) {
                    method.invoke(null, TX_ID, check, true) as ErrorFooter
                } else {
                    method.invoke(null, TX_ID, check) as ErrorFooter
                }
            wallet.tip = E + 10
            TexApi.pass(monitor)
            val first = footer(TexSameBlockFixture.check(monitor))
            if (method.parameterCount == 3) {
                val titleId = R.string::class.java.getField("transaction_detail_expired_checking_title").getInt(null)
                assertEquals(StringResource.ByResource(titleId, emptyList()), first.title)
                assertEquals(StringResource.ByResource(R.string.transaction_detail_checking_body, emptyList()), first.subtitle)
            } else {
                assertEquals(StringResource.ByResource(R.string.transaction_detail_waiting_title, emptyList()), first.title)
                assertEquals(R.string.transaction_detail_waiting_body, (first.subtitle as StringResource.ByResource).resource)
            }
            wallet.tip = E + 11
            TexApi.pass(monitor)
            val second = footer(TexSameBlockFixture.check(monitor))
            assertEquals(StringResource.ByResource(R.string.transaction_detail_not_sent_title, emptyList()), second.title)
            assertEquals(StringResource.ByResource(R.string.transaction_detail_not_sent_body, emptyList()), second.subtitle)
        }

    @Test
    fun the_open_detail_combines_the_monitor_flow_without_polling_a_snapshot() {
        val vm = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt")
        val texCheck = vm.substringAfter("private val texCheck =").substringBefore("val state =")
        assertTrue("texSendMonitor.check" in texCheck)
        assertFalse("check.value" in texCheck, "the detail reads a stale snapshot")
        assertTrue(
            Regex("""combine\(transaction\.filterNotNull\(\), texCheck\)""").containsMatchIn(vm),
            "the open detail no longer observes later checker publications",
        )
    }
}
