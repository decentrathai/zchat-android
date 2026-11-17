package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F8 (ZC-112): the proof's epoch. A commit that rests on a proof never lands after that proof was cleared. */
class SendAttemptProofEpochTest {
    @Test
    fun `clearAll moves the epoch on and ifEpoch runs only on the epoch it was given`() {
        val before = SendAttemptProof.epoch()
        assertEquals("ifEpoch runs on a matching epoch", "ran", SendAttemptProof.ifEpoch(before) { "ran" })

        SendAttemptProof.clearAll()

        assertTrue("clearAll moves the epoch on", SendAttemptProof.epoch() > before)
        var ran = false
        assertNull("a commit captured before the clear does not run", SendAttemptProof.ifEpoch(before) { ran = true })
        assertFalse(ran)
        assertEquals(1, SendAttemptProof.ifEpoch(SendAttemptProof.epoch()) { 1 })
    }

    @Test
    fun `observe and clear leave the epoch as it is`() {
        val key = SendAttemptProof.key("epoch-test-row", 1L)
        val before = SendAttemptProof.epoch()

        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, 100L))
        assertTrue("two sightings at rising tips still prove", SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, 101L))
        SendAttemptProof.clear(key)

        assertEquals(before, SendAttemptProof.epoch())
    }
}
