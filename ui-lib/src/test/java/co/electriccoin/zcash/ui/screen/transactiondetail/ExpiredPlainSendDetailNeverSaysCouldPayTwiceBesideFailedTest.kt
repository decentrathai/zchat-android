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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** SDK expiry cannot invite another payment before F4's two-pass proof. */
class ExpiredPlainSendDetailNeverSaysCouldPayTwiceBesideFailedTest {
    private companion object {
        const val E = 5_000L
        val PLAIN: String = txid(0xF1)
        val MINED: String = txid(0xF3)
        val UNSEEN: String = txid(0xF5)
    }

    private fun res(name: String, vararg args: Any): StringResource.ByResource =
        StringResource.ByResource(R.string::class.java.getField(name).getInt(null), args.toList())

    private fun footer(check: Any?, txId: String = PLAIN, expired: Boolean = true): ErrorFooter? {
        val methods = Class.forName("co.electriccoin.zcash.ui.screen.transactiondetail.PlainSendAgainKt").methods
        val candidate = methods.firstOrNull { it.name == "plainSendFooter" && it.parameterCount == 3 }
        val method = candidate ?: methods.first { it.name == "plainSendFooter" && it.parameterCount == 2 }
        val args = if (method.parameterCount == 3) arrayOf(txId, check, expired) else arrayOf(txId, check)
        return method.invoke(null, *args) as ErrorFooter?
    }

    @Test
    fun expired_waiting_shows_checking_until_the_second_rising_tip_proves_not_sent() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    transactions =
                        listOf(
                            TexApi.facts(
                                txId = PLAIN,
                                mined = null,
                                expiry = E,
                                outputs = listOf(TexApi.output("u1syntheticplainrecipient0001", isOwn = false, kind = "OTHER")),
                            ),
                            TexApi.facts(
                                txId = MINED,
                                mined = E - 30,
                                expiry = E,
                                outputs = listOf(TexApi.output("u1syntheticplainrecipient0001", isOwn = false, kind = "OTHER")),
                            ),
                        )
                }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
            assertNull(footer(null), "no verdict")
            wallet.tip = E - 20
            TexApi.pass(monitor)
            val before = footer(TexSameBlockFixture.check(monitor), expired = false) ?: fail("no unexpired footer")
            assertEquals(res("transaction_detail_waiting_title"), before.title)
            assertEquals(R.string.transaction_detail_waiting_body, (before.subtitle as StringResource.ByResource).resource)

            wallet.tip = E + 10
            TexApi.pass(monitor)
            val first = TexSameBlockFixture.check(monitor)
            assertExpired(first)
            assertNull(footer(first, MINED), "mined send")
            assertNull(footer(first, UNSEEN), "unseen send")
            TexApi.pass(monitor)
            assertExpired(TexSameBlockFixture.check(monitor))

            wallet.tip = E + 11
            TexApi.pass(monitor)
            val proven = footer(TexSameBlockFixture.check(monitor)) ?: fail("no proven-dead footer")
            assertEquals(res("transaction_detail_not_sent_title"), proven.title)
            assertEquals(res("transaction_detail_not_sent_body"), proven.subtitle)
        }

    @Test
    fun detail_passes_the_sdk_failed_state_to_the_plain_footer() {
        val vm = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt")
        val call = vm.substringAfter("private fun createPlainSendFooter(").substringBefore("private fun createErrorFooter(")
        assertTrue(
            Regex(
                """plainSendFooter\(\s*transactionDetailArgs\.transactionId,\s*check,\s*expired\s*=\s*data\.transaction is SendTransaction\.Failed""",
            ).containsMatchIn(call),
            "the detail does not pass the SDK's expired state to the waiting footer",
        )
    }

    private fun assertExpired(check: Any?) {
        val result = footer(check) ?: fail("SDK-expired Waiting has no footer")
        assertNotEquals(res("transaction_detail_waiting_title"), result.title, "an expired send still says Not confirmed yet")
        assertEquals(res("transaction_detail_expired_checking_title"), result.title)
        assertEquals(res("transaction_detail_checking_body"), result.subtitle)
        assertNotEquals(R.string.transaction_detail_waiting_body, (result.subtitle as StringResource.ByResource).resource)
    }
}
