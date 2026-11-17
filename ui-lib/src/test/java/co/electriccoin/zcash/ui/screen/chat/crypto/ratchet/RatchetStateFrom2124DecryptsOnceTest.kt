package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Bridge 1: an encrypted chat set up on 2.12.4 keeps working after the in-place upgrade.
 *
 * Fixtures are in the 2.12.4 format:
 * - the ratchet state blob uses exactly the keys 2.12.4's EncryptedPrefsRatchetStateStore.toJson
 *   wrote (git show v2.12.4:.../EncryptedPrefsRatchetStateStore.kt), and the counter-floor file is
 *   EMPTY, because 2.12.4 had none;
 * - the KEX state is a 2.12.4 single-KEX chat: only the legacy KEX / KEXACK txid scalars, no txid sets;
 * - [ROOT_HEX], [PEER_WIRE_C4] and [OUR_WIRE_C5] were produced OUTSIDE this code base by an
 *   independent re-implementation of the v2.12.4 ratchet
 *   (zchat-evidence/round2-20260921/bridge1/gen_2124_ratchet_vectors.py), so a drift in HEAD's
 *   derivation, nonce, AAD or wire format fails here.
 *
 * All keys, txids and texts are test values.
 */
class RatchetStateFrom2124DecryptsOnceTest {
    private fun rootFrom2124KexState(): ByteArray =
        E2ERatchet.deriveRatchetRoot(
            ecdhSharedSecret = ECDH,
            psk = null,
            kexTxid = E2ERatchet.canonicalTxidMaterial(emptySet(), KEX_LEGACY),
            kexAckTxid = E2ERatchet.canonicalTxidMaterial(emptySet(), KEXACK_LEGACY),
        )

    @Test
    fun root_from_2124_kex_state_is_unchanged_in_head() {
        assertEquals(ROOT_HEX, rootFrom2124KexState().hex())
    }

    @Test
    fun state_2124_loads_in_head_and_a_new_message_decrypts_exactly_once() =
        runTest {
            val statePrefs = FakePrefs(mutableMapOf("ratchet_state_$CONV" to BLOB_2124))
            val floorPrefs = FakePrefs(mutableMapOf()) // 2.12.4 had no floor file
            val store = EncryptedPrefsRatchetStateStore(statePrefs, floorPrefs)

            val loaded = store.load(CONV)!!
            assertEquals(5L, loaded.nextCounterA2B)
            assertEquals(3L, loaded.maxSeenB2A)

            val processor = E2EMessageProcessor(rootFrom2124KexState(), CONV, isLower = true, store = store)
            assertEquals("first message after the upgrade", processor.decryptIncoming(PEER_WIRE_C4))

            // The same message delivered again (second relay, rescan in the same session) is refused.
            assertFailsWith<ReplayDetectedException> { processor.decryptIncoming(PEER_WIRE_C4) }

            // The accepted counter is persisted, in the 2.12.4 state and in the new floor file.
            assertEquals(4L, store.load(CONV)!!.maxSeenB2A)
            assertEquals(4L, store.loadCounterFloor(CONV, 0x01))
            // Our own send counter is untouched by a receive.
            assertEquals(5L, store.load(CONV)!!.nextCounterA2B)
        }

    @Test
    fun first_send_after_the_upgrade_continues_the_2124_counter_and_a_2124_peer_can_read_it() =
        runTest {
            val store =
                EncryptedPrefsRatchetStateStore(
                    FakePrefs(mutableMapOf("ratchet_state_$CONV" to BLOB_2124)),
                    FakePrefs(mutableMapOf()),
                )
            val processor = E2EMessageProcessor(rootFrom2124KexState(), CONV, isLower = true, store = store)
            val wire = processor.encryptOutgoing("reply from the upgraded phone")

            // Counter 5 (never 0: no nonce reuse), byte-identical to what 2.12.4 itself would emit, so a
            // peer still on 2.12.4 decrypts it with its unchanged code.
            assertEquals(OUR_WIRE_C5, wire)
            assertEquals(6L, store.load(CONV)!!.nextCounterA2B)
            assertEquals(6L, store.loadCounterFloor(CONV, 0x00))
        }

    @Test
    fun blob_written_by_head_keeps_every_key_2124_reads() =
        runTest {
            // Rollback safety: HEAD must still write the keys 2.12.4's fromJson requires.
            val backing = mutableMapOf<String, Any?>("ratchet_state_$CONV" to BLOB_2124)
            val store = EncryptedPrefsRatchetStateStore(FakePrefs(backing), FakePrefs(mutableMapOf()))
            E2EMessageProcessor(rootFrom2124KexState(), CONV, isLower = true, store = store)
                .decryptIncoming(PEER_WIRE_C4)
            val written = org.json.JSONObject(backing["ratchet_state_$CONV"] as String)
            for (key in listOf("convId", "nextA2B", "nextB2A", "seenA2B", "seenB2A", "maxSeenA2B", "maxSeenB2A")) {
                assertTrue(written.has(key), "HEAD dropped 2.12.4 key $key")
            }
        }

    private class FakePrefs(
        private val backing: MutableMap<String, Any?>
    ) : SharedPreferences {
        override fun getString(key: String, defValue: String?): String? = backing[key] as? String ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor(backing)

        override fun getAll(): MutableMap<String, *> = backing

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String, defValue: Int): Int = defValue

        override fun getLong(key: String, defValue: Long): Long = backing[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue

        override fun contains(key: String): Boolean = backing.containsKey(key)

        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeEditor(
        private val backing: MutableMap<String, Any?>
    ) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { backing[key] = value }

        override fun commit(): Boolean = true

        override fun apply() = Unit

        override fun remove(key: String): SharedPreferences.Editor = apply { backing.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { backing.clear() }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { backing[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
    }

    private companion object {
        const val CONV = "C2124AAA"
        val ECDH = ByteArray(32) { (0x21 + it).toByte() }
        val KEX_LEGACY = "a".repeat(64)
        val KEXACK_LEGACY = "b".repeat(64)

        // We sent counters 0..4 (next = 5) and accepted the peer's 0..3 before the upgrade.
        const val BLOB_2124 =
            """{"convId":"C2124AAA","nextA2B":5,"nextB2A":0,"seenA2B":[],"seenB2A":[],"maxSeenA2B":0,"maxSeenB2A":3}"""

        const val ROOT_HEX = "3e6425c29769f416f436243f8ddea0aeb3c0b1580fe8ca37deef1b663cc4225f"
        const val PEER_WIRE_C4 =
            "E2E1:01:0000000000000004:AyNBLhm+enQtGe0DtX1mYcwH6No4QK9dv8F0Q6TL2d5bpO1fXodYfsA8SDpdMVM="
        const val OUR_WIRE_C5 =
            "E2E1:00:0000000000000005:5HFTmTFXb1Ic0VBJVMgFqovXSl2TOoF5+XNfF7m7EG0+ZhcrQukqpV+i0beN"

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
