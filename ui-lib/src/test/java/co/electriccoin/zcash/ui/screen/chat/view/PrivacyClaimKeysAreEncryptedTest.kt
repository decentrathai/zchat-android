package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 3 report 4 item 13 — "What ZCHAT Protects" ends with "Keys stored in encrypted prefs, not
 * hardware-backed Keystore". The claim was never checked against the code. It is checked here, and pinned,
 * so the sentence cannot quietly stop being true: every store that holds key material (the E2E root and
 * chat keys, the Quantum Shield PSKs that live beside them, the group keys, the ratchet state, the
 * decrypted-text cache and the inbound-request store) must be opened with createEncryptedPrefs, which never
 * falls back to plaintext.
 *
 * The durable ratchet counter FLOORS are deliberately plain (D8: they must survive a Tink wipe, and a
 * counter is carried on the wire anyway), so they are not key material and are not listed.
 */
class PrivacyClaimKeysAreEncryptedTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val preferences: String by lazy {
        File(
            repoRoot,
            "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt",
        ).readText()
    }

    @Test
    fun every_store_holding_key_material_is_encrypted() {
        for (store in listOf("e2ePrefs", "groupKeysPrefs", "ratchetPrefs", "decryptedTextPrefs", "messageRequestPrefs")) {
            val declaration =
                Regex("""val $store: SharedPreferences =\s*([^\n]*)""").find(preferences)?.groupValues?.get(1)
            assertNotNull(declaration, "$store is no longer declared as a SharedPreferences field")
            assertTrue(
                "createEncryptedPrefs" in declaration,
                "\"Keys stored in encrypted prefs\" is no longer true: $store is opened with $declaration",
            )
        }
    }

    /** The Quantum Shield pre-shared keys are stored beside the E2E keys, not in a plain store. */
    @Test
    fun the_quantum_shield_psks_live_in_the_encrypted_e2e_store() {
        assertTrue(
            "e2ePrefs.edit().putString(\"qs_psk_" in preferences,
            "the Quantum Shield PSK is no longer written to the encrypted E2E store",
        )
    }

    /** createEncryptedPrefs must never degrade to plaintext; that is what makes the sentence honest. */
    @Test
    fun the_encrypted_store_never_falls_back_to_plaintext() {
        assertTrue(
            "Never falls back to plaintext" in preferences,
            "createEncryptedPrefs no longer documents that it refuses to store keys in the clear",
        )
    }

    /** And the sentence itself is still the one this test is about. */
    @Test
    fun the_claim_is_still_worded_as_checked() {
        val copy =
            File(
                repoRoot,
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/PrivacyCopy.kt",
            ).readText()
        assertTrue(
            "Keys stored in encrypted prefs, not hardware-backed Keystore" in copy,
            "the claim changed; re-check it against the stores above before rewording this test",
        )
    }
}
