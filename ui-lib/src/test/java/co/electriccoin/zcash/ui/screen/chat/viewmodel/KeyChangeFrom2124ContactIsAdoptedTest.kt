package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 13, §3A.6; skill compat-2124 rules 1 and 3) — a key message built by ZChat
 * 2.12.4 and a key-changed flag left by a 2.12.4 install.
 *
 * The 2.12.4 phone is modelled by [Frozen2124], a verbatim copy of `v2.12.4` (commit a1fb4136f),
 * `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EEncryption.kt`: `sign` (lines 413-425),
 * `createKEXPayload` (510-521), `createKEXAckPayload` (559-569) and `buildKEXWire` (593-618, its comment left out),
 * with the constant `KEY_ALGORITHM` (120). Only the object they live in differs.
 *
 * Must hold: a KEX and a KEXACK as 2.12.4 builds them are refused, remembered and adopted exactly like 2.13's (no wire
 * change); a flag 2.12.4 left behind (flag up, the old key stored, no record) loads in place and its × behaves as
 * before (control, green before and after).
 */
class KeyChangeFrom2124ContactIsAdoptedTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a KEXACK built by 2_12_4 is refused, remembered and adopted`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            val memo =
                ZMSGProtocol.createV4KEXAckMessage(
                    KeyChangeAcceptChat.CONV_ID,
                    PEER,
                    Frozen2124.createKEXAckPayload(PEER, newKeys.publicKey, newKeys.privateKey),
                )

            chat.deliver(vm, memo, "synthetic-zc545-2124-ack")

            assertTrue("a 2.12.4 KEXACK with a changed key must raise the banner", chat.flag())
            assertEquals("a 2.12.4 KEXACK is not remembered", newKeys.publicKey, chat.recordKey())
            assertNull("a KEXACK's key is free to accept in a Shielded chat", chat.accept(vm, newKeys.publicKey))
            chat.assertAdopted(newKeys, "after accepting a 2.12.4 KEXACK's key")
        }

    @Test
    fun `a KEX built by 2_12_4 is refused, remembered and adopted for one quoted key exchange`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            val memo =
                ZMSGProtocol.createV4KEXMessage(
                    KeyChangeAcceptChat.CONV_ID,
                    PEER,
                    Frozen2124.createKEXPayload(PEER, newKeys.publicKey, newKeys.privateKey),
                )

            chat.deliver(vm, memo, "synthetic-zc545-2124-kex")

            assertTrue("a 2.12.4 KEX with a changed key must raise the banner", chat.flag())
            assertEquals("a 2.12.4 KEX is not remembered", newKeys.publicKey, chat.recordKey())
            assertEquals("KEY_CHANGE_ACCEPT" to 1, chat.accept(vm, newKeys.publicKey))
            chat.assertAdopted(newKeys, "after accepting a 2.12.4 KEX's key")
            assertEquals("the one quoted key exchange", 1, chat.seam.submitted.size)
        }

    @Test
    fun `control - a flag left by 2_12_4 has no record and its accept behaves as before`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            // What a 2.12.4 install left: the flag up, verified off, the old key stored. It never wrote a record.
            chat.impl.setE2EKeyChanged(PEER, true)
            chat.impl.setE2EVerified(PEER, false)
            assertFalse(
                "fixture: 2.12.4 wrote no remembered key",
                chat.files
                    .getValue("e2ePrefs")
                    .snapshot()
                    .keys
                    .any { it.startsWith("keyoffer:") },
            )
            assertTrue("the flag loads in place", vm.isE2EKeyChanged(PEER))
            assertNotNull("the old pair still has its number", vm.computeSafetyNumber(PEER))

            vm.dismissE2EKeyChanged(PEER)
            chat.settle(vm)

            assertFalse("the Shielded × clears a flag that has no remembered key", chat.flag())
            assertEquals("the old key stays", chat.oldContact.publicKey, chat.storedKey())
            assertEquals("nothing is paid", 0, chat.seam.proposals())
        }

    /** Verbatim from v2.12.4 (a1fb4136f) E2EEncryption.kt; see the class KDoc for the line ranges. */
    @Suppress("LongParameterList")
    private object Frozen2124 {
        private const val KEY_ALGORITHM = "EC"

        fun sign(privateKeyBase64: String, message: String): String {
            val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)
            val privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64)
            val privateKeySpec = java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes)
            val privateKey = keyFactory.generatePrivate(privateKeySpec)

            val signature = java.security.Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(message.toByteArray(Charsets.UTF_8))
            val signatureBytes = signature.sign()

            return Base64.getEncoder().encodeToString(signatureBytes)
        }

        fun createKEXPayload(
            senderAddress: String,
            publicKey: String,
            privateKey: String,
            nostrPubkeyHex: String? = null,
            relayUrl: String? = null,
        ): String {
            // Sign: SHA256(address || pubkey)
            val messageToSign = senderAddress + publicKey
            val signature = sign(privateKey, messageToSign)
            return buildKEXWire("KEX:", publicKey, signature, nostrPubkeyHex, relayUrl, senderAddress)
        }

        fun createKEXAckPayload(
            senderAddress: String,
            publicKey: String,
            privateKey: String,
            nostrPubkeyHex: String? = null,
            relayUrl: String? = null,
        ): String {
            val messageToSign = senderAddress + publicKey
            val signature = sign(privateKey, messageToSign)
            return buildKEXWire("KEXACK:", publicKey, signature, nostrPubkeyHex, relayUrl, senderAddress)
        }

        private fun buildKEXWire(
            prefix: String,
            publicKey: String,
            signature: String,
            nostrPubkeyHex: String?,
            relayUrl: String?,
            senderAddress: String? = null,
        ): String {
            var wire = "$prefix$publicKey:$signature"
            if (!senderAddress.isNullOrBlank()) {
                wire = "$wire:$senderAddress"
            } else if (!nostrPubkeyHex.isNullOrBlank() && !relayUrl.isNullOrBlank()) {
                val relayB64 = Base64.getEncoder().encodeToString(relayUrl.toByteArray(Charsets.UTF_8))
                wire = "$wire:$nostrPubkeyHex:$relayB64"
            }
            return wire
        }
    }

    private companion object {
        const val PEER = "usyntheticzc545from2124peer1"
    }
}
