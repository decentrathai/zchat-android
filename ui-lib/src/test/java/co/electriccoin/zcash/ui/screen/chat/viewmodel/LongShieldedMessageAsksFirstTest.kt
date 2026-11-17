package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 R8-fixup F1 (REVIEW_R8 F1, device D-F1, LEDGER row 14) — a long message in an existing Shielded chat pays
 * with no question, and the composer shows a tenth of the price.
 *
 * Only the first message to a contact is priced and asked about (PaidAction.FIRST_MESSAGE). In an existing chat
 * `sendMessage` goes straight to the builder, which packs the encrypted text into as many memos as it needs. On the
 * device a 5,000-character message became 14 memos in one transaction: 80,000 zatoshi fee, 95,000 debited, while the
 * composer's cost row said "Message cost: 0.00001 ZEC" (the dust of one output) and nothing asked.
 *
 * Wanted:
 * - an on-chain message that needs more than one memo asks first, quoting at least `onChainSendMinimumCost(memos,
 *   amount)` for the memos the builder really packs (the encrypted text), and pays nothing before the answer;
 * - declining pays nothing;
 * - a one-memo message in an existing chat is sent with no question, as before;
 * - the composer's cost row shows the least a message of the typed length costs (outputs, platform fee and network
 *   fee, `composerMessageCost`), never the dust of one output: a one-memo message is at least 0.00016 ZEC.
 *
 * Harness: [PaidChatFixture] (an existing Shielded chat with keys and a ready ratchet). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LongShieldedMessageAsksFirstTest {
    private val sendScopes = SendHarnessScopes()

    /** The B9 cap: the longest message the composer lets through. */
    private val longMessage = "S".repeat(5_000)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The memos the builder packs for [text] once the ratchet has encrypted it (same length as the real wire). */
    private fun encryptedMemoCount(text: String): Int {
        val wire =
            CiphertextWireFormat.serialize(
                Ciphertext(0, 0L, ByteArray(text.toByteArray(Charsets.UTF_8).size + GCM_TAG_BYTES)),
            )
        return ZMSGProtocol.calculateV4ChunkCount(wire, false)
    }

    @Test
    fun `a long message in an existing Shielded chat asks first and quotes every memo`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticlongmessage000001", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.sendMessage(f.peer, longMessage)
            awaitIdle(vm)

            val memos = encryptedMemoCount(longMessage)
            assertTrue("fixture sanity: the encrypted message needs many memos, was $memos", memos >= 14)
            assertEquals("the long message was paid for with no question on screen", 0, f.seam.proposalRequests.get())
            val state = HandshakeHarness.sendMessageState(vm)
            assertEquals(
                "a $memos-memo message must be asked about before it is paid for, was $state",
                "NeedsPaidActionConfirmation",
                state.javaClass.simpleName,
            )
            val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
            val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation) as Zatoshi
            val floor = onChainSendMinimumCost(memos, Zatoshi(DEFAULT_AMOUNT))
            assertTrue(
                "quoted ${total.value} is below the ${floor.value} a $memos-memo message costs",
                total.value >= floor.value,
            )
        }

    @Test
    fun `declining the long-message question pays nothing`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticlongmessage000002", ConversationMode.VAULT)
            val vm = f.viewModel()
            // The fixture's ViewModel is allocated without its initializers: declining hands the text back through this.
            AutoSpendVm.setField(vm, "_restoredTypedText", kotlinx.coroutines.flow.MutableStateFlow<Any?>(null))

            vm.sendMessage(f.peer, longMessage)
            awaitIdle(vm)
            ChatViewModel::class.java.getMethod("dismissPaidAction").invoke(vm)
            awaitIdle(vm)

            assertEquals("declining must pay nothing", 0, f.seam.proposalRequests.get())
            assertEquals("declining must submit nothing", 0, f.seam.submits.get())
            assertNotEquals(
                "the question was never shown",
                "Sending",
                HandshakeHarness.sendMessageState(vm).javaClass.simpleName,
            )
        }

    /** Control, green before and after: a one-memo message in an existing chat is sent with no question. */
    @Test
    fun `control - a short message in an existing Shielded chat is sent with no question`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticlongmessage000003", ConversationMode.VAULT)
            val vm = f.viewModel()
            assertEquals("fixture sanity: one memo", 1, encryptedMemoCount("hello"))

            vm.sendMessage(f.peer, "hello")
            awaitIdle(vm)

            assertNotEquals(
                "a one-memo message in an existing chat must not ask",
                "NeedsPaidActionConfirmation",
                HandshakeHarness.sendMessageState(vm).javaClass.simpleName,
            )
        }

    @Test
    fun `the composer shows what a message of the typed length costs, not the dust of one output`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticlongmessage000006", ConversationMode.VAULT)
            val vm = f.viewModel()

            fun cost(text: String) = composerCost(vm, f.peer, text, null, "").value

            assertTrue("a one-memo message costs at least 0.00016 ZEC, the row said ${cost("hi")}", cost("hi") >= 16_000L)
            assertEquals(onChainSendMinimumCost(1, Zatoshi(DEFAULT_AMOUNT)).value, cost("hi"))
            // F-R9P-1: the row grows with the memos the ENCRYPTED text needs, not the plain text's count.
            val encryptedFloor = onChainSendMinimumCost(encryptedMemoCount(longMessage), Zatoshi(DEFAULT_AMOUNT)).value
            assertTrue(
                "the row must grow with the memos the encrypted text needs: ${cost(longMessage)} < $encryptedFloor",
                cost(longMessage) >= encryptedFloor,
            )
            assertTrue(
                "the plain-text sizer the row used to read is still reachable",
                Class
                    .forName("co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmationKt")
                    .methods
                    .none { it.name == "composerMessageCost" },
            )
            composerSourceChecks()
        }

    private fun composerSourceChecks() {
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        assertTrue("the cost row does not use composerMessageCost", "composerMessageCost(" in view)
        assertTrue(
            "the cost row still shows the dust of one output",
            "val zecAmount = selectedAmount / 100_000_000.0" !in view,
        )
    }

    /**
     * Round 10a R10A-1 (F-R9P-1, Seeker run of cf72159ad) — the composer's row sized the plain text as a reply (11 memos,
     * 0.00077 ZEC for 5,000 characters) while the send's question and the debit sized the encrypted wire (14 memos,
     * 0.00095 ZEC); a 700-character reply showed 0.00023 and was asked and charged 0.00029. Wanted: the row is the very
     * figure the question then asks, for a message and for a reply (its reply reference included).
     */
    @Test
    fun `the composer row shows the figure the long-message question then asks`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticlongmessage000004", ConversationMode.VAULT)
            val vm = f.viewModel()

            val shown = composerCost(vm, f.peer, longMessage, null, "")
            vm.sendMessage(f.peer, longMessage, DEFAULT_AMOUNT)
            awaitIdle(vm)

            val asked = askedTotal(vm)
            assertEquals("the composer row shows ${shown.value}, the question then asks ${asked.value}", asked.value, shown.value)
            val floor = onChainSendMinimumCost(encryptedMemoCount(longMessage), Zatoshi(DEFAULT_AMOUNT))
            assertTrue("the row's ${shown.value} is below the ${floor.value} the encrypted message costs", shown.value >= floor.value)
            assertEquals("fixture sanity: nothing is paid before the answer", 0, f.seam.proposalRequests.get())

            val g = PaidChatFixture("usyntheticlongmessage000005", ConversationMode.VAULT)
            val replyVm = g.viewModel()
            val reply = "R".repeat(700)
            val quotedId = "synthetic-quoted-message-01"
            val quote = "a synthetic quoted message"

            val replyShown = composerCost(replyVm, g.peer, reply, quotedId, quote)
            replyVm.sendReply(g.peer, reply, quotedId, quote, DEFAULT_AMOUNT)
            awaitIdle(replyVm)

            val replyAsked = askedTotal(replyVm)
            assertEquals(
                "the composer row shows ${replyShown.value} for the reply, the question then asks ${replyAsked.value}",
                replyAsked.value,
                replyShown.value,
            )
        }

    /** ChatViewModel.composerMessageCost(peer, text, amount, replyToId, replyPreview), by name so this file compiles before it exists. */
    private fun composerCost(
        vm: ChatViewModel,
        peer: String,
        text: String,
        replyToId: String?,
        replyPreview: String,
    ): Zatoshi {
        val method =
            try {
                ChatViewModel::class.java.getMethod(
                    "composerMessageCost",
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                )
            } catch (e: NoSuchMethodException) {
                throw AssertionError("the composer's row is not sized by the send's own rule (no ChatViewModel.composerMessageCost)", e)
            }
        return method.invoke(vm, peer, text, DEFAULT_AMOUNT, replyToId, replyPreview) as Zatoshi
    }

    /** The total the send's cost question quotes (fails when the send did not ask). */
    private fun askedTotal(vm: ChatViewModel): Zatoshi {
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals("the send did not ask first, was $state", "NeedsPaidActionConfirmation", state.javaClass.simpleName)
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        return confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation) as Zatoshi
    }

    private companion object {
        const val DEFAULT_AMOUNT = 1_000L
        const val GCM_TAG_BYTES = 16
    }
}
