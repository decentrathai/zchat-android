package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.WelcomeZec
import co.electriccoin.zcash.ui.screen.chat.model.paymentMemoText
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1273: a payment sent from a chat. The keyboard could not be closed over the dialog's buttons, Close on the sending
 * screen left the chat for the chat list, and the bubble shows the raw memo ("Payment from ZCHAT") with no amount.
 *
 * Seams, by reflection so the module's other tests still compile before they exist, all in `screen.chat.model`:
 * - `object PaymentBubble { fun of(message: ChatMessage, amountZatoshi: Long?): PaymentBubbleLine? }`, with
 *   `data class PaymentBubbleLine(val isSent: Boolean, val amountZatoshi: Long, val memo: String?)`. Null: not a payment,
 *   or nothing true to say, and the bubble keeps the row's own text.
 * - `class ChatPaymentExit { fun mark(peerAddress: String); fun clear(); fun command(): NavigationCommand }`: command
 *   consumes the mark, so a later send from the wallet goes to the root as it does today.
 *
 * `ChatMessage.text` and the memo stay as they are: Retry and the retire-only match read the text, and 2.12.4 reads the
 * memo. The display line is drawn at render time, so old rows show it too.
 *
 * Read from the sources, as a JVM test cannot compose them: the dialog's keyboard options, the bubble's use of the line,
 * and the wiring of the exit.
 */
class ChatPaymentFlowTest {
    private val model = "co.electriccoin.zcash.ui.screen.chat.model"
    private val main = "ui-lib/src/main/java/co/electriccoin/zcash/ui"

    private fun classOrNull(name: String): Class<*>? = runCatching { Class.forName("$model.$name") }.getOrNull()

    private fun row(
        text: String,
        outgoing: Boolean,
        status: MessageStatus = MessageStatus.SENT,
        pending: Boolean = false,
    ) = ChatMessage(
        id = "abc123",
        txId = null,
        text = text,
        timestamp = Instant.parse("2026-10-01T00:00:00Z"),
        isOutgoing = outgoing,
        peerAddress = "peer",
        status = status,
        isPending = pending,
    )

    private data class Line(
        val isSent: Boolean,
        val amountZatoshi: Long,
        val memo: String?,
    )

    private fun line(
        message: ChatMessage,
        amountZatoshi: Long?,
    ): Line? {
        val type = assertNotNull(classOrNull("PaymentBubble"), "$model.PaymentBubble does not exist")
        val of = assertNotNull(type.methods.firstOrNull { it.name == "of" }, "PaymentBubble.of does not exist")
        val result = of.invoke(type.getField("INSTANCE").get(null), message, amountZatoshi) ?: return null

        fun get(name: String) = result.javaClass.getMethod(name).invoke(result)
        return Line(get("isSent") as Boolean, get("getAmountZatoshi") as Long, get("getMemo") as String?)
    }

    private fun exit(): Any {
        val type = assertNotNull(classOrNull("ChatPaymentExit"), "$model.ChatPaymentExit does not exist")
        return type.getDeclaredConstructor().newInstance()
    }

    private fun Any.call(
        name: String,
        vararg args: Any,
    ): Any? = javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(this, *args)

    // ---- the wire ----

    @Test
    fun the_memo_on_the_wire_stays_byte_identical() {
        assertEquals("Payment from ZCHAT", paymentMemoText(""))
        assertEquals("Payment from ZCHAT", paymentMemoText("   "))
        assertEquals("Payment: lunch", paymentMemoText("lunch"))
        assertEquals("Payment: Welcome to ZCHAT!", paymentMemoText(WelcomeZec.MEMO))
        assertEquals(
            listOf(80, 97, 121, 109, 101, 110, 116, 32, 102, 114, 111, 109, 32, 90, 67, 72, 65, 84),
            paymentMemoText("").toByteArray(Charsets.UTF_8).map { it.toInt() },
        )
    }

    // ---- the bubble ----

    @Test
    fun the_sender_sees_sent_and_the_receiver_sees_received_with_the_exact_amount() {
        assertEquals(Line(true, 156_000L, null), line(row("Payment from ZCHAT", outgoing = true), 156_000L))
        assertEquals(Line(false, 156_000L, null), line(row("Payment from ZCHAT", outgoing = false), 156_000L))
    }

    @Test
    fun a_memo_the_user_wrote_is_still_shown() {
        assertEquals(Line(true, 1_000L, "lunch"), line(row("Payment: lunch", outgoing = true), 1_000L))
        assertEquals(Line(false, 1_000L, "lunch"), line(row("Payment: lunch", outgoing = false), 1_000L))
    }

    @Test
    fun the_welcome_payment_renders_the_same_way() {
        assertEquals(
            Line(true, WelcomeZec.AMOUNT_ZATOSHI, WelcomeZec.MEMO),
            line(row(paymentMemoText(WelcomeZec.MEMO), outgoing = true), WelcomeZec.AMOUNT_ZATOSHI),
        )
    }

    @Test
    fun a_row_that_is_not_a_payment_keeps_its_text() {
        assertNull(line(row("hello", outgoing = true), 1_000L))
        assertNull(line(row("Payment", outgoing = false), 1_000L))
    }

