package co.electriccoin.zcash.ui.screen.chat.util

import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ZC-1014 (the DESTROY-ADV-W finding F-W1): the seal of a Destroy record that keeps names.
 *
 * A record with keep=contacts carries one more line, `mac=`, made with the wallet's own Android Keystore key, the one
 * the encrypted stores of the wallet are made with ([KEY_ALIAS]; it is created with them and W3 deletes it with
 * everything else): AES-GCM over the SHA-256 of the record's five lines, with the nonce the Keystore's cipher makes. A
 * start that still finds the recovery-phrase store finishes such a Destroy only when the seal opens with that key and
 * matches; otherwise it deletes nothing and offers the user to run Destroy again, once ([takeRerunOffer]). A record
 * without keep keeps the first spec's form and rules (its record is bound to the phrase store by its store line).
 *
 * ZC1014-X1 (T7aD review of 19 Oct, finding 3): the check answers three ways ([Check]). A Keystore that throws or does not
 * answer in time cannot tell, and the start pass then keeps the record for the next start (its cannot-judge path), so a
 * cut Destroy is never dropped over a slow or failing Keystore; only a seal that is missing, malformed or does not match
 * under this wallet's key is "does not verify". The rerun offer is also kept on disk ([rerunMarkAt], a mark beside the
 * record), so it survives the end of the process before the chat list opens.
 *
 * The key lookup sits behind one private replaceable field; a JVM test gives an AES key held in memory.
 */
object DestroyRecordSeal {
    private const val TAG = "DestroyRecordSeal"

    /** The wallet's existing Keystore key (the encrypted stores' master key); no new alias. */
    const val KEY_ALIAS = "_androidx_security_master_key_"
    const val LINE_PREFIX = "mac="
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    private var recordKey: () -> SecretKey? = {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        store.getKey(KEY_ALIAS, null) as SecretKey?
    }

    private val rerunOffer = AtomicBoolean(false)

    /** ZC1014-X1: the offer's mark on disk, set by every start pass ([DestroyWipe]); null before one ran. */
    @Volatile
    private var rerunMark: File? = null

    /** ZC1014-X1: what a seal check answers. */
    enum class Check {
        VERIFIES,
        DOES_NOT_VERIFY,

        /** The Keystore threw or did not answer: nothing can be said about the seal. */
        CANNOT_TELL,
    }

    /** The seal of [body] (the record's five lines), or null when the key is missing or cannot be used. */
    fun seal(body: String): String? =
        try {
            val key = recordKey() ?: throw IllegalStateException("no key")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val sealed = cipher.doFinal(digest(body))
            val nonce = cipher.iv ?: throw IllegalStateException("no nonce")
            Base64.getEncoder().encodeToString(nonce + sealed)
        } catch (t: Throwable) {
            Log.w(TAG, "the record was not sealed: ${t.javaClass.simpleName}")
            null
        }

    /**
     * Whether [mac] is the seal of [body] under this wallet's key. No seal, one that is not a seal, no key, or a seal that
     * does not open or match: [Check.DOES_NOT_VERIFY]. A Keystore that throws: [Check.CANNOT_TELL] (ZC1014-X1).
     */
    @Suppress("ReturnCount")
    fun check(
        body: String,
        mac: String?,
    ): Check {
        val bytes =
            try {
                Base64.getDecoder().decode(mac ?: return Check.DOES_NOT_VERIFY)
            } catch (e: IllegalArgumentException) {
                return Check.DOES_NOT_VERIFY
            }
        if (bytes.size <= NONCE_SIZE) return Check.DOES_NOT_VERIFY
        return try {
            val key = recordKey() ?: return Check.DOES_NOT_VERIFY
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, NONCE_SIZE)))
            val opened = cipher.doFinal(bytes.copyOfRange(NONCE_SIZE, bytes.size))
            if (MessageDigest.isEqual(opened, digest(body))) Check.VERIFIES else Check.DOES_NOT_VERIFY
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "the record's seal does not verify")
            Check.DOES_NOT_VERIFY
        } catch (t: Throwable) {
            Log.w(TAG, "the record's seal cannot be checked: ${t.javaClass.simpleName}")
            Check.CANNOT_TELL
        }
    }

    /** ZC1014-X1: where this process's start pass keeps the offer's mark (beside the record). */
    fun rerunMarkAt(mark: File?) {
        rerunMark = mark
    }

    /** A keep record that did not verify was set aside: the app asks once whether to run Destroy again. */
    fun offerRerun() {
        rerunOffer.set(true)
    }

    /**
     * True once after [offerRerun], in this process or (ZC1014-X1) a later one: the mark on disk is taken with it. The
     * chat list takes it when it opens.
     */
    fun takeRerunOffer(): Boolean {
        val inMemory = rerunOffer.getAndSet(false)
        val mark = rerunMark
        val onDisk =
            try {
                val there = mark?.isFile == true
                if (there) mark.delete()
                there
            } catch (t: Throwable) {
                Log.w(TAG, "the rerun offer's mark: ${t.javaClass.simpleName}")
                false
            }
        return inMemory || onDisk
    }

    private fun digest(body: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
}
