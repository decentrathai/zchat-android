package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R2-4 (a) — the counter floor must follow the ratchet root, not the conversation id.
 *
 * The message key and the AES-GCM nonce depend only on (root, direction, counter); the conversation id is only
 * part of the AAD. The send counter and its durable floor were kept per conversation id, and one peer can have
 * two conversation ids on this device (ours and the one its KEX/KEXACK carried, or a new one after a restore).
 * A ratchet under the second id then started at counter 0 under the SAME root and reused a (key, nonce) pair
 * already used under the first id: an AES-GCM break (keystream and authentication key exposed).
 *
 * The floor is now kept under a hash of the root (never the raw key), older per-conversation floors are
 * migrated into it by taking the maximum, and a new root (Reset Encryption) starts fresh.
 */
class RatchetFloorKeyedByRootTest {
    private val root = ByteArray(32) { (it * 11 + 5).toByte() }
    private val otherRoot = ByteArray(32) { (it * 3 + 101).toByte() }
    private val convA = "CONVAAAA"
    private val convB = "CONVBBBB"
    private val myDirection: Byte = 0x00

    private fun ratchet(
        store: RatchetStateStore,
        convId: String,
        rootKey: ByteArray = root,
        legacyFloorKeys: Set<String> = emptySet(),
    ) = E2ERatchet(rootKey, convId, isLower = true, store = store, legacyFloorKeys = legacyFloorKeys)

    @Test
    fun a_send_under_a_second_conversation_id_for_the_same_root_never_reuses_a_counter() =
        runTest {
            val floors = mutableMapOf<String, Long>()
            val store = InMemoryRatchetStateStore(floors)
            val underA = (0 until 5).map { ratchet(store, convA).encrypt("message $it under A".toByteArray()).counter }
            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), underA, "fixture: counters 0..4 used under the first id")

            // The same store, peer now linked to the second id: no state exists for it yet.
            val sameStore = ratchet(store, convB).encrypt("first under B".toByteArray()).counter
            assertTrue(sameStore > underA.max(), "counter $sameStore was already used under $convA: key and nonce reuse")

            // The ratchet state wiped (keystore self-heal), the plain floor file kept.
            val wiped = InMemoryRatchetStateStore(floors)
            val afterWipe = ratchet(wiped, convB).encrypt("after the wipe".toByteArray()).counter
            assertTrue(afterWipe > sameStore, "counter $afterWipe was already used: key and nonce reuse after a wipe")
        }

    @Test
    fun after_a_restore_the_floor_raised_from_unread_own_messages_covers_a_send_under_another_conversation_id() =
        runTest {
            val oldDevice = ratchet(InMemoryRatchetStateStore(), convA)
            val onChain = (0 until 4).map { oldDevice.encrypt("old message $it".toByteArray()) }

            // Restore: empty state and floors. The scan reads counters 0..1; 2..3 stay unreadable.
            val restoredStore = InMemoryRatchetStateStore()
            val restoredA = ratchet(restoredStore, convA)
            onChain.take(2).forEach { restoredA.decrypt(it) }
            assertTrue(
                RestoreKexPolicy.floorSendCounterAboveUnreadOwnEnvelopes(
                    restoredStore,
                    restoredA.floorKey,
                    myDirection,
                    onChain.drop(2),
                ),
                "fixture: the policy floors the send counter above the unread messages",
            )

            // The restored device links the peer to a second conversation id and sends.
            val next = ratchet(restoredStore, convB).encrypt("first after the restore".toByteArray()).counter
            val used = onChain.map { it.counter }.toSet()
            assertFalse(next in used, "counter $next was already used on chain: key and nonce reuse")
            assertEquals(4L, next)
        }

    @Test
    fun a_new_root_after_reset_encryption_starts_at_counter_zero() =
        runTest {
            val store = InMemoryRatchetStateStore()
            repeat(3) { ratchet(store, convA).encrypt("old root $it".toByteArray()) }
            store.delete(convA) // Reset Encryption

            assertEquals(0L, ratchet(store, convA, rootKey = otherRoot).encrypt("new root".toByteArray()).counter)
            assertEquals(0L, ratchet(store, convB, rootKey = otherRoot.copyOf().also { it[0] = 42 }).encrypt("x".toByteArray()).counter)
        }

    @Test
    fun floors_kept_per_conversation_id_before_the_change_still_hold_for_the_same_root() =
        runTest {
            // A floor written by the previous version under the first conversation id ("<convId>:<direction>").
            val floors = mutableMapOf("$convA:$myDirection" to 7L)
            val store = InMemoryRatchetStateStore(floors)
            val next = ratchet(store, convB, legacyFloorKeys = setOf(convA, convB)).encrypt("m".toByteArray()).counter
            assertTrue(next >= 7L, "counter $next is below the floor kept under $convA: key and nonce reuse")

            // Migrated into the root's floor: a later ratchet that no longer lists the old id still resumes above it.
            val later = ratchet(store, "CONVCCCC").encrypt("later".toByteArray()).counter
            assertTrue(later > next, "counter $later was already used: the migrated floor was not kept")
        }

    @Test
    fun a_ratchet_state_from_before_the_floor_file_existed_still_holds_for_the_same_root() =
        runTest {
            // 2.12.4 kept only the per-conversation state (no floor file).
            val store = InMemoryRatchetStateStore()
            store.save(
                RatchetConversationState(
                    convId = convA,
                    nextCounterA2B = 9L,
                    nextCounterB2A = 0L,
                    seenCountersA2B = emptySet(),
                    seenCountersB2A = emptySet(),
                ),
            )
            val next = ratchet(store, convB, legacyFloorKeys = setOf(convA, convB)).encrypt("m".toByteArray()).counter
            assertTrue(next >= 9L, "counter $next was already used under $convA before the floor file existed")
        }

    @Test
    fun the_floor_key_follows_the_root_and_is_not_the_raw_key() {
        val store = InMemoryRatchetStateStore()
        val a = ratchet(store, convA).floorKey
        assertEquals(a, ratchet(store, convB).floorKey, "one root, one floor, whatever the conversation id")
        assertNotEquals(a, ratchet(store, convA, rootKey = otherRoot).floorKey, "a new root has its own floor")
        val hex = root.joinToString("") { "%02x".format(it) }
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(root)
        assertFalse(a.contains(hex, ignoreCase = true) || a.contains(b64), "the floor key must not contain the root key")
    }

    @Test
    fun two_conversation_ids_sending_at_once_for_the_same_root_never_share_a_counter() =
        runBlocking {
            val store = InMemoryRatchetStateStore()
            val a = ratchet(store, convA)
            val b = ratchet(store, convB)
            val counters =
                (0 until 40)
                    .map { i ->
                        async(Dispatchers.Default) { (if (i % 2 == 0) a else b).encrypt("m$i".toByteArray()).counter }
                    }.awaitAll()
            assertEquals(counters.size, counters.toSet().size, "a counter was used twice under one root: $counters")
        }
}
