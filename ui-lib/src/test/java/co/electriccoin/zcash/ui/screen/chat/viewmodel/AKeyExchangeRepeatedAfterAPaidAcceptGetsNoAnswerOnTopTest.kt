package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-660 (T7B-HANDSHAKE-SPEC rev 2, section 2.3) — after a paid "Accept the new key" in a Shielded chat, the contact's key
 * exchange with the key just adopted can arrive again under a new transaction (a Reconnect or a Call tap on a 2.13 phone,
 * sent before our key exchange reached them). The accept's wipe cleared the answer marker, so that repeat is answered
 * with a paid answer (KEXACK, 17,000 zat) on top of the accept's quote, under the standing consent.
 *
 * Must hold: while our own key exchange to that key was paid within the hour, or is still owed with the reconnect reason,
 * the repeat is "answered by our key exchange": 0 more proposals. Controls: after the hour of the paid record (and
 * nothing owed) the repeat is answered once; a key exchange with yet another key raises the warning and is not
 * answered; an accept quoted at 0 (the key came in an answer, so no key exchange of ours was sent for it): a later key
 * exchange with that key is answered as today.
 *
 * Built on [KeyChangeAcceptChat] with its automatic-answer budget granted (the real store for keys and the record; [ResetKexSeam]: "OTHER" is the answer). Every
 * key and transaction id is synthetic.
 */
class AKeyExchangeRepeatedAfterAPaidAcceptGetsNoAnswerOnTopTest : KeyChangeAcceptTestBase() {
    private val kexSlot = HandshakePaidRecord.slot(AutomaticSetupKind.KEX, PEER)

    /** A Shielded accept of a key that came in a KEX, quoted at 1 and paid. @return the adopted keys. */
    private suspend fun paidAccept(
        chat: KeyChangeAcceptChat,
        vm: ChatViewModel,
    ) = E2EEncryption.generateKeyPair().also { newKeys ->
        chat.raiseBanner(vm, newKeys)
        assertNotNull("fixture: the refused key is not remembered", chat.record())
        assertEquals("fixture: the Shielded accept quotes one message", "KEY_CHANGE_ACCEPT" to 1, chat.accept(vm, newKeys.publicKey))
        chat.assertAdopted(newKeys, "after the paid accept")
        assertEquals("fixture: the accept did not pay exactly its key exchange", listOf("KEX"), chat.seam.submitted.toList())
    }

    @Test
    fun `the adopted key's key exchange repeated within the hour of our paid key exchange gets no answer on top`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true, kexAckBudget = true)
            val vm = chat.viewModel()
            val adopted = paidAccept(chat, vm)
            val budget = chat.budgetUsed.get()

            chat.kexFromContact(vm, adopted, "synthetic-zc660-repeat-1")
            chat.settle(vm)

            assertEquals("the repeated key exchange was answered on top of the accept's quote", listOf("KEX"), chat.seam.submitted.toList())
            assertEquals("the repeat used an automatic-answer budget unit", budget, chat.budgetUsed.get())
        }

    @Test
    fun `the adopted key's key exchange repeated while our key exchange is still owed gets no answer`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true, kexAckBudget = true)
            val vm = chat.viewModel()
            val adopted = paidAccept(chat, vm)
            // Our key exchange for the adopted key is owed again with the reconnect reason (its paid record is gone).
            chat.owed.paidRecords.remove(kexSlot)
            chat.owed.kexOwed += PEER
            chat.owed.kexOwedReason[PEER] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
            val before = chat.seam.otherAnswered.get()
            val budget = chat.budgetUsed.get()

            chat.kexFromContact(vm, adopted, "synthetic-zc660-repeat-2")
            chat.settle(vm)

            assertEquals("the repeat was answered while our key exchange to that key is owed", before, chat.seam.otherAnswered.get())
            assertEquals("the repeat used an automatic-answer budget unit", budget, chat.budgetUsed.get())
        }

    @Test
    fun `control - after the hour of the paid record a repeat is answered once`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true, kexAckBudget = true)
            val vm = chat.viewModel()
            val adopted = paidAccept(chat, vm)
            assertNotNull("fixture: the accept's paid record", chat.owed.paidRecords.remove(kexSlot))

            chat.kexFromContact(vm, adopted, "synthetic-zc660-late-1")
            chat.settle(vm)
            chat.kexFromContact(vm, adopted, "synthetic-zc660-late-2")
            chat.settle(vm)

            assertEquals("the late repeat was not answered exactly once", 1, chat.seam.submitted.count { it == "OTHER" })
        }

    @Test
    fun `control - a key exchange with another key raises the warning and is not answered`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true, kexAckBudget = true)
            val vm = chat.viewModel()
            paidAccept(chat, vm)

            chat.kexFromContact(vm, E2EEncryption.generateKeyPair(), "synthetic-zc660-other")
            chat.settle(vm)

            assertTrue("a key exchange with another key did not raise the warning", chat.flag())
            assertEquals("a key exchange with another key was answered", 0, chat.seam.submitted.count { it == "OTHER" })
        }

    @Test
    fun `control - after an accept quoted at 0 a later key exchange with that key is answered as today`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true, kexAckBudget = true)
            val vm = chat.viewModel()
            val adopted = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, adopted, "synthetic-zc660-ack")
            assertNull("fixture: the accept of a key from an answer asks nothing", chat.accept(vm, adopted.publicKey))
            chat.assertAdopted(adopted, "after the free accept")

            chat.kexFromContact(vm, adopted, "synthetic-zc660-after-free")
            chat.settle(vm)

            assertEquals("the key exchange after a free accept was not answered", 1, chat.seam.submitted.count { it == "OTHER" })
        }

    private companion object {
        const val PEER = "usyntheticzc660repeatpeer0001"
    }
}
