package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.3.5 and §3A.5 row 7; money-path rules 1-3) — what the accept may pay.
 *
 * Must hold: a Shielded accept of a key that came in a KEX asks one on-chain message (our key exchange, 17,000 zat)
 * and pays exactly that; of a key that came in a KEXACK, or with our KEX paid in the last hour, it asks and pays
 * nothing; a Tunnel accept asks and pays ZC-535's count (2, then 1, then 0); the adoption itself sends nothing (no
 * KEXACK even with the standing consent on, no responder marker, so no later retry); Cancel changes and pays nothing;
 * a repeated accept pays nothing more; an accept interrupted after its records pays its owed key exchange once; with a
 * Keystone account selected nothing changes. What is paid never exceeds what was quoted (asserted against the seam's
 * submitted transactions).
 */
class KeyChangeAcceptPaysOnlyWhatItQuotedTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a Shielded accept of a key from a KEX asks one message and pays exactly our key exchange`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            val asked = chat.accept(vm, newKeys.publicKey)

            assertEquals("the Shielded accept asks for exactly one on-chain message", "KEY_CHANGE_ACCEPT" to 1, asked)
            chat.assertAdopted(newKeys, "after Pay")
            assertEquals("the quoted key exchange was not sent", 1, chat.seam.submittedOf("KEX"))
            assertEquals("the accept paid more than the one message it quoted: ${chat.seam.submitted}", 1, chat.seam.submitted.size)
            assertFalse("the adoption set the responder marker (a KEXACK would follow)", "setReceivedKexPubkey" in chat.writes)
            assertFalse("the paid key exchange is still marked owed", PEER in chat.owed.kexOwed)
            assertEquals("the one-off accept was turned into the standing consent", 0, chat.owed.consentWrites.get())

            // A later chat open and a new block pay nothing on top.
            chat.owed.openChat(vm)
            chat.owed.tick(vm)
            chat.settle(vm)
            assertEquals("something was paid after the accept: ${chat.seam.submitted}", 1, chat.seam.submitted.size)

            // A repeated accept finds no record.
            val again = chat.accept(vm, newKeys.publicKey)
            assertNull("a repeated accept asked again", again)
            assertEquals("a repeated accept paid", 1, chat.seam.submitted.size)
        }

    @Test
    fun `a Shielded accept of a key from a KEXACK asks and pays nothing`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-pay-ack")
            assertNotNull("the refused key is not remembered", chat.record())

            val asked = chat.accept(vm, newKeys.publicKey)

            assertNull("the contact already holds our key: nothing to ask", asked)
            chat.assertAdopted(newKeys, "after the free accept")
            assertEquals("a free accept built a transaction", 0, chat.seam.proposals())
            assertFalse("a free accept left an owed KEX", PEER in chat.owed.kexOwed)
            assertFalse("a free accept wrote an allowance", chat.owed.zbootQuoted[PEER] == true)
        }

    @Test
    fun `a Shielded accept within the hour of our paid key exchange asks and pays nothing more`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val first = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, first)
            assertNotNull("the refused key is not remembered", chat.record())
            assertEquals("KEY_CHANGE_ACCEPT" to 1, chat.accept(vm, first.publicKey))
            assertEquals("the first accept pays its key exchange", 1, chat.seam.submitted.size)

            val second = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, second)
            val asked = chat.accept(vm, second.publicKey)

            assertNull("our key exchange went out within the hour: nothing to ask", asked)
            chat.assertAdopted(second, "after the second accept")
            assertEquals("a key exchange paid within the hour was paid again", 1, chat.seam.submitted.size)
        }

    @Test
    fun `Cancel changes and pays nothing`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            val asked = chat.accept(vm, newKeys.publicKey, pay = false)
            assertEquals("KEY_CHANGE_ACCEPT" to 1, asked)
            vm.dismissPaidAction()
            chat.settle(vm)

            assertTrue("the banner must stay after Cancel", chat.flag())
            assertEquals("the record must stay after Cancel", newKeys.publicKey, chat.recordKey())
            chat.assertNothingAdopted("after Cancel")
        }

    @Test
    fun `a Tunnel accept asks and pays the reconnect's count - two, then one, then none`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val first = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, first)
            assertNotNull("the refused key is not remembered", chat.record())

            assertEquals("KEY_CHANGE_RECONNECT" to 2, chat.accept(vm, first.publicKey))
            chat.assertAdopted(first, "after the first accept")
            assertEquals("our key exchange was not sent", 1, chat.seam.submittedOf("KEX"))
            assertEquals("our relay identity was not sent", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("quoted 2", 2, chat.seam.submitted.size)
            assertEquals("the accept keeps the contact's relay identity", KeyChangeReconnectChat.OLD_RELAY, chat.reconnect.relayKey)
            assertTrue("the paid ZBOOT did not use up its allowance", chat.owed.zbootQuoted[PEER] != true)

            // Only the relay identity is older than an hour.
            assertNotNull("fixture: the ZBOOT's paid record", chat.owed.paidRecords.remove(ZBOOT_SLOT))
            val second = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, second)
            assertEquals("KEY_CHANGE_RECONNECT" to 1, chat.accept(vm, second.publicKey))
            chat.assertAdopted(second, "after the second accept")
            assertEquals("a key exchange paid within the hour was paid again", 1, chat.seam.submittedOf("KEX"))
            assertEquals("quoted 2, then 1", 3, chat.seam.submitted.size)

            // Both went out within the hour: a free accept.
            val third = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, third)
            assertNull("both messages went out within the hour: nothing to ask", chat.accept(vm, third.publicKey))
            chat.assertAdopted(third, "after the free accept")
            assertEquals("quoted 2, then 1, then nothing", 3, chat.seam.submitted.size)
            assertFalse("nothing may stay owed", PEER in chat.owed.kexOwed)
        }

    @Test
    fun `an accept interrupted after its records pays its owed key exchange once`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            // The process ends at the preference wipe: the owed KEX is recorded, nothing is adopted or paid.
            chat.killAtWrite = "clearE2EPeerState"
            assertEquals("KEY_CHANGE_ACCEPT" to 1, chat.accept(vm, newKeys.publicKey))
            assertTrue("fixture: the accept reached its preference wipe", chat.killed)
            assertTrue("the owed key exchange must be recorded before anything is wiped", PEER in chat.owed.kexOwed)
            assertEquals("nothing was paid before the kill", 0, chat.seam.submitted.size)
            assertTrue("the banner must stay after an interrupted accept", chat.flag())
            ResetOwedChat.kill(vm)

            chat.revive()
            val again = chat.viewModel()
            chat.accept(again, newKeys.publicKey)
            chat.owed.openChat(again)
            chat.owed.tick(again)
            chat.settle(again)

            chat.assertAdopted(newKeys, "after accepting again")
            assertEquals("the owed key exchange must be paid exactly once: ${chat.seam.submitted}", 1, chat.seam.submittedOf("KEX"))
            assertEquals("two attempts paid more than the one message quoted", 1, chat.seam.submitted.size)
            assertFalse("the paid key exchange is still marked owed", PEER in chat.owed.kexOwed)
        }

    @Test
    fun `with a Keystone account selected at Pay nothing changes and the banner stays`() =
        runBlocking {
            val accounts = SelectedAccountFeed.create()
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel(accounts)
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            assertEquals("KEY_CHANGE_ACCEPT" to 1, chat.accept(vm, newKeys.publicKey, pay = false))
            accounts.keystoneSelected()
            vm.confirmPaidAction()
            chat.settle(vm)

            assertEquals(ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP, chat.error(vm))
            assertTrue("the banner must stay with Keystone selected", chat.flag())
            assertEquals("the record must stay with Keystone selected", newKeys.publicKey, chat.recordKey())
            chat.assertNothingAdopted("with Keystone selected")
        }

    private companion object {
        const val PEER = "usyntheticzc545paysquoted001"
        val ZBOOT_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)
    }
}
