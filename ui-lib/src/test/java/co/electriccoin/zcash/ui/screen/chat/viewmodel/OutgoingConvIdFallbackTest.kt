package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RED pin for the conversation id our OWN mined control messages are decrypted under on rescan.
 *
 * The conversation-bound plaintext cache (DecryptedTextCacheKey.of(convId, wire)) is read only
 * after a non-null convId is known — that guard is what closed the plaintext oracle. The send path
 * self-caches every outgoing E2E1 blob under `of(convId, enc)`, using the convId it obtained from
 * ZchatPreferences.getOrCreateConversationId(peer).
 *
 * A ZDIS (disappearing-timer) or ZMODE (transport-mode) control send over VAULT is written with
 * rawMemo = true: the memo on chain is the bare E2E1 blob with NO ZMSG v4 envelope. On rescan the
 * derive path parses the convId out of the memo, finds none, hands null to tryDecryptMessage — and
 * the guard returns "secure session re-establishing" before the self-cached plaintext is ever
 * consulted. The user's own timer/mode change renders as a permanent placeholder bubble.
 *
 * The fix is to fall back to the stored peer -> convId mapping — the same id the send path cached
 * under — when the memo carries no envelope. These tests pin that composition on
 * [ConvIdResolver], which holds no ViewModel state.
 */
class OutgoingConvIdFallbackTest {
    private fun resolve(memo: String, stored: String?): String? =
        ConvIdResolver.outgoingConvId(memo) { stored }

    @Test
    fun v4_envelope_still_wins_over_the_stored_mapping() {
        val memo = "ZMSG|v4|ABC12345|deadbeef|hello"
        assertEquals("ABC12345", resolve(memo, stored = "stored99"))
    }

    @Test
    fun bare_e2e1_control_blob_falls_back_to_the_stored_mapping() {
        // Exactly what a rawMemo ZDIS/ZMODE send puts on chain: no envelope at all.
        val memo = "E2E1:00:000000000000002a:aGVsbG8gZW5jcnlwdGVkIHdvcmxk"
        assertEquals(
            "stored99",
            resolve(memo, stored = "stored99"),
            "A control send carries no envelope; the stored peer mapping must supply the convId",
        )
    }

    @Test
    fun no_envelope_and_no_stored_mapping_is_honestly_null() {
        val memo = "E2E1:00:000000000000002a:aGVsbG8gZW5jcnlwdGVkIHdvcmxk"
        assertNull(resolve(memo, stored = null))
    }
}