    @Test
    fun without_a_known_amount_the_bubble_invents_none() {
        // A relay message saying "Payment from ZCHAT" carries no money.
        assertNull(line(row("Payment from ZCHAT", outgoing = false), null))
    }

    @Test
    fun a_payment_that_did_not_go_out_never_says_sent() {
        assertNull(line(row("Payment from ZCHAT", outgoing = true, status = MessageStatus.FAILED), 1_000L))
        assertNull(line(row("Payment from ZCHAT", outgoing = true, pending = true), 1_000L))
    }

    @Test
    fun the_bubble_lines_are_english_and_spanish_with_the_amount_as_their_placeholder() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("Sent %1\$s ZEC via ZChat", en["chat_payment_bubble_sent"])
        assertEquals("Payment received %1\$s ZEC", en["chat_payment_bubble_received"])
        for (key in listOf("chat_payment_bubble_sent", "chat_payment_bubble_received")) {
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(en[key], spanish, "$key is not translated")
            assertTrue("%1\$s" in spanish, "$key: the Spanish lost the amount")
        }
    }

    @Test
    fun the_bubble_draws_the_line_and_the_stored_text_stays() {
        val view = ChatResourceText.source("$main/screen/chat/view/ChatDetailView.kt")
        assertTrue("PaymentBubble.of(" in view, "the bubble never asks for the payment line")
        assertTrue("R.string.chat_payment_bubble_sent" in view, "the bubble does not use chat_payment_bubble_sent")
        assertTrue("R.string.chat_payment_bubble_received" in view, "the bubble does not use chat_payment_bubble_received")

        val vm = ChatResourceText.source("$main/screen/chat/viewmodel/ChatViewModel.kt")
        assertFalse("chat_payment_bubble" in vm, "the line was written into the row, not drawn at render time")
    }

    /**
     * `tx.amount` is the SDK's netValue. On a send that total takes in the fee and the extra outputs (dust, the platform
     * fee), so it is not what the contact got. The row's amount is read per output, from the note tables.
     */
    @Test
    fun the_row_amount_is_what_the_contact_got_not_the_transaction_total() {
        val vm = ChatResourceText.source("$main/screen/chat/viewmodel/ChatViewModel.kt")
        val set = Regex("""paymentAmountZatoshi\s*=\s*([^\n]+)""").findAll(vm).map { it.groupValues[1] }.toList()
        assertTrue(set.isNotEmpty(), "no chat row carries paymentAmountZatoshi")
        set.forEach { value ->
            assertFalse("netValue" in value || "tx.amount" in value, "the row's amount is the transaction total: $value")
        }
    }

    // ---- the keyboard ----

    @Test
    fun both_fields_of_the_payment_dialog_close_the_keyboard_on_done() {
        val source = ChatResourceText.source("$main/screen/chat/view/ChatDialogs.kt")
        val start = source.indexOf("internal fun PaymentDialog(")
        val dialog = source.substring(start, source.indexOf("\n@Composable", start))
        assertEquals(2, Regex("""imeAction\s*=\s*ImeAction\.Done""").findAll(dialog).count(), "ImeAction.Done on amount and memo")
        assertEquals(2, Regex("""onDone\s*=""").findAll(dialog).count(), "a Done action on amount and memo")
        assertTrue(
            "clearFocus" in dialog || "keyboardController" in dialog,
            "Done does not close the keyboard",
        )
    }

    // ---- Close ----

    @Test
    fun close_after_a_chat_payment_returns_to_the_chat_once() {
        val exit = exit()
        exit.call("mark", "peer")
        assertEquals(NavigationCommand.BackTo(ChatDetail::class), exit.call("command"))
        assertEquals(NavigationCommand.BackToRoot, exit.call("command"), "a later wallet send must not return to the chat")
    }

    @Test
    fun a_send_from_the_wallet_still_goes_to_the_root() {
        assertEquals(NavigationCommand.BackToRoot, exit().call("command"))
    }

    @Test
    fun a_payment_refused_before_it_went_out_leaves_no_mark() {
        val exit = exit()
        exit.call("mark", "peer")
        exit.call("clear")
        assertEquals(NavigationCommand.BackToRoot, exit.call("command"))
    }

    @Test
    fun the_payment_marks_the_exit_and_both_ways_out_of_the_sending_screen_read_it() {
        val vm = ChatResourceText.source("$main/screen/chat/viewmodel/ChatViewModel.kt")
        val start = vm.indexOf("fun sendPayment(")
        val send = vm.substring(start, vm.indexOf("Uses ZREQ protocol", start))
        assertTrue(Regex("""[Ee]xit\.mark\(""").containsMatchIn(send), "sendPayment does not mark its chat")
        assertTrue(Regex("""[Ee]xit\.clear\(""").containsMatchIn(send), "a refused payment leaves its mark")

        for (useCase in listOf("ViewTransactionsAfterSuccessfulProposalUseCase", "CancelProposalFlowUseCase")) {
            val source = ChatResourceText.source("$main/common/usecase/$useCase.kt")
            assertTrue("ChatPaymentExit" in source, "$useCase ignores the chat the payment came from")
        }
    }
}
