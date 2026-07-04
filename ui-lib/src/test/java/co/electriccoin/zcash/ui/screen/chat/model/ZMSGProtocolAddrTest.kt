package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + malformed rejection for the v4 ADDR (address-change notification) control in
 * [ZMSGProtocol]. Wire: ZMSG|v4|<convID>|ADDR|<old_sender_hash>|<new_address>|<signature>.
 *
 * Pure JVM: create/parse touch only [ZMSGProtocol.generateAddressHash] (MessageDigest). The
 * `parts.size < 5 → null` early-return happens BEFORE any android.util.Log call, so the malformed
 * path is also JVM-safe (Log is only reached inside the catch block, which these inputs don't hit).
 */
class ZMSGProtocolAddrTest {

    private val convId = "ABC12345" // 8 chars, all in CONV_ID_CHARS
    private val oldSender = "u1oldaddressforaddrchange000000000000000000000000000000000000000000"
    private val oldHash = ZMSGProtocol.generateAddressHash(oldSender)
    private val newAddress = "u1newaddressforaddrchange000000000000000000000000000000000000000000"
    private val signature = "c2lnbmF0dXJlYmFzZTY0" // base64-ish placeholder, no pipes

    @Test
    fun addr_roundTrip_recoversConvIdOldHashNewAddressSignature() {
        val wire = ZMSGProtocol.createV4ADDRMessage(convId, oldSender, newAddress, signature)
        assertTrue(ZMSGProtocol.isADDRMessage(wire))
        val parsed = ZMSGProtocol.parseADDRMessage(wire)
        assertEquals(convId, parsed?.conversationId)
        assertEquals(oldHash, parsed?.oldSenderHash)
        assertEquals(newAddress, parsed?.newAddress)
        assertEquals(signature, parsed?.signature)
    }

    @Test
    fun addr_exactWireShape() {
        val wire = ZMSGProtocol.createV4ADDRMessage(convId, oldSender, newAddress, signature)
        assertEquals("ZMSG|v4|$convId|ADDR|$oldHash|$newAddress|$signature", wire)
    }

    @Test
    fun addr_signatureContainingPipes_survivesBecauseSplitLimitIsFive() {
        // parseADDRMessage uses split("|", limit = 5) so a signature with pipes is kept intact.
        val sigWithPipes = "sig|with|pipes"
        val wire = ZMSGProtocol.createV4ADDRMessage(convId, oldSender, newAddress, sigWithPipes)
        val parsed = ZMSGProtocol.parseADDRMessage(wire)
        assertEquals(sigWithPipes, parsed?.signature)
        assertEquals(newAddress, parsed?.newAddress)
    }

    @Test
    fun addr_fewerThanFiveParts_returnsNull() {
        // ZMSG|v4|<convId>|ADDR|<hash>|<newAddr>  — missing the signature field → 4 parts → null
        val wire = "ZMSG|v4|$convId|ADDR|$oldHash|$newAddress"
        assertTrue(ZMSGProtocol.isADDRMessage(wire)) // detected as ADDR...
        assertNull(ZMSGProtocol.parseADDRMessage(wire)) // ...but rejected as malformed
    }

    @Test
    fun addr_nonAddrMessage_returnsNull() {
        assertNull(ZMSGProtocol.parseADDRMessage("ZMSG|v4|$convId|INIT|$newAddress|hello"))
        assertNull(ZMSGProtocol.parseADDRMessage("plain text"))
    }

    @Test
    fun isADDRMessage_onlyForAddrMarker() {
        val wire = ZMSGProtocol.createV4ADDRMessage(convId, oldSender, newAddress, signature)
        assertTrue(ZMSGProtocol.isADDRMessage(wire))
        assertFalse(ZMSGProtocol.isADDRMessage("ZMSG|v4|$convId|KEX|$oldHash|payload"))
        assertFalse(ZMSGProtocol.isADDRMessage("ZMSG|v3|INIT|$newAddress|hi"))
    }
}
