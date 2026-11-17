package co.electriccoin.zcash.ui.common.tex

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F3 (PLAN.md §3 F3 TexResendGuardTest; owner 25 Sep: "'Send again' only once the money is provably back"): the
 * transaction detail of a TEX leg offers "Send again" only when nothing left the wallet (NOT_SENT), the exchange was
 * paid (DONE) or the ZEC is back and mined (SHIELDED); never while the money may still arrive, is being checked or
 * moved, or needs support: sending again then can pay twice. A transaction that is no TEX leg keeps its button.
 * Fails at 15e2b1c39: the detail offers "Send again" for every sent transaction with a contact.
 */
class TexResendGuardTest {
    @Test
    fun send_again_only_when_the_money_is_provably_where_the_user_thinks() {
        val allowed = setOf("NOT_SENT", "DONE", "SHIELDED")
        val states = TexApi.cls("TexState").enumConstants!!.map { (it as Enum<*>).name }
        assertEquals(
            listOf(
                "SENDING_STEP1",
                "SENDING_STEP2",
                "DONE",
                "NOT_SENT",
                "CHECKING",
                "NEEDS_TOR",
                "RETURNED",
                "SHIELDING",
                "SHIELDED",
                "NEEDS_SUPPORT",
            ),
            states,
        )
        for (state in states) {
            assertEquals(state in allowed, TexApi.sendAgainAllowed(state), state)
        }
        assertTrue(TexApi.sendAgainAllowed(null), "a transaction that is no TEX leg keeps Send again")
    }

    @Test
    fun the_detail_withholds_send_again_through_the_guard_and_says_the_tex_state() {
        val vm =
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                .resolve("src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt")
                .readText()
        val sendAgain = vm.substringBefore("R.string.transaction_detail_send_again").takeLast(SEND_AGAIN_WINDOW)
        assertTrue(
            "texSendAgainAllowed(" in sendAgain,
            "the Send again button is not guarded by texSendAgainAllowed: …$sendAgain",
        )
        assertTrue("forTransaction(transactionDetailArgs.transactionId)" in vm, "the detail does not find its TEX send")
    }

    private companion object {
        const val SEND_AGAIN_WINDOW = 200
    }
}
