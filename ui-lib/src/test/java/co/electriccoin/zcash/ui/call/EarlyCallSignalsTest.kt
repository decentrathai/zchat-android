package co.electriccoin.zcash.ui.call

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-409 (fix-all 2.13.0, train T5b; t5b-add SPECS §4.4): the callee keeps the call signals that arrive before their
 * RING.
 *
 * A caller sends RING, then OFFER and its ICE candidates, as separate messages published concurrently to three relays,
 * so the callee can get the OFFER (or ICE, or a HANGUP) before the RING. VoiceCallManager dropped those, the callee
 * had nothing to answer and the call ended at the 45 s setup timeout (4 of about 20 calls on the T5 phones).
 *
 * This is the pure store that holds them: `co.electriccoin.zcash.ui.call.EarlyCallSignals(maxIcePerCall,
 * maxIcePayloadChars)` with `hold(sender, envelope, nowMs): Boolean` and `take(sender, callId, nowMs): Held?`
 * (`offer`, `ice`, `hungUp`). The class is reached by name so this file compiles before the class exists.
 * CallSignalsBeforeTheRingAreKeptWiringTest checks how VoiceCallManager uses it.
 */
class EarlyCallSignalsTest {
    @Test
    fun `an OFFER held before the RING is the one taken at the RING`() {
        val store = Store()
        val offer = offer(CALL_A, "sdp-1")
        assertTrue(store.hold(ALICE, offer, T0), "an OFFER for a call that has not rung must be held")
        val held = assertNotNull(store.take(ALICE, CALL_A, T0 + 300), "the RING must find the held OFFER")
        assertEquals(offer, held.offer)
        assertEquals(emptyList<String>(), held.ice)
        assertFalse(held.hungUp)
    }

    @Test
    fun `the first OFFER is kept`() {
        val store = Store()
        store.hold(ALICE, offer(CALL_A, "sdp-first"), T0)
        store.hold(ALICE, offer(CALL_A, "sdp-second"), T0 + 10)
        assertEquals("sdp-first", store.take(ALICE, CALL_A, T0 + 20)?.offer?.payload)
    }

    @Test
    fun `ICE before the RING is kept in order and the 65th evicts the oldest`() {
        val store = Store(maxIcePerCall = 64)
        (1..64).forEach { assertTrue(store.hold(ALICE, ice(CALL_A, "0|0|candidate:$it"), T0 + it)) }
        store.hold(ALICE, offer(CALL_A, "sdp"), T0 + 70)
        store.hold(ALICE, ice(CALL_A, "0|0|candidate:65"), T0 + 80)
        val held = assertNotNull(store.take(ALICE, CALL_A, T0 + 90))
        assertEquals((2..65).map { "0|0|candidate:$it" }, held.ice, "64 kept, in arrival order, the oldest evicted")
        assertEquals("sdp", held.offer?.payload, "ICE never pushes the OFFER out")
    }

    @Test
    fun `an ICE payload the call manager would refuse is not kept`() {
        val store = Store(maxIcePayloadChars = 1_024)
        assertFalse(store.hold(ALICE, ice(CALL_A, "0|0|candidate:" + "x".repeat(1_024)), T0), "longer than the limit")
        assertNull(store.take(ALICE, CALL_A, T0 + 1), "a refused signal must not open an entry")
        val longest = "0|0|candidate:" + "x".repeat(1_024 - 14)
        assertTrue(store.hold(ALICE, ice(CALL_A, longest), T0 + 2), "exactly at the limit is kept")
        assertEquals(listOf(longest), store.take(ALICE, CALL_A, T0 + 3)?.ice)
    }

    @Test
    fun `a HANGUP before the RING marks the call hung up`() {
        val store = Store()
        store.hold(ALICE, offer(CALL_A, "sdp"), T0)
        assertTrue(store.hold(ALICE, hangup(CALL_A), T0 + 100), "a HANGUP for a call that has not rung must be held")
        val held = assertNotNull(store.take(ALICE, CALL_A, T0 + 200))
        assertTrue(held.hungUp, "the RING of a call its caller already ended must find it hung up")

        assertTrue(store.hold(ALICE, hangup(CALL_B), T0 + 300))
        assertTrue(assertNotNull(store.take(ALICE, CALL_B, T0 + 400)).hungUp, "a HANGUP alone is enough")
    }

    @Test
    fun `another sender or another call gets nothing`() {
        val store = Store()
        store.hold(ALICE, offer(CALL_A, "sdp"), T0)
        store.hold(ALICE, ice(CALL_A, "0|0|candidate:1"), T0)
        assertNull(store.take(BOB, CALL_A, T0 + 1), "the same callId from another sender is another call")
        assertNull(store.take(ALICE, CALL_B, T0 + 1), "another callId of the same sender is another call")
        val held = assertNotNull(store.take(ALICE, CALL_A, T0 + 2), "the misses above must not remove the entry")
        assertEquals("sdp", held.offer?.payload)
        assertEquals(listOf("0|0|candidate:1"), held.ice)
    }

