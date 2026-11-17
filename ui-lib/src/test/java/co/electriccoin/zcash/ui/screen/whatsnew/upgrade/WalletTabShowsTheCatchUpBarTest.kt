package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.PercentDecimal
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SY-1 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §2): What's New 2.13.0 says the catch-up progress "shows at
 * the top of your chat list and on the wallet screen". The wallet screen a user opens is the chat list's Wallet tab
 * ([co.electriccoin.zcash.ui.screen.wallettab.WalletTab]); Bridge 2 put the bar only on the Zashi Home message, a
 * screen nothing navigates to. The Fold at 72% showed no bar on the Wallet tab (fold run U1).
 *
 * The Wallet tab must show the same bar, with the same N, for as long as the chat list does: it reads the same
 * [PostUpgradeNotice.catchUpPending] flag and the same wallet snapshot (WalletSnapshotDataSource, which the chat
 * list reads through ChatViewModel), turned into a percentage the same way. Display only: nothing here sets or
 * clears the flag.
 */
class WalletTabShowsTheCatchUpBarTest {
    private fun src(path: String): String =
        stripComments(File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText())

    /** Line and block comments out, string literals kept whole (none of the scanned strings sit in a comment). */
    private fun stripComments(code: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    out.append(code, i, end)
                    i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '"' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    val end = minOf(j + 1, code.length)
                    out.append(code, i, end)
                    i = end
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '\'' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    val end = minOf(j + 1, code.length)
                    out.append(code, i, end)
                    i = end
                }
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    i = end
                }
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun the_wallet_screen_the_user_opens_is_the_wallet_tab() {
        // Guard (green before the fix): the chat list and the AI tab open WalletTab, and WalletTab draws
        // AndroidWalletTab, so that is where the bar must be.
        assertTrue("navigationRouter.replace(WalletTab)" in src("screen/chat/view/ChatListView.kt"))
        assertTrue("navigationRouter.replace(WalletTab)" in src("screen/ai/AndroidAiTab.kt"))
        assertTrue("composable<WalletTab> { AndroidWalletTab() }" in src("WalletNavGraph.kt"))
    }

    @Test
    fun nothing_navigates_to_the_zashi_home_screen() {
        // Guard (green before the fix): why UpgradeCatchUpBannerTest passed while no user saw a wallet bar. The
        // Home message is only drawn on the Home screen, and no code in src/main opens it.
        val nav = Regex("""\b(forward|replace|replaceAll|backTo|navigate)\(\s*Home\b""")
        val hits =
            File(repoRoot, "ui-lib/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { nav.containsMatchIn(stripComments(it.readText())) }
                .map { it.name }
                .toList()
        assertEquals(emptyList(), hits, "a screen now navigates to Home; revisit SY-1 and the What's New sentence")
    }

    @Test
    fun the_wallet_tab_reads_the_same_flag_and_snapshot_as_the_chat_list() {
        val vm = src("screen/wallettab/WalletTabVM.kt")
        assertTrue("postUpgradeNotice.catchUpPending" in vm, "WalletTabVM reads PostUpgradeNotice.catchUpPending")
        assertTrue(
            "walletSnapshotDataSource.observe()" in vm,
            "WalletTabVM reads the wallet snapshot the chat list reads (WalletSnapshotDataSource)",
        )
        assertTrue(
            "PostUpgradeRules.catchUpBannerPercentOf(" in vm,
            "WalletTabVM turns them into a percentage with the chat list's rule",
        )
        assertTrue("val catchUpPercent: StateFlow<Int?>" in vm, "WalletTabVM exposes catchUpPercent")

        val screen = src("screen/wallettab/AndroidWalletTab.kt")
        assertTrue("walletTabVM.catchUpPercent.collectAsStateWithLifecycle()" in screen)
        assertTrue(Regex("""catchUpPercent\s*=\s*catchUpPercent""").containsMatchIn(screen), "passed to WalletTabView")

        // The chat list's side of "the same N": ChatViewModel's progress is the snapshot's fraction times 100, and
        // ChatListView gives it to catchUpBannerPercent.
        assertTrue("val progress = snapshot.progress.decimal * 100f" in src("screen/chat/viewmodel/ChatViewModel.kt"))
        assertTrue(
            "PostUpgradeRules.catchUpBannerPercent(upgradeCatchUpPending, walletSyncStatus.progress)" in
                src("screen/chat/view/ChatListView.kt")
        )
    }

    @Test
    fun the_wallet_tab_shows_the_bar_under_its_header_above_the_balance() {
        val view = src("screen/wallettab/WalletTabView.kt")
        assertTrue(Regex("""catchUpPercent:\s*Int\?""").containsMatchIn(view), "WalletTabView takes catchUpPercent")
        val bar = view.indexOf("UpgradeCatchUpBar(")
        assertTrue(bar >= 0, "WalletTabView draws UpgradeCatchUpBar")
        val header = view.indexOf("text = stringResource(R.string.tab_wallet),")
        val balance = view.indexOf("BalanceWidget(")
        assertTrue(header in 0 until bar, "under the Wallet header")
        assertTrue(bar < balance, "above the balance, which may look low until the catch-up ends")

        val barFile = src("screen/whatsnew/upgrade/UpgradeCatchUpBar.kt")
        assertTrue("fun UpgradeCatchUpBar(" in barFile)
        assertTrue("stringResource(R.string.upgrade_catch_up_title, percent)" in barFile, "the translated title")
        assertTrue("stringResource(R.string.upgrade_catch_up_subtitle)" in barFile, "the translated subtitle")
        assertTrue("LinearProgressIndicator(" in barFile, "a progress bar")
    }

    @Test
    fun the_wallet_tab_percent_equals_the_chat_list_percent() {
        val of = percentOf()
        val fractions = listOf(0f, 0.001f, 0.1f, 0.4271f, 0.72f, 0.7299f, 0.98f, 0.999f, 1f)
        for (f in fractions) {
            val snapshot = snapshot(f)
            // The chat list: ChatViewModel's progress, then ChatListView's rule.
            val chatList = PostUpgradeRules.catchUpBannerPercent(true, snapshot.progress.decimal * 100f)
            assertEquals(chatList, of.invoke(PostUpgradeRules, true, snapshot), "fraction $f")
            assertNull(of.invoke(PostUpgradeRules, false, snapshot), "no bar without the flag (fraction $f)")
        }
        assertEquals(72, of.invoke(PostUpgradeRules, true, snapshot(0.72f)), "the Fold's 72%")
        // No synchronizer yet: the chat list shows 0 % (WalletSyncStatus() has progress 0), so does the tab.
        assertEquals(0, of.invoke(PostUpgradeRules, true, null))
        assertNull(of.invoke(PostUpgradeRules, false, null))
    }

    private fun percentOf(): Method =
        try {
            PostUpgradeRules::class.java.getMethod(
                "catchUpBannerPercentOf",
                Boolean::class.javaPrimitiveType,
                WalletSnapshot::class.java,
            )
        } catch (e: NoSuchMethodException) {
            fail("PostUpgradeRules.catchUpBannerPercentOf(catchUpPending: Boolean, snapshot: WalletSnapshot?) is missing")
        }

    private fun snapshot(fraction: Float) =
        WalletSnapshot(
            status = Synchronizer.Status.SYNCING,
            progress = PercentDecimal(fraction),
            synchronizerError = null,
            isSpendable = false,
            restoringState = WalletRestoringState.NONE,
        )
}
