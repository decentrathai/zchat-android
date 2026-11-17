package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression pins for "recipient dropped first-contact KEX" (senderAddress = null recovery).
 *
 * A recipient with NO prior convId mapping (true first contact / fresh install) cannot supply the
 * sender's address to [E2EEncryption.parseKEXPayloadFull] — the ONLY way it can learn and verify
 * the sender is the u1 address APPENDED to the wire by [E2EEncryption.createKEXPayload]
 * (`KEX:<pub>:<sig>:<u1addr>`). The fix under pin: parseKEXWire falls back to that payload-carried
 * address (`verifyAddress = senderAddress ?: payloadAddress`) and STILL runs full signature
 * verification over (address || pubkey) against it.
 *
 * Mutations that must turn these RED:
 *  - dropping the payload-address fallback (verify only against the caller-supplied address):
 *    the first-contact parses return null → the KEX is silently dropped (the original bug);
 *  - returning the recovered address WITHOUT verifying the signature over it (TOFU without
 *    proof): the tampered-address / wrong-key / no-address negatives return non-null;
 *  - swapping precedence to `payloadAddress ?: senderAddress`: the established-conversation
 *    override test returns non-null for a payload whose signature doesn't match the caller's
 *    peer address.
 *
 * All addresses are SYNTHETIC (same "u1" + repeat idiom as E2ESeedRecoveryProofTest) — the parser
 * only requires startsWith("u1") && length > 60; nothing here touches a real wallet.
 */
class KexFirstContactAddressRecoveryTest {

    private val addrA = "u1" + "a".repeat(75)
    private val addrB = "u1" + "b".repeat(75)

    @Test
    fun `first-contact KEX (no caller address) recovers the appended u1 address and verifies`() {
        val kp = E2EEncryption.generateKeyPair()
        val wire = E2EEncryption.createKEXPayload(addrA, kp.publicKey, kp.privateKey)

        // senderAddress = null is EXACTLY the recipient-side first-contact state (no convId mapping).
        val parsed = E2EEncryption.parseKEXPayloadFull(wire, senderAddress = null)

        // MUTATION: reverting the payload-address fallback makes verifyAddress null → parse returns
        // null here and the first-contact KEX is dropped (the original bug).
        assertNotNull(parsed, "first-contact KEX must parse via the payload-carried address")
        assertEquals(kp.publicKey, parsed.publicKey)
        assertEquals(
            addrA,
            parsed.senderAddress,
            "the recovered sender address must be the one the signature was verified against",
        )
    }

    @Test
    fun `first-contact KEXACK (no caller address) recovers the appended u1 address and verifies`() {
        val kp = E2EEncryption.generateKeyPair()
        val wire = E2EEncryption.createKEXAckPayload(addrB, kp.publicKey, kp.privateKey)

        val parsed = E2EEncryption.parseKEXAckPayloadFull(wire, senderAddress = null)

        // MUTATION: same fallback removal as the KEX case — the initiator restoring from a wipe
        // (or racing convId persistence) loses the peer's KEXACK.
        assertNotNull(parsed, "first-contact KEXACK must parse via the payload-carried address")
        assertEquals(kp.publicKey, parsed.publicKey)
        assertEquals(addrB, parsed.senderAddress)
    }

    @Test
    fun `a KEX signed by a DIFFERENT private key is rejected even with a well-formed appended address`() {
        val honest = E2EEncryption.generateKeyPair()
        val forger = E2EEncryption.generateKeyPair()
        // Wire claims honest's public key but the signature over (addrA || honest.pub) was made
        // with the forger's private key.
        val wire = E2EEncryption.createKEXPayload(addrA, honest.publicKey, forger.privateKey)

        // MUTATION: an address-recovery path that ACCEPTS the payload address without running
        // signature verification against it returns non-null here → key-substitution on first
        // contact. Must stay null.
        assertNull(E2EEncryption.parseKEXPayloadFull(wire, senderAddress = null))
    }

    @Test
    fun `tampering the appended address after signing is rejected - recovery is VERIFIED not trusted`() {
        val kp = E2EEncryption.generateKeyPair()
        val wire = E2EEncryption.createKEXPayload(addrA, kp.publicKey, kp.privateKey)

        // Surgical tamper: keep <pub>:<sig> (signed over addrA), swap the appended address to addrB.
        val parts = wire.removePrefix("KEX:").split(":")
        assertEquals(3, parts.size, "fixture expects KEX:<pub>:<sig>:<u1addr>")
        val tampered = "KEX:${parts[0]}:${parts[1]}:$addrB"

        // MUTATION: returning ParsedKEX(senderAddress = payloadAddress) without folding the
        // recovered address into the verified message lets an attacker re-bind a victim's real
        // signed key to an attacker-chosen address → non-null here. Must stay null.
        assertNull(E2EEncryption.parseKEXPayloadFull(tampered, senderAddress = null))
    }

    @Test
    fun `caller-known address takes precedence over the payload address`() {
        val kp = E2EEncryption.generateKeyPair()
        // Signed + appended for addrA, but the recipient's convId mapping says this conversation
        // peer is addrB — the established mapping is authoritative, so verification must run
        // against addrB and FAIL.
        val wire = E2EEncryption.createKEXPayload(addrA, kp.publicKey, kp.privateKey)

        // MUTATION: swapping precedence to `payloadAddress ?: senderAddress` verifies against the
        // attacker-controlled appended addrA instead → non-null. Must stay null.
        assertNull(E2EEncryption.parseKEXPayloadFull(wire, senderAddress = addrB))

        // Sanity (same wire): with the MATCHING caller address the established path still verifies.
        val parsed = E2EEncryption.parseKEXPayloadFull(wire, senderAddress = addrA)
        assertNotNull(parsed)
        assertEquals(addrA, parsed.senderAddress)
    }

    @Test
    fun `legacy wire with NO appended address fails closed on first contact but parses when caller knows the address`() {
        val kp = E2EEncryption.generateKeyPair()
        // Legacy 2-segment wire: signature over (addrA || pub) but the address is NOT on the wire.
        val sig = E2EEncryption.sign(kp.privateKey, addrA + kp.publicKey)
        val legacyWire = "KEX:${kp.publicKey}:$sig"

        // First contact: nothing to verify against → must fail CLOSED (null), never invent/skip.
        // MUTATION: "recovering" by accepting an unverifiable wire returns non-null here.
        assertNull(E2EEncryption.parseKEXPayloadFull(legacyWire, senderAddress = null))

        // Established conversation: the caller-supplied address verifies (backward compat intact).
        val parsed = E2EEncryption.parseKEXPayloadFull(legacyWire, senderAddress = addrA)
        assertNotNull(parsed, "legacy established-peer KEX must keep verifying")
        assertEquals(kp.publicKey, parsed.publicKey)
        assertEquals(addrA, parsed.senderAddress)
    }
}