    @Test
    fun `a call is dropped 60 s after its first signal`() {
        assertEquals(60_000L, keepMs(), "EarlyCallSignals.KEEP_MS (the setup timeout is 45 s)")
        val kept = Store()
        kept.hold(ALICE, offer(CALL_A, "sdp"), T0)
        assertNotNull(kept.take(ALICE, CALL_A, T0 + 59_999), "still held just before 60 s")

        val gone = Store()
        gone.hold(ALICE, offer(CALL_A, "sdp"), T0)
        gone.hold(ALICE, ice(CALL_A, "0|0|candidate:1"), T0 + 50_000)
        assertNull(gone.take(ALICE, CALL_A, T0 + 60_000), "60 s after the FIRST signal, whatever came later")

        val again = Store()
        again.hold(ALICE, offer(CALL_A, "sdp-old"), T0)
        again.hold(ALICE, ice(CALL_A, "0|0|candidate:late"), T0 + 61_000)
        val held = assertNotNull(again.take(ALICE, CALL_A, T0 + 61_001), "a signal after the drop starts a new entry")
        assertNull(held.offer, "the dropped OFFER must not come back")
        assertEquals(listOf("0|0|candidate:late"), held.ice)
    }

    @Test
    fun `a fifth call evicts the oldest`() {
        assertEquals(4, maxCalls(), "EarlyCallSignals.MAX_CALLS")
        val store = Store()
        val calls = (1..5).map { callId(it) }
        calls.forEachIndexed { i, id -> store.hold(ALICE, offer(id, "sdp-$i"), T0 + i) }
        assertNull(store.take(ALICE, calls[0], T0 + 10), "the oldest of five must be gone")
        (1..4).forEach { i ->
            assertEquals("sdp-$i", store.take(ALICE, calls[i], T0 + 10)?.offer?.payload, "call ${i + 1} must be held")
        }
    }

    @Test
    fun `a call that is 60 s old does not count against the four`() {
        val store = Store()
        (1..4).forEach { store.hold(ALICE, offer(callId(it), "old-$it"), T0) }
        store.hold(BOB, offer(CALL_A, "new-1"), T0 + 60_000)
        store.hold(BOB, offer(CALL_B, "new-2"), T0 + 60_001)
        assertEquals("new-1", store.take(BOB, CALL_A, T0 + 60_002)?.offer?.payload)
        assertEquals("new-2", store.take(BOB, CALL_B, T0 + 60_002)?.offer?.payload)
    }

    @Test
    fun `take removes the entry`() {
        val store = Store()
        store.hold(ALICE, offer(CALL_A, "sdp"), T0)
        assertNotNull(store.take(ALICE, CALL_A, T0 + 1))
        assertNull(store.take(ALICE, CALL_A, T0 + 2), "a second RING for the same call must find nothing")
    }

    @Test
    fun `a RING or an ANSWER is never held`() {
        val store = Store()
        assertFalse(store.hold(ALICE, CallSignalEnvelope(CALL_A, CallSignalType.RING, "audio"), T0))
        assertFalse(store.hold(ALICE, CallSignalEnvelope(CALL_A, CallSignalType.ANSWER, "sdp"), T0))
        assertNull(store.take(ALICE, CALL_A, T0 + 1))
    }

    /** What `take` returned, read through the getters of EarlyCallSignals.Held. */
    private data class Held(
        val offer: CallSignalEnvelope?,
        val ice: List<String>,
        val hungUp: Boolean,
    )

    /** EarlyCallSignals, reached by name. */
    private class Store(
        maxIcePerCall: Int = 64,
        maxIcePayloadChars: Int = 1_024,
    ) {
        private val type = storeClass()
        private val instance: Any =
            unwrap { type.getConstructor(INT, INT).newInstance(maxIcePerCall, maxIcePayloadChars) }

        fun hold(
            sender: String,
            envelope: CallSignalEnvelope,
            nowMs: Long,
        ): Boolean =
            unwrap {
                type
                    .getMethod("hold", String::class.java, CallSignalEnvelope::class.java, LONG)
                    .invoke(instance, sender, envelope, nowMs)
            } as Boolean

        @Suppress("UNCHECKED_CAST")
        fun take(
            sender: String,
            callId: String,
            nowMs: Long,
        ): Held? {
            val held =
                unwrap {
                    type
                        .getMethod("take", String::class.java, String::class.java, LONG)
                        .invoke(instance, sender, callId, nowMs)
                } ?: return null
            val heldType = held.javaClass
            return Held(
                offer = heldType.getMethod("getOffer").invoke(held) as CallSignalEnvelope?,
                ice = heldType.getMethod("getIce").invoke(held) as List<String>,
                hungUp = heldType.getMethod("getHungUp").invoke(held) as Boolean,
            )
        }
    }

    private companion object {
        const val STORE = "co.electriccoin.zcash.ui.call.EarlyCallSignals"
        const val ALICE = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        const val BOB = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
        const val T0 = 1_000_000L
        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val LONG: Class<*> = Long::class.javaPrimitiveType!!
        val CALL_A = callId(0xa)
        val CALL_B = callId(0xb)

        fun callId(n: Int): String = "%032x".format(n)

        fun offer(
            callId: String,
            sdp: String,
        ) = CallSignalEnvelope(callId, CallSignalType.OFFER, sdp)

        fun ice(
            callId: String,
            payload: String,
        ) = CallSignalEnvelope(callId, CallSignalType.ICE, payload)

        fun hangup(callId: String) = CallSignalEnvelope(callId, CallSignalType.HANGUP, "user")

        fun storeClass(): Class<*> =
            try {
                Class.forName(STORE)
            } catch (e: ClassNotFoundException) {
                fail("ZC-409: $STORE does not exist: a call signal that arrives before its RING is dropped ($e)")
            }

        fun keepMs(): Long = storeClass().getField("KEEP_MS").getLong(null)

        fun maxCalls(): Int = storeClass().getField("MAX_CALLS").getInt(null)

        /** Reflection wraps what the store throws; show the cause. */
        fun <T> unwrap(call: () -> T): T =
            try {
                call()
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }
}
