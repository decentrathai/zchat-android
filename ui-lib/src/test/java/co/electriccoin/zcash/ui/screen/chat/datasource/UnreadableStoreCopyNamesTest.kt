package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2c KR-1 companions: the copies kept before an unreadable store is recreated are found by name so a
 * wallet reset / identity change (ZchatPreferencesImpl.clearAll) removes them with the rest, and the
 * permanent-vs-temporary split looks through wrapped causes.
 */
class UnreadableStoreCopyNamesTest {
    @Test
    fun `only the unreadable-store copies are selected, without the xml suffix`() {
        val listing =
            listOf(
                "zchat_e2e_keys_encrypted.xml",
                "zchat_e2e_keys_encrypted__unreadable_1758450000000.xml",
                "zchat_group_keys_encrypted__unreadable_1758450000001.xml",
                "zchat_preferences.xml",
                "zchat_encrypted_store_open_failures.xml",
                "zchat_e2e_keys_encrypted__unreadable_1758450000000.bak",
            )

        assertEquals(
            listOf(
                "zchat_e2e_keys_encrypted__unreadable_1758450000000",
                "zchat_group_keys_encrypted__unreadable_1758450000001",
            ),
            unreadableStoreCopyNames(listing)
        )
    }

    @Test
    fun `a wrong-key failure wrapped by Tink is permanent, a Keystore error is not`() {
        assertTrue(isPermanentKeyLoss(java.security.GeneralSecurityException("decrypt failed", AEADBadTagException("tag"))))
        assertFalse(isPermanentKeyLoss(KeyStoreException("the master key exists but is unusable")))
        assertFalse(isPermanentKeyLoss(java.security.ProviderException("Keystore operation failed")))
    }
}
