package co.electriccoin.zcash.ui.screen.update

import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.KotlinUiLiterals
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ZC-538: one non-modal bar on each tab, with the guard restricted to spend funnels and its notice surfaces. */
class UpdateRequiredBarShowsOnceOnBothTabsAndNeverBlocksReadingTest {
    @Before
    fun setUp() = NetworkUpgradeTestApi.resetIfPresent()

    @After
    fun tearDown() = NetworkUpgradeTestApi.resetIfPresent()

    private fun source(path: String): String {
        assertTrue(File(ChatResourceText.repoRoot, MAIN + path).isFile, "required ZC-538 source absent: $path")
        return SourceScan.stripComments(ChatResourceText.source(MAIN + path))
    }

    @Test
    fun one_shared_bar_composable_is_used_once_on_each_tab_above_existing_banners() {
        val bar = source("screen/update/UpdateRequiredBar.kt")
        assertEquals(1, Regex("""fun\s+UpdateRequiredBar\s*\(""").findAll(bar).count())
        for (path in listOf("screen/chat/view/ChatListView.kt", "screen/wallettab/WalletTabView.kt")) {
            val host = source(path)
            assertEquals(1, Regex("""\bUpdateRequiredBar\s*\(""").findAll(host).count(), "$path must show exactly one bar")
            val catchUpAnchor =
                if (path == "screen/chat/view/ChatListView.kt") {
                    "WalletSyncProgressBanner("
                } else {
                    "UpgradeCatchUpBar("
                }
            val catchUpIndex = host.indexOf(catchUpAnchor)
            assertTrue(catchUpIndex >= 0, "$path existing catch-up banner is absent")
            assertTrue(host.indexOf("UpdateRequiredBar(") < catchUpIndex, "$path notice is below catch-up")
        }
        val chats = source("screen/chat/view/ChatListView.kt")
        assertTrue(
            chats.indexOf("UpdateRequiredBar(") < chats.indexOf("SeedBackupReminderBanner(onClick"),
            "notice is below backup reminder",
        )
        assertFalse("AlertDialog(" in bar || "Dialog(" in bar, "the passive notice became modal")
    }

    @Test
    fun both_tabs_are_fed_by_the_same_guard_state_and_no_notice_is_persisted() {
        val chat = source("screen/chat/AndroidChat.kt")
        val wallet = source("screen/wallettab/WalletTabVM.kt")
        assertTrue("NetworkUpgradeGuard.state" in chat, "Chats does not collect the process-wide state")
        assertTrue("NetworkUpgradeGuard.state" in wallet, "Wallet does not observe the same state")
        val gating = source("common/model/NetworkUpgradeGating.kt")
        for (forbidden in listOf("Preference", "SharedPreferences", "save", "store(")) {
            assertFalse(forbidden in gating, "upgrade latch must be in memory: $forbidden")
        }
    }

    @Test
    fun updater_button_is_self_update_only_user_triggered_and_uses_resources() {
        val bar = source("screen/update/UpdateRequiredBar.kt")
        val enabled = SourceScan.braceBlock(bar, "if (BuildConfig.IS_SELF_UPDATE_ENABLED)")
        val onClick = SourceScan.braceBlock(enabled, "onClick =")
        assertTrue("UpdateCheckTrigger.trigger()" in onClick, "button bypasses the existing updater trigger")
        assertEquals(1, Regex("""UpdateCheckTrigger\.trigger\(\)""").findAll(bar).count())
        assertEquals(1, Regex("""UpdateCheckTrigger\.trigger\(\)""").findAll(onClick).count())
        for (key in listOf("update_required_title", "update_required_banner_body", "update_required_button")) {
            assertTrue("R.string.$key" in bar, "bar does not use $key")
        }
        assertTrue(
            KotlinUiLiterals.offenders(KotlinUiLiterals.lex(bar), emptySet(), emptyList()).isEmpty(),
            "bar contains English UI literals",
        )
        val updater = source("screen/update/UpdateChecker.kt")
        val upToDate = SourceScan.braceBlock(updater, "is UpdateState.UpToDate ->")
        assertTrue(
            "NetworkUpgradeGuard.state" in upToDate && "update_required_up_to_date_body" in upToDate,
            "UpToDate contradicts the active notice",
        )
    }

    @Test
    fun only_allowed_spend_funnels_and_notice_surfaces_reference_the_guard() {
        val files =
            File(ChatResourceText.repoRoot, MAIN).walkTopDown().filter { it.isFile && it.extension == "kt" }
        val found =
            files
                .filter { Regex("""\bNetworkUpgradeGuard\b""").containsMatchIn(SourceScan.stripComments(it.readText())) }
                .map { it.relativeTo(File(ChatResourceText.repoRoot, MAIN)).invariantSeparatorsPath }
                .toSet()
        assertEquals(emptySet(), found - ALLOWED, "guard spread into reading, sync, receipt, address, backup or relay paths")
        val requiredPaths =
            listOf(
                "common/model/NetworkUpgradeGating.kt",
                "common/provider/SynchronizerProvider.kt",
                "common/datasource/ProposalDataSource.kt",
                "common/tex/SdkTexWallet.kt",
                "screen/chat/usecase/CreateChunkedMessageProposalUseCase.kt",
            )
        for (required in requiredPaths) {
            assertTrue(required in found, "guard missing from required spend/setup funnel $required")
        }
    }

    private companion object {
        const val MAIN = "ui-lib/src/main/java/co/electriccoin/zcash/ui/"
        val ALLOWED =
            setOf(
                "common/model/NetworkUpgradeGating.kt",
                "common/provider/SynchronizerProvider.kt",
                "common/datasource/ProposalDataSource.kt",
                "common/tex/SdkTexWallet.kt",
                "common/model/PoolSpendGating.kt",
                "screen/chat/usecase/CreateChunkedMessageProposalUseCase.kt",
                "screen/chat/util/ZchatErrorMessage.kt",
                "screen/chat/model/ChatSendCopy.kt",
                "screen/send/model/SendStage.kt",
                "screen/send/view/SendView.kt",
                "screen/error/ErrorVM.kt",
                "screen/transactionprogress/TransactionProgressVM.kt",
                "screen/update/UpdateRequiredBar.kt",
                "screen/update/UpdateChecker.kt",
                "screen/chat/AndroidChat.kt",
                "screen/chat/view/ChatListView.kt",
                "screen/wallettab/WalletTabVM.kt",
                "screen/wallettab/WalletTabView.kt",
            )
    }
}
