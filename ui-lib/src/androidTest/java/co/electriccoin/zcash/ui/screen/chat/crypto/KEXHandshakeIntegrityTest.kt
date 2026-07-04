package co.electriccoin.zcash.ui.screen.chat.crypto

import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import java.util.Base64

/**
 * Handshake INTEGRITY for the authenticated key exchange (KEX / KEXACK). A bug here forges peer
 * identity or breaks the first-contact recovery that lets a recipient with no prior convId mapping
 * reply — so the signature-binding, prefix-isolation, first-contact recovery, and the 512-byte memo
 * budget in [E2EEncryption.createKEXPayload] / [E2EEncryption.parseKEXPayloadFull] are pinned here.
 *
 * These run as androidTest (instrumented) on purpose, mirroring [KEXNostrPayloadTest]: they exercise
 * the REAL ECDSA sign/verify path and the REAL java.util.Base64 encoder/decoder. The JVM unit-test set
 * runs with isReturnDefaultValues=true, so — while E2EEncryption happens to use java.util.Base64 — the
 * ECDSA/wire integrity of a security handshake is validated against real primitives here, next to the
 * existing KEX instrumented tests, not against stubs.
 *
 * These cases are DISTINCT from [KEXNostrPayloadTest] (NOSTR round-trip / legacy / tampered-pubkey /
 * malformed-tail). They add: wrong-address verify, signature-byte mutation, first-contact recovery,
 * prefix cross-rejection, and the buildKEXWire size-budget branch.
 */
class KEXHandshakeIntegrityTest {

    private val senderAddress = "u1kexhandshakeintegritysenderaddress0001longenoughtoberecovered"
    private val peerNostrHex = "b".repeat(64)
    private val relay = "wss://relay.damus.io"

    private fun freshKeyPair(): E2EKeyPair = E2EEncryption.generateKeyPair()

    // ---- signature binds (address || pubkey): a DIFFERENT verify address must fail ----

    @Test
    @SmallTest
    fun kexVerifiedWithDifferentAddress_returnsNull() {
        val kp = freshKeyPair()
        // Signed over senderAddress || pubkey.
        val payload = E2EEncryption.createKEXPayload(senderAddress, kp.publicKey, kp.privateKey)

        // Same key, same signature — but the caller claims the KEX came from a DIFFERENT address.
        // The signature binds the address, so verification MUST fail (prevents address-spoofing a
        // genuinely signed key onto someone else's conversation).
        val otherAddress = "u1someoneelsesaddress0002differentfromthesigner00000000000000"
        val parsed = E2EEncryption.parseKEXPayloadFull(payload, senderAddress = otherAddress)

        assertThat("KEX verified against a different address must fail", parsed, nullValue())
    }

    // ---- 1-byte mutation of the signature must fail verify ----

    @Test
    @SmallTest
    fun kexWithMutatedSignatureByte_returnsNull() {
        val kp = freshKeyPair()
        val payload = E2EEncryption.createKEXPayload(senderAddress, kp.publicKey, kp.privateKey)

        val parts = payload.removePrefix("KEX:").split(":").toMutableList()
        // Decode the real signature, flip a single byte, re-encode. A one-bit change anywhere in a
        // valid ECDSA signature must break verification.
        val sigBytes = Base64.getDecoder().decode(parts[1])
        val mid = sigBytes.size / 2
        sigBytes[mid] = (sigBytes[mid].toInt() xor 0x01).toByte()
        parts[1] = Base64.getEncoder().encodeToString(sigBytes)
        val tampered = "KEX:" + parts.joinToString(":")

        assertThat(E2EEncryption.parseKEXPayloadFull(tampered, senderAddress), nullValue())
        assertThat(E2EEncryption.parseKEXPayload(tampered, senderAddress), nullValue())
    }

    // ---- 1-byte mutation of the pubkey must fail verify (complements KEXNostrPayloadTest's char-flip) ----

