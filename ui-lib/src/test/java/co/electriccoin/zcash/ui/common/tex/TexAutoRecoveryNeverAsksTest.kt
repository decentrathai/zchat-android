package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F3 (owner 25 Sep: stuck-step recovery is FULLY AUTOMATIC; PLAN_V2 §2 F3 replaces the "Check with Tor" sheet and the
 * Shield tap): from a stuck TEX send with Tor off to the ZEC back in the wallet, nobody taps anything. Tor is turned on
 * when nothing else is in progress, the lookup finds the ZEC, the shield is built and sent, and once it is mined the
 * notice is posted once, with the amount, the fee and that ZCHAT turned Tor on. Home and the transaction detail keep
 * saying it until it is read. Nothing in the checker navigates or opens a question.
 * Fails at 15e2b1c39: there is no TEX recovery.
 */
class TexAutoRecoveryNeverAsksTest {
    @Test
    fun a_stuck_send_is_recovered_end_to_end_with_no_tap() =
        runBlocking {
            val wallet =
                TexFixture.stuckWallet().apply {
                    lookupAnswer = { true }
                    shieldPlan = { TexFixture.planSpendingLeg1() }
                }
            val tor = FakeTexTor()
            val store = TexApi.memoryStore()
            val notices = mutableListOf<Any>()
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, store = store, notices = notices)

            val states = mutableListOf<String>()
            states += TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2))
            states += TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2))
            wallet.transactions = listOf(TexFixture.leg1(), TexFixture.leg2(), TexFixture.shieldTx())
            states += TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2))
            wallet.transactions = listOf(TexFixture.leg1(), TexFixture.leg2(), TexFixture.shieldTx(mined = 1_010))
            val done = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            states += TexApi.state(done)
            TexApi.pass(monitor)

            assertEquals(listOf("NEEDS_TOR", "SHIELDING", "SHIELDING", "SHIELDED"), states)
            assertEquals(1, tor.turnOns)
            assertEquals(1, wallet.submitted.size)
            assertEquals(1, notices.size, "the notice is posted once")
            val notice = notices.single()
            assertEquals(TexFixture.FOUND_ZAT, TexApi.prop(notice, "foundZat"))
            assertEquals(TexFixture.SHIELD_FEE_ZAT, TexApi.prop(notice, "feeZat"))
            assertEquals(true, TexApi.prop(notice, "torTurnedOnByZchat"))
            assertEquals(false, TexApi.prop(done, "noticeShown"), "Home keeps the notice until it is read")
        }

    @Test
    fun reading_the_notice_is_remembered() =
        runBlocking {
            val wallet =
                TexFixture.stuckWallet().apply {
                    lookupAnswer = { true }
                    shieldPlan = { TexFixture.planSpendingLeg1() }
                }
            val store = TexApi.memoryStore()
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store)
            TexApi.pass(monitor)
            wallet.transactions = listOf(TexFixture.leg1(), TexFixture.leg2(), TexFixture.shieldTx(mined = 1_010))
            val key = TexApi.prop(TexApi.statusOf(TexApi.pass(monitor), LEG2), "key") as String

            TexApi.callSuspend(monitor, "acknowledgeNotice", key)

            assertEquals(true, TexApi.prop(TexApi.statusOf(TexApi.pass(monitor), LEG2), "noticeShown"))
            val restarted = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store)
            assertEquals(true, TexApi.prop(TexApi.statusOf(TexApi.pass(restarted), LEG2), "noticeShown"))
        }

    @Test
    fun nothing_in_the_checker_navigates_or_asks() {
        val dir = File(uiLibDir(), "src/main/java/co/electriccoin/zcash/ui/common/tex")
        val sources = dir.listFiles().orEmpty().filter { it.name.endsWith(".kt") }
        assertTrue(sources.any { it.name == "TexSendMonitor.kt" }, "no TexSendMonitor.kt in ${dir.path}")
        for (file in sources) {
            val text = file.readText()
            for (word in listOf("NavigationRouter", "navigationRouter", "Dialog", "BottomSheet", "Confirm")) {
                assertFalse(word in text, "${file.name} names $word: the recovery must never ask")
            }
        }
    }

    @Test
    fun home_and_the_detail_read_the_checker() {
        val home = source("ui/common/usecase/GetHomeMessageUseCase.kt")
        assertTrue("texSendMonitor.statuses" in home, "Home's messages do not read the TEX checker")
        assertTrue("HomeMessageData.TexRecovery" in home)
        val homeVm = source("ui/screen/home/HomeVM.kt")
        assertTrue("is HomeMessageData.TexRecovery" in homeVm, "HomeVM does not show the TEX message")
        val detail = source("ui/screen/transactiondetail/TransactionDetailVM.kt")
        assertTrue("texSendMonitor.statuses" in detail, "the transaction detail does not read the TEX checker")
        assertTrue("texTextsOf(" in detail, "the transaction detail does not say the TEX state")
        val app = File(uiLibDir().parentFile, "app/src/main/java/co/electriccoin/zcash/app/ZcashApplication.kt").readText()
        assertTrue("get<TexSendMonitor>().startForProcess()" in app, "nothing starts the TEX checker")
    }

    private fun source(relative: String): String =
        File(uiLibDir(), "src/main/java/co/electriccoin/zcash/$relative").readText()

    private fun uiLibDir(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
}
