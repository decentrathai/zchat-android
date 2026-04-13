package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD for Quantum Shield state management. The shield goes through:
 *   NONE → PENDING (our secret generated, waiting for peer's) → ACTIVE (both exchanged, PSK derived)
 *
 * The PSK is derived from both parties' secrets using QuantumShield.derivePSK().
 */
class QuantumShieldStateTest {

    @Test
    fun initial_state_is_none() {
        val state = QuantumShieldState()
        assertEquals(QuantumShieldStatus.NONE, state.status)
        assertNull(state.ourSecret)
        assertNull(state.peerSecret)
        assertNull(state.psk)
    }

    @Test
    fun generate_our_secret_transitions_to_pending() {
        val state = QuantumShieldState()
        val updated = state.generateOurSecret()
        assertEquals(QuantumShieldStatus.PENDING, updated.status)
        assertNotNull(updated.ourSecret)
        assertEquals(32, updated.ourSecret!!.size)
        assertNull(updated.peerSecret)
        assertNull(updated.psk)
    }

    @Test
    fun adding_peer_secret_with_our_secret_present_transitions_to_active() {
        val state = QuantumShieldState()
            .generateOurSecret()
        val peerSecret = QuantumShield.generateRandom()
        val updated = state.addPeerSecret(peerSecret)
        assertEquals(QuantumShieldStatus.ACTIVE, updated.status)
        assertNotNull(updated.psk)
        assertEquals(32, updated.psk!!.size)
    }

    @Test
    fun psk_is_order_independent() {
        val secretA = QuantumShield.generateRandom()
        val secretB = QuantumShield.generateRandom()

        val stateAlice = QuantumShieldState(ourSecret = secretA)
            .addPeerSecret(secretB)
        val stateBob = QuantumShieldState(ourSecret = secretB)
            .addPeerSecret(secretA)

        assertTrue(stateAlice.psk!!.contentEquals(stateBob.psk!!))
    }

    @Test
    fun reset_returns_to_none() {
        val active = QuantumShieldState()
            .generateOurSecret()
            .addPeerSecret(QuantumShield.generateRandom())
        assertEquals(QuantumShieldStatus.ACTIVE, active.status)
        val reset = active.reset()
        assertEquals(QuantumShieldStatus.NONE, reset.status)
        assertNull(reset.psk)
    }
}
