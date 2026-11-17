package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ZC-1474 as a feature (OT213n B1c; owner W-123; FIX_PLAN REVISION 2): which way a photo, file or voice note goes.
 *
 * At 669cf19e1 there is one way only: the file key is wrapped with the on-chain end-to-end shared key
 * (E2EEncryption.wrapFileKey), so an Open or Tunnel chat without a paid key exchange can never send an attachment.
 *
 * Wanted, pure `AttachmentTransport.choose(mode, peerNostrKeyBound, peerCap2130, hasE2ESharedKey)`:
 * - Open or Tunnel, the contact's relay key bound, its 2.13 capability recorded: NOSTR_ZATT, with or without a shared
 *   key (no on-chain key is needed);
 * - Open or Tunnel with a 2.12.4 contact (capability false or unknown, both passed as false) or no bound relay key:
 *   the existing paths only, E2E_WRAP with a shared key, else REFUSE (B1b's reason dialog and priced setup);
 * - Shielded never NOSTR_ZATT: E2E_WRAP with a shared key, else REFUSE.
 * Reached by name ([B1cPlanned]); the Route constants are compared by name.
 */
class AttachmentTransportChoosesFreeZattTest {
    private val relayModes = listOf(ConversationMode.OPEN, ConversationMode.TUNNEL)
    private val bools = listOf(false, true)

    @Test
    fun `an Open or Tunnel chat with a bound 2_13 contact sends over the relays with no on-chain key`() {
        for (mode in relayModes) {
            assertEquals("NOSTR_ZATT", B1cPlanned.choose(mode, true, true, false), "$mode, bound, 2.13, no shared key")
        }
    }

    @Test
    fun `a shared key does not take the free path away from a bound 2_13 contact`() {
        for (mode in relayModes) {
            val route = B1cPlanned.choose(mode, true, true, true)
            assertTrue(route == "NOSTR_ZATT" || route == "E2E_WRAP", "$mode, bound, 2.13, shared key: $route")
        }
    }

    @Test
    fun `a 2_12_4 or unknown contact keeps the existing paths in an Open or Tunnel chat`() {
        for (mode in relayModes) {
            for (bound in bools) {
                assertEquals("REFUSE", B1cPlanned.choose(mode, bound, false, false), "$mode, bound=$bound, no 2.13, no key")
                assertEquals("E2E_WRAP", B1cPlanned.choose(mode, bound, false, true), "$mode, bound=$bound, no 2.13, key")
            }
        }
    }

    @Test
    fun `no bound relay key means no free path even with the 2_13 capability`() {
        for (mode in relayModes) {
            assertEquals("REFUSE", B1cPlanned.choose(mode, false, true, false), "$mode, unbound, 2.13, no key")
            assertEquals("E2E_WRAP", B1cPlanned.choose(mode, false, true, true), "$mode, unbound, 2.13, key")
        }
    }

    @Test
    fun `a Shielded chat keeps the on-chain wrap and never goes over the relays`() {
        for (bound in bools) {
            for (cap in bools) {
                assertNotEquals("NOSTR_ZATT", B1cPlanned.choose(ConversationMode.VAULT, bound, cap, true))
                assertEquals("E2E_WRAP", B1cPlanned.choose(ConversationMode.VAULT, bound, cap, true), "VAULT bound=$bound cap=$cap key")
                assertEquals("REFUSE", B1cPlanned.choose(ConversationMode.VAULT, bound, cap, false), "VAULT bound=$bound cap=$cap no key")
            }
        }
    }
}