    @Test
    @SmallTest
    fun kexWithMutatedPubkeyByte_returnsNull() {
        val kp = freshKeyPair()
        val payload = E2EEncryption.createKEXPayload(senderAddress, kp.publicKey, kp.privateKey)

        val parts = payload.removePrefix("KEX:").split(":").toMutableList()
        val pubBytes = Base64.getDecoder().decode(parts[0])
        // Flip a byte deep in the key material (past the SubjectPublicKeyInfo header) so it stays a
        // structurally-decodable EC point but a DIFFERENT key than the one that was signed.
        val idx = pubBytes.size - 4
        pubBytes[idx] = (pubBytes[idx].toInt() xor 0x01).toByte()
        parts[0] = Base64.getEncoder().encodeToString(pubBytes)
        val tampered = "KEX:" + parts.joinToString(":")

        assertThat(E2EEncryption.parseKEXPayloadFull(tampered, senderAddress), nullValue())
    }

    // ---- first-contact address recovery: parse with senderAddress=null recovers the UA ----

    @Test
    @SmallTest
    fun firstContact_parseWithNullAddress_recoversAppendedAddress() {
        val kp = freshKeyPair()
        // createKEXPayload appends the sender's UA (no NOSTR fields, because the address is present —
        // the 512-byte-budget branch). A recipient with NO prior convId mapping passes senderAddress=null
        // and must recover + verify against the address carried in the payload (TOFU first-contact).
        val payload = E2EEncryption.createKEXPayload(senderAddress, kp.publicKey, kp.privateKey)

        val parsed = E2EEncryption.parseKEXPayloadFull(payload, senderAddress = null)

        assertThat("first-contact KEX must verify via the appended address", parsed, notNullValue())
        assertThat(parsed!!.publicKey, equalTo(kp.publicKey))
        assertThat("recovered address must be the signer's UA", parsed.senderAddress, equalTo(senderAddress))
    }

    // ---- no address anywhere (legacy 2-segment wire) + null caller address → cannot verify → null ----

    @Test
    @SmallTest
    fun noAddressInPayloadAndNullCaller_returnsNull() {
        val kp = freshKeyPair()
        // Hand-build a legacy 2-segment wire (pubkey:sig) with NO appended address, as a pre-address
        // client would emit. With senderAddress=null there is nothing to verify against → null.
        val sig = E2EEncryption.sign(kp.privateKey, senderAddress + kp.publicKey)
        val legacyWire = "KEX:${kp.publicKey}:$sig"

        assertThat(E2EEncryption.parseKEXPayloadFull(legacyWire, senderAddress = null), nullValue())

        // Sanity: the SAME wire verifies fine once the caller supplies the address out-of-band.
        assertThat(E2EEncryption.parseKEXPayloadFull(legacyWire, senderAddress), notNullValue())
    }

    // ---- KEXACK payload round-trips (with the appended-address first-contact path too) ----

    @Test
    @SmallTest
    fun kexAck_roundTrips_andRecoversAddressOnFirstContact() {
        val kp = freshKeyPair()
        val payload = E2EEncryption.createKEXAckPayload(senderAddress, kp.publicKey, kp.privateKey)

        val withAddr = E2EEncryption.parseKEXAckPayloadFull(payload, senderAddress)
        assertThat(withAddr, notNullValue())
        assertThat(withAddr!!.publicKey, equalTo(kp.publicKey))

        val firstContact = E2EEncryption.parseKEXAckPayloadFull(payload, senderAddress = null)
        assertThat("KEXACK must also recover its address on first contact", firstContact, notNullValue())
        assertThat(firstContact!!.senderAddress, equalTo(senderAddress))
    }

    // ---- prefix isolation: a KEXACK must NOT parse as a KEX, and vice-versa ----

