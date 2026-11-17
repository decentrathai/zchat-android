package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D3-2 (fix-all 2.13.0, train T5; t5-specs SPECS §2.4 C5): the Quantum Shield claim carrier is wired in the shipped
 * ChatViewModel, not only in tests.
 *
 * Every D3 path starts with `val claims = qsClaims ?: return <today's behaviour>` (so the three QS tests that build the
 * ViewModel without its constructor stay unchanged), which means a missing assignment would ship D3 switched off with
 * every other test green. Wanted: `init` assigns the store over the ViewModel's own preferences before the relay
 * reader starts, with no new constructor parameter; the refresh tick retries claims right after the mode-change retry;
 * the process-wide in-flight set exists; the relay reader stores claims in its read-receipt branch before it returns;
 * and the relay plumbing (Nip17, NostrInboxManager, NostrChatBridge, SyncForegroundService) knows nothing of claims.
 */
class QsClaimStoreWiredTest {
    private val main = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui")
    private val vm = File(main, "screen/chat/viewmodel/ChatViewModel.kt").readText()

    private fun body(
        src: String,
        header: String,
    ): String {
        val start = src.indexOf(header)
        assertTrue(start >= 0, "not found: $header")
        var depth = 0
        var i = src.indexOf('{', start)
        val open = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced: $header")
    }

    @Test
    fun `init assigns the claim store before the relay reader starts, with no new constructor parameter`() {
        val init = body(vm, "\n    init {")
        val assign = Regex("qsClaims\\s*=\\s*[\\w.]*PrefsQsClaimStore\\(zchatPreferences\\)").find(init)
        assertNotNull(assign, "ChatViewModel's init must assign qsClaims = PrefsQsClaimStore(zchatPreferences)")
        assertTrue(assign.range.first < init.indexOf("observeNostrInbound()"), "the store is set before the relay reader starts")
        val declared = vm.indexOf("private var qsClaims")
        assertTrue(declared in 0 until vm.indexOf("\n    init {"), "the field is declared before init (initialiser order)")
        val constructor = vm.substring(vm.indexOf("class ChatViewModel("), vm.indexOf(") : ViewModel()"))
        assertFalse("QsClaim" in constructor, "no new ChatViewModel constructor parameter")
    }

    @Test
    fun `the refresh tick retries claims right after the mode-change retry`() {
        val tick = body(vm, "private suspend fun performAutoRefresh()")
        val lines = tick.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val modeSync = lines.indexOf("retryPendingModeSyncs()")
        assertTrue(modeSync >= 0, "the tick still retries mode changes")
        assertEquals("retryQsClaims()", lines.getOrNull(modeSync + 1), "the claim retry follows the mode-change retry")
        assertTrue(Regex("private val qsClaimInFlight\\s*=").containsMatchIn(vm), "a process-wide in-flight set stops double publishes")
    }

    @Test
    fun `the relay reader stores a claim in its read-receipt branch before it returns`() {
        val start = vm.indexOf("if (co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol.isReadReceipt(chat.plaintext)) {")
        assertTrue(start >= 0, "the relay reader's read-receipt branch")
        val branch = vm.substring(start, vm.indexOf("return@collect", start))
        assertTrue("recordQsPeerClaim(chat.peerAddress, chat.plaintext)" in branch, "the branch hands the claim to the store gate: $branch")
    }

    @Test
    fun `the relay plumbing is unchanged`() {
        listOf("nostr/Nip17.kt", "nostr/NostrInboxManager.kt", "nostr/NostrChatBridge.kt", "service/SyncForegroundService.kt").forEach {
            val src = File(main, it).readText()
            assertFalse("zqs1" in src || "QsClaim" in src, "$it must not know about Quantum Shield claims")
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "repo root not found" }
    }
}
