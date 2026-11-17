package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SY-2 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §3 with §9 row 7; Fold U1 and U2): `SyncStatusBar` built
 * "HH:mm:ss · Ns · Synced · $price" and added `chat_list_status_synced` every time the 60-second local refresh was not
 * running. It never read the wallet: the time was ChatViewModel's `_lastSyncTime`, set by a refresh of the local
 * database that succeeds offline. On the Fold it said "Synced" under "Catching up … 55%" (U1) and offline at the
 * first start (U2).
 *
 * What it must be:
 * - `SyncStatusBar` takes a `SyncLineState` and says "Synced" only in its `SyncLineState.Synced` branch; the other
 *   branches say "Catching up N%", "Offline", "Sync stopped" and "Connecting…" from string resources.
 * - The time is the wallet's last full sync in this process (`syncedAt`, from `WalletSyncStatus.syncedAtMillis`), not
 *   the local refresh time.
 * - ChatListView computes the line with `SyncLine.state(..)` from the SDK status, the progress, the catch-up flag
 *   (`upgradeCatchUpPending`) and the stamp.
 * - ChatViewModel passes the SDK status and the stamp of the process-wide `WalletSyncClock` (a Koin single fed by
 *   `WalletSnapshotDataSourceImpl`), so a second ChatViewModel does not start without it.
 */
class SyncLineTellsTheTruthTest {
    private val listView =
        KotlinUiLiterals.lex(
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt")
        )

    private fun code(
        lexed: KotlinUiLiterals.Lexed,
        anchor: String
    ): String {
        val r = KotlinUiLiterals.block(lexed, anchor)
        return lexed.blanked.substring(r.first, r.last + 1)
    }

    /** The text between the parenthesis at [open] and its match. */
    private fun parens(
        s: String,
        open: Int
    ): String {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return s.substring(open + 1, i)
                }
            }
        }
        error("unbalanced parentheses at $open")
    }

    private val bar: String get() = code(listView, "private fun SyncStatusBar(")

    private val barParams: String get() = parens(bar, bar.indexOf('('))

    @Test
    fun synced_is_said_only_in_the_synced_branch() {
        assertTrue(
            Regex("""\bline\s*:\s*SyncLineState\b""").containsMatchIn(barParams),
            "SyncStatusBar takes no SyncLineState: \"Synced\" is added whatever the wallet does",
        )
        val b = listView.blanked
        val uses = Regex("""R\.string\.chat_list_status_synced\b""").findAll(b).map { it.range.first }.toList()
        assertEquals(1, uses.size, "chat_list_status_synced is used ${uses.size} times in ChatListView")
        val branches = Regex("""(?:\bis\s+)?SyncLineState\.(\w+)\s*->""").findAll(bar).toList()
        val synced =
            assertNotNull(branches.firstOrNull { it.groupValues[1] == "Synced" }, "SyncStatusBar has no SyncLineState.Synced branch")
        val barStart = KotlinUiLiterals.block(listView, "private fun SyncStatusBar(").first
        val next = branches.firstOrNull { it.range.first > synced.range.first }
        val end = next?.range?.first ?: bar.length
        val use = uses.single() - barStart
        assertTrue(
            use > synced.range.last && use < end,
            "chat_list_status_synced is said outside the SyncLineState.Synced branch",
        )
        for (key in listOf("catching_up", "offline", "stopped", "connecting")) {
            assertTrue(
                Regex("""R\.string\.chat_list_sync_line_$key\b""").containsMatchIn(bar),
                "SyncStatusBar does not say chat_list_sync_line_$key",
            )
        }
        for (name in listOf("CatchingUp", "Offline", "Stopped", "Connecting")) {
            assertTrue(branches.any { it.groupValues[1] == name }, "SyncStatusBar has no SyncLineState.$name branch")
        }
    }

    @Test
    fun the_time_is_the_wallets_last_full_sync_not_the_local_refresh() {
        assertTrue(Regex("""\bsyncedAt\s*:\s*Instant\?""").containsMatchIn(barParams), "SyncStatusBar takes no syncedAt")
        assertFalse("lastSyncTime" in barParams, "SyncStatusBar still takes the local refresh time (lastSyncTime)")
        val b = listView.blanked
        val call =
            Regex("""(?<!fun )\bSyncStatusBar\s*\(""").findAll(b).map { it.range.last }.singleOrNull()
                ?: error("one SyncStatusBar( call expected")
        val args = parens(b, call)
        assertTrue(
            Regex("""\bsyncedAt\s*=[^,]*syncedAtMillis""").containsMatchIn(args),
            "the call does not pass the wallet's stamp (WalletSyncStatus.syncedAtMillis)",
        )
        assertFalse("lastSyncTime" in args, "the call still passes the local refresh time")
        assertTrue(Regex("""\bline\s*=""").containsMatchIn(args), "the call passes no line state")
    }

    @Test
    fun the_list_computes_the_line_from_the_sdk_status_and_the_catch_up_flag() {
        val b = listView.blanked
        val at =
            assertNotNull(Regex("""\bSyncLine\.state\s*\(""").find(b)?.range?.last, "ChatListView does not call SyncLine.state(..)")
        val args = parens(b, at)
        for (part in listOf("sdkStatus", "progress", "upgradeCatchUpPending", "syncedAtMillis")) {
            assertTrue(part in args, "SyncLine.state(..) does not get $part: $args")
        }
    }

    @Test
    fun the_view_model_passes_the_sdk_status_and_the_process_wide_stamp() {
        val vm =
            KotlinUiLiterals.lex(
                ChatResourceText.source(
                    "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
                )
            )
        val observe = code(vm, "private fun observeWalletSyncStatus(")
        assertTrue(Regex("""\bsdkStatus\s*=\s*snapshot\.status\b""").containsMatchIn(observe), "no sdkStatus = snapshot.status")
        assertTrue(Regex("""\bsyncedAtMillis\s*=""").containsMatchIn(observe), "no syncedAtMillis in WalletSyncStatus")
        assertTrue("walletSyncClock" in observe, "the stamp is not the process-wide WalletSyncClock's")
        assertFalse("_lastSyncTime" in observe, "the stamp is the local refresh time")

        val model =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ChatMessage.kt")
        val status = model.substring(model.indexOf("data class WalletSyncStatus("))
        val params = parens(status, status.indexOf('('))
        assertTrue(Regex("""\bsdkStatus\s*:\s*Synchronizer\.Status\?""").containsMatchIn(params), "WalletSyncStatus has no sdkStatus")
        assertTrue(Regex("""\bsyncedAtMillis\s*:\s*Long\?""").containsMatchIn(params), "WalletSyncStatus has no syncedAtMillis")

        val module = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/di/DataSourceModule.kt")
        assertTrue(
            Regex("""single\s*\{\s*WalletSyncClock\(|singleOf\(\s*::WalletSyncClock\s*\)""").containsMatchIn(module),
            "WalletSyncClock is not one process-wide Koin single",
        )
        val source =
            ChatResourceText.source(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/WalletSnapshotDataSource.kt"
            )
        assertTrue("walletSyncClock.onSnapshot(" in source, "WalletSnapshotDataSourceImpl does not feed the clock")
    }
}
