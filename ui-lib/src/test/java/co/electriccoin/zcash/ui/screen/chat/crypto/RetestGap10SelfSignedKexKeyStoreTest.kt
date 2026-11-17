package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RetestGap10 — self-signed KEX key-substitution: an ESTABLISHED E2E key must never be auto-overwritten
 * by a self-signed substitute presented on the wire.
 *
 * Production guard (ChatViewModel.handleKEXMessage, ChatViewModel.kt:5400, and its KEXACK twin at :5534):
 *
 *     val previousPubkey = zchatPreferences.getE2EPeerPublicKey(senderAddress)
 *     if (previousPubkey != null && previousPubkey != peerPublicKey) {
 *         zchatPreferences.setE2EKeyChanged(senderAddress, true)
 *         zchatPreferences.setE2EVerified(senderAddress, false)
 *         return@launch                      // REFUSE — keep the old key, do NOT store the substitute
 *     }
 *     zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)   // store only when null / same
 *
 * A KEX/KEXACK signature is only SELF-signed (verified against the payload's OWN pubkey), so it cannot
 * authenticate the sender as the already-established peer. This test pins the two load-bearing facts that
 * make that guard necessary and correct, entirely at the pure crypto layer (no Android deps → src/test):
 *
 *   1. The attack is REAL: a KEX self-signed with a SUBSTITUTE key, carrying an established peer's address,
 *      passes full signature verification (parseKEXPayloadFull returns non-null with the substitute pubkey).
 *      Signature verification alone therefore does NOT stop key substitution — hence the store guard.
 *   2. The store/refuse decision is exactly the policy `held == null || held == incoming`, encoded in
 *      production as [E2EEncryption.mayRemapConvIdForKexAck] — the same predicate the KEXACK conv-id-remap
 *      arm calls (ChatViewModel.kt:5518) and that the two inline key-store guards (:5400, :5534) implement
 *      by hand. First contact (no key held) and a same-key re-KEX STORE; an established peer presenting a
 *      DIFFERENT (validly self-signed) key is REFUSED so the stored key stays put.
 *
 * NOTE on revert scope: the :5400/:5534 key-store guards are hand-inlined, not routed through a callable
 * function, so a pure-JVM test cannot redden an ISOLATED deletion of only those two lines without either
 * (a) the `mayStoreKexKey` extraction the retest spec proposes (route :5400/:5534 through it) or (b)
 * Android-lane wiring of the real ChatViewModel (its ctor needs a Context via AvatarStore — not JVM-safe).
 * This test reddens on any revert of the SHARED authorization policy [E2EEncryption.mayRemapConvIdForKexAck]
 * (the extracted twin of that guard) and on any weakening of KEX self-signature verification.
 *
 * Addresses are SYNTHETIC (same "u1"+repeat idiom as KexFirstContactAddressRecoveryTest); real ECDSA keys
 * are generated so the substitution signature genuinely verifies.
 */
class RetestGap10SelfSignedKexKeyStoreTest {

    private val peerAddress = "u1" + "p".repeat(75)

    @Test
    fun `a substitute self-signed KEX for an established peer VERIFIES - proving signature check alone is not enough`() {
        // The established peer's real key (what we already hold, TOFU-bound).
        val established = E2EEncryption.generateKeyPair()
        // An attacker fabricates a fresh keypair and self-signs a KEX carrying the SAME peer address.
        val substitute = E2EEncryption.generateKeyPair()
        val substituteWire =
            E2EEncryption.createKEXPayload(peerAddress, substitute.publicKey, substitute.privateKey)

        // Established-conversation verify path (ChatViewModel.kt:5379 uses the mapped peer address).
        val parsed = E2EEncryption.parseKEXPayloadFull(substituteWire, senderAddress = peerAddress)

        // The self-signature over (peerAddress || substitutePub) is internally consistent, so it VERIFIES
        // and yields the substitute key. This is precisely why an ADDITIONAL store guard is required — the
        // signature cannot tell an established peer apart from an attacker reusing that peer's address.
        assertNotNull("a validly self-signed substitute KEX must still pass signature verification", parsed)
        assertEquals(
            "verification yields the substitute pubkey — the guard, not the signature, must reject it",
            substitute.publicKey,
            parsed!!.publicKey,
        )
        // Sanity: the two keys really are different, so the store guard's mismatch branch is what fires.
        assertFalse("fixture must generate two distinct keys", established.publicKey == substitute.publicKey)
    }

    @Test
    fun `first contact with no key held STORES the incoming key (TOFU)`() {
        val incoming = E2EEncryption.generateKeyPair()
        // held == null -> policy allows the store (ChatViewModel.kt:5400 `previousPubkey != null` is false).
        assertTrue(
            "first-contact TOFU must store the incoming key",
            E2EEncryption.mayRemapConvIdForKexAck(heldKey = null, incomingPublicKey = incoming.publicKey),
        )
    }

    @Test
    fun `a same-key re-KEX is a safe no-op store`() {
        val held = E2EEncryption.generateKeyPair()
        // held == incoming -> policy allows (the legit re-KEX; the guard's `!=` branch is false).
        assertTrue(
            "a repeat of the already-held key must remain storable",
            E2EEncryption.mayRemapConvIdForKexAck(heldKey = held.publicKey, incomingPublicKey = held.publicKey),
        )
    }

    @Test
    fun `an established peer presenting a DIFFERENT validly self-signed key is REFUSED - stored key stays put`() {
        // We already hold the established peer's key; a self-signed substitute (proven verifiable above)
        // arrives with a DIFFERENT pubkey. The guard MUST refuse the overwrite.
        val established = E2EEncryption.generateKeyPair()
        val substitute = E2EEncryption.generateKeyPair()

        // RED-ON-REVERT ANCHOR: held != incoming (both real, distinct pubkeys) -> policy REFUSES the store.
        // Reverting the self-signed-substitution defense (e.g. mayRemapConvIdForKexAck -> always-true, or
        // dropping the inline `previousPubkey != peerPublicKey` guard's decision) flips this to true and
        // reddens the test: the established key would be silently overwritten by the attacker's key.
        assertFalse(
            "an established peer's key must NOT be auto-overwritten by a self-signed substitute",
            E2EEncryption.mayRemapConvIdForKexAck(
                heldKey = established.publicKey,
                incomingPublicKey = substitute.publicKey,
            ),
        )
    }
}
