package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ZC-1941 (owner W-148): the in-chat 2.12.4 reminder of a Shielded chat (banner and its "May need update" chip) has a
 * fixed end, about 30 days after the 2.13.0 release of 17 Nov 2026: from 2026-12-17 (inclusive, the phone's local
 * date) it shows nothing.
 *
 * The seam, read by reflection so the rest of the module's tests still compile before it exists:
 * `UpgradeReminders.shieldedReminder(mode, canReply, hiddenByUser, contactProven, today: LocalDate)`. Without that
 * parameter the rule has no date at all, so the test asks the 4-parameter rule, which is what the app does today
 * whatever the date: that answer is the behaviour under test.
 */
class ShieldedReminderEndsTest {
    private val dated =
        UpgradeReminders::class.java.methods.firstOrNull {
            it.name == "shieldedReminder" && it.parameterCount == 5 && it.parameterTypes[4] == LocalDate::class.java
        }

    private val undated =
        UpgradeReminders::class.java.methods.firstOrNull { it.name == "shieldedReminder" && it.parameterCount == 4 }

    private fun shown(
        hidden: Boolean,
        today: LocalDate,
        mode: ConversationMode = ConversationMode.VAULT,
        proven: Boolean = false,
    ): Any? =
        if (dated != null) {
            dated.invoke(UpgradeReminders, mode, true, hidden, proven, today)
        } else {
            assertNotNull(undated, "UpgradeReminders.shieldedReminder is gone").invoke(UpgradeReminders, mode, true, hidden, proven)
        }

    @Test
    fun before_the_end_the_reminder_and_its_chip_still_show() {
        for (day in listOf(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 12, 16))) {
            assertEquals(ShieldedReminderUi.BANNER, shown(hidden = false, today = day), "$day")
            assertEquals(ShieldedReminderUi.CHIP, shown(hidden = true, today = day), "$day")
        }
    }

    @Test
    fun from_17_december_2026_neither_the_banner_nor_the_chip_shows() {
        for (day in listOf(LocalDate.of(2026, 12, 17), LocalDate.of(2026, 12, 18), LocalDate.of(2027, 6, 1))) {
            for (hidden in listOf(false, true)) {
                assertEquals(ShieldedReminderUi.NONE, shown(hidden = hidden, today = day), "$day hidden=$hidden")
            }
        }
    }

    @Test
    fun the_end_changes_nothing_else() {
        val before = LocalDate.of(2026, 12, 1)
        assertEquals(ShieldedReminderUi.NONE, shown(hidden = false, today = before, mode = ConversationMode.OPEN))
        assertEquals(ShieldedReminderUi.NONE, shown(hidden = false, today = before, proven = true))
    }
}
