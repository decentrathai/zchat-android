package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.EXPIRY
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG1
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture.PLAIN_T
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T3-fixup (fix-all 2.13.0, train T3; cross-family review G-F3-1): "Send again" was offered on a sent transaction the
 * TEX checker had no status for (texSendAgainAllowed(null) is true), and the checker has none for a transaction it has
 * not classified yet: before its first pass completes (the detail can be open before the wallet is ready), when every
 * pass throws, for a transaction made after the last pass, and for a leg of a send it could not pair. Sending leg 2 of
 * a TEX send again while the first leg 2 can still be mined pays the exchange twice. Now the detail asks the
 * checker's last completed pass (its `check`): Send again only for a transaction that pass saw and found in no TEX
 * send, or whose TEX send is provably settled (NOT_SENT, DONE, SHIELDED).
 * Fails at 7e8800885: there is no `check`, and the detail passes texSendAgainAllowed the status or null.
 */
class TexSendAgainNeedsACheckTest {
    private fun onTor() = FakeTexTor().apply { on = true }

    @Test
    fun before_a_pass_completed_nothing_is_offered_again() {
        val monitor = TexApi.monitor(FakeTexWallet(), onTor(), idle = { true })

        assertNull(TexSameBlockFixture.check(monitor))
        assertFalse(TexSameBlockFixture.sendAgainAllowed(LEG2, null))
        assertFalse(TexSameBlockFixture.sendAgainAllowed(PLAIN_T, null))
    }

    @Test
    fun after_a_pass_a_transaction_it_saw_in_no_tex_send_is_offered_again() =
        runBlocking {
            val plain = TexApi.facts(txId = PLAIN_T, mined = EXPIRY - 100, expiry = EXPIRY - 60)
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY - 20
                    transactions = listOf(plain, TexFixture.leg1(mined = null), TexFixture.leg2())
                }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            TexApi.pass(monitor)
            val check = TexSameBlockFixture.check(monitor)

            assertTrue(TexSameBlockFixture.sendAgainAllowed(PLAIN_T, check), "a transaction in no TEX send keeps Send again")
            assertFalse(TexSameBlockFixture.sendAgainAllowed(LEG2, check), "leg 2 on its way")
            assertFalse(TexSameBlockFixture.sendAgainAllowed(LEG1, check), "leg 1 on its way")
            // Made after that pass: not classified yet.
            assertFalse(TexSameBlockFixture.sendAgainAllowed(txid(0x99), check), "a transaction the pass never saw")
        }

    @Test
    fun a_settled_tex_send_is_offered_again() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY
                    transactions = listOf(TexFixture.leg1(), TexFixture.leg2(mined = EXPIRY - 5))
                }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            TexApi.pass(monitor)

            assertTrue(TexSameBlockFixture.sendAgainAllowed(LEG2, TexSameBlockFixture.check(monitor)), "DONE")
        }

    @Test
    fun while_every_pass_throws_nothing_is_offered_again_and_a_later_failure_keeps_the_last_check() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY
                    transactions = listOf(TexFixture.leg1(), TexFixture.leg2(mined = EXPIRY - 5))
                }
            var failing = true
            val monitor = TexSameBlockFixture.monitorWithFailingReads(wallet) { failing }

            repeat(2) { assertTrue(runCatching { TexApi.pass(monitor) }.isFailure, "the pass should have thrown") }
            assertNull(TexSameBlockFixture.check(monitor))
            assertFalse(TexSameBlockFixture.sendAgainAllowed(LEG2, TexSameBlockFixture.check(monitor)))

            failing = false
            TexApi.pass(monitor)
            failing = true
            runCatching { TexApi.pass(monitor) }
            assertTrue(TexSameBlockFixture.sendAgainAllowed(LEG2, TexSameBlockFixture.check(monitor)))
        }

    @Test
    fun the_detail_asks_the_check_for_its_own_transaction() {
        val vm =
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                .resolve("src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt")
                .readText()
        val sendAgain = vm.substringBefore("R.string.transaction_detail_send_again").takeLast(SEND_AGAIN_WINDOW)
        assertTrue(
            "texSendAgainAllowed(transactionDetailArgs.transactionId, " in sendAgain,
            "Send again is not decided by the checker's last pass for this transaction: …$sendAgain",
        )
        assertTrue("texSendMonitor.check" in vm, "the detail does not read the checker's last pass")
        assertFalse("texSendAgainAllowed(tex?.state)" in vm, "the detail still treats 'no status' as 'no TEX send'")
    }

    private companion object {
        const val SEND_AGAIN_WINDOW = 200
    }
}
