package co.electriccoin.zcash.ui.screen.chat.model

import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

/**
 * Instrumented tests for [ZMSGGroupProtocol]'s AES-256-GCM group-message encryption.
 *
 * These run as androidTest ON PURPOSE: encryptMessage/decryptMessage/encodeGroupKey/decodeGroupKey
 * use android.util.Base64, which the JVM unit-test set stubs to return defaults — a real device (or
 * Robolectric) is required to exercise the actual Base64 codec and the GCM auth-tag behavior.
 *
 * The security property under test: a message decrypts ONLY with the exact key it was sealed under;
 * the wrong key, a tampered ciphertext, or a swapped nonce must return null (NEVER throw), because the
 * decrypt path is called on every inbound memo and a thrown exception would crash the receive loop.
 *
 * The pure-JSON payload parsing + signing lives in the JVM sibling [GroupProtocolPayloadTest].
 */
class GroupMessageCryptoTest {

    @Test
    @SmallTest
    fun encryptDecrypt_roundTripsWithTheSameKey() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        val plaintext = "gm to the group 🔐 你好"
        val enc = ZMSGGroupProtocol.encryptMessage(plaintext, key)
        val dec = ZMSGGroupProtocol.decryptMessage(enc.nonce, enc.ciphertext, key)
        assertThat(dec, equalTo(plaintext))
    }

    @Test
    @SmallTest
    fun encryptDecrypt_emptyMessageRoundTrips() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        val enc = ZMSGGroupProtocol.encryptMessage("", key)
        assertThat(ZMSGGroupProtocol.decryptMessage(enc.nonce, enc.ciphertext, key), equalTo(""))
    }

    @Test
    @SmallTest
    fun encrypt_producesDistinctCiphertextEachTime() {
        // Random nonce per call → the same plaintext must not encrypt to the same ciphertext.
        val key = ZMSGGroupProtocol.generateGroupKey()
        val a = ZMSGGroupProtocol.encryptMessage("same", key)
        val b = ZMSGGroupProtocol.encryptMessage("same", key)
        assertThat(a.nonce, not(equalTo(b.nonce)))
        assertThat(a.ciphertext, not(equalTo(b.ciphertext)))
        // Both still decrypt to the original.
        assertThat(ZMSGGroupProtocol.decryptMessage(a.nonce, a.ciphertext, key), equalTo("same"))
        assertThat(ZMSGGroupProtocol.decryptMessage(b.nonce, b.ciphertext, key), equalTo("same"))
    }

    @Test
    @SmallTest
    fun decrypt_withWrongKey_returnsNullNotThrows() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        val wrongKey = ZMSGGroupProtocol.generateGroupKey()
        val enc = ZMSGGroupProtocol.encryptMessage("secret", key)
        // The GCM tag fails to authenticate under the wrong key → must return null, never crash.
        assertThat(ZMSGGroupProtocol.decryptMessage(enc.nonce, enc.ciphertext, wrongKey), nullValue())
    }

    @Test
    @SmallTest
    fun decrypt_withTamperedCiphertext_returnsNull() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        val enc = ZMSGGroupProtocol.encryptMessage("secret", key)
        // Flip a character in the base64 ciphertext.
        val flippedChar = if (enc.ciphertext[0] == 'A') 'B' else 'A'
        val tampered = flippedChar + enc.ciphertext.substring(1)
        assertThat(ZMSGGroupProtocol.decryptMessage(enc.nonce, tampered, key), nullValue())
    }

    @Test
    @SmallTest
    fun decrypt_withSwappedNonce_returnsNull() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        val a = ZMSGGroupProtocol.encryptMessage("message A", key)
        val b = ZMSGGroupProtocol.encryptMessage("message B", key)
        // Using B's nonce with A's ciphertext breaks GCM authentication → null.
        assertThat(ZMSGGroupProtocol.decryptMessage(b.nonce, a.ciphertext, key), nullValue())
    }

    @Test
    @SmallTest
    fun decrypt_withGarbageBase64_returnsNullNotThrows() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        // Non-base64 / structurally invalid inputs must be swallowed to null.
        assertThat(ZMSGGroupProtocol.decryptMessage("!!!not-base64!!!", "###", key), nullValue())
    }

    @Test
    @SmallTest
    fun groupKey_encodeDecode_roundTrips() {
        val key = ZMSGGroupProtocol.generateGroupKey()
        val encoded = ZMSGGroupProtocol.encodeGroupKey(key)
        val decoded = ZMSGGroupProtocol.decodeGroupKey(encoded)
        assertThat(decoded, equalTo(key))
        // A message sealed with the original key decrypts with the round-tripped key.
        val enc = ZMSGGroupProtocol.encryptMessage("keyed", key)
        assertThat(ZMSGGroupProtocol.decryptMessage(enc.nonce, enc.ciphertext, decoded), equalTo("keyed"))
    }

    @Test
    @SmallTest
    fun generateGroupKey_is256Bit() {
        assertThat(ZMSGGroupProtocol.generateGroupKey().size, equalTo(32))
    }

    @Test
    @SmallTest
    fun fullGroupMsg_buildEncryptParseDecrypt_endToEnd() {
        // Build a real GROUP_MSG memo, parse its payload back, and decrypt the carried ciphertext —
        // the complete on-wire path a member exercises for every inbound group message.
        val key = ZMSGGroupProtocol.generateGroupKey()
        val memo = ZMSGGroupProtocol.createGroupMsgMessage(
            groupId = "gid1",
            seq = 7L,
            epoch = 2,
            senderAddress = "u1sender",
            plaintext = "end to end",
            groupKey = key
        )
        assertThat(ZMSGGroupProtocol.parseMessageType(memo), equalTo(GroupMessageType.GROUP_MSG))
        val payload = ZMSGGroupProtocol.parsePayload(memo)
        assertThat(payload, notNullValue())
        val parsed = ZMSGGroupProtocol.parseGroupMsgPayload(payload!!)
        assertThat(parsed, notNullValue())
        assertThat(parsed!!.seq, equalTo(7L))
        assertThat(parsed.epoch, equalTo(2))
        assertThat(parsed.sender, equalTo("u1sender"))
        val decrypted = ZMSGGroupProtocol.decryptMessage(parsed.nonce, parsed.ciphertext, key)
        assertThat(decrypted, equalTo("end to end"))
        // And a member on the wrong epoch key gets null (the "missing key" case), not a crash.
        val otherEpochKey = ZMSGGroupProtocol.generateGroupKey()
        assertThat(ZMSGGroupProtocol.decryptMessage(parsed.nonce, parsed.ciphertext, otherEpochKey), nullValue())
    }
}
