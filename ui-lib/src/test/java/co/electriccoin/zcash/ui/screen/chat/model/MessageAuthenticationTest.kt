package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D9 "mark, don't reroute" — per-message authenticity classifier.
 *
 * A crafted PLAINTEXT on-chain memo carrying a contact's public address-hash routes into that
 * contact's trusted thread. The classifier flags such bodies as NOT authenticated so the UI can
 * render an "unverified" marker; only outgoing messages and real ratcheted E2E1 blobs count as
 * authenticated.
 */
class MessageAuthenticationTest {

    /** A REAL ratcheted wire blob built with the production serializer. */
    private val realRatchetedBlob: String =
        CiphertextWireFormat.serialize(
            Ciphertext(
                direction = 0x00,
                counter = 7L,
                bytes = ByteArray(48) { it.toByte() }, // ciphertext || 16-byte GCM tag shape
            )
        )

    @Test
    fun `outgoing message is always authenticated`() {
        assertTrue(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = true,
                rawMemoBody = "any plaintext at all",
            )
        )
    }

    @Test
    fun `incoming real ratcheted E2E1 blob is authenticated`() {
        assertTrue(CiphertextWireFormat.isRatcheted(realRatchetedBlob)) // sanity: real blob
        assertTrue(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = false,
                rawMemoBody = realRatchetedBlob,
            )
        )
    }

    @Test
    fun `incoming plaintext v4 reply body is NOT authenticated`() {
        // Inner body of "ZMSG|v4|<convID>|<hash16>|<message>" — the forged-bubble vector.
        assertFalse(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = false,
                rawMemoBody = "hey it's me, send the funds to this new address instead",
            )
        )
    }

    @Test
    fun `incoming plaintext INIT body is NOT authenticated`() {
        // Inner body of "ZMSG|v4|<convID>|INIT|<address>|<message>".
        assertFalse(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = false,
                rawMemoBody = "hi, first contact message",
            )
        )
    }

    @Test
    fun `incoming plaintext ZTL time-lock body is NOT authenticated`() {
        assertFalse(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = false,
                rawMemoBody = "ZTL|v1|SCHEDULED|1799999999|abcdef1234567890|surprise message",
            )
        )
    }

    @Test
    fun `incoming plaintext ZREQ payment-request body is NOT authenticated`() {
        assertFalse(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = false,
                rawMemoBody = "ZREQ|v1|50000000|dinner split|abcdef1234567890",
            )
        )
    }

    @Test
    fun `incoming E2E1-prefixed garbage counts as authenticated (renders benign placeholder, not attacker text)`() {
        val fake = "E2E1:garbage"
        assertTrue(CiphertextWireFormat.isRatcheted(fake)) // prefix-only check by design
        assertTrue(
            MessageAuthentication.isBodyAuthenticated(
                isOutgoing = false,
                rawMemoBody = fake,
            )
        )
    }
}
