package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R3-3 (1), round-2 report section 4 item 8 — a chat must keep decrypting after a Reset Encryption done by an app
 * version from before R2-4.
 *
 * That Reset only cleared the ratchet state and floors kept under the CURRENT conversation id. What an older id of
 * the same contact kept (the old root's counters, possibly thousands) stayed on the phone. Since R2-4 a ratchet
 * carries every conversation id's older counters into its root's floor, so the first send under the fresh root
 * jumped to the old id's counter: more than [E2ERatchet.MAX_SKIP] steps past what the contact has seen, so the
 * contact rejects it and the chat stops decrypting.
 *
 * The stored numbers alone cannot tell "counters of an older root" from "counters this root used under another id"
 * (the R2-4 case, where carrying them is what prevents a reused key and nonce). Our own messages on chain can: a
 * counter this root used under the older id is one of our own messages there that authenticates under this root.
 * So the older id's counters stay carried (never a reused counter) until the history pass settles them:
 * - one of our own messages under the older id authenticates under this root: they are kept for good;
 * - none does: they belong to an older root and stop counting for this one.
 */
class LegacyFloorAfterOldResetTest {
    private val oldRoot = ByteArray(32) { (it * 7 + 3).toByte() }
    private val root = ByteArray(32) { (it * 13 + 1).toByte() }
    private val convA = "CONVAAAA" // current: the id the old Reset cleared, used by the fresh root since
    private val convB = "CONVBBBB" // older: still holds the old root's counters
    private val myDirection: Byte = 0x00

    /** Messages encrypted under [rootKey] and [convId] at counters [from] until [until] (what is on chain). */
    private suspend fun onChain(
        rootKey: ByteArray,
        convId: String,
        from: Long,
        until: Long,
        isLower: Boolean = true,
    ): List<Ciphertext> {
        val scratch = InMemoryRatchetStateStore()
        scratch.saveCounterFloor(E2ERatchet.floorKeyFor(rootKey), if (isLower) 0x00 else 0x01, from)
        val ratchet = E2ERatchet(rootKey, convId, isLower = isLower, store = scratch)
        return (from until until).map { ratchet.encrypt("m$it".toByteArray()) }
    }

    /**
     * Our phone as the old version left it: the old root sent 5000 messages under [convB]; the Reset cleared
     * [convA] only, and the fresh root has sent 3 there since (counters 0..2).
     */
    private suspend fun phoneAfterOldReset(
        floors: MutableMap<String, Long> = mutableMapOf(),
    ): InMemoryRatchetStateStore {
        floors["$convB:$myDirection"] = OLD_ROOT_SENT
        floors["$convA:$myDirection"] = 3L
        return InMemoryRatchetStateStore(floors).apply {
            save(state(convB, nextA2B = OLD_ROOT_SENT))
            save(state(convA, nextA2B = 3L))
        }
    }

    private fun state(
        convId: String,
        nextA2B: Long,
    ) = RatchetConversationState(
        convId = convId,
        nextCounterA2B = nextA2B,
        nextCounterB2A = 0L,
        seenCountersA2B = emptySet(),
        seenCountersB2A = emptySet(),
    )

    private fun ours(
        store: RatchetStateStore,
        convId: String = convA,
    ) = E2ERatchet(root, convId, isLower = true, store = store, legacyFloorKeys = setOf(convA, convB), sendConvId = convA)

    /** The contact, on its own phone: it has read our counters 0..2 under the fresh root. */
    private suspend fun contact(): E2ERatchet {
        val contact = E2ERatchet(root, convA, isLower = false, store = InMemoryRatchetStateStore())
        onChain(root, convA, 0L, 3L).forEach { contact.decrypt(it) }
        return contact
    }

    @Test
    fun counters_the_old_root_left_under_an_older_id_do_not_push_the_next_send_past_the_contacts_window() =
        runTest {
            val phone = phoneAfterOldReset()
            val ratchet = ours(phone)
            val oldRootMessagesUnderB = onChain(oldRoot, convB, OLD_ROOT_SENT - 10, OLD_ROOT_SENT)

            assertEquals(setOf(convB), ratchet.unsettledLegacyJumps(), "the older id's counters must wait for the settlement")
            ratchet.settleLegacyJump(convB, oldRootMessagesUnderB)
            assertEquals(emptySet(), ratchet.unsettledLegacyJumps(), "none of our messages under $convB is this root's")

            val next = ratchet.encrypt("after the update".toByteArray())
            assertEquals(3L, next.counter, "the send jumped to the old root's counter")
            assertEquals("after the update", String(contact().decrypt(next)), "the contact cannot read the chat any more")
        }

    @Test
    fun a_message_read_before_the_settlement_does_not_make_the_old_counters_permanent() =
        runTest {
            val phone = phoneAfterOldReset()
            val ratchet = ours(phone)
            // The contact writes first; reading it loads the floors before the history pass settled them.
            ratchet.decrypt(onChain(root, convA, 0L, 1L, isLower = false).single())
            // The same contact's older id is read too (the processor kept for incoming messages under it).
            ours(phone, convId = convB).decrypt(onChain(root, convB, 0L, 1L, isLower = false).single())

            ratchet.settleLegacyJump(convB, onChain(oldRoot, convB, OLD_ROOT_SENT - 10, OLD_ROOT_SENT))

            assertEquals(3L, ratchet.encrypt("after the update".toByteArray()).counter, "the old root's counter was made permanent")
        }

    // Pins: hold before and after the fix (R2-4 (a) must stay true).

    @Test
    fun counters_this_root_used_under_the_older_id_are_still_never_reused() =
        runTest {
            val floors = mutableMapOf("$convB:$myDirection" to 10L, "$convA:$myDirection" to 3L)
            val phone = InMemoryRatchetStateStore(floors).apply { save(state(convA, nextA2B = 3L)) }
            val usedUnderB = onChain(root, convB, 3L, 10L)
            val ratchet = ours(phone)

            ratchet.settleLegacyJump(convB, usedUnderB)
            val next = ratchet.encrypt("m".toByteArray()).counter

            assertFalse(next in usedUnderB.map { it.counter }, "counter $next was already used under $convB: key and nonce reuse")
            assertTrue(next >= 10L, "counter $next is below what this root used under $convB")
            assertEquals(emptySet(), ratchet.unsettledLegacyJumps())
        }

    @Test
    fun until_the_settlement_the_older_ids_counters_are_carried() =
        runTest {
            val next = ours(phoneAfterOldReset()).encrypt("m".toByteArray()).counter
            assertTrue(next >= OLD_ROOT_SENT, "counter $next may have been used under $convB: nothing proved otherwise")
        }

    @Test
    fun an_older_state_seen_before_a_state_wipe_is_still_carried() =
        runTest {
            // 2.12.4 kept only the per-conversation state (no floor file); this root used counters 0..8 under B.
            val floors = mutableMapOf<String, Long>()
            val before = InMemoryRatchetStateStore(floors).apply { save(state(convB, nextA2B = 9L)) }
            ours(before).decrypt(onChain(root, convA, 0L, 1L, isLower = false).single())

            // The encrypted ratchet state is wiped (keystore self-heal); the plain floor file survives.
            val next = ours(InMemoryRatchetStateStore(floors)).encrypt("m".toByteArray()).counter

            assertTrue(next >= 9L, "counter $next was already used under $convB before the wipe")
        }

    private companion object {
        const val OLD_ROOT_SENT = 5000L
    }
}
