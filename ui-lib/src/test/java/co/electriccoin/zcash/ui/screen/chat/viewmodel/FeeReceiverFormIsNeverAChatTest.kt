package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.CURRENT_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.PREVIOUS_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.mix
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.receiverForm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Out
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1082 (FIRST-CONTACT-SPEC N-13; plan cases b and c) — the platform fee is found by its receivers, not by its text.
 *
 * Seen on the Fold on 20 Oct (`phones-20261020/LANE_REPORT_2.md`, `logs/N13-*`): after a restore the wallet library names
 * each output of an own send by the single receiver its note was paid to. The 1,000-zat fee output then reads as the
 * Unified Address made of the fee address's Orchard receiver, which never equals the fee address's text
 * (`ZMSGConstants.isPlatformFeeAddress` compares texts), so the wallet history picks it as the send's recipient and the
 * chat list files the send under a nameless chat at the platform wallet, whose composer offers paid sends to it.
 *
 * Wanted: every receiver form of the current and the previous fee address is a fee address (and an address that mixes a
 * fee receiver with another one is not); the history names the contact's form as the recipient in any output order; and
 * no chat is ever filed under a fee form, the contact being unknown (it keeps its receiver form: case c) or the fee being
 * the previous one (case b). Failure messages name addresses by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeeReceiverFormIsNeverAChatTest {
    private val sendScopes = SendHarnessScopes()

    private val peer = contact(seed = 10821)
    private val peerForm = receiverForm(peer)
    private val currentFeeForms = listOf(receiverForm(CURRENT_FEE, 3), receiverForm(CURRENT_FEE, 2))
    private val previousFeeForms = listOf(receiverForm(PREVIOUS_FEE, 3), receiverForm(PREVIOUS_FEE, 2))

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `every receiver form of both fee addresses is a fee address, a mix with another receiver is not`() {
        (currentFeeForms + previousFeeForms).forEachIndexed { i, form ->
            assertTrue("fee form $i is not taken for a fee", ZMSGConstants.isPlatformFeeAddress(form))
        }
        assertTrue(ZMSGConstants.isPlatformFeeAddress(CURRENT_FEE))
        assertTrue(ZMSGConstants.isPlatformFeeAddress(PREVIOUS_FEE))
        assertFalse("a contact's form is taken for a fee", ZMSGConstants.isPlatformFeeAddress(peerForm))
        assertFalse("a contact is taken for a fee", ZMSGConstants.isPlatformFeeAddress(peer))
        assertFalse("a mix of a fee receiver and a contact's is a fee", ZMSGConstants.isPlatformFeeAddress(mix(CURRENT_FEE, 3, peer, 2)))
        assertFalse(ZMSGConstants.isPlatformFeeAddress(SyntheticUnifiedAddress.mainnet(seed = 10822)))
    }

    @Test
    fun `the wallet history names the contact's form as the recipient of a restored send, in any output order`() =
        runBlocking {
            val scan = RestoredSendScan(peer)
            val shapes =
                (currentFeeForms + previousFeeForms).flatMap { fee ->
                    listOf(listOf(Out(fee), Out(peerForm)), listOf(Out(peerForm), Out(fee)))
                }
            val txs = shapes.mapIndexed { i, outputs -> Tx(i + 1, message("restored $i"), outputs) }

            val picked = txs.map { label(scan.historyRecipient(txs, it)) }

            assertEquals("the history recipient of each restored send", txs.map { "contact form" }, picked)
        }

    @Test
    fun `(c) a restored send to a contact this phone does not know is filed under the contact's form, never the fee's`() =
        runBlocking {
            val txs =
                listOf(
                    Tx(1, message("to an unknown contact 1"), listOf(Out(currentFeeForms[0]), Out(peerForm))),
                    Tx(2, message("to an unknown contact 2"), listOf(Out(peerForm), Out(currentFeeForms[0]))),
                    Tx(3, message("to an unknown contact 3"), listOf(Out(currentFeeForms[0]), Out(peerForm), Out(null, ownAccount = true))),
                )

            val conversations = RestoredSendScan(peer).scan(txs)

            assertEquals("chats after a restore: ${rows(conversations)}", listOf("contact form"), chatsOf(conversations))
            assertEquals(txs.size, conversations.single().messages.size)
        }

    @Test
    fun `(b) a restored send whose fee went to the previous fee address is never filed under that fee's form`() =
        runBlocking {
            val txs =
                previousFeeForms.flatMapIndexed { i, fee ->
                    listOf(
                        Tx(2 * i + 1, message("old fee ${2 * i + 1}"), listOf(Out(fee), Out(peerForm))),
                        Tx(2 * i + 2, message("old fee ${2 * i + 2}"), listOf(Out(peerForm), Out(fee))),
                    )
                }

            val conversations = RestoredSendScan(peer).scan(txs)

            assertEquals("chats after a restore: ${rows(conversations)}", listOf("contact form"), chatsOf(conversations))
        }

    private fun message(text: String) = ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text)

    private fun label(address: String?): String =
        when (address) {
            null -> "none"
            peer -> "contact"
            peerForm -> "contact form"
            CURRENT_FEE -> "current fee address"
            PREVIOUS_FEE -> "previous fee address"
            in currentFeeForms -> "current fee form"
            in previousFeeForms -> "previous fee form"
            else -> "other (${address.length} chars)"
        }

    private fun chatsOf(conversations: List<Conversation>) = conversations.map { label(it.peerAddress) }

    private fun rows(conversations: List<Conversation>) = conversations.map { "${label(it.peerAddress)}: ${it.messages.size} rows" }
}
