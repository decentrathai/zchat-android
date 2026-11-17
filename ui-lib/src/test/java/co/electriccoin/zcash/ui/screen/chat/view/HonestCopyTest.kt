package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Batch A6 (verification-20260915): user-facing copy must not promise more than the code does.
 *
 * - CB-3: Tunnel and Open share ONE relay pool (NostrRelayPool.DEFAULT_RELAYS: ZChat's relay plus two public
 *   relays, relay.primal.net and nos.lol since RELAY-HEALTH; relay health may swap one of the two public ones for a
 *   relay from RelayCatalog) and every gift-wrap and call signal is published to all three active relays.
 *   The Tunnel note said "then a private relay". Public relays see the recipient key, timing and IP.
 * - CB-4: Quantum Shield is active only after an in-person QR exchange (QuantumShieldState.status is
 *   NONE until then), and the compose blurb is shown before any chat exists, so it may not claim
 *   Vault is "E2E + Quantum Shield". Orchard is not post-quantum, so the About screen may not say
 *   funds are "protected today and tomorrow" or that encryption is "secure against all known
 *   attacks". SECURITY.md must name the third-party attachment hosts (FileUploadManager) and the
 *   AI provider path.
 * - RD-1: the default "keep" checkbox on Reset keeps every ZCHAT chat store (ResetZashiUseCase skips
 *   wipeZchatStores when keepFiles), so its text must say chats and chat keys are kept.
 * - RP-8: the README's Wallet SDK row must match ZCASH_SDK_VERSION in gradle.properties.
 */
class HonestCopyTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root (gradle.properties + README.md) not found above the test working dir")
    }

    private fun read(path: String): String = File(repoRoot, path).readText()

    private fun stringRes(path: String, name: String): String {
        val m = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(read(path))
        assertNotNull(m, "string $name missing in $path")
        return m.groupValues[1]
    }

    // ---- CB-3 ----

    @Test
    fun tunnel_security_note_never_promises_a_private_relay() {
        val (title, body) = assertNotNull(modeSecurityNoteCopy(ConversationMode.TUNNEL))
        val text = "$title $body".lowercase()
        assertFalse("private relay" in text, "Tunnel note must not say 'private relay': $title / $body")
        assertFalse("private nostr relay" in text, "Tunnel note must not say 'private NOSTR relay': $body")
    }

    @Test
    fun tunnel_and_open_notes_both_disclose_public_relays() {
        for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.OPEN)) {
            val (title, body) = assertNotNull(modeSecurityNoteCopy(mode))
            val text = "$title $body".lowercase()
            assertTrue("public nostr relays" in text || "public relays" in text, "$mode note must disclose public relays: $body")
            assertFalse(
                "hides who you're talking to" in text,
                "$mode note must not claim gift-wrapping hides the recipient from relays: $body"
            )
        }
    }

    @Test
    fun open_compose_warning_does_not_say_a_single_public_relay() {
        // The first-use Open warning lives inside a composable; pin its title in the source.
        val src = read("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt")
        assertFalse("Open mode: free over a public relay\"" in src, "Open warning still names a single public relay")
    }

    // ---- CB-4 ----

    @Test
    fun vault_compose_blurb_makes_no_quantum_shield_claim_while_status_is_none() {
        // The blurb is shown in the compose/new-chat context, where no QR exchange has happened, so
        // QuantumShieldStatus is NONE. It may mention Quantum Shield only as an optional extra.
        val blurb = ConversationMode.VAULT.composeBlurb()
        assertFalse("+ Quantum Shield" in blurb, "Vault blurb claims Quantum Shield is on: $blurb")
        if ("quantum shield" in blurb.lowercase()) {
            assertTrue("optional" in blurb.lowercase(), "Vault blurb must mark Quantum Shield optional: $blurb")
        }
    }

    @Test
    fun about_quantum_bullets_do_not_overpromise_in_english_or_spanish() {
        val en = "ui-lib/src/main/res/ui/about/values/strings.xml"
        val es = "ui-lib/src/main/res/ui/about/values-es/strings.xml"
        val f1en = stringRes(en, "about_quantum_feature_1").lowercase()
        val f3en = stringRes(en, "about_quantum_feature_3").lowercase()
        assertFalse("all known attacks" in f1en, f1en)
        assertFalse("today and tomorrow" in f3en, f3en)
        val f1es = stringRes(es, "about_quantum_feature_1").lowercase()
        val f3es = stringRes(es, "about_quantum_feature_3").lowercase()
        assertFalse("todos los ataques conocidos" in f1es, f1es)
        assertFalse("hoy y mañana" in f3es, f3es)
    }

    @Test
    fun security_md_host_list_matches_file_upload_manager() {
        val src = read("ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileUploadManager.kt")
        val hosts = Regex("\"https://([a-z0-9.\\-]+)\"").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(hosts.isNotEmpty())
        val sec = read("SECURITY.md")
        for (h in hosts) assertTrue(h in sec, "SECURITY.md must name attachment host $h")
        assertTrue("Venice" in sec, "SECURITY.md must disclose that AI prompts go to Venice via our backend")
    }

    // ---- RD-1 ----

    @Test
    fun reset_keep_checkbox_says_chats_and_chat_keys_are_kept_in_english_and_spanish() {
        val en =
            stringRes("ui-lib/src/main/res/ui/delete_wallet/values/strings.xml", "delete_wallet_checkbox_title")
                .lowercase()
        assertTrue("chat" in en && "key" in en, "checkbox must say chats and chat keys are kept: $en")
        assertTrue("different recovery phrase" in en, "checkbox must warn about restoring a different phrase: $en")
        val es =
            stringRes("ui-lib/src/main/res/ui/delete_wallet/values-es/strings.xml", "delete_wallet_checkbox_title")
                .lowercase()
        assertTrue("chat" in es && "clave" in es, "Spanish checkbox must say chats and chat keys are kept: $es")
        assertTrue("frase de recuperación distinta" in es, "Spanish checkbox must warn about a different phrase: $es")
    }

    // ---- RP-8 ----

    @Test
    fun readme_wallet_sdk_row_matches_gradle_properties() {
        val sdk = Regex("""(?m)^ZCASH_SDK_VERSION=(\S+)""").find(read("gradle.properties"))?.groupValues?.get(1)
        assertNotNull(sdk)
        val row = Regex("""(?m)^\| Wallet SDK \| Zcash Android SDK (\S+) \|""").find(read("README.md"))?.groupValues?.get(1)
        assertEquals(sdk, row, "README Wallet SDK row must match ZCASH_SDK_VERSION")
    }
}
