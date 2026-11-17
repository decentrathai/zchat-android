package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.TexApi
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture
import co.electriccoin.zcash.ui.common.tex.txid
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 W5, §1.2 "Send again" and footer): the transaction detail of a plain
 * Wallet send says where it stands, marks "Send again" used before the pre-fill, and leaves a chat transaction's one
 * new attempt to the chat.
 *
 * At 3df251d7f the detail shows no status for a plain send (only a TEX leg gets a footer), nothing records that
 * "Send again" was used, and a proven-dead chat transaction (a ZMSG message or a request's `ZREQ_FULFILL|` payment) would
 * be offered "Send again" beside the chat's own Retry or Pay again: two new paid attempts for one message.
 *
 * Wanted (pure functions in PlainSendAgain.kt, reached by name so this file compiles before them, plus the VM's wiring):
 * - `plainSendFooter(txId, check)`: waiting with a known expiry: "Not confirmed yet" with the block and the minutes;
 *   waiting without one: "checking"; proven never mined: "Not sent — nothing was paid"; mined or unknown: none;
 * - `chatOwnsTheNextAttempt(txId, check, memos, memoCount)`: true for a proven never-mined send whose memo belongs to
 *   the chat (or whose memo was not read), false for a mined one (Zashi's repeat payment stays);
 * - the VM falls back to that footer after the TEX footer, refuses Send again when the chat owns the next attempt, and
 *   calls `texSendMonitor.markSendAgainUsed` before `sendTransactionAgain`. All txids and memos are synthetic.
 */
class TransactionDetailFollowsPlainSendTest {
    private companion object {
        const val E = 5_000L
        const val PAYEE = "u1fixtureplainpayeenotreal0003"
        const val BLOCK_SECONDS = 75L
        const val FILE_CLASS = "co.electriccoin.zcash.ui.screen.transactiondetail.PlainSendAgainKt"
        val WAITING: String = txid(0xE1)
        val DEAD: String = txid(0xE3)
        val MINED: String = txid(0xE5)
        val UNSEEN: String = txid(0xE7)
        val NO_TIP: String = txid(0xE9)
    }

    private fun plain(
        txId: String,
        mined: Long?,
    ): Any =
        TexApi.facts(
            txId = txId,
            mined = mined,
            expiry = E,
            outputs = listOf(TexApi.output(PAYEE, isOwn = false, kind = "OTHER")),
        )

    /** The checker's check after passes at [tips] over [transactions]. */
    private fun checkAfter(
        transactions: List<Any>,
        vararg tips: Long?,
    ): Any? =
        runBlocking {
            val wallet = FakeTexWallet().apply { this.transactions = transactions }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
            for (tip in tips) {
                wallet.tip = tip
                TexApi.pass(monitor)
            }
            TexSameBlockFixture.check(monitor)
        }

    private fun pure(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            try {
                Class.forName(FILE_CLASS).methods.first { it.name == name && it.parameterCount == args.size }
            } catch (e: ClassNotFoundException) {
                fail("PlainSendAgain.kt does not exist: $e")
            } catch (e: NoSuchElementException) {
                fail("PlainSendAgain.kt has no $name/${args.size}: $e")
            }
        return method.invoke(null, *args)
    }

    private fun footer(
        txId: String,
        check: Any?,
    ): ErrorFooter? = pure("plainSendFooter", txId, check) as ErrorFooter?

    private fun res(id: Int): StringResource = StringResource.ByResource(id, emptyList())

    private fun resId(name: String): Int =
        try {
            R.string::class.java.getField(name).getInt(null)
        } catch (e: NoSuchFieldException) {
            fail("R.string.$name does not exist: $e")
        }

    @Test
    fun the_footer_follows_the_checkers_verdict() {
        val waiting = checkAfter(listOf(plain(WAITING, null)), E - 20)
        val waitingFooter = footer(WAITING, waiting) ?: fail("a waiting send has no footer")
        assertEquals(res(resId("transaction_detail_waiting_title")), waitingFooter.title)
        val minutes = ((E + 10 - (E - 20)) * BLOCK_SECONDS + 59) / 60
        assertEquals(
            StringResource.ByResource(resId("transaction_detail_waiting_body"), listOf((E + 10).toString(), minutes.toInt())),
            waitingFooter.subtitle,
        )

        val noTip = checkAfter(listOf(plain(NO_TIP, null)), null)
        val checking = footer(NO_TIP, noTip) ?: fail("a send the checker cannot judge yet has no footer")
        assertEquals(res(resId("transaction_detail_checking_body")), checking.subtitle)

        val dead = checkAfter(listOf(plain(DEAD, null), plain(MINED, E - 30)), E + 10, E + 11)
        val deadFooter = footer(DEAD, dead) ?: fail("a send proven never mined has no footer")
        assertEquals(res(resId("transaction_detail_not_sent_title")), deadFooter.title)
        assertEquals(res(resId("transaction_detail_not_sent_body")), deadFooter.subtitle)

        assertNull(footer(MINED, dead), "a mined send has a footer")
        assertNull(footer(UNSEEN, dead), "a send the checker never read has a footer")
        assertNull(footer(DEAD, null), "a footer before any pass")
    }

    @Test
    fun a_chat_transaction_proven_never_mined_leaves_its_new_attempt_to_the_chat() {
        val check = checkAfter(listOf(plain(DEAD, null), plain(MINED, E - 30)), E + 10, E + 11)

        fun owns(
            txId: String,
            memos: List<String>?,
            memoCount: Int,
        ): Boolean = pure("chatOwnsTheNextAttempt", txId, check, memos, memoCount) as Boolean

        assertTrue(owns(DEAD, listOf("ZREQ_FULFILL|synthetic-request-1"), 1), "a request's payment")
        assertTrue(owns(DEAD, listOf("ZMSG|v4|SYNTHETIC|INIT|hello"), 1), "a chat message")
        assertTrue(owns(DEAD, listOf("ZBOOT|v4|synthetic"), 1), "a key rotation")
        assertTrue(owns(DEAD, null, 1), "a memo that was not read")
        assertFalse(owns(DEAD, listOf("Thanks for dinner"), 1), "a plain Wallet memo")
        assertFalse(owns(DEAD, null, 0), "a send with no memo")
        assertFalse(owns(MINED, listOf("ZMSG|v4|SYNTHETIC|INIT|hello"), 1), "a mined chat transaction keeps the repeat payment")
    }

    @Test
    fun the_detail_wires_the_footer_the_chat_rule_and_the_used_mark() {
        val vm = source("screen/transactiondetail/TransactionDetailVM.kt")
        assertTrue(
            Regex("""createTexFooter\(tex\)\s*\?:\s*createPlainSendFooter\(""").containsMatchIn(vm),
            "the error footer does not fall back to the plain-send footer",
        )
        val button = vm.substringBefore("R.string.transaction_detail_send_again").takeLast(BUTTON_WINDOW)
        assertTrue("chatOwnsTheNextAttempt(" in button, "Send again does not leave a chat transaction to the chat: …$button")
        assertTrue("texSendAgainAllowed(transactionDetailArgs.transactionId, " in button, "the checker no longer decides Send again")

        val click = vm.substringAfter("private fun onSendAgainClick(").substringBefore("\n    }\n")
        val mark = click.indexOf("texSendMonitor.markSendAgainUsed(")
        val prefill = click.indexOf("sendTransactionAgain(")
        assertTrue(mark >= 0, "Send again is never marked used: $click")
        assertTrue(prefill > mark, "the pre-fill runs before Send again is marked used: $click")
    }

    private fun source(path: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
            .resolve("src/main/java/co/electriccoin/zcash/ui/$path")
            .readText()
}

private const val BUTTON_WINDOW = 400
