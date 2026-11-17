package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.EXPIRY
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG1
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F3 (PLAN.md §2 correction 3, TexMonitorResubmitsOnceTest): an unmined, unexpired TEX leg is handed to the server
 * again once per process (a leg left AwaitingPlan by a kill between create and submit would otherwise never go out;
 * leg 1 may already be mined). TEX legs only: a transaction without a TEX partner is never touched. The debug seam
 * TEX_FORCE_STUCK stops it, so a leg kept AwaitingPlan stays unsent and the send gets stuck for the phone check.
 * Fails at 15e2b1c39: there is no TEX checker.
 */
class TexMonitorResubmitsOnceTest {
    @After
    fun disarm() = FaultSeams.disarmAll()

    private fun wallet(
        leg1Mined: Long?,
        tip: Long,
    ) = FakeTexWallet().apply {
        this.tip = tip
        transactions = listOf(TexFixture.leg1(mined = leg1Mined), TexFixture.leg2())
    }

    @Test
    fun both_legs_unmined_are_handed_over_once_in_order() =
        runBlocking {
            val wallet = wallet(leg1Mined = null, tip = EXPIRY - 20)
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })

            repeat(3) { TexApi.pass(monitor) }

            assertEquals(listOf(LEG1, LEG2), wallet.resubmitted)
        }

    @Test
    fun with_leg_one_mined_only_leg_two_is_handed_over() =
        runBlocking {
            val wallet = wallet(leg1Mined = EXPIRY - 30, tip = EXPIRY - 20)
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })

            repeat(2) { TexApi.pass(monitor) }

            assertEquals(listOf(LEG2), wallet.resubmitted)
        }

    @Test
    fun an_expired_leg_is_never_handed_over() =
        runBlocking {
            val wallet = wallet(leg1Mined = EXPIRY - 30, tip = EXPIRY)
            TexApi.pass(TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }))

            assertEquals(emptyList(), wallet.resubmitted)
        }

    @Test
    fun a_transaction_without_a_tex_partner_is_never_touched() =
        runBlocking {
            val plain =
                TexApi.facts(
                    txId = txid(0x33),
                    expiry = EXPIRY + 40,
                    outputs = listOf(TexApi.output("u1FixturePeerNotReal", isOwn = false, kind = "OTHER")),
                )
            val loneLeg1 =
                TexApi.facts(
                    txId = txid(0x44),
                    expiry = EXPIRY + 50,
                    outputs = listOf(TexApi.output("t1FixtureOwnNotReal", true, "TRANSPARENT"))
                )
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY
                    transactions = listOf(plain, loneLeg1)
                }
            val statuses = TexApi.pass(TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }))

            assertEquals(emptyList(), statuses)
            assertEquals(emptyList(), wallet.resubmitted)
            assertEquals(listOf("scannedTip", "transactions", "scannedTip"), wallet.calls)
        }

    @Test
    fun the_force_stuck_seam_hands_nothing_over() =
        runBlocking {
            FaultSeams.arm(FaultSeam.TEX_FORCE_STUCK, 1)
            val wallet = wallet(leg1Mined = EXPIRY - 30, tip = EXPIRY - 20)
            TexApi.pass(TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }))

            assertEquals(emptyList(), wallet.resubmitted)
        }

    @Test
    fun the_f3_seams_are_read_by_the_checker() {
        val monitor =
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                .resolve("src/main/java/co/electriccoin/zcash/ui/common/tex/TexSendMonitor.kt")
                .readText()
        assertTrue("FaultSeam.TEX_FORCE_STUCK" in monitor)
        assertTrue("FaultSeam.TOR_BOOTSTRAP_FAILS" in monitor)
    }
}
