package co.electriccoin.zcash.ui.screen.chat.crypto

import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

/**
 * BUG-4: one-tap calling via NOSTR keys carried in the FIRST KEX handshake.
 *
 * These run as androidTest (instrumented) on purpose: they exercise the REAL java.util.Base64
 * encoder/decoder and the REAL ECDSA sign/verify path. The JVM unit-test set stubs Base64, which
 * would let a buggy delimiter/encoding slip through — so the dual-path wire format is validated here.
 */
class KEXNostrPayloadTest {

    private val senderAddress = "u1kexnostrtestsenderaddress0001"
    private val peerNostrHex = "a".repeat(64) // valid 64-hex-char x-only pubkey
    private val relay = "wss://relay.damus.io"

    private fun freshKeyPair(): E2EKeyPair = E2EEncryption.generateKeyPair()

    // ---- KEX WITH nostr field: parses, verifies, populates peer nostr pubkey ----

    @Test
    @SmallTest
    fun kexWithNostr_parsesAndPopulatesNostrPubkey() {
        val kp = freshKeyPair()

        val payload = E2EEncryption.createKEXPayload(
            senderAddress, kp.publicKey, kp.privateKey, peerNostrHex, relay
        )

        val parsed = E2EEncryption.parseKEXPayloadFull(payload, senderAddress)

        assertThat("KEX with NOSTR should verify", parsed, notNullValue())
        assertThat(parsed!!.publicKey, equalTo(kp.publicKey))
        assertThat(parsed.nostrPubkeyHex, equalTo(peerNostrHex))
        assertThat(parsed.relayUrl, equalTo(relay))
    }

    @Test
    @SmallTest
    fun kexAckWithNostr_parsesAndPopulatesNostrPubkey() {
        val kp = freshKeyPair()

        val payload = E2EEncryption.createKEXAckPayload(
            senderAddress, kp.publicKey, kp.privateKey, peerNostrHex, relay
        )

        val parsed = E2EEncryption.parseKEXAckPayloadFull(payload, senderAddress)

        assertThat(parsed, notNullValue())
        assertThat(parsed!!.publicKey, equalTo(kp.publicKey))
        assertThat(parsed.nostrPubkeyHex, equalTo(peerNostrHex))
        assertThat(parsed.relayUrl, equalTo(relay))
    }

    // ---- KEX WITHOUT nostr field (legacy): parses, verifies, nostr NOT set ----

    @Test
    @SmallTest
    fun kexWithoutNostr_legacy_verifiesButNoNostrPubkey() {
        val kp = freshKeyPair()

        // No NOSTR args → legacy 2-segment wire format.
        val payload = E2EEncryption.createKEXPayload(senderAddress, kp.publicKey, kp.privateKey)

        // Sanity: legacy wire shape is exactly KEX:<pubkey>:<sig> (no extra segments).
        assertThat(payload.removePrefix("KEX:").split(":").size, equalTo(2))

        val parsed = E2EEncryption.parseKEXPayloadFull(payload, senderAddress)

        assertThat("legacy KEX must still verify", parsed, notNullValue())
        assertThat(parsed!!.publicKey, equalTo(kp.publicKey))
        // Falls back to ZBOOT path — NOSTR fields absent.
        assertThat(parsed.nostrPubkeyHex, nullValue())
        assertThat(parsed.relayUrl, nullValue())
    }

    @Test
    @SmallTest
    fun newClientVerifiesLegacyWireString() {
        // A hand-built legacy payload (as an OLD client would emit) must verify on a NEW client and
        // expose no NOSTR fields. Sign the SAME canonical bytes (address || pubkey).
        val kp = freshKeyPair()
        val sig = E2EEncryption.sign(kp.privateKey, senderAddress + kp.publicKey)
        val legacyWire = "KEX:${kp.publicKey}:$sig"

        val parsed = E2EEncryption.parseKEXPayloadFull(legacyWire, senderAddress)

        assertThat(parsed, notNullValue())
        assertThat(parsed!!.publicKey, equalTo(kp.publicKey))
        assertThat(parsed.nostrPubkeyHex, nullValue())
    }

    // ---- Tampered KEX (flipped pubkey byte) still fails verify ----

    @Test
    @SmallTest
    fun tamperedKex_flippedPubkeyByte_failsVerify() {
        val kp = freshKeyPair()
        val payload = E2EEncryption.createKEXPayload(
            senderAddress, kp.publicKey, kp.privateKey, peerNostrHex, relay
        )

        val parts = payload.removePrefix("KEX:").split(":").toMutableList()
        // Flip one character of the Base64 pubkey segment (keep it valid Base64-ish but a different key).
        val pub = parts[0]
        val idx = pub.length / 2
        val orig = pub[idx]
        val replacement = if (orig == 'A') 'B' else 'A'
        parts[0] = pub.substring(0, idx) + replacement + pub.substring(idx + 1)
        val tampered = "KEX:" + parts.joinToString(":")

        val parsed = E2EEncryption.parseKEXPayloadFull(tampered, senderAddress)

        assertThat("tampered pubkey must fail signature verification", parsed, nullValue())
        // And the convenience accessor must agree.
        assertThat(E2EEncryption.parseKEXPayload(tampered, senderAddress), nullValue())
    }

    @Test
    @SmallTest
    fun malformedNostrTail_keepsVerifiedKeyButDropsNostr() {
        // A valid signature with a garbage NOSTR tail must NOT fail the KEX — we keep the verified
        // key and simply fall back to ZBOOT for the NOSTR identity.
        val kp = freshKeyPair()
        val sig = E2EEncryption.sign(kp.privateKey, senderAddress + kp.publicKey)
        val wire = "KEX:${kp.publicKey}:$sig:not_hex_pubkey:bm90LWEtcmVsYXk=" // bad hex + non-wss relay

        val parsed = E2EEncryption.parseKEXPayloadFull(wire, senderAddress)

        assertThat(parsed, notNullValue())
        assertThat(parsed!!.publicKey, equalTo(kp.publicKey))
        assertThat(parsed.nostrPubkeyHex, nullValue())
        assertThat(parsed.relayUrl, nullValue())
    }
}