    @Test
    @SmallTest
    fun kexAckDoesNotParseAsKex_andKexDoesNotParseAsKexAck() {
        val kp = freshKeyPair()
        val kex = E2EEncryption.createKEXPayload(senderAddress, kp.publicKey, kp.privateKey)
        val kexAck = E2EEncryption.createKEXAckPayload(senderAddress, kp.publicKey, kp.privateKey)

        // Each parses under its OWN prefix.
        assertThat(E2EEncryption.parseKEXPayloadFull(kex, senderAddress), notNullValue())
        assertThat(E2EEncryption.parseKEXAckPayloadFull(kexAck, senderAddress), notNullValue())

        // A KEXACK fed to the KEX parser must be rejected on the prefix (it never even reaches verify),
        // and a KEX fed to the KEXACK parser likewise. This keeps the two handshake stages from being
        // confused/replayed across roles.
        assertThat("KEXACK must not parse as KEX", E2EEncryption.parseKEXPayloadFull(kexAck, senderAddress), nullValue())
        assertThat("KEX must not parse as KEXACK", E2EEncryption.parseKEXAckPayloadFull(kex, senderAddress), nullValue())
    }

    // ---- 512B budget: sender address PRESENT ⇒ NOSTR fields dropped from the wire ----

    @Test
    @SmallTest
    fun addressPresent_dropsNostrFields_toStayUnderMemoBudget() {
        val kp = freshKeyPair()
        // A u1 address is ~178 chars; adding it PLUS the NOSTR fields overflows the 512-byte memo →
        // MemoTooLong → the whole first-contact KEX send silently fails. So when the address is present,
        // buildKEXWire drops the (optional, also-delivered-via-ZBOOT) NOSTR fields.
        val payload = E2EEncryption.createKEXPayload(
            senderAddress, kp.publicKey, kp.privateKey, peerNostrHex, relay
        )

        val parsed = E2EEncryption.parseKEXPayloadFull(payload, senderAddress)
        assertThat(parsed, notNullValue())
        // Wire shape is KEX:<pubkey>:<sig>:<address> — exactly 3 body segments, NO NOSTR pubkey/relay.
        assertThat(payload.removePrefix("KEX:").split(":").size, equalTo(3))
        assertThat("NOSTR pubkey must be dropped when address is present", parsed!!.nostrPubkeyHex, nullValue())
        assertThat("relay must be dropped when address is present", parsed.relayUrl, nullValue())
        // The appended segment is the address itself (recoverable on first contact).
        assertThat(parsed.senderAddress, equalTo(senderAddress))
    }

    // ---- 512B budget: address BLANK ⇒ NOSTR pubkey (+ base64 relay) appended instead ----

    @Test
    @SmallTest
    fun blankAddress_appendsNostrPubkeyAndB64Relay() {
        val kp = freshKeyPair()
        // Established-peer re-KEX: the convId mapping already exists so the address is NOT needed and is
        // passed blank. buildKEXWire then carries the NOSTR fields (one-tap calling) instead.
        val payload = E2EEncryption.createKEXPayload(
            senderAddress = "", // blank ⇒ NOSTR branch
            publicKey = kp.publicKey,
            privateKey = kp.privateKey,
            nostrPubkeyHex = peerNostrHex,
            relayUrl = relay,
        )

        // Wire shape is KEX:<pubkey>:<sig>:<nostrHex>:<relayB64> — 4 body segments.
        val segments = payload.removePrefix("KEX:").split(":")
        assertThat(segments.size, equalTo(4))
        assertThat("3rd segment is the raw 64-hex NOSTR pubkey", segments[2], equalTo(peerNostrHex))
        // The relay is base64-encoded on the wire (it contains ':' and '/').
        val decodedRelay = String(Base64.getDecoder().decode(segments[3]), Charsets.UTF_8)
        assertThat(decodedRelay, equalTo(relay))

        // A blank-address KEX is signed over ("" + pubkey), so it can NEVER verify against a KNOWN
        // address (the sign address and the verify address must match) — parseKEXPayloadFull safely
        // returns null rather than accepting a KEX signed over a blank address. Production never emits a
        // blank-address KEX: the sole caller (ChatViewModel.sendKEXMessage) always passes the real sender
        // address, so this branch only documents buildKEXWire's wire SHAPE. Rejecting the mismatched
        // sign/verify address is the safe outcome (the address-present KEX round-trips are covered above).
        val parsed = E2EEncryption.parseKEXPayloadFull(payload, senderAddress)
        assertThat("blank-address KEX must not verify against a known address", parsed, nullValue())
    }
}
