package co.electriccoin.zcash.ui.screen.moveoldbalance

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F9 (fix-all 2.13.0, train T1): the Move old balance sheet must not say that a more private way is planned.
 *
 * Round 8 D10 added a consent line from 1,000,000 zat: "You don't have to move it now. It stays in your wallet, and a
 * more private way to move larger balances is planned for a later ZChat version." That is wrong twice:
 * - the private route is not "planned for later": SDK 3.0.0, which this build uses, already ships it
 *   (OrchardMigrationSdk), and ZChat does not offer it in 2.13.0;
 * - from 1,000,000 to 1,094,999 zat the SDK's private route can move nothing at all (one 0.01 ZEC crossing plus one
 *   80,000 zat preparation needs 1,095,000), so the line promises something impossible there.
 *
 * Wanted: the line, its string (English and Spanish), the `PRIVATE_MIGRATION_WORTHWHILE_ZAT` threshold and
 * `Consent.showWaitForPrivateLine` are gone, and no Move old balance text promises a later version. The private route
 * comes back only with figures read from the SDK's own schedule (D1).
 *
 * Replaces MoveOldBalanceWaitForPrivateLineTest, which pinned the line. Reached by name and read from the files, so
 * this file compiles either way.
 */
class MoveOldBalanceNoPlannedLineTest {
    @Test
    fun `the planned-later line is gone in English and Spanish`() {
        for (dir in listOf("values", "values-es")) {
            val strings = ChatResourceText.strings(dir)
            assertFalse(KEY in strings, "$dir still defines $KEY: \"${strings[KEY]}\"")
        }
    }

    @Test
    fun `no move old balance text promises a later version`() {
        for (dir in listOf("values", "values-es")) {
            ChatResourceText
                .strings(dir)
                .filterKeys { it.startsWith("move_old_balance_") }
                .forEach { (key, text) ->
                    assertFalse(
                        PROMISE.containsMatchIn(text),
                        "$dir/$key promises a later version or a planned private way: \"$text\"",
                    )
                }
        }
    }

    @Test
    fun `the threshold and the consent flag are gone`() {
        val fileClass = runCatching { Class.forName("$PACKAGE.MoveOldBalanceVMKt") }.getOrNull()
        val threshold = fileClass?.declaredFields?.firstOrNull { it.name == "PRIVATE_MIGRATION_WORTHWHILE_ZAT" }
        assertTrue(threshold == null, "MoveOldBalanceVM.kt still declares PRIVATE_MIGRATION_WORTHWHILE_ZAT")

        val consent = Class.forName("$PACKAGE.MoveOldBalanceState\$Consent")
        assertFalse(
            consent.methods.any { it.name == "getShowWaitForPrivateLine" },
            "MoveOldBalanceState.Consent still has showWaitForPrivateLine",
        )
    }

    @Test
    fun `the consent sheet shows no planned line`() {
        val view = ChatResourceText.source(VIEW_PATH)
        val vm = ChatResourceText.source(VM_PATH)
        assertFalse(KEY in view, "MoveOldBalanceView still shows R.string.$KEY")
        assertFalse("showWaitForPrivateLine" in view, "MoveOldBalanceView still reads showWaitForPrivateLine")
        assertFalse("PRIVATE_MIGRATION_WORTHWHILE_ZAT" in vm, "MoveOldBalanceVM still mentions the threshold")
    }

    private companion object {
        const val PACKAGE = "co.electriccoin.zcash.ui.screen.moveoldbalance"
        const val KEY = "move_old_balance_consent_wait_private"
        const val VM_PATH = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/moveoldbalance/MoveOldBalanceVM.kt"
        const val VIEW_PATH =
            "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/moveoldbalance/MoveOldBalanceView.kt"

        /** "planned", "a later … version", and the Spanish "prevista", "versión posterior". */
        val PROMISE =
            Regex(
                """planned|later (ZChat )?version|later version|prevista|previsto|versión posterior""",
                RegexOption.IGNORE_CASE,
            )
    }
}
