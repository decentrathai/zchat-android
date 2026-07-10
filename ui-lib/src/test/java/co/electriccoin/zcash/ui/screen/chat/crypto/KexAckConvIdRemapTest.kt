package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MED-A: authorization for remapping the OUTBOUND conversation-id off an incoming KEXACK. A KEXACK is
 * only SELF-signed, so it can't authenticate an already-established peer; [E2EEncryption.mayRemapConvIdForKexAck]
 * therefore permits a remap ONLY for genuine first contact (no key held) or when the incoming key
 * MATCHES the key already held (the legit #205 UA-drift re-KEX). A DIFFERENT held key must be refused,
 * so an attacker-forgeable KEXACK can't overwrite peer→convId. Pure decision (no Android deps) → src/test.
 */
class KexAckConvIdRemapTest {

    @Test
    fun `first contact (no key held) is allowed to map the convId`() {
        assertTrue(E2EEncryption.mayRemapConvIdForKexAck(heldKey = null, incomingPublicKey = "keyA"))
    }

    @Test
    fun `re-KEX with the SAME held key is allowed (legit #205 UA-drift recovery)`() {
        assertTrue(E2EEncryption.mayRemapConvIdForKexAck(heldKey = "keyA", incomingPublicKey = "keyA"))
    }

    @Test
    fun `a KEXACK carrying a DIFFERENT key for an established peer is REFUSED (attack)`() {
        // An established peer P (we hold keyA); a self-signed KEXACK claims P's address but a fresh keyB
        // + unmapped convId. Remapping would overwrite peer:P→attacker-convId — must be refused.
        assertFalse(E2EEncryption.mayRemapConvIdForKexAck(heldKey = "keyA", incomingPublicKey = "keyB"))
    }

    @Test
    fun `key comparison is exact — a near-miss substitution is refused`() {
        assertFalse(E2EEncryption.mayRemapConvIdForKexAck(heldKey = "keyABC", incomingPublicKey = "keyAB"))
        assertFalse(E2EEncryption.mayRemapConvIdForKexAck(heldKey = "keyAB", incomingPublicKey = "keyABC"))
    }

    @Test
    fun `an empty held key is still a concrete key — mismatch is refused, exact match allowed`() {
        // "" is a stored value, distinct from null (= no key). It must not be treated as first-contact.
        assertFalse(E2EEncryption.mayRemapConvIdForKexAck(heldKey = "", incomingPublicKey = "keyA"))
        assertTrue(E2EEncryption.mayRemapConvIdForKexAck(heldKey = "", incomingPublicKey = ""))
    }
}
