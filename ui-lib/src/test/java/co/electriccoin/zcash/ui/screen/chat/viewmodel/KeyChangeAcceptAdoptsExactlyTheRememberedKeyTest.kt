package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 3; owner decision W-32) — "Accept the new key" clears the warning and keeps the
 * OLD key (ChatViewModel.dismissE2EKeyChanged writes no peer key), so later messages are encrypted to a key the contact
 * no longer holds and the safety dialog shows the old pair's number.
 *
 * Must hold after the user accepts the remembered key in the dialog: the stored key is exactly that key; the shared
 * key is ECDH(our private, their new public); OUR key pair, its provenance and version are unchanged; the chat is
 * unverified; the old ratchet is gone (processors dropped, state deleted, Quantum Shield cleared, relay epoch reset as
 * Reset leaves it); the safety number afterwards is the number the dialog showed; the record and the flag go last, in
 * one write, and until then every send stays refused; the contact's next message with the new key raises nothing; a
 * message with the old key is refused again and starts a new record; a key that came with an unmapped conversation
 * id maps it on the accept.
 */
class KeyChangeAcceptAdoptsExactlyTheRememberedKeyTest : KeyChangeAcceptTestBase() {
    @Test
    fun `the accept stores exactly the remembered key, keeps our key pair and drops the old ratchet`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            // A KEXACK: the contact already holds our key, so a Shielded accept asks and pays nothing.
            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-adopt-ack")
            val view = KeyAcceptApi.view(vm, PEER)
            assertNotNull("the dialog has no remembered key to show", view)
            val shown = KeyAcceptApi.get(view!!, "safetyNumber") as String
            assertEquals("the dialog's snapshot names the refused key", newKeys.publicKey, KeyAcceptApi.get(view, "refusedKey"))
            assertEquals("the dialog's snapshot names our key", chat.our.publicKey, KeyAcceptApi.get(view, "ourKey"))
            assertFalse("the dialog still shows the OLD pair's number", shown == vm.computeSafetyNumber(PEER))

            val asked = chat.accept(vm, newKeys.publicKey)

            assertNull("a key that came in a KEXACK is free to accept in a Shielded chat", asked)
            chat.assertAdopted(newKeys, "after the accept")
            assertEquals("our key's provenance changed", KeyChangeAcceptChat.PROVENANCE, chat.impl.getE2EKeyProvenance(PEER))
            assertEquals("our key's version changed", KeyChangeAcceptChat.KEY_VERSION, chat.impl.getE2EKeyVersion(PEER))
            assertFalse("the chat must be unverified until the user compares again", chat.impl.isE2EVerified(PEER))
            val expected =
                E2EEncryption.deriveSharedSecret(
                    newKeys.privateKey,
                    chat.our.publicKey,
                    E2EKeyVersion.fromValue(KeyChangeAcceptChat.KEY_VERSION),
                )
            assertArrayEquals("the shared key is not the one the contact derives", expected, vm.getE2ESharedKey(PEER))
            assertEquals("the number after the accept is not the number the dialog showed", shown, vm.computeSafetyNumber(PEER))
            assertFalse("the old ratchet processor was kept", processorKeys(vm).any { it.startsWith("$PEER:") })
            assertTrue("the old ratchet state was kept", KeyChangeAcceptChat.CONV_ID in chat.reset.ratchetDeletes)
            assertTrue("the Quantum Shield key was kept: ${chat.reset.wipes}", "clearQuantumShieldPSK" in chat.reset.wipes)
            assertTrue("the relay epoch was not reset as Reset leaves it: ${chat.reset.wipes}", "setPeerBootEpoch" in chat.reset.wipes)
            assertFalse("sends must work again after the accept", chat.sendsRefused(vm))
            assertEquals("the conversation id must not move", KeyChangeAcceptChat.CONV_ID, chat.impl.getConversationId(PEER))
            assertEquals("the adoption paid something", 0, chat.seam.submitted.size)
            assertFalse("the adoption set the responder marker", "setReceivedKexPubkey" in chat.writes)
        }

    @Test
    fun `the record and the flag go last and sends stay refused until then`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-last-ack")
            assertNotNull("the refused key is not remembered", chat.record())

            // The process ends at the accept's final write.
            chat.killAtWrite = "takeRefusedKeyOffer"
            chat.accept(vm, newKeys.publicKey)

            assertTrue("fixture: the accept reached its final write", chat.killed)
            assertEquals("the key is stored before the record goes", newKeys.publicKey, chat.storedKey())
            assertTrue("the flag went before the final write", chat.flag())
            assertEquals("the record went before the final write", newKeys.publicKey, chat.recordKey())
            assertTrue("a send got through before the accept finished", chat.sendsRefused(vm))

            chat.revive()
            val again = chat.viewModel()
            chat.accept(again, newKeys.publicKey)

            chat.assertAdopted(newKeys, "after accepting again")
            assertFalse("sends must work again", chat.sendsRefused(again))
        }

    @Test
    fun `afterwards the new key raises nothing and the old key is refused as a new offer`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-after-ack")
            assertNotNull("the refused key is not remembered", chat.record())
            chat.accept(vm, newKeys.publicKey)
            chat.assertAdopted(newKeys, "after the accept")

            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-after-ack-2")
            assertFalse("the adopted key raised the banner again", chat.flag())
            assertNull("the adopted key was remembered as a refused one", chat.record())

            chat.kexAckFromContact(vm, chat.oldContact, "synthetic-zc545-after-old")
            assertTrue("a message with the OLD key must be refused again", chat.flag())
            assertEquals("the adopted key must stay", newKeys.publicKey, chat.storedKey())
            assertEquals("the old key starts a new record", chat.oldContact.publicKey, chat.recordKey())
        }

    @Test
    fun `a key that came with an unmapped conversation id maps it on the accept only`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(LONG_PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-newconv", convId = NEW_CONV)
            assertNotNull("the refused key is not remembered", chat.record())
            assertNull("an unauthenticated offer mapped its conversation id before any accept", chat.impl.getPeerByConversationId(NEW_CONV))
            assertEquals("the outbound id moved before any accept", KeyChangeAcceptChat.CONV_ID, chat.impl.getConversationId(LONG_PEER))

            chat.accept(vm, newKeys.publicKey)

            chat.assertAdopted(newKeys, "after the accept")
            assertEquals("the accepted key's conversation id is not mapped", LONG_PEER, chat.impl.getPeerByConversationId(NEW_CONV))
            assertEquals("the old id's mapping was removed", LONG_PEER, chat.impl.getPeerByConversationId(KeyChangeAcceptChat.CONV_ID))
        }

    @Suppress("UNCHECKED_CAST")
    private fun processorKeys(vm: ChatViewModel): Set<String> =
        (
            ChatViewModel::class.java
                .getDeclaredField("messageProcessors")
                .apply { isAccessible = true }
                .get(vm) as Map<String, Any>
        ).keys.toSet()

    private companion object {
        const val PEER = "usyntheticzc545adoptsexact01"
        const val NEW_CONV = "NEWC2345"

        // A memo with an unmapped conversation id names its sender only by a Unified-Address-shaped payload address.
        val LONG_PEER = "u1synthetic" + "zc545".repeat(22)
    }
}
