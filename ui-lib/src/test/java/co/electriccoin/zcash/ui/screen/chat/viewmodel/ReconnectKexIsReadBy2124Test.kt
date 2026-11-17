package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * ZC-535 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3.8, skill compat-2124 rule 1) — a format pin, green before and
 * after: the key exchange a confirmed key-change reconnect builds is read by ZChat 2.12.4's own parser. The reconnect
 * sends the same memo as every other KEX (ChatViewModel.buildKexMemo: our key, signed over our address and the key, our
 * address appended); ZC-535 changes when it is sent, not what it carries.
 *
 * The 2.12.4 phone is modelled by [Frozen2124], a verbatim copy of `v2.12.4` (commit a1fb4136f),
 * `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EEncryption.kt`: `ParsedKEX` (lines 478-485),
 * `parseKEXPayloadFull` (543-548), `NOSTR_HEX_64` (619), `parseKEXWire` (625-675) and `verify` (435-451), with the
 * constants `TAG` (119) and `KEY_ALGORITHM` (120). Only the object they live in differs.
 *
 * The fixture uses an incomplete exchange, where the reconnect's KEX is built before and after ZC-535. A 2.12.4 phone
 * sees an on-chain memo from 2.13.0 only after it updates and rescans (compat-2124); this pins that the bytes are the
 * ones its parser takes. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectKexIsReadBy2124Test {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc535frozen212401"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `the key exchange a confirmed reconnect builds is read by 2_12_4's parser with our key and address`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.seam.submittedOf("KEX") == 1 }
            awaitIdle(vm)

            val memo = chat.seam.kexMemos.single()
            val payload = ZMSGProtocol.parseKEXMessage(memo)?.second
            assertNotNull("fixture sanity: the reconnect's memo is a v4 KEX", payload)
            val ourKey = chat.store.getE2EOurPublicKey(PEER)
            // The builder appends our address as the payload's last field.
            val ourAddress = payload!!.substringAfterLast(':')

            // A contact that knows this conversation verifies against the address it has for us.
            val parsed = Frozen2124.parseKEXPayloadFull(payload, ourAddress)
            assertNotNull("2.12.4 does not read the reconnect's key exchange", parsed)
            assertEquals("2.12.4 reads another key", ourKey, parsed!!.publicKey)
            assertEquals(ourAddress, parsed.senderAddress)
            assertNull("the reconnect's KEX carries no relay key (the address takes its place)", parsed.nostrPubkeyHex)
            // And it refuses the same payload for another address: the signature binds our address and our key.
            assertNull(Frozen2124.parseKEXPayloadFull(payload, "u1syntheticsomeoneelse"))
        }

    /** The first-contact shape: 2.12.4 recovers a real-length unified address from the payload itself. */
    @Test
    fun `a KEX payload from the same builder with a full-length address is read by 2_12_4 without knowing the sender`() {
        val keys = E2EEncryption.generateKeyPair()
        val address = "u1" + "syntheticzc535".repeat(8)
        val relay = "wss://relay.synthetic.example"
        val payload = E2EEncryption.createKEXPayload(address, keys.publicKey, keys.privateKey, "ab".repeat(32), relay)

        val parsed = Frozen2124.parseKEXPayloadFull(payload, null)

        assertNotNull("2.12.4 does not read a first-contact key exchange", parsed)
        assertEquals(keys.publicKey, parsed!!.publicKey)
        assertEquals(address, parsed.senderAddress)
        assertNull(parsed.nostrPubkeyHex)
    }

    /** `v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EEncryption.kt`, verbatim (see the class KDoc). */
    @Suppress(
        "ReturnCount",
        "TooGenericExceptionCaught",
        "MagicNumber",
        "MaxLineLength",
        "ktlint:standard:function-expression-body",
        "ktlint:standard:multiline-expression-wrapping",
    )
    private object Frozen2124 {
        private const val TAG = "E2E_ENCRYPTION"
        private const val KEY_ALGORITHM = "EC"

        data class ParsedKEX(
            val publicKey: String,
            val nostrPubkeyHex: String? = null,
            val relayUrl: String? = null,
            // The sender's address that the signature was verified against. For a first-contact KEX this is
            // recovered FROM the payload (the only way a recipient with no prior convId mapping can learn it).
            val senderAddress: String? = null,
        )

        fun verify(publicKeyBase64: String, message: String, signatureBase64: String): Boolean {
            return try {
                val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)
                val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
                val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
                val publicKey = keyFactory.generatePublic(publicKeySpec)

                val signature = java.security.Signature.getInstance("SHA256withECDSA")
                signature.initVerify(publicKey)
                signature.update(message.toByteArray(Charsets.UTF_8))

                val signatureBytes = Base64.getDecoder().decode(signatureBase64)
                signature.verify(signatureBytes)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Signature verification failed", e)
                false
            }
        }

        fun parseKEXPayloadFull(payload: String, senderAddress: String? = null): ParsedKEX? {
            if (!payload.startsWith("KEX:")) {
                return null
            }
            return parseKEXWire(payload.removePrefix("KEX:"), senderAddress, "KEX")
        }

        private val NOSTR_HEX_64 = Regex("^[0-9a-f]{64}$")

        /**
         * Parse the body (after the "KEX:"/"KEXACK:" prefix) and verify the signature.
         * Returns null only when the signature fails; malformed trailing NOSTR fields are ignored.
         */
        private fun parseKEXWire(body: String, senderAddress: String?, tag: String): ParsedKEX? {
            val parts = body.split(":")
            if (parts.size < 2) {
                android.util.Log.e(TAG, "Invalid $tag payload format")
                return null
            }

            val publicKey = parts[0]
            val signature = parts[1]

            // Recover the appended sender address (raw unified address: starts with "u1", long, no ':').
            // This is distinguishable from the 64-hex NOSTR pubkey and the Base64 relay field.
            val payloadAddress = parts.drop(2).firstOrNull { it.length > 60 && it.startsWith("u1") }

            // Verify against the caller's address (established conversation) when known, else against the
            // address carried in the payload (first contact, TOFU). The signature binds (address || pubkey),
            // so a self-consistent payload proves the holder of the private key claims that address.
            val verifyAddress = senderAddress ?: payloadAddress
            if (verifyAddress == null) {
                android.util.Log.e(TAG, "$tag has no address to verify against (no convId mapping, no payload address)")
                return null
            }
            val messageToVerify = verifyAddress + publicKey
            if (!verify(publicKey, messageToVerify, signature)) {
                android.util.Log.e(TAG, "$tag signature verification failed for ${verifyAddress.redactAddress()}")
                return null
            }

            // Optional NOSTR segments (BUG-4 one-tap calling). Absent or malformed → treat as absent
            // (legacy fallback to ZBOOT), never fail the verified key exchange over them.
            var nostrPubkeyHex: String? = null
            var relayUrl: String? = null
            if (parts.size >= 4) {
                val candidatePubkey = parts[2].lowercase()
                val relayB64 = parts[3]
                if (NOSTR_HEX_64.matches(candidatePubkey)) {
                    val decodedRelay = runCatching {
                        String(Base64.getDecoder().decode(relayB64), Charsets.UTF_8)
                    }.getOrNull()
                    if (decodedRelay != null && decodedRelay.startsWith("wss://") && decodedRelay.length <= 80) {
                        nostrPubkeyHex = candidatePubkey
                        relayUrl = decodedRelay
                    }
                }
                if (nostrPubkeyHex == null) {
                    android.util.Log.w(TAG, "$tag carried malformed NOSTR fields — ignoring, will fall back to ZBOOT")
                }
            }

            return ParsedKEX(
                publicKey = publicKey,
                nostrPubkeyHex = nostrPubkeyHex,
                relayUrl = relayUrl,
                senderAddress = verifyAddress,
            )
        }
    }
}
